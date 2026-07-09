package dev.ambon.engine

import dev.ambon.bus.OutboundBus
import dev.ambon.config.AkathavaeConfig
import dev.ambon.domain.arcanum.ArcanumEntry
import dev.ambon.domain.arcanum.ArcanumSource
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.mob.MobState
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.status.StatusEffectSystem
import dev.ambon.metrics.GameMetrics
import java.time.Clock
import java.util.Random

/**
 * Illumination — recording the world in an Arcanum, and the Akathavae's
 * replacement for combat.
 *
 * Anyone may `illuminate` a creature to record it in their own Arcanum — a
 * field journal any traveler can keep. Illumination never harms the subject: it
 * leaves the creature exactly where it stands, so even quest-givers and unique
 * mobs are safe — and free — to record. Success is stat-driven (success stat
 * raises the chance, the subject's level above the player lowers it, the
 * gap-relief stat softens that penalty) and pays first-time-big / repeat-small
 * XP scaled by the XP stat. Illumination is not instant: the quill works for
 * roughly as long as the fight would have taken (see [PendingSketch] and
 * [tick]), with the roll landing at the end — moving away, entering combat,
 * or losing the subject abandons the page.
 *
 * The pledged get the full craft; the unpledged get field notes. Unpledged
 * odds and XP are scaled down by config, and everything past the creature page
 * itself is reserved for the pledged: drop cataloguing (which feeds the
 * wardrobe), quest/achievement kill credit (once per living instance — the
 * unpledged can simply fight), quest-item rubbings, and the passive discovery
 * of rooms visited and items first seen. Failure puts the subject on a retry
 * cooldown and turns it hostile unless the escape stat talks the player out
 * of it.
 *
 * World-firsts: the first player ever to illuminate a subject is stamped
 * permanently into [WorldStateRegistry] — "First illuminated by Thalen" — but
 * only a pledged Akathavae's journal is accepted at the shrines, so only the
 * pledged can seal that page. Separately, the first player to *kill* a
 * creature is stamped as "First slain by" (see [onMobSlain]) — the two records
 * sit side by side in the Arcanum.
 */
