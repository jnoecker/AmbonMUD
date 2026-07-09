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
 * Illumination — the Akathavae's replacement for combat.
 *
 * A pledged player `illuminate`s a creature to record it in their Arcanum.
 * Illumination never harms the subject: it leaves the creature exactly where it
 * stands, so even quest-givers and unique mobs are safe — and free — to record.
 * Success is stat-driven (success stat raises the chance, the subject's level
 * above the player lowers it, the gap-relief stat softens that penalty) and pays
 * first-time-big / repeat-small XP scaled by the XP stat. The subject's possible
 * drops are catalogued into the Arcanum as templates — feeding the wardrobe —
 * without anything being taken from it. A recorded creature still counts as a
 * defeated one for quest/achievement credit (once per living instance), so the
 * pledged complete the same objectives as everyone else. Failure puts the subject
 * on a retry cooldown and turns it hostile unless the escape stat talks the player
 * out of it.
 *
 * The system also records passive discoveries: rooms visited and items first
 * seen, each worth XP behind an anti-speedrun throttle. Non-combat NPCs can be
 * observed (recorded without removal) for a small award.
 *
 * World-firsts: the first player ever to illuminate a subject is stamped
 * permanently into [WorldStateRegistry] — "First illuminated by Thalen."
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
) {
    /** Per-session, per-subject retry locks after a failed illumination. Runtime-only. */
    private val failCooldowns = mutableMapOf<SessionId, MutableMap<String, Long>>()

    /** Per-session anti-speedrun throttle on discovery XP awards. Runtime-only. */
    private val nextDiscoveryXpAt = mutableMapOf<SessionId, Long>()

    /**
     * Per-session set of mob *instance* ids already credited as illumination
     * kills. Mirrors the old capture path, where the subject was consumed: a given
     * living creature grants quest/achievement credit once, so a persistent
     * subject can't be re-illuminated to farm kill objectives. Runtime-only.
     */
    private val creditedIlluminations = mutableMapOf<SessionId, MutableSet<String>>()

    fun onSessionRemoved(sessionId: SessionId) {
        failCooldowns.remove(sessionId)
        nextDiscoveryXpAt.remove(sessionId)
        creditedIlluminations.remove(sessionId)
    }

    // ── Illumination ─────────────────────────────────────────────────────

    suspend fun illuminate(
        sessionId: SessionId,
        keywordRaw: String,
    ) {
        val me = players.get(sessionId) ?: return
        if (!me.isAkathavae) {
            outbound.send(
                OutboundEvent.SendError(
                    sessionId,
                    "Only those who have taken the Akathavae pledge may illuminate. Seek a shrine.",
                ),
            )
            return
        }
        val keyword = keywordRaw.trim()
        if (keyword.isEmpty()) {
            outbound.send(OutboundEvent.SendError(sessionId, "Illuminate what?"))
            return
        }
        if (combat.isInCombat(sessionId)) {
            outbound.send(OutboundEvent.SendError(sessionId, "You cannot hold a steady image while under attack — flee first."))
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

        if (!mob.role.isCombatant) {
            observeNpc(sessionId, me, mob, subjectKey, now)
            return
        }

        val stats = resolvePlayerStats(me, items, statusEffects, classRegistry)
        val successPct = illuminationSuccessPct(
            statValue = stats[config.successStat],
            reliefStatValue = stats[config.gapReliefStat],
            playerLevel = me.level,
            mobLevel = mob.level,
        )
        if (rng.nextInt(100) < successPct) {
            resolveSuccess(sessionId, me, mob, subjectKey, now)
        } else {
            resolveFailure(sessionId, me, mob, subjectKey, now, stats[config.escapeStat])
        }
    }

    /**
     * Illumination success odds for [me] against [mob], resolving the player's
     * current effective stats (equipment + status effects included) — the same
     * inputs [illuminate] rolls against. Surfaced to the player via `consider`
     * and the `Room.MobInfo` GMCP payload so the pledged never gamble blind.
     */
    fun illuminationOddsFor(
        me: PlayerState,
        mob: MobState,
    ): Int {
        val stats = resolvePlayerStats(me, items, statusEffects, classRegistry)
        return illuminationSuccessPct(
            statValue = stats[config.successStat],
            reliefStatValue = stats[config.gapReliefStat],
            playerLevel = me.level,
            mobLevel = mob.level,
        )
    }

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
        announceWorldFirst(sessionId, me, "mob:$subjectKey", mob.name, now)

        val baseXp = progression.killXpReward(mob)
        if (firstTime) {
            awardDiscoveryXp(sessionId, me, baseXp, "first illumination", now)
        } else {
            val cooledDown = now >= entry.lastXpAtMs + config.repeatXpCooldownMs
            if (cooledDown) {
                me.arcanum.mobs[subjectKey]?.lastXpAtMs = now
                awardDiscoveryXp(
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

        // Catalogue what the creature carries as Arcanum pages — this feeds the
        // wardrobe — but take nothing: the subject is unharmed and stays put.
        // The illumination XP above just armed the discovery-XP throttle, so these
        // same-action item awards bypass it — otherwise the items get recorded with
        // their XP silently and permanently swallowed.
        for (drop in mob.drops) {
            recordItemDiscovery(sessionId, drop.itemId, ArcanumSource.ILLUMINATED, bypassThrottle = true)
        }

        // A recorded creature counts as a defeated one for quests and achievements,
        // so the pledged complete the same objectives as everyone else — but only
        // once per living instance, since illumination no longer consumes it.
        if (creditedIlluminations.getOrPut(sessionId) { mutableSetOf() }.add(mob.id.value)) {
            combat.creditIlluminationKill(sessionId, mob)
        }
        markVitalsDirty?.invoke(sessionId)
        emitStatus(sessionId)
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
        if (escapePct > 0 && rng.nextInt(100) < escapePct) {
            outbound.send(
                OutboundEvent.SendText(
                    sessionId,
                    "You raise empty hands and offer a few soothing words — ${mob.name} loses interest in you.",
                ),
            )
            metrics.onGameEvent("akathavae", "illuminate_talked_out")
            return
        }

        combat.engageMobCombat(sessionId, mob)
        outbound.send(OutboundEvent.SendText(sessionId, "${theCap(mob.name)} turns on you! Your pledge holds — flee!"))
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
        if (firstTime) {
            outbound.send(
                OutboundEvent.SendText(
                    sessionId,
                    "You quietly observe ${mob.name}, noting their manner and bearing in your Arcanum.",
                ),
            )
            announceWorldFirst(sessionId, me, "mob:$subjectKey", mob.name, now)
            awardDiscoveryXp(sessionId, me, config.observeNpcXp, "observation", now)
            markVitalsDirty?.invoke(sessionId)
            emitStatus(sessionId)
        } else {
            outbound.send(OutboundEvent.SendText(sessionId, "Your Arcanum already holds a page on ${mob.name}."))
        }
    }

    // ── Passive discovery ────────────────────────────────────────────────

    /** Records the player's current room. Fires on every movement; no-op unless pledged and new. */
    suspend fun onRoomVisited(sessionId: SessionId) {
        val me = players.get(sessionId) ?: return
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
        awardDiscoveryXp(sessionId, me, config.roomDiscoveryXp, "discovery", now)
        markVitalsDirty?.invoke(sessionId)
        emitStatus(sessionId)
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
        awardDiscoveryXp(sessionId, me, config.itemDiscoveryXp, "discovery", now, bypassThrottle)
        markVitalsDirty?.invoke(sessionId)
        emitStatus(sessionId)
    }

    // ── Journal stats (for the arcanum command and, later, GMCP) ─────────

    data class ZoneCompletion(
        val zone: String,
        val roomsRecorded: Int,
        val roomsTotal: Int,
        val mobsRecorded: Int,
        val mobsTotal: Int,
    )

    /** Per-zone completion for [me], for every zone with at least one recorded entry plus the current zone. */
    fun zoneCompletion(me: PlayerState, zone: String): ZoneCompletion {
        val roomsTotal = world.rooms.keys.count { it.zone == zone }
        val roomsRecorded = me.arcanum.rooms.keys.count { RoomId(it).zone == zone }
        val mobsTotal = world.mobTemplates.keys.count { it.value.substringBefore(':') == zone }
        val mobsRecorded = me.arcanum.mobs.keys.count { it.substringBefore(':') == zone }
        return ZoneCompletion(zone, roomsRecorded, roomsTotal, mobsRecorded, mobsTotal)
    }

    /** Returns the permanent world-first credit line for a subject, or null. */
    fun worldFirstCredit(kind: String, subjectKey: String): Pair<String, Long>? =
        worldState?.getArcanumFirst("$kind:$subjectKey")

    /** Pushes the lightweight pledge + counts sync to the client. */
    suspend fun emitStatus(sessionId: SessionId) {
        val me = players.get(sessionId) ?: return
        gmcpEmitter?.sendArcanumStatus(
            sessionId,
            pledged = me.isAkathavae,
            rooms = me.arcanum.rooms.size,
            mobs = me.arcanum.mobs.size,
            items = me.arcanum.items.size,
        )
    }

    /** Builds and pushes the full journal payload for the Arcanum panel. */
    suspend fun emitJournal(sessionId: SessionId) {
        val emitter = gmcpEmitter ?: return
        val me = players.get(sessionId) ?: return
        val zones = (
            me.arcanum.rooms.keys.map { it.substringBefore(':') } +
                me.arcanum.mobs.keys.map { it.substringBefore(':') }
        ).toSortedSet().map { zoneCompletion(me, it) }
        val payload = GmcpEmitter.ArcanumJournalPayload(
            pledged = me.isAkathavae,
            zones = zones.map {
                GmcpEmitter.ArcanumZonePayload(it.zone, it.roomsRecorded, it.roomsTotal, it.mobsRecorded, it.mobsTotal)
            },
            mobs = me.arcanum.mobs.entries.sortedBy { it.key }.map { (key, entry) ->
                val template = world.mobTemplate(dev.ambon.domain.ids.MobId(key))
                val first = worldFirstCredit("mob", key)
                GmcpEmitter.ArcanumMobPayload(
                    key = key,
                    name = template?.name ?: key.substringAfter(':').replace('_', ' '),
                    image = template?.image,
                    timesRecorded = entry.timesRecorded,
                    firstRecordedAtMs = entry.firstRecordedAtMs,
                    source = entry.source,
                    firstBy = first?.first,
                    firstAtMs = first?.second,
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

    private fun mobSubjectKey(mob: MobState): String = mob.templateKey.ifEmpty { mob.id.value }

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

    private suspend fun announceWorldFirst(
        sessionId: SessionId,
        me: PlayerState,
        firstKey: String,
        displayName: String,
        now: Long,
    ) {
        val ws = worldState ?: return
        if (ws.recordArcanumFirst(firstKey, me.name, now)) {
            outbound.send(
                OutboundEvent.SendInfo(
                    sessionId,
                    "★ Yours is the first account of $displayName — the Arcanum will forever read \"First illuminated by ${me.name}.\"",
                ),
            )
            metrics.onGameEvent("akathavae", "world_first")
        }
    }

    /**
     * Grants discovery XP scaled by the XP stat, behind the anti-speedrun throttle.
     *
     * [bypassThrottle] is for intra-action awards only (e.g. cataloguing a mob's
     * drops in the same illumination that just awarded XP): it skips the throttle
     * check but still updates the next-award window, so a bypassed award never
     * opens a farming window.
     */
    private suspend fun awardDiscoveryXp(
        sessionId: SessionId,
        me: PlayerState,
        baseAmount: Long,
        label: String,
        now: Long,
        bypassThrottle: Boolean = false,
    ) {
        if (baseAmount <= 0L) return
        if (!bypassThrottle && now < (nextDiscoveryXpAt[sessionId] ?: 0L)) return
        nextDiscoveryXpAt[sessionId] = now + config.discoveryXpThrottleMs

        val stats = resolvePlayerStats(me, items, statusEffects, classRegistry)
        val bonus = (stats[config.xpStat] - PlayerState.BASE_STAT) * config.xpBonusPerStatPoint
        val amount = (baseAmount * (1.0 + bonus.coerceAtLeast(-0.5))).toLong().coerceAtLeast(1L)

        val result = players.grantXp(sessionId, amount, progression) ?: return
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