class AkathavaeSystem(
    private val players: PlayerRegistry,
    private val items: ItemRegistry,
    private val world: dev.ambon.domain.world.World,
    private val outbound: OutboundBus,
    private val combat: CombatSystem,
    private val worldState: WorldStateRegistry? = null,
    private val progression: PlayerProgression = PlayerProgression(),
    private val classRegistry: PlayerClassRegistry? = null,
    private val statusEffects: StatusEffectSystem? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val rng: Random = Random(),
    private val config: AkathavaeConfig = AkathavaeConfig(),
    private val metrics: GameMetrics = GameMetrics.noop(),
    private val markVitalsDirty: ((SessionId) -> Unit)? = null,
    private val onLevelUp: (suspend (SessionId, LevelUpResult) -> Unit)? = null,
    private val gmcpEmitter: GmcpEmitter? = null,
    /**
     * Re-emits `Room.MobInfo` for the session after a successful recording, so the
     * Monster Manual's recorded/world-first badge flips live (issue #1389).
     */
    private val refreshRoomMobInfo: (suspend (SessionId) -> Unit)? = null,
    /**
     * Fired after a first-time Arcanum record (creature, room, or item) or a newly
     * stamped world-first, so achievement progress can update immediately.
     */
    private val onArcanumRecorded: (suspend (SessionId) -> Unit)? = null,
    /**
     * Daily-quest hook for `illuminate`-type commissions. Fired once per living
     * mob instance on a successful illumination (alongside the kill credit) and
     * once per first-time NPC observation — commissions are about recording,
     * so observations count, but re-recording the same subject never does.
     */
    private val onIlluminated: (suspend (SessionId) -> Unit)? = null,
) {
    companion object {
        /**
         * Arcanum subject key for a mob — the shared template key (`zone:template`),
         * falling back to the instance id for mobs without a template. Shared with
         * [GmcpEmitter]'s Room.MobInfo arcanum badges so both sides agree on identity.
         */
        fun mobSubjectKey(mob: MobState): String = mob.templateKey.ifEmpty { mob.id.value }
    }

    /**
     * Late-bound quest bridge (wired by GameEngine after both systems exist).
     * Illumination grants a recorded creature's drops when — and only when — an
     * active collect objective still needs them, so drop-only quest items stay
     * reachable on the pacifist path (#1392).
     */
    var quests: QuestSystem? = null

    /** Per-session, per-subject retry locks after a failed illumination. Runtime-only. */
    private val failCooldowns = mutableMapOf<SessionId, MutableMap<String, Long>>()

    /** Per-session anti-speedrun throttle on discovery XP awards. Runtime-only. */
    private val nextDiscoveryXpAt = mutableMapOf<SessionId, Long>()

    /**
     * Average mob-template level per zone — the zone-difficulty proxy that scales
     * room-discovery XP. Cached lazily because [onRoomVisited] fires on every
     * movement; zones without mob templates read as 0 (flat base XP).
     */
    private val zoneAvgMobLevel = mutableMapOf<String, Int>()

    /**
     * Per-session set of mob *instance* ids already credited as illumination
     * kills. Mirrors the old capture path, where the subject was consumed: a given
     * living creature grants quest/achievement credit once, so a persistent
     * subject can't be re-illuminated to farm kill objectives. Runtime-only.
     */
    private val creditedIlluminations = mutableMapOf<SessionId, MutableSet<String>>()

    /**
     * An in-flight sketch: illumination is not instant, it takes roughly as
     * long as the fight would have (see [AkathavaeConfig.sketchMsPerEstimatedRound]).
     * The success roll happens at resolution, in [tick]. Runtime-only — a
     * relog simply abandons the page.
     */
    private data class PendingSketch(
        val mobId: dev.ambon.domain.ids.MobId,
        val mobName: String,
        val subjectKey: String,
        val roomId: RoomId,
        val endsAtMs: Long,
        val observe: Boolean,
    )

    /** At most one sketch per session — a second `illuminate` while drawing is refused. */
    private val pendingSketches = mutableMapOf<SessionId, PendingSketch>()

    fun onSessionRemoved(sessionId: SessionId) {
        failCooldowns.remove(sessionId)
        nextDiscoveryXpAt.remove(sessionId)
        creditedIlluminations.remove(sessionId)
        pendingSketches.remove(sessionId)
    }

    // ── Illumination ─────────────────────────────────────────────────────

    suspend fun illuminate(
        sessionId: SessionId,
        keywordRaw: String,
    ) {
        val me = players.get(sessionId) ?: return
        val keyword = keywordRaw.trim()
        if (keyword.isEmpty()) {
            outbound.send(OutboundEvent.SendError(sessionId, "Illuminate what?"))
            return
        }
        if (combat.isInCombat(sessionId)) {
            outbound.send(OutboundEvent.SendError(sessionId, "You cannot hold a steady image while under attack — flee first."))
            return
        }
        pendingSketches[sessionId]?.let { pending ->
            outbound.send(OutboundEvent.SendError(sessionId, "Your quill is already busy with ${pending.mobName}."))
            return
        }
        val mob = combat.findMobInRoom(me.roomId, keyword)
        if (mob == null || mob.isPet) {
            outbound.send(OutboundEvent.SendError(sessionId, "You don't see '$keyword' here."))
            return
        }

        val now = clock.millis()
        val subjectKey = mobSubjectKey(mob)
        val lockedUntil = failCooldowns[sessionId]?.get(subjectKey) ?: 0L
        if (now < lockedUntil) {
            val secs = (lockedUntil - now).ceilSeconds()
            outbound.send(
                OutboundEvent.SendError(
                    sessionId,
                    "${theCap(mob.name)} is still wary of you — give it ${secs}s before trying again.",
                ),
            )
            return
        }

        val observe = !mob.role.isCombatant
        // A repeat observation resolves instantly: the page already exists, there
        // is no roll, and nobody wants to watch a sketch end in "already have it".
        if (observe && me.arcanum.mobs.containsKey(subjectKey)) {
            outbound.send(OutboundEvent.SendText(sessionId, "Your Arcanum already holds a page on ${mob.name}."))
            return
        }

        val durationMs =
            if (observe) {
                config.observeSketchMs
            } else {
                (combat.estimatedHitsToKill(sessionId, mob) * config.sketchMsPerEstimatedRound)
                    .coerceIn(config.sketchMinMs, config.sketchMaxMs)
            }
        if (durationMs <= 0L) {
            // Instant mode — all sketch knobs zeroed (the pre-sketch behavior).
            resolveSketch(sessionId, me, mob, subjectKey, now)
            return
        }

        pendingSketches[sessionId] = PendingSketch(
            mobId = mob.id,
            mobName = mob.name,
            subjectKey = subjectKey,
            roomId = me.roomId,
            endsAtMs = now + durationMs,
            observe = observe,
        )
        outbound.send(
            OutboundEvent.SendText(
                sessionId,
                "You open your Arcanum and set your quill to ${mob.name} — hold steady...",
            ),
        )
        broadcastToRoom(
            players,
            outbound,
            me.roomId,
            "${me.name} opens a journal and begins sketching ${mob.name}.",
            exclude = sessionId,
        )
        gmcpEmitter?.sendArcanumSketch(
            sessionId,
            GmcpEmitter.ArcanumSketchPayload(
                phase = "start",
                mobId = mob.id.value,
                mobName = mob.name,
                subjectKey = subjectKey,
                observe = observe,
                durationMs = durationMs,
            ),
        )
        metrics.onGameEvent("akathavae", "sketch_start")
    }

    // ── Sketch lifecycle ─────────────────────────────────────────────────

    /**
     * Resolves due sketches; called from the engine tick loop. Combat cancels
     * a sketch (checked here rather than via a combat callback so any aggro
     * source — wandering aggressives, a duel — interrupts it), and a subject
     * that died or left mid-sketch leaves the page unfinished. The success
     * roll happens now, at the end, against current stats.
     */
    suspend fun tick() {
        if (pendingSketches.isEmpty()) return
        val now = clock.millis()
        for ((sid, sketch) in pendingSketches.entries.toList()) {
            val me = players.get(sid)
            if (me == null) {
                pendingSketches.remove(sid)
                continue
            }
            if (combat.isInCombat(sid)) {
                cancelSketch(sid, "The fight shatters your concentration — the sketch is ruined.")
                continue
            }
            if (me.roomId != sketch.roomId) {
                // Safety net for any movement path that skips onRoomVisited.
                cancelSketch(sid, "You have moved on — the sketch is abandoned.")
                continue
            }
            if (now < sketch.endsAtMs) continue
            pendingSketches.remove(sid)
            val mob = combat.mobById(sketch.mobId)
            if (mob == null || mob.roomId != me.roomId) {
                sendSketchCancel(sid, sketch, "Your subject is gone — the page stays unfinished.")
                continue
            }
            resolveSketch(sid, me, mob, sketch.subjectKey, now)
        }
    }

    /** Rolls and resolves a finished sketch — the tail of the old instant illuminate. */
    private suspend fun resolveSketch(
        sessionId: SessionId,
        me: PlayerState,
        mob: MobState,
        subjectKey: String,
        now: Long,
    ) {
        if (!mob.role.isCombatant) {
            observeNpc(sessionId, me, mob, subjectKey, now)
            return
        }
        val stats = resolvePlayerStats(me, items, statusEffects, classRegistry)
        val successPct = pledgeScaledPct(
            illuminationSuccessPct(
                statValue = stats[config.successStat],
                reliefStatValue = stats[config.gapReliefStat],
                playerLevel = me.level,
                mobLevel = mob.level,
            ),
            me.isAkathavae,
        )
        if (rng.nextInt(100) < successPct) {
            resolveSuccess(sessionId, me, mob, subjectKey, now)
        } else {
            resolveFailure(sessionId, me, mob, subjectKey, now, stats[config.escapeStat])
        }
    }

    /** Cancels [sessionId]'s in-progress sketch, if any, telling the player [reason]. */
    private suspend fun cancelSketch(
        sessionId: SessionId,
        reason: String,
    ) {
        val sketch = pendingSketches.remove(sessionId) ?: return
        sendSketchCancel(sessionId, sketch, reason)
    }

    private suspend fun sendSketchCancel(
        sessionId: SessionId,
        sketch: PendingSketch,
        reason: String,
    ) {
        outbound.send(OutboundEvent.SendText(sessionId, reason))
        gmcpEmitter?.sendArcanumSketch(
            sessionId,
            GmcpEmitter.ArcanumSketchPayload(
                phase = "cancel",
                mobId = sketch.mobId.value,
                mobName = sketch.mobName,
                subjectKey = sketch.subjectKey,
                observe = sketch.observe,
                reason = reason,
            ),
        )
        metrics.onGameEvent("akathavae", "sketch_cancel")
    }

    /**
     * Illumination success odds for [me] against [mob], resolving the player's
     * current effective stats (equipment + status effects included) — the same
     * inputs [illuminate] rolls against, including the unpledged scaling.
     * Surfaced to the player via `consider` and the `Room.MobInfo` GMCP payload
     * so nobody gambles blind.
     */
    fun illuminationOddsFor(
        me: PlayerState,
        mob: MobState,
    ): Int {
        val stats = resolvePlayerStats(me, items, statusEffects, classRegistry)
        return pledgeScaledPct(
            illuminationSuccessPct(
                statValue = stats[config.successStat],
                reliefStatValue = stats[config.gapReliefStat],
                playerLevel = me.level,
                mobLevel = mob.level,
            ),
            me.isAkathavae,
        )
    }

    /**
     * Scales success odds for the pledge state: the unpledged keep field notes,
     * not a practiced chronicler's hand, so their odds are cut by
     * [AkathavaeConfig.unpledgedSuccessMultiplier] (floored at 1%).
     */
    private fun pledgeScaledPct(
        pct: Int,
        pledged: Boolean,
    ): Int = if (pledged) pct else (pct * config.unpledgedSuccessMultiplier).toInt().coerceAtLeast(1)

    /**
     * Success chance: base + per-point bonus above [PlayerState.BASE_STAT] on the
     * success stat, minus a per-level penalty for subjects above the player's level
     * (softened per point of the gap-relief stat), clamped to the configured band.
     */
    internal fun illuminationSuccessPct(
        statValue: Int,
        reliefStatValue: Int,
        playerLevel: Int,
        mobLevel: Int,
    ): Int {
        val statBonus = (statValue - PlayerState.BASE_STAT) * config.successPerStatPoint
        val gap = (mobLevel - playerLevel).coerceAtLeast(0)
        val perLevelPenalty =
            (config.levelGapPenaltyPct - (reliefStatValue - PlayerState.BASE_STAT) * config.gapReliefPerStatPoint)
                .coerceAtLeast(0.0)
        val raw = config.illuminateBaseSuccessPct + statBonus - gap * perLevelPenalty
        return raw.toInt().coerceIn(config.minSuccessPct, config.maxSuccessPct)
    }

    private suspend fun resolveSuccess(
        sessionId: SessionId,
        me: PlayerState,
        mob: MobState,
        subjectKey: String,
        now: Long,
    ) {
        val entry = me.arcanum.mobs[subjectKey]
        val firstTime = entry == null
        recordEntry(me.arcanum.mobs, subjectKey, now, ArcanumSource.ILLUMINATED)

        outbound.send(
            OutboundEvent.SendText(
                sessionId,
                "You still your breath and illuminate ${mob.name} — line by line it takes shape in your Arcanum.",
            ),
        )
        broadcastToRoom(
            players,
            outbound,
            me.roomId,
            "${me.name} studies ${mob.name} with quiet intensity, committing it to the Arcanum.",
            exclude = sessionId,
        )
        val worldFirst = announceWorldFirst(sessionId, me, "mob:$subjectKey", mob.name, now, firstTimeForPlayer = firstTime)
        if (firstTime) onArcanumRecorded?.invoke(sessionId)

        val baseXp = progression.killXpReward(mob)
        var xpGained = 0L
        if (firstTime) {
            xpGained = awardDiscoveryXp(sessionId, me, baseXp, "first illumination", now)
        } else {
            val cooledDown = now >= entry.lastXpAtMs + config.repeatXpCooldownMs
            if (cooledDown) {
                me.arcanum.mobs[subjectKey]?.lastXpAtMs = now
                xpGained = awardDiscoveryXp(
                    sessionId,
                    me,
                    (baseXp * config.repeatXpFraction).toLong().coerceAtLeast(1L),
                    "repeat illumination",
                    now,
                )
            } else {
                outbound.send(OutboundEvent.SendText(sessionId, "Your Arcanum already holds this page; it yields nothing new yet."))
            }
        }
        gmcpEmitter?.sendArcanumSketch(
            sessionId,
            GmcpEmitter.ArcanumSketchPayload(
                phase = "success",
                mobId = mob.id.value,
                mobName = mob.name,
                subjectKey = subjectKey,
                xpGained = xpGained,
                firstTime = firstTime,
                worldFirst = worldFirst,
            ),
        )

        if (me.isAkathavae) {
            // Catalogue what the creature carries as Arcanum pages — this feeds the
            // wardrobe — but take nothing: the subject is unharmed and stays put.
            // The illumination XP above just armed the discovery-XP throttle, so these
            // same-action item awards bypass it — otherwise the items get recorded with
            // their XP silently and permanently swallowed.
            for (drop in mob.drops) {
                recordItemDiscovery(sessionId, drop.itemId, ArcanumSource.ILLUMINATED, bypassThrottle = true)
            }
        }

        // A recorded creature counts as a defeated one — but only once per living
        // instance, since illumination no longer consumes it. Kill credit and quest
        // rubbings stay pledged-only (the unpledged can fight for their objectives,
        // so a sketch must not bypass the fight), while daily illumination
        // commissions count the recording itself, whoever holds the pen.
        if (creditedIlluminations.getOrPut(sessionId) { mutableSetOf() }.add(mob.id.value)) {
            if (me.isAkathavae) {
                combat.creditIlluminationKill(sessionId, mob)
                grantNeededQuestItems(sessionId, mob)
            }
            onIlluminated?.invoke(sessionId)
        }
        if (firstTime) {
            checkZoneCompletion(sessionId, me, subjectKey.substringBefore(':'), now)
        }
        markVitalsDirty?.invoke(sessionId)
        emitStatus(sessionId)
        refreshRoomMobInfo?.invoke(sessionId)
    }

    /**
     * The collect-objective half of the illumination bridge (#1392): kill
     * credit covers kill objectives, but the pledged never loot, so a collect
     * item that only drops from mobs would otherwise be unobtainable. For each
     * of the subject's drops still needed by an active, unfinished collect
     * objective, one real copy is granted into the inventory — deterministic
     * (no drop-chance roll: if the quest needs it and the mob can drop it, the
     * rubbing succeeds) and farm-proof, because this path is reachable only
     * through the once-per-living-instance credit gate and re-checks the
     * remaining need per drop entry, so no sellable surplus can accumulate.
     * Granting through the inventory keeps the downstream flow intact:
     * [QuestSystem.onItemCollected] advances the objective and turn-in
     * consumption later finds the item it expects.
     */
    private suspend fun grantNeededQuestItems(
        sessionId: SessionId,
        mob: MobState,
    ) {
        val quests = quests ?: return
        for (drop in mob.drops) {
            if (quests.neededForCollectObjectives(sessionId, drop.itemId) <= 0) continue
            val instance = items.createFromTemplate(drop.itemId) ?: continue
            items.addToInventory(sessionId, instance)
            outbound.send(
                OutboundEvent.SendText(
                    sessionId,
                    "Sketching its likeness, you take a careful rubbing of ${instance.item.displayName} for your quest.",
                ),
            )
            gmcpEmitter?.sendCharItemsAdd(sessionId, instance)
            quests.onItemCollected(sessionId, instance)
            metrics.onGameEvent("akathavae", "quest_item_rubbing")
        }
    }

    private suspend fun resolveFailure(
        sessionId: SessionId,
        me: PlayerState,
        mob: MobState,
        subjectKey: String,
        now: Long,
        escapeStatValue: Int,
    ) {
        failCooldowns.getOrPut(sessionId) { mutableMapOf() }[subjectKey] = now + config.failRetryCooldownMs
        metrics.onGameEvent("akathavae", "illuminate_fail")
        outbound.send(
            OutboundEvent.SendText(
                sessionId,
                "Your hand slips — the likeness twists wrong, and ${mob.name} notices you staring.",
            ),
        )

        val escapePct =
            ((escapeStatValue - PlayerState.BASE_STAT) * config.escapePerStatPoint)
                .toInt()
                .coerceIn(0, 95)
        val talkedOut = escapePct > 0 && rng.nextInt(100) < escapePct
        if (talkedOut) {
            outbound.send(
                OutboundEvent.SendText(
                    sessionId,
                    "You raise empty hands and offer a few soothing words — ${mob.name} loses interest in you.",
                ),
            )
            metrics.onGameEvent("akathavae", "illuminate_talked_out")
        } else {
            combat.engageMobCombat(sessionId, mob)
            val cry =
                if (me.isAkathavae) {
                    "${theCap(mob.name)} turns on you! Your pledge holds — flee!"
                } else {
                    "${theCap(mob.name)} turns on you!"
                }
            outbound.send(OutboundEvent.SendText(sessionId, cry))
        }
        gmcpEmitter?.sendArcanumSketch(
            sessionId,
            GmcpEmitter.ArcanumSketchPayload(
                phase = "fail",
                mobId = mob.id.value,
                mobName = mob.name,
                subjectKey = subjectKey,
                hostile = !talkedOut,
            ),
        )
    }

    private suspend fun observeNpc(
        sessionId: SessionId,
        me: PlayerState,
        mob: MobState,
        subjectKey: String,
        now: Long,
    ) {
        val firstTime = !me.arcanum.mobs.containsKey(subjectKey)
        recordEntry(me.arcanum.mobs, subjectKey, now, ArcanumSource.OBSERVED)
        var worldFirst = false
        var xpGained = 0L
        if (firstTime) {
            outbound.send(
                OutboundEvent.SendText(
                    sessionId,
                    "You quietly observe ${mob.name}, noting their manner and bearing in your Arcanum.",
                ),
            )
            worldFirst = announceWorldFirst(sessionId, me, "mob:$subjectKey", mob.name, now)
            onArcanumRecorded?.invoke(sessionId)
            xpGained = awardDiscoveryXp(sessionId, me, config.observeNpcXp, "observation", now)
            // First-time observations count toward illumination commissions —
            // recording a subject matters, not whether it was dangerous.
            onIlluminated?.invoke(sessionId)
            checkZoneCompletion(sessionId, me, subjectKey.substringBefore(':'), now)
            markVitalsDirty?.invoke(sessionId)
            emitStatus(sessionId)
            refreshRoomMobInfo?.invoke(sessionId)
        } else {
            outbound.send(OutboundEvent.SendText(sessionId, "Your Arcanum already holds a page on ${mob.name}."))
        }
        gmcpEmitter?.sendArcanumSketch(
            sessionId,
            GmcpEmitter.ArcanumSketchPayload(
                phase = "success",
                mobId = mob.id.value,
                mobName = mob.name,
                subjectKey = subjectKey,
                observe = true,
                xpGained = xpGained,
                firstTime = firstTime,
                worldFirst = worldFirst,
            ),
        )
    }

    // ── Passive discovery ────────────────────────────────────────────────

    /** Records the player's current room. Fires on every movement; no-op unless pledged and new. */
    suspend fun onRoomVisited(sessionId: SessionId) {
        val me = players.get(sessionId) ?: return
        // Movement is the primary sketch interrupt — you cannot draw and walk.
        if (pendingSketches[sessionId]?.roomId?.let { it != me.roomId } == true) {
            cancelSketch(sessionId, "You have moved on — the sketch is abandoned.")
        }
        if (!me.isAkathavae) return
        val roomId = me.roomId
        if (world.rooms[roomId] == null) return
        val key = roomId.value
        if (me.arcanum.rooms.containsKey(key)) return
        val now = clock.millis()
        recordEntry(me.arcanum.rooms, key, now, ArcanumSource.VISITED)
        val title = world.rooms[roomId]?.title ?: roomId.value
        outbound.send(OutboundEvent.SendText(sessionId, "[Arcanum] You record $title."))
        announceWorldFirst(sessionId, me, "room:$key", title, now)
        onArcanumRecorded?.invoke(sessionId)
        awardDiscoveryXp(sessionId, me, roomDiscoveryXp(roomId.zone), "discovery", now)
        checkZoneCompletion(sessionId, me, roomId.zone, now)
        markVitalsDirty?.invoke(sessionId)
        emitStatus(sessionId)
    }

    /**
     * Room-discovery XP for [zone]: the flat base plus a per-level bonus on the
     * zone's average mob-template level, so high-level zones pay high-level rates.
     */
    internal fun roomDiscoveryXp(zone: String): Long =
        config.roomDiscoveryXp + avgZoneMobLevel(zone) * config.roomDiscoveryXpPerZoneLevel

    private fun avgZoneMobLevel(zone: String): Int = zoneAvgMobLevel.getOrPut(zone) {
        val levels = world.mobTemplates
            .filterKeys { it.value.substringBefore(':') == zone }
            .values.map { it.level }
        if (levels.isEmpty()) 0 else levels.average().toInt()
    }

    /**
     * Records an item discovery by template id — the entry point for shop
     * purchases, crafting outputs, and gathering yields, where only the
     * template id is at hand.
     */
    suspend fun recordItemDiscovery(
        sessionId: SessionId,
        itemId: dev.ambon.domain.ids.ItemId,
        source: String,
        bypassThrottle: Boolean = false,
    ) {
        val template = items.getTemplate(itemId) ?: return
        recordItemDiscovery(sessionId, ItemInstance(id = itemId, item = template), source, bypassThrottle)
    }

    /**
     * Records [item] in the player's Arcanum (first time only awards XP). Called
     * for illumination captures now; shop purchases, crafting, and gathering hook
     * in via the same entry point.
     */
    suspend fun recordItemDiscovery(
        sessionId: SessionId,
        item: ItemInstance,
        source: String,
        bypassThrottle: Boolean = false,
    ) {
        val me = players.get(sessionId) ?: return
        if (!me.isAkathavae) return
        val key = item.id.value
        if (me.arcanum.items.containsKey(key)) {
            me.arcanum.items[key]?.let { it.timesRecorded += 1 }
            return
        }
        val now = clock.millis()
        recordEntry(me.arcanum.items, key, now, source)
        outbound.send(OutboundEvent.SendText(sessionId, "[Arcanum] You record ${item.item.displayName}."))
        announceWorldFirst(sessionId, me, "item:$key", item.item.displayName, now)
        onArcanumRecorded?.invoke(sessionId)
        awardDiscoveryXp(sessionId, me, config.itemDiscoveryXp, "discovery", now, bypassThrottle)
        markVitalsDirty?.invoke(sessionId)
        emitStatus(sessionId)
    }

    // ── First slain ──────────────────────────────────────────────────────

    /**
     * Stamps the permanent "First slain by" record when [sessionId]'s killing
     * blow on [mob] is the first in the world's history — the combat-side
     * sibling of the illumination world-first, sitting beside it in the
     * Arcanum. Open to anyone (the pledged never land killing blows while
     * their vow holds), and unlike illumination firsts it needs no shrine
     * seal: a corpse is its own certification. Wired from [CombatSystem]'s
     * mob-death path via GameEngine.
     */
    suspend fun onMobSlain(
        sessionId: SessionId,
        mob: MobState,
    ) {
        val ws = worldState ?: return
        val me = players.get(sessionId) ?: return
        if (mob.isPet || !mob.role.isCombatant || mob.templateKey.isEmpty()) return
        if (ws.recordArcanumFirst("slain:${mobSubjectKey(mob)}", me.name, clock.millis())) {
            outbound.send(
                OutboundEvent.SendInfo(
                    sessionId,
                    "★ ${theCap(mob.name)} falls for the first time in recorded history — " +
                        "the Arcanum will forever read \"First slain by ${me.name}.\"",
                ),
            )
            metrics.onGameEvent("akathavae", "world_first_slain")
        }
    }

    // ── Zone completion ──────────────────────────────────────────────────

    /**
     * Pays the one-time zone-completion bundle if this recording brought [zone]
     * to 100% — every room visited and every mob template recorded, the same
     * numerator/denominator [zoneCompletion] reports. Completed zones persist in
     * the Arcanum blob, so the bundle never re-fires across relogs.
     */
    private suspend fun checkZoneCompletion(
        sessionId: SessionId,
        me: PlayerState,
        zone: String,
        now: Long,
    ) {
        if (zone in me.arcanum.completedZones) return
        val completion = zoneCompletion(me, zone)
        // A zone with nothing to record can't be "completed" — guards degenerate keys.
        if (completion.roomsTotal == 0 && completion.mobsTotal == 0) return
        if (completion.roomsRecorded < completion.roomsTotal || completion.mobsRecorded < completion.mobsTotal) return
        me.arcanum.completedZones.add(zone)
        metrics.onGameEvent("akathavae", "zone_completed")

        outbound.send(
            OutboundEvent.SendInfo(
                sessionId,
                "★ Your Arcanum's record of $zone is complete — every room walked, every creature illuminated.",
            ),
        )
        awardDiscoveryXp(
            sessionId,
            me,
            completion.roomsTotal * config.zoneCompletionXpPerRoom,
            "zone completion",
            now,
            bypassThrottle = true,
        )
        if (config.zoneCompletionGold > 0) {
            me.gold += config.zoneCompletionGold
            outbound.send(
                OutboundEvent.SendText(sessionId, "Collectors of the Arcanum pay ${config.zoneCompletionGold} gold for your record."),
            )
        }
        broadcastToRoom(
            players,
            outbound,
            me.roomId,
            "${me.name}'s Arcanum flares with light — their record of $zone is complete.",
            exclude = sessionId,
        )
        broadcastInfoToAll(
            players,
            outbound,
            "★ ${me.name} has completed the Arcanum record of $zone.",
            exclude = sessionId,
        )
    }

    // ── Journal stats (for the arcanum command and, later, GMCP) ─────────

    data class ZoneCompletion(
        val zone: String,
        val roomsRecorded: Int,
        val roomsTotal: Int,
        val mobsRecorded: Int,
        val mobsTotal: Int,
        val itemsRecorded: Int,
        val itemsTotal: Int,
    )

    /** Per-zone completion for [me], for every zone with at least one recorded entry plus the current zone. */
    fun zoneCompletion(me: PlayerState, zone: String): ZoneCompletion {
        val roomsTotal = world.rooms.keys.count { it.zone == zone }
        val roomsRecorded = me.arcanum.rooms.keys.count { RoomId(it).zone == zone }
        val mobsTotal = world.mobTemplates.keys.count { it.value.substringBefore(':') == zone }
        val mobsRecorded = me.arcanum.mobs.keys.count { it.substringBefore(':') == zone }
        val itemsTotal = items.templateCountForZone(zone)
        val itemsRecorded = me.arcanum.items.keys.count { it.contains(':') && it.substringBefore(':') == zone }
        return ZoneCompletion(zone, roomsRecorded, roomsTotal, mobsRecorded, mobsTotal, itemsRecorded, itemsTotal)
    }

    /** Every zone touched by [me]'s journal — rooms, mobs, and items — in sorted order. */
    fun recordedZones(me: PlayerState): List<String> = (
        me.arcanum.rooms.keys.map { it.substringBefore(':') } +
            me.arcanum.mobs.keys.map { it.substringBefore(':') } +
            me.arcanum.items.keys.filter { it.contains(':') }.map { it.substringBefore(':') }
    ).toSortedSet().toList()

    /** Returns the permanent world-first credit line for a subject, or null. */
    fun worldFirstCredit(kind: String, subjectKey: String): Pair<String, Long>? =
        worldState?.getArcanumFirst("$kind:$subjectKey")

    /** Pushes the lightweight pledge + counts sync to the client. */
    suspend fun emitStatus(sessionId: SessionId) {
        val me = players.get(sessionId) ?: return
        gmcpEmitter?.sendArcanumStatus(sessionId, me)
    }

    /** Builds and pushes the full journal payload for the Arcanum panel. */
    suspend fun emitJournal(sessionId: SessionId) {
        val emitter = gmcpEmitter ?: return
        val me = players.get(sessionId) ?: return
        val zones = recordedZones(me).map { zoneCompletion(me, it) }
        val payload = GmcpEmitter.ArcanumJournalPayload(
            pledged = me.isAkathavae,
            zones = zones.map {
                GmcpEmitter.ArcanumZonePayload(
                    zone = it.zone,
                    roomsRecorded = it.roomsRecorded,
                    roomsTotal = it.roomsTotal,
                    mobsRecorded = it.mobsRecorded,
                    mobsTotal = it.mobsTotal,
                    itemsRecorded = it.itemsRecorded,
                    itemsTotal = it.itemsTotal,
                )
            },
            mobs = me.arcanum.mobs.entries.sortedBy { it.key }.map { (key, entry) ->
                val template = world.mobTemplate(dev.ambon.domain.ids.MobId(key))
                val first = worldFirstCredit("mob", key)
                val slain = worldFirstCredit("slain", key)
                GmcpEmitter.ArcanumMobPayload(
                    key = key,
                    name = template?.name ?: key.substringAfter(':').replace('_', ' '),
                    image = template?.image,
                    timesRecorded = entry.timesRecorded,
                    firstRecordedAtMs = entry.firstRecordedAtMs,
                    source = entry.source,
                    firstBy = first?.first,
                    firstAtMs = first?.second,
                    firstSlainBy = slain?.first,
                    firstSlainAtMs = slain?.second,
                )
            },
            items = me.arcanum.items.entries.sortedBy { it.key }.map { (key, entry) ->
                val template = items.getTemplate(dev.ambon.domain.ids.ItemId(key))
                val first = worldFirstCredit("item", key)
                GmcpEmitter.ArcanumItemPayload(
                    key = key,
                    name = template?.displayName ?: key.substringAfter(':').replace('_', ' '),
                    image = template?.image,
                    slot = template?.slot?.label(),
                    wearable = template?.slot != null,
                    timesRecorded = entry.timesRecorded,
                    firstRecordedAtMs = entry.firstRecordedAtMs,
                    source = entry.source,
                    firstBy = first?.first,
                )
            },
            rooms = me.arcanum.rooms.entries.sortedBy { it.key }.map { (key, entry) ->
                val roomId = RoomId(key)
                val first = worldFirstCredit("room", key)
                GmcpEmitter.ArcanumRoomPayload(
                    key = key,
                    title = world.rooms[roomId]?.title ?: key,
                    zone = roomId.zone,
                    firstRecordedAtMs = entry.firstRecordedAtMs,
                    firstBy = first?.first,
                )
            },
        )
        emitter.sendArcanumJournal(sessionId, payload)
    }

    // ── Internals ────────────────────────────────────────────────────────

    private fun recordEntry(
        map: MutableMap<String, ArcanumEntry>,
        key: String,
        now: Long,
        source: String,
    ) {
        val existing = map[key]
        if (existing == null) {
            map[key] = ArcanumEntry(firstRecordedAtMs = now, timesRecorded = 1, lastXpAtMs = now, source = source)
        } else {
            existing.timesRecorded += 1
        }
    }

    /** Returns true when this call sealed the world-first for [firstKey]. */
    private suspend fun announceWorldFirst(
        sessionId: SessionId,
        me: PlayerState,
        firstKey: String,
        displayName: String,
        now: Long,
        firstTimeForPlayer: Boolean = true,
    ): Boolean {
        val ws = worldState ?: return false
        if (!me.isAkathavae) {
            // Only a pledged Akathavae's journal is accepted at the shrines: the
            // unpledged see the unclaimed page, but cannot seal it. Noted only on
            // the player's own first recording, so repeats stay quiet.
            if (firstTimeForPlayer && ws.getArcanumFirst(firstKey) == null) {
                outbound.send(
                    OutboundEvent.SendInfo(
                        sessionId,
                        "No account of $displayName exists in the shrine records — but only a pledged " +
                            "Akathavae's journal is accepted there. The page stays unsealed.",
                    ),
                )
            }
            return false
        }
        if (ws.recordArcanumFirst(firstKey, me.name, now)) {
            me.worldFirstsCount += 1
            outbound.send(
                OutboundEvent.SendInfo(
                    sessionId,
                    "★ Yours is the first account of $displayName — the Arcanum will forever read \"First illuminated by ${me.name}.\"",
                ),
            )
            metrics.onGameEvent("akathavae", "world_first")
            onArcanumRecorded?.invoke(sessionId)
            return true
        }
        return false
    }

    /**
     * Grants discovery XP scaled by the XP stat, behind the anti-speedrun throttle.
     * Unpledged awards are first scaled by [AkathavaeConfig.unpledgedXpMultiplier];
     * a zero result awards nothing (and leaves the throttle unarmed).
     *
     * [bypassThrottle] serves both intra-action awards (e.g. cataloguing a mob's
     * drops in the same illumination that just awarded XP) and one-off milestone
     * awards (zone completion): it skips the throttle *check* so the award is never
     * swallowed, but still advances the next-award window, so a bypassed award
     * never opens a farming window.
     */
    private suspend fun awardDiscoveryXp(
        sessionId: SessionId,
        me: PlayerState,
        baseAmount: Long,
        label: String,
        now: Long,
        bypassThrottle: Boolean = false,
    ): Long {
        val pledgeScaled = if (me.isAkathavae) baseAmount else (baseAmount * config.unpledgedXpMultiplier).toLong()
        if (pledgeScaled <= 0L) return 0L
        if (!bypassThrottle && now < (nextDiscoveryXpAt[sessionId] ?: 0L)) return 0L
        nextDiscoveryXpAt[sessionId] = now + config.discoveryXpThrottleMs

        val stats = resolvePlayerStats(me, items, statusEffects, classRegistry)
        val bonus = (stats[config.xpStat] - PlayerState.BASE_STAT) * config.xpBonusPerStatPoint
        val amount = (pledgeScaled * (1.0 + bonus.coerceAtLeast(-0.5))).toLong().coerceAtLeast(1L)

        val result = players.grantXp(sessionId, amount, progression) ?: return 0L
        metrics.onXpAwarded(amount, "illumination")
        outbound.send(OutboundEvent.SendText(sessionId, "You gain $amount XP ($label)."))
        markVitalsDirty?.invoke(sessionId)
        if (result.levelsGained > 0) {
            metrics.onLevelUp()
            val message = progression.buildLevelUpMessage(
                result,
                me.stats[progression.bindings.hpScalingStat],
                me.stats[progression.bindings.manaScalingStat],
                me.playerClass,
            )
            outbound.send(OutboundEvent.SendText(sessionId, message))
            onLevelUp?.invoke(sessionId, result)
        }
        return amount
    }
}

/** "the rat" → "The rat" for sentence starts, mirroring HandlerHelpers' theCap. */
private fun theCap(displayName: String): String {
    val lower = displayName.lowercase()
    val withArticle =
        if (lower.startsWith("the ") || lower.startsWith("a ") || lower.startsWith("an ")) {
            displayName
        } else {
            "the $displayName"
        }
    return withArticle.replaceFirstChar { it.uppercaseChar() }
}
