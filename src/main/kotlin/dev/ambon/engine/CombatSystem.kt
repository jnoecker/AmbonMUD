package dev.ambon.engine

import dev.ambon.bus.OutboundBus
import dev.ambon.config.DeathConfig
import dev.ambon.config.StatBindingsConfig
import dev.ambon.domain.StatMap
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobSpell
import dev.ambon.domain.mob.MobState
import dev.ambon.engine.events.CombatEvent
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.status.StatusEffectSystem
import dev.ambon.metrics.GameMetrics
import java.time.Clock
import java.util.Random
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

data class CombatSystemConfig(
    val tickMillis: Long = 1_000L,
    val healingThreatMultiplier: Double = 0.5,
    val groupXpBonusPerMember: Double = 0.10,
    val detailedFeedbackEnabled: Boolean = false,
    val detailedFeedbackRoomBroadcastEnabled: Boolean = false,
    val bindings: StatBindingsConfig = StatBindingsConfig(),
)

/** Seven-tier verbal threat rating, ordered from safest to deadliest. */
enum class ConsiderRating(
    val label: String,
    val flavor: String,
) {
    TRIVIAL("trivial", "barely a warm-up — they would fall in moments."),
    EASY("easy", "you should win without breaking a sweat."),
    FAVORED("favored", "the odds favor you, but you'll take some hits."),
    EVEN("even", "an even match — it could go either way."),
    RISKY("risky", "you would be hard pressed to come out on top."),
    DANGEROUS("dangerous", "this fight would likely be the end of you."),
    SUICIDAL("suicidal", "you'd be cut down in seconds. Run."),
}

/** Result of a successful `consider` calculation — fed to both narrative output and GMCP. */
data class ConsiderResult(
    val mobId: String,
    val mobName: String,
    val mobLevel: Int,
    val mobMaxHp: Int,
    val mobCategory: String,
    val mobImage: String?,
    val playerLevel: Int,
    val playerMaxHp: Int,
    val playerAvgDamage: Int,
    val mobAvgDamage: Int,
    val hitsToKillMob: Int,
    val hitsToKillPlayer: Int,
    val dodgeChancePct: Int,
    val winChancePct: Int,
    val rating: ConsiderRating,
)

sealed interface ConsiderOutcome {
    data class Ok(
        val result: ConsiderResult,
    ) : ConsiderOutcome

    data class Error(
        val message: String,
    ) : ConsiderOutcome
}

data class CombatSystemCallbacks(
    val onMobRemoved: suspend (MobId, RoomId) -> Unit = { _, _ -> },
    val onLevelUp: suspend (SessionId, LevelUpResult) -> Unit = { _, _ -> },
    val onMobKilledByPlayer: suspend (SessionId, String) -> Unit = { _, _ -> },
    val onRoomItemsChanged: suspend (RoomId) -> Unit = {},
)

class CombatSystem(
    private val players: PlayerRegistry,
    private val mobs: MobRegistry,
    private val items: ItemRegistry,
    private val outbound: OutboundBus,
    private val clock: Clock = Clock.systemUTC(),
    private val rng: Random = Random(),
    private val progression: PlayerProgression = PlayerProgression(),
    private val metrics: GameMetrics = GameMetrics.noop(),
    private val dirtyNotifier: DirtyNotifier = DirtyNotifier.NO_OP,
    private val statusEffects: StatusEffectSystem? = null,
    private val groupSystem: GroupSystem? = null,
    private val config: CombatSystemConfig = CombatSystemConfig(),
    private val callbacks: CombatSystemCallbacks = CombatSystemCallbacks(),
    private val classRegistry: PlayerClassRegistry? = null,
    private val petSystem: PetSystem? = null,
    private val racialAbilitySystem: RacialAbilitySystem? = null,
) : GameSystem,
    RacialCombatBridge {
    /** Callback for combat events; wired by GameEngine after construction. */
    var onCombatEvent: suspend (SessionId, CombatEvent) -> Unit = { _, _ -> }

    /** Callback for XP gain events; wired by GameEngine after construction. */
    var onXpGained: suspend (SessionId, Long, String) -> Unit = { _, _, _ -> }

    /** Callback for gold gain events; wired by GameEngine after construction. */
    var onGoldGained: suspend (SessionId, Long, String) -> Unit = { _, _, _ -> }

    /** Callback for player death cleanup; wired by GameEngine after construction. */
    var onPlayerDeath: suspend (SessionId) -> Unit = { _ -> }

    /** Callback fired after a dead player has been moved to their respawn room; wired by GameEngine.
     *  Used to push a fresh room look (Room.Info, map, players, mobs, items) so the web client
     *  doesn't keep rendering the location where the player died. */
    var onPlayerRespawned: suspend (SessionId) -> Unit = { _ -> }

    /** Callback when a player kills another player in PvP; wired by GameEngine. */
    var onPvpKill: suspend (killerSid: SessionId) -> Unit = { _ -> }

    /** Callback when a player enters combat (attacker or victim); wired by GameEngine.
     *  Used to close dialogue overlays so the player isn't trapped in conversation. */
    var onPlayerEnteredCombat: suspend (SessionId) -> Unit = { _ -> }

    /** Callback fired for each item transferred into a killer's inventory via auto-loot;
     *  wired by GameEngine to emit GMCP inventory updates and advance collection quests. */
    var onItemAutoLooted: suspend (SessionId, dev.ambon.domain.items.ItemInstance) -> Unit = { _, _ -> }

    /**
     * Callback fired after [flee] or [fleePvp] successfully disengages the player; wired by
     * GameEngine to relocate the player to an adjacent room (so flee actually escapes aggro,
     * not just breaks the current fight). The boolean parameter is `true` for wimpy auto-flee.
     */
    var onPlayerFled: suspend (SessionId, Boolean) -> Unit = { _, _ -> }

    // Per-mob combat state (tracks tick timing)
    private data class MobCombatState(
        val mobId: MobId,
        var nextTickAtMs: Long,
    )

    /**
     * Min/max damage [player] would deal against a 0-armor target with [equipAttack]
     * gear contribution. Drives the `score` screen's "Dmg: a–b" display — uses the
     * same formula as actual swings, with variance bounds replacing the random roll.
     */
    fun damageRangeForDisplay(player: PlayerState, stats: StatMap, equipAttack: Int): IntRange {
        // 0 armor — score display is "what you'd do to an unarmored target"; mob-specific
        // mitigation shows up in `consider`, not here.
        val b = config.bindings
        val attackPower = b.meleeBaseAttackPower + equipAttack
        val statTotal = stats[b.meleeDamageStat]
        val statBonus = (statTotal - PlayerState.BASE_STAT) * b.meleeStatMultiplier
        val levelScale = b.meleeLevelScalingRate.pow((player.level - 1).coerceAtLeast(0))
        val core = (attackPower + statBonus) * levelScale
        val lo = (core * b.meleeVarianceMin).roundToInt().coerceAtLeast(1)
        val hi = (core * b.meleeVarianceMax).roundToInt().coerceAtLeast(lo)
        return lo..hi
    }

    // Which mob each player is attacking
    private val playerTarget = mutableMapOf<SessionId, MobId>()

    // Active mobs in combat (with tick timing)
    private val activeMobs = mutableMapOf<MobId, MobCombatState>()

    // Threat table: per-mob, tracks cumulative threat from each player
    internal val threatTable = ThreatTable()

    // Per-mob spell cooldown tracking: mobId -> (spellId -> nextCastAtMs)
    private val mobSpellCooldowns = mutableMapOf<MobId, MutableMap<String, Long>>()

    // --- PvP combat state ---

    /** Tracks active PvP fights: player -> opponent session. */
    private val pvpTarget = mutableMapOf<SessionId, SessionId>()

    /** PvP combat tick timing per attacker. */
    private data class PvpCombatState(
        val attackerSid: SessionId,
        val targetSid: SessionId,
        var nextTickAtMs: Long,
    )

    private val pvpCombatStates = mutableMapOf<SessionId, PvpCombatState>()

    /** Zone start room lookup, wired by GameEngine after construction. */
    var zoneStartRoomLookup: (String) -> RoomId? = { _ -> null }

    /** Sanctum room lookup for PvE death respawn, wired by GameEngine after construction. */
    var sanctumRoomLookup: () -> RoomId? = { null }

    /** Death behavior config, wired by GameEngine after construction. */
    var deathConfig: DeathConfig = DeathConfig()

    fun isInCombat(sessionId: SessionId): Boolean =
        playerTarget.containsKey(sessionId) || pvpTarget.containsKey(sessionId)

    fun isMobInCombat(mobId: MobId): Boolean = activeMobs.containsKey(mobId)

    fun currentTarget(sessionId: SessionId): MobId? = playerTarget[sessionId]

    fun getCombatTarget(sessionId: SessionId): MobState? {
        val mobId = playerTarget[sessionId] ?: return null
        return mobs.get(mobId)
    }

    fun findMobInRoom(
        roomId: RoomId,
        keyword: String,
    ): MobState? = findMobsInRoom(roomId, keyword).firstOrNull()

    /**
     * No-op kept for callers (item equip/unequip hooks) that used to trigger a
     * maxHP recompute when equipment armor changed. Armor now mitigates damage
     * multiplicatively instead — there's nothing for the engine to sync, but
     * removing the function would touch every call site for no gain.
     */
    fun syncPlayerDefense(sessionId: SessionId) {
        // intentionally empty
    }

    suspend fun startCombat(
        sessionId: SessionId,
        keywordRaw: String,
    ): String? {
        val player = players.get(sessionId) ?: return ERR_NOT_CONNECTED
        if (player.isAkathavae) return ERR_AKATHAVAE_PLEDGE
        val keyword = keywordRaw.trim()
        if (keyword.isEmpty()) return "Kill what?"

        val existingTarget = playerTarget[sessionId]
        if (existingTarget != null) {
            val mobName = mobs.get(existingTarget)?.name ?: "your target"
            return "You are already fighting $mobName."
        }

        val roomId = player.roomId
        val matches = findMobsInRoom(roomId, keyword)
        if (matches.isEmpty()) return "You don't see '$keyword' here."

        val mob = matches.first()
        nonCombatantRefusal(mob)?.let { return it }

        val now = clock.millis()
        registerCombatant(sessionId, mob.id, player, now)
        onPlayerEnteredCombat(sessionId)

        outbound.send(OutboundEvent.SendText(sessionId, "You attack ${mob.name}."))
        broadcastToRoom(players, outbound, roomId, "${player.name} attacks ${mob.name}.", exclude = sessionId)

        return null
    }

    /**
     * Player-facing refusal for attempting to fight [mob], or null when the mob
     * is a combatant. Shared by the kill command and hostile ability casts so
     * non-combat NPCs (vendors, quest givers, dialogue NPCs, props) refuse the
     * spell exactly like they refuse the sword (#1232).
     */
    fun nonCombatantRefusal(mob: MobState): String? = when (mob.role) {
        dev.ambon.domain.mob.MobRole.COMBAT -> null
        dev.ambon.domain.mob.MobRole.VENDOR -> "${mob.name} isn't interested in fighting — try the shop instead."
        dev.ambon.domain.mob.MobRole.QUEST_GIVER -> "${mob.name} has no quarrel with you. Maybe they have work to offer?"
        dev.ambon.domain.mob.MobRole.DIALOG -> "${mob.name} has no interest in fighting you."
        dev.ambon.domain.mob.MobRole.PROP -> "${mob.name} is not something you can attack."
    }

    /**
     * Engages combat between a player and a specific mob without keyword resolution
     * or attack messaging. Used by hostile ability casts so an out-of-combat target
     * fights back instead of being farmed risk-free from outside combat.
     * No-op if the player already has a target, the mob is not a combatant, or
     * either side is already dead.
     */
    suspend fun engageMobCombat(
        sessionId: SessionId,
        mob: MobState,
    ) {
        val player = players.get(sessionId) ?: return
        if (playerTarget[sessionId] != null) return
        if (!mob.role.isCombatant) return
        if (mob.hp <= 0 || player.hp <= 0) return

        registerCombatant(sessionId, mob.id, player, clock.millis())
        onPlayerEnteredCombat(sessionId)
    }

    /**
     * Estimate the player's odds against a mob in the same room without engaging.
     *
     * Uses expected-value damage (mean of the damage range) on both sides, applies
     * the player's equipment attack/armor bonuses, the player's STR-derived melee
     * bonus, and the player's dodge chance. The hits-to-kill ratio is converted into
     * a win-chance percentage and bucketed into a [ConsiderRating]. Pet contributions
     * and status-effect modifiers other than current stat mods are deliberately
     * ignored so the player gets a baseline read on their own capabilities.
     */
    fun consider(
        sessionId: SessionId,
        keywordRaw: String,
    ): ConsiderOutcome {
        val player = players.get(sessionId) ?: return ConsiderOutcome.Error(ERR_NOT_CONNECTED)
        val keyword = keywordRaw.trim()
        if (keyword.isEmpty()) return ConsiderOutcome.Error("Consider what?")

        val matches = findMobsInRoom(player.roomId, keyword)
        if (matches.isEmpty()) return ConsiderOutcome.Error("You don't see '$keyword' here.")

        val mob = matches.first()
        if (!mob.role.isCombatant) {
            return ConsiderOutcome.Error("${mob.name} isn't a threat — no need to size them up.")
        }

        val stats = resolvePlayerStats(player, items, statusEffects, classRegistry)
        val equip = items.equipmentBonuses(sessionId, classRegistry?.get(player.playerClass))
        val playerAvgDamage = avgPlayerMeleeDamage(player, stats, equip, mob.armor)

        val mobAvgRoll = (mob.damage.min + mob.damage.max) / 2.0
        val dodgePct =
            ((stats[config.bindings.dodgeStat] - PlayerState.BASE_STAT) * config.bindings.dodgePerPoint)
                .coerceIn(0, config.bindings.maxDodgePercent)
        val effectiveMobDamage = (mobAvgRoll * (1.0 - dodgePct / 100.0)).coerceAtLeast(0.1)

        val hitsToKillMob = ceil(mob.maxHp.toDouble() / playerAvgDamage).toInt().coerceAtLeast(1)
        val hitsToKillPlayer = ceil(player.maxHp.toDouble() / effectiveMobDamage).toInt().coerceAtLeast(1)

        val winChancePct =
            (hitsToKillPlayer.toDouble() / (hitsToKillPlayer + hitsToKillMob) * 100.0)
                .toInt()
                .coerceIn(0, 100)

        val rating = when {
            winChancePct >= 95 -> ConsiderRating.TRIVIAL
            winChancePct >= 75 -> ConsiderRating.EASY
            winChancePct >= 60 -> ConsiderRating.FAVORED
            winChancePct >= 40 -> ConsiderRating.EVEN
            winChancePct >= 25 -> ConsiderRating.RISKY
            winChancePct >= 10 -> ConsiderRating.DANGEROUS
            else -> ConsiderRating.SUICIDAL
        }

        return ConsiderOutcome.Ok(
            ConsiderResult(
                mobId = mob.id.value,
                mobName = mob.name,
                mobLevel = mob.level,
                mobMaxHp = mob.maxHp,
                mobCategory = mob.category,
                mobImage = mob.image,
                playerLevel = player.level,
                playerMaxHp = player.maxHp,
                playerAvgDamage = playerAvgDamage.toInt(),
                mobAvgDamage = mobAvgRoll.toInt().coerceAtLeast(1),
                hitsToKillMob = hitsToKillMob,
                hitsToKillPlayer = hitsToKillPlayer,
                dodgeChancePct = dodgePct,
                winChancePct = winChancePct,
                rating = rating,
            ),
        )
    }

    suspend fun flee(
        sessionId: SessionId,
        forced: Boolean = false,
    ): String? {
        val mobId = playerTarget[sessionId] ?: return "You are not in combat."
        val mobName = mobs.get(mobId)?.name ?: "your foe"

        removePlayerFromCombat(sessionId)

        val msg =
            if (forced) {
                "You are forced to flee from $mobName."
            } else {
                "You flee from $mobName."
            }
        outbound.send(OutboundEvent.SendText(sessionId, msg))
        onCombatEvent(sessionId, CombatEvent.Flee(targetName = mobName, forced = forced))
        // GameEngine wires this to relocate the player to an adjacent room. The prompt is
        // sent after the room move so the new room's GMCP/look land before the prompt.
        onPlayerFled(sessionId, forced)
        outbound.send(OutboundEvent.SendPrompt(sessionId))
        return null
    }

    /**
     * If the player has wimpy enabled and their current HP% is at or below the threshold,
     * break PvE combat with the "forced to flee" path. Returns true if combat was broken.
     * Called after a mob lands a hit on a still-alive player so the next mob tick can't
     * resolve before the player has a chance to escape.
     */
    private suspend fun checkWimpyAutoFlee(
        targetSid: SessionId,
        target: PlayerState,
    ): Boolean {
        if (!isAtOrBelowWimpyThreshold(target)) return false
        if (playerTarget[targetSid] == null) return false
        outbound.send(OutboundEvent.SendText(targetSid, "Your wounds force you to disengage!"))
        flee(targetSid, forced = true)
        return true
    }

    /** PvP variant — disengages the PvP fight when the player's HP drops below the wimpy threshold. */
    private suspend fun checkWimpyAutoFleePvp(
        targetSid: SessionId,
        target: PlayerState,
    ): Boolean {
        if (!isAtOrBelowWimpyThreshold(target)) return false
        if (pvpTarget[targetSid] == null) return false
        val opponent = pvpTarget[targetSid]?.let { players.get(it) }?.name ?: "your opponent"
        endPvpCombat(targetSid)
        outbound.send(OutboundEvent.SendText(targetSid, "You are forced to flee from $opponent!"))
        onCombatEvent(targetSid, CombatEvent.Flee(targetName = opponent, forced = true))
        outbound.send(OutboundEvent.SendPrompt(targetSid))
        return true
    }

    /**
     * Returns true iff the player has wimpy enabled and their current HP is at or below the
     * configured percentage of max. Uses integer cross-multiplication rather than a truncated
     * percent because `(hp.toDouble() / maxHp * 100).toInt()` floors values like 25.5% to 25
     * and would fire one HP early — e.g. at 51/200 HP for a 25% threshold.
     */
    private fun isAtOrBelowWimpyThreshold(target: PlayerState): Boolean {
        val threshold = target.wimpyThresholdPct
        if (threshold <= 0 || target.maxHp <= 0) return false
        return target.hp * 100 <= threshold * target.maxHp
    }

    override fun remapSession(
        oldSid: SessionId,
        newSid: SessionId,
    ) {
        val mobId = playerTarget.remove(oldSid)
        if (mobId != null) {
            playerTarget[newSid] = mobId
        }
        threatTable.remapSession(oldSid, newSid)

        // Remap PvP combat state
        val pvpOpponent = pvpTarget.remove(oldSid)
        if (pvpOpponent != null) {
            pvpTarget[newSid] = pvpOpponent
            if (pvpTarget[pvpOpponent] == oldSid) {
                pvpTarget[pvpOpponent] = newSid
            }
        }
        val pvpState = pvpCombatStates.remove(oldSid)
        if (pvpState != null) {
            pvpCombatStates[newSid] = pvpState.copy(attackerSid = newSid)
        }
        for ((sid, state) in pvpCombatStates) {
            if (state.targetSid == oldSid) {
                pvpCombatStates[sid] = state.copy(targetSid = newSid)
            }
        }
    }

    override suspend fun onPlayerDisconnected(sessionId: SessionId) {
        removePlayerFromCombat(sessionId)
        endPvpCombat(sessionId)
    }

    fun endCombatFor(sessionId: SessionId) {
        removePlayerFromCombat(sessionId)
    }

    // --- PvP combat API ---

    fun isInPvpCombat(sessionId: SessionId): Boolean = pvpTarget.containsKey(sessionId)

    fun getPvpOpponent(sessionId: SessionId): SessionId? = pvpTarget[sessionId]

    suspend fun startPvpCombat(
        attackerSid: SessionId,
        targetSid: SessionId,
    ): String? {
        val attacker = players.get(attackerSid) ?: return ERR_NOT_CONNECTED
        val target = players.get(targetSid) ?: return "That player is not available."

        if (attacker.isAkathavae) return ERR_AKATHAVAE_PLEDGE
        if (attackerSid == targetSid) return "You cannot attack yourself."
        if (target.isStaff) return "You cannot attack staff members."
        if (target.isAkathavae) return "${target.name} is an Akathavae — their pledge of peace protects them from duels and bloodshed."
        if (attacker.roomId != target.roomId) return "${'$'}{target.name} is not here."
        if (pvpTarget[attackerSid] != null) return "You are already in PvP combat."
        if (playerTarget[attackerSid] != null) return "You are already in combat."
        if (target.hp <= 0) return "${'$'}{target.name} is already defeated."

        val now = clock.millis()

        pvpTarget[attackerSid] = targetSid
        pvpCombatStates[attackerSid] = PvpCombatState(
            attackerSid = attackerSid,
            targetSid = targetSid,
            nextTickAtMs = now + config.tickMillis,
        )
        dirtyNotifier.playerVitalsDirty(attackerSid)
        dirtyNotifier.playerCombatDirty(attackerSid)
        onPlayerEnteredCombat(attackerSid)

        // If the target is not already fighting back, auto-engage them
        if (pvpTarget[targetSid] == null && playerTarget[targetSid] == null) {
            pvpTarget[targetSid] = attackerSid
            pvpCombatStates[targetSid] = PvpCombatState(
                attackerSid = targetSid,
                targetSid = attackerSid,
                nextTickAtMs = now + config.tickMillis,
            )
            dirtyNotifier.playerVitalsDirty(targetSid)
            dirtyNotifier.playerCombatDirty(targetSid)
            onPlayerEnteredCombat(targetSid)
        }

        outbound.send(OutboundEvent.SendText(attackerSid, "You attack ${'$'}{target.name}!"))
        outbound.send(OutboundEvent.SendText(targetSid, "${'$'}{attacker.name} attacks you!"))
        broadcastToRoom(
            players,
            outbound,
            attacker.roomId,
            "${'$'}{attacker.name} attacks ${'$'}{target.name}!",
            exclude1 = attackerSid,
            exclude2 = targetSid,
        )

        return null
    }

    fun endPvpCombat(sessionId: SessionId) {
        val opponentSid = pvpTarget.remove(sessionId)
        pvpCombatStates.remove(sessionId)
        dirtyNotifier.playerVitalsDirty(sessionId)
        dirtyNotifier.playerCombatDirty(sessionId)

        if (opponentSid != null && pvpTarget[opponentSid] == sessionId) {
            pvpTarget.remove(opponentSid)
            pvpCombatStates.remove(opponentSid)
            dirtyNotifier.playerVitalsDirty(opponentSid)
            dirtyNotifier.playerCombatDirty(opponentSid)
        }
    }

    suspend fun fleePvp(sessionId: SessionId): String? {
        val targetSid = pvpTarget[sessionId] ?: return "You are not in PvP combat."
        val targetName = players.get(targetSid)?.name ?: "your opponent"

        endPvpCombat(sessionId)
        outbound.send(OutboundEvent.SendText(sessionId, "You flee from $targetName."))
        onCombatEvent(sessionId, CombatEvent.Flee(targetName = targetName, forced = false))
        onPlayerFled(sessionId, false)
        outbound.send(OutboundEvent.SendPrompt(sessionId))
        return null
    }

    @Suppress("CyclomaticComplexity", "LongMethod")
    suspend fun tickPvpCombat(maxPerTick: Int = 20): Int {
        val now = clock.millis()
        var ran = 0

        val entries = pvpCombatStates.values.toList()
        for (state in entries) {
            if (ran >= maxPerTick) break
            if (now < state.nextTickAtMs) continue
            // The snapshot can go stale mid-tick: an earlier iteration (or wimpy auto-flee
            // on this state's target) may have called endPvpCombat, which removes the
            // reciprocal entry too. Skip stale states so we don't apply a phantom hit
            // after the fight has already ended.
            if (pvpCombatStates[state.attackerSid] !== state) continue

            val attacker = players.get(state.attackerSid)
            val target = players.get(state.targetSid)
            if (attacker == null || target == null) {
                endPvpCombat(state.attackerSid)
                continue
            }
            if (attacker.roomId != target.roomId) {
                endPvpCombat(state.attackerSid)
                outbound.send(OutboundEvent.SendText(state.attackerSid, "${'$'}{target.name} is no longer here."))
                outbound.send(OutboundEvent.SendPrompt(state.attackerSid))
                continue
            }

            val stunned = statusEffects?.hasPlayerEffect(state.attackerSid, "stun") == true
            if (!stunned) {
                val attackerStats = resolvePlayerStats(attacker, items, statusEffects, classRegistry)
                val attackerEquip = items.equipmentBonuses(state.attackerSid, classRegistry?.get(attacker.playerClass))
                val targetEquip = items.equipmentBonuses(state.targetSid, classRegistry?.get(target.playerClass))
                val swing = rollPlayerMeleeSwing(attacker, attackerStats, attackerEquip, targetEquip.armor)
                var effectiveDamage = swing.final

                if (statusEffects != null) {
                    effectiveDamage = statusEffects.absorbPlayerDamage(state.targetSid, effectiveDamage)
                }

                target.takeDamage(effectiveDamage)
                dirtyNotifier.playerVitalsDirty(state.targetSid)

                outbound.send(
                    OutboundEvent.SendText(state.attackerSid, "You hit ${'$'}{target.name} for $effectiveDamage damage."),
                )
                outbound.send(
                    OutboundEvent.SendText(state.targetSid, "${'$'}{attacker.name} hits you for $effectiveDamage damage!"),
                )
                onCombatEvent(
                    state.attackerSid,
                    CombatEvent.MeleeHit(
                        targetName = target.name,
                        targetId = null,
                        damage = effectiveDamage,
                        sourceIsPlayer = true,
                    ),
                )

                if (target.hp <= 0) {
                    handlePvpDeath(
                        killerSid = state.attackerSid,
                        killerName = attacker.name,
                        loserSid = state.targetSid,
                        loserName = target.name,
                        loser = target,
                    )
                    ran++
                    continue
                }
                if (checkWimpyAutoFleePvp(state.targetSid, target)) {
                    ran++
                    continue
                }
            } else {
                outbound.send(OutboundEvent.SendText(state.attackerSid, "You are stunned and cannot act!"))
            }

            state.nextTickAtMs = now + config.tickMillis
            outbound.send(OutboundEvent.SendPrompt(state.attackerSid))
            ran++
        }
        return ran
    }

    private suspend fun handlePvpDeath(
        killerSid: SessionId,
        killerName: String,
        loserSid: SessionId,
        loserName: String,
        loser: PlayerState,
    ) {
        endPvpCombat(loserSid)

        outbound.send(OutboundEvent.SendText(loserSid, "You have been slain by $killerName!"))
        outbound.send(OutboundEvent.SendText(killerSid, "You have defeated $loserName!"))
        broadcastToRoom(
            players,
            outbound,
            loser.roomId,
            "$loserName has been slain by $killerName!",
            exclude1 = killerSid,
            exclude2 = loserSid,
        )

        players.get(killerSid)?.let {
            it.pvpKills += 1
            dirtyNotifier.playerVitalsDirty(killerSid)
        }
        onPvpKill(killerSid)
        loser.pvpDeaths += 1
        onPvpKill(killerSid)

        onCombatEvent(
            loserSid,
            CombatEvent.Death(killerName = killerName, killerIsPlayer = true),
        )

        statusEffects?.removeAllFromPlayer(loserSid)

        onPlayerDeath(loserSid)

        // Respawn at zone start room with full HP/mana
        val loserZone = loser.roomId.zone
        val startRoom = zoneStartRoomLookup(loserZone)
        if (startRoom != null) {
            loser.roomId = startRoom
        }
        loser.hp = loser.maxHp
        loser.mana = loser.maxMana
        dirtyNotifier.playerVitalsDirty(loserSid)

        outbound.send(OutboundEvent.SendText(loserSid, "You respawn at the arena gates."))
        outbound.send(OutboundEvent.SendPrompt(loserSid))
        outbound.send(OutboundEvent.SendPrompt(killerSid))
    }

    suspend fun onMobRemovedExternally(mobId: MobId) {
        val affectedPlayers = threatTable.playersThreateningMob(mobId)
            .filterNot { petSystem?.isPetSession(it) == true }
        removeMobFromCombat(mobId)
        for (sid in affectedPlayers) {
            outbound.send(OutboundEvent.SendText(sid, "Your opponent vanishes."))
            outbound.send(OutboundEvent.SendPrompt(sid))
        }
    }

    suspend fun startMobCombat(
        mobId: MobId,
        sessionId: SessionId,
    ): Boolean {
        val player = players.get(sessionId) ?: return false
        val mob = mobs.get(mobId) ?: return false

        if (!mob.role.isCombatant) return false
        if (playerTarget.containsKey(sessionId)) return false
        if (player.roomId != mob.roomId) return false

        val now = clock.millis()
        registerCombatant(sessionId, mobId, player, now)
        onPlayerEnteredCombat(sessionId)

        outbound.send(OutboundEvent.SendText(sessionId, "${mob.name} attacks you!"))
        broadcastToRoom(players, outbound, player.roomId, "${mob.name} attacks ${player.name}.", exclude = sessionId)
        outbound.send(OutboundEvent.SendPrompt(sessionId))

        return true
    }

    /** Links [sessionId] as a combatant targeting [mobId]: records the target, marks vitals dirty,
     *  activates the mob in the combat tick loop (if not already active), and seeds the threat table. */
    private fun registerCombatant(
        sessionId: SessionId,
        mobId: MobId,
        player: PlayerState,
        now: Long,
    ) {
        playerTarget[sessionId] = mobId
        dirtyNotifier.playerVitalsDirty(sessionId)
        dirtyNotifier.playerCombatDirty(sessionId)
        if (!activeMobs.containsKey(mobId)) {
            activeMobs[mobId] = MobCombatState(mobId = mobId, nextTickAtMs = now + config.tickMillis)
        }
        val multiplier = threatMultiplier(player)
        threatTable.addThreat(mobId, sessionId, multiplier)
    }

    suspend fun fleeMob(mobId: MobId): Boolean {
        if (!activeMobs.containsKey(mobId)) return false

        // Find all players fighting this mob and notify them
        val affectedPlayers =
            playerTarget.entries
                .filter { it.value == mobId }
                .map { it.key }

        removeMobFromCombat(mobId)

        for (sid in affectedPlayers) {
            outbound.send(OutboundEvent.SendPrompt(sid))
        }

        return true
    }

    fun addThreat(
        mobId: MobId,
        sessionId: SessionId,
        amount: Double,
    ) {
        if (!activeMobs.containsKey(mobId)) return
        threatTable.addThreat(mobId, sessionId, amount)
    }

    fun addHealingThreat(
        sessionId: SessionId,
        healAmount: Int,
    ) {
        val threat = healAmount.toDouble() * config.healingThreatMultiplier
        if (threat <= 0.0) return

        // Add healing threat to all mobs that are engaged with the healer's group members in the room
        val player = players.get(sessionId) ?: return
        val groupMembers =
            groupSystem?.membersInRoom(sessionId, player.roomId)
                ?: listOf(sessionId)

        for ((mobId, _) in activeMobs) {
            val mob = mobs.get(mobId) ?: continue
            if (mob.roomId != player.roomId) continue
            // Only add threat if the mob is fighting someone in the group
            val hasGroupThreat = groupMembers.any { threatTable.hasThreat(mobId, it) }
            if (hasGroupThreat) {
                threatTable.addThreat(mobId, sessionId, threat)
            }
        }
    }

    // ── RacialCombatBridge ───────────────────────────────────────────────
    // Combat-internal operations the RacialAbilitySystem invokes (Pyrae AoE, Lustriae extra swing,
    // Lithae disengage, room broadcasts). See RacialCombatBridge for contracts.

    override fun enemiesInCombatWith(sessionId: SessionId): List<MobState> {
        val player = players.get(sessionId) ?: return emptyList()
        return threatTable.mobsThreatenedBy(sessionId)
            .mapNotNull { mobs.get(it) }
            .filter { !it.isPet && it.hp > 0 && it.roomId == player.roomId }
    }

    override suspend fun dealRacialDamage(
        sessionId: SessionId,
        mob: MobState,
        damage: Int,
        hitText: String,
    ) {
        val player = players.get(sessionId) ?: return
        mob.takeDamage(damage)
        dirtyNotifier.mobHpDirty(mob.id)
        threatTable.addThreat(mob.id, sessionId, damage.toDouble() * threatMultiplier(player))
        outbound.send(OutboundEvent.SendText(sessionId, hitText))
        broadcastToRoom(players, outbound, mob.roomId, hitText, exclude = sessionId)
        if (mob.hp <= 0) {
            handleMobDeath(sessionId, mob)
            // handleMobDeath drops this mob from playerTarget, so the mob-attack phase's later
            // prompt sweep won't cover it. Prompt here (like the player/pet kill paths) so a racial
            // proc that ends combat still leaves the triggering client with a fresh prompt.
            outbound.send(OutboundEvent.SendPrompt(sessionId))
        }
    }

    override suspend fun extraMeleeSwing(
        sessionId: SessionId,
        mob: MobState,
    ): Boolean {
        val player = players.get(sessionId) ?: return false
        if (mob.hp <= 0) return true
        val stats = resolvePlayerStats(player, items, statusEffects, classRegistry)
        val equip = items.equipmentBonuses(sessionId, classRegistry?.get(player.playerClass))
        val swing = rollPlayerMeleeSwing(player, stats, equip, mob.armor)
        val damage = swing.final
        mob.takeDamage(damage)
        dirtyNotifier.mobHpDirty(mob.id)
        threatTable.addThreat(mob.id, sessionId, damage.toDouble() * threatMultiplier(player))
        val hitText = "You slip through time and strike ${mob.name} for $damage damage!"
        outbound.send(OutboundEvent.SendText(sessionId, hitText))
        onCombatEvent(
            sessionId,
            CombatEvent.MeleeHit(
                targetName = mob.name,
                targetId = mob.id.value,
                damage = damage,
                sourceIsPlayer = true,
                text = hitText,
            ),
        )
        broadcastToRoom(players, outbound, mob.roomId, hitText, exclude = sessionId)
        val killed = mob.hp <= 0
        if (killed) {
            handleMobDeath(sessionId, mob)
        }
        return killed
    }

    override fun disengageFromCombat(sessionId: SessionId) {
        removePlayerFromCombat(sessionId)
    }

    override suspend fun broadcastToRoomExcept(
        sessionId: SessionId,
        text: String,
    ) {
        val player = players.get(sessionId) ?: return
        broadcastToRoom(players, outbound, player.roomId, text, exclude = sessionId)
    }

    @Suppress("CyclomaticComplexity", "LongMethod")
    suspend fun tick(maxCombatsPerTick: Int = 20): Int {
        val now = clock.millis()
        var ran = 0

        // --- Player attack phase ---
        val playerEntries = playerTarget.entries.toMutableList()
        playerEntries.shuffle(rng)
        for ((sessionId, mobId) in playerEntries) {
            if (ran >= maxCombatsPerTick) break
            val mobState = activeMobs[mobId]
            if (mobState == null) {
                removePlayerFromCombat(sessionId)
                continue
            }
            if (now < mobState.nextTickAtMs) continue

            val player = players.get(sessionId)
            val mob = mobs.get(mobId)
            if (player == null || mob == null) {
                removePlayerFromCombat(sessionId)
                continue
            }

            if (player.roomId != mob.roomId) {
                removePlayerFromCombat(sessionId)
                outbound.send(OutboundEvent.SendText(sessionId, "${mob.name} is no longer here."))
                outbound.send(OutboundEvent.SendPrompt(sessionId))
                ran++
                continue
            }

            val playerBonuses = items.equipmentBonuses(sessionId, classRegistry?.get(player.playerClass))

            if (player.hp <= 0) {
                metrics.onPlayerDeath()
                removePlayerFromCombat(sessionId)
                handlePlayerDeath(
                    sessionId = sessionId,
                    playerName = player.name,
                    roomId = player.roomId,
                    deathMessage = "You collapse, too wounded to keep fighting.",
                    roomMessage = "${player.name} has fallen in battle.",
                    killerName = mob.name,
                )
                ran++
                continue
            }

            // STUN check. Akathavae never swing back: a mob can force them into
            // combat (aggro, a failed illumination) but their pledge holds — they
            // dodge, flee, or fall, and never deal damage.
            val stunned = statusEffects?.hasPlayerEffect(sessionId, "stun") == true
            if (!stunned && !player.isAkathavae) {
                val playerStats = resolvePlayerStats(player, items, statusEffects, classRegistry)
                val swing = rollPlayerMeleeSwing(player, playerStats, playerBonuses, mob.armor)
                // Ophirae wrath (and any future damage buff) multiplies outgoing melee damage.
                val effectivePlayerDamage =
                    (swing.final * player.outgoingDamageMultiplier(now)).roundToInt().coerceAtLeast(1)
                val playerFeedbackSuffix =
                    combatFeedbackSuffix(
                        roll = swing.raw,
                        // `roll` is the post-formula pre-armor damage; the attackPower term
                        // is already baked into it, so there's no separate +atk to surface.
                        attackBonus = 0,
                        armorAbsorbed = swing.armorAbsorbed,
                        clampedToMinimum = swing.clampedToMinimum,
                    )
                mob.takeDamage(effectivePlayerDamage)
                dirtyNotifier.mobHpDirty(mob.id)

                // Add threat (damage * class multiplier)
                val multiplier = threatMultiplier(player)
                threatTable.addThreat(mob.id, sessionId, effectivePlayerDamage.toDouble() * multiplier)

                val playerHitText = "You hit ${mob.name} for $effectivePlayerDamage damage$playerFeedbackSuffix."
                outbound.send(OutboundEvent.SendText(sessionId, playerHitText))
                onCombatEvent(
                    sessionId,
                    CombatEvent.MeleeHit(
                        targetName = mob.name,
                        targetId = mob.id.value,
                        damage = effectivePlayerDamage,
                        sourceIsPlayer = true,
                        text = playerHitText,
                    ),
                )
                if (config.detailedFeedbackEnabled && config.detailedFeedbackRoomBroadcastEnabled) {
                    broadcastToRoom(
                        players,
                        outbound,
                        player.roomId,
                        "[Combat] ${player.name} hits ${mob.name} for $effectivePlayerDamage damage$playerFeedbackSuffix.",
                        exclude = sessionId,
                    )
                }
                if (mob.hp <= 0) {
                    handleMobDeath(sessionId, mob)
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    ran++
                    continue
                }
            } else {
                outbound.send(OutboundEvent.SendText(sessionId, "You are stunned and cannot act!"))
            }

            ran++
        }

        // --- Pet attack phase ---
        // Pets deal damage to their owner's combat target on the same tick cadence.
        // If the pet has signature skills off cooldown AND the owner hasn't manually triggered
        // a skill in the recent grace window, fall through to one of those skills instead of
        // a plain melee — that's how rangers see "Wolf bites!" rotations without doing anything.
        if (petSystem != null) {
            for ((sessionId, mobId) in playerEntries) {
                val mobCombatState = activeMobs[mobId] ?: continue
                if (now < mobCombatState.nextTickAtMs) continue
                val mob = mobs.get(mobId) ?: continue
                if (mob.hp <= 0) continue
                // Every pet the owner controls attacks (a single companion is the common case; the
                // Mycorae spore swarm and other multi-summons all act, and tank pets each build
                // threat so a wall of mushrooms actually holds aggro).
                for (pet in petSystem.getPets(sessionId)) {
                    if (mob.hp <= 0) break
                    if (pet.roomId != mob.roomId) continue

                    val autoSkill =
                        if (petSystem.isManualGraceActive(sessionId, now)) {
                            null
                        } else {
                            selectPetSkill(pet, now)
                        }

                    if (autoSkill != null) {
                        executePetSkill(pet, autoSkill, mob, sessionId, now)
                        onPetSkillCast(sessionId)
                        continue
                    }

                    val petRoll = rollRange(rng, pet.damage.min, pet.damage.max)
                    val petDamage = (petRoll - mob.armor).coerceAtLeast(1)
                    mob.takeDamage(petDamage)
                    dirtyNotifier.mobHpDirty(mob.id)
                    val petHitText = "${pet.name} hits ${mob.name} for $petDamage damage."
                    outbound.send(OutboundEvent.SendText(sessionId, petHitText))
                    onCombatEvent(
                        sessionId,
                        CombatEvent.PetHit(
                            petName = pet.name,
                            targetName = mob.name,
                            targetId = mob.id.value,
                            damage = petDamage,
                            text = petHitText,
                        ),
                    )
                    broadcastToRoom(
                        players,
                        outbound,
                        mob.roomId,
                        petHitText,
                        exclude = sessionId,
                    )

                    // Tank pets generate threat so mobs target them instead of the player.
                    if (pet.threatMultiplier > 0.0) {
                        val petSid = petSystem.getPetSessionId(pet.id)
                        if (petSid != null) {
                            threatTable.addThreat(mob.id, petSid, petDamage.toDouble() * pet.threatMultiplier)
                        }
                    }

                    if (mob.hp <= 0) {
                        handleMobDeath(sessionId, mob)
                        outbound.send(OutboundEvent.SendPrompt(sessionId))
                    }
                }
            }
        }

        // --- Mob attack phase ---
        val mobEntries = activeMobs.values.toMutableList()
        mobEntries.shuffle(rng)
        for (mobState in mobEntries) {
            if (ran >= maxCombatsPerTick) break
            if (now < mobState.nextTickAtMs) continue

            val mob = mobs.get(mobState.mobId)
            if (mob == null) {
                removeMobFromCombat(mobState.mobId)
                continue
            }

            // Stunned mobs (e.g. Aurelia's dazzle) forfeit their attack but stay in combat. Still
            // advance the tick and prompt targeting players, otherwise prompt-driven clients look
            // idle through every stunned round even though combat is ongoing.
            if (statusEffects?.hasMobEffect(mob.id, "stun") == true) {
                mobState.nextTickAtMs = now + config.tickMillis
                promptPlayersTargeting(mobState.mobId)
                continue
            }

            // Pick target = highest threat in same room (includes pet synthetic SIDs), skipping
            // players who are currently untargetable (Aetherae phase / Lithae stone form).
            val targetSid =
                threatTable.topThreatInRoom(mobState.mobId) { sid ->
                    val p = players.get(sid)
                    if (p != null) {
                        p.roomId == mob.roomId && !p.isUntargetable(now)
                    } else {
                        // Check if it's a tank pet in the same room
                        val pet = petSystem?.getPetBySession(sid)
                        pet != null && pet.hp > 0 && pet.roomId == mob.roomId
                    }
                }

            if (targetSid == null) {
                // No targetable foe. If a phased player is still present and holding threat, the mob
                // simply whiffs this round rather than leaving combat — so it resumes attacking once
                // the phase ends. Otherwise the room is genuinely clear and the mob disengages.
                val phasedPresent =
                    threatTable.playersThreateningMob(mobState.mobId).any { sid ->
                        val p = players.get(sid)
                        p != null && p.roomId == mob.roomId && p.isUntargetable(now)
                    }
                if (phasedPresent) {
                    mobState.nextTickAtMs = now + config.tickMillis
                    promptPlayersTargeting(mobState.mobId)
                    continue
                }
                removeMobFromCombat(mobState.mobId)
                continue
            }

            // Branch: is the target a pet or a player?
            val targetPet = petSystem?.getPetBySession(targetSid)
            if (targetPet != null) {
                executeMobMeleeOnPet(mob, targetPet, targetSid)
            } else {
                val target = players.get(targetSid) ?: continue
                val preHitHp = target.hp

                // Pick a spell from the mob's pool (excluding defaultAttack), or fall back.
                val chosenSpell = selectMobSpell(mob, now)
                val blowDamage =
                    if (chosenSpell != null) {
                        executeMobSpell(chosenSpell, mob, target, targetSid, now)
                    } else {
                        // Use defaultAttack spell or standard melee.
                        val defaultSpell = mob.defaultAttack?.let { id -> mob.spells.find { it.id == id } }
                        if (defaultSpell != null) {
                            executeMobSpell(defaultSpell, mob, target, targetSid, now)
                        } else {
                            executeMobMelee(mob, target, targetSid)
                        }
                    }

                if (target.hp <= 0) {
                    // Give a lethal-blow racial passive the chance to cheat death before we kill them.
                    val survived =
                        racialAbilitySystem?.tryPreventLethalBlow(
                            sessionId = targetSid,
                            player = target,
                            attacker = mob,
                            blowDamage = blowDamage,
                            preHitHp = preHitHp,
                            nowMs = now,
                        ) ?: false
                    if (survived) {
                        outbound.send(OutboundEvent.SendPrompt(targetSid))
                    } else {
                        metrics.onPlayerDeath()
                        removePlayerFromCombat(targetSid)
                        handlePlayerDeath(
                            sessionId = targetSid,
                            playerName = target.name,
                            roomId = target.roomId,
                            deathMessage = "You have been slain by ${mob.name}.",
                            roomMessage = "${target.name} has been slain by ${mob.name}.",
                            killerName = mob.name,
                        )
                    }
                } else {
                    // Survived the hit — fire a low-health racial passive before wimpy auto-flee.
                    racialAbilitySystem?.onPlayerSurvivedHit(targetSid, target, now)
                    checkWimpyAutoFlee(targetSid, target)
                }
            }

            mobState.nextTickAtMs = now + config.tickMillis
            promptPlayersTargeting(mobState.mobId)
            ran++
        }
        return ran
    }

    /** Sends a prompt to every player whose current combat target is [mobId]. */
    private suspend fun promptPlayersTargeting(mobId: MobId) {
        for ((sid, mid) in playerTarget) {
            if (mid == mobId) {
                outbound.send(OutboundEvent.SendPrompt(sid))
            }
        }
    }

    /**
     * Selects an off-cooldown skill from a pet's pool using weighted random.
     * Unlike [selectMobSpell] this does not exclude a defaultAttack — pets don't model a
     * default-attack spell separate from their melee, and every entry in `pet.spells` is a
     * triggerable signature skill.
     */
    private fun selectPetSkill(pet: MobState, now: Long): MobSpell? {
        if (pet.spells.isEmpty()) return null
        val cooldowns = mobSpellCooldowns[pet.id] ?: emptyMap()
        val eligible = pet.spells.filter { (cooldowns[it.id] ?: 0L) <= now }
        if (eligible.isEmpty()) return null
        val totalWeight = eligible.sumOf { it.weight }
        if (totalWeight <= 0) return eligible.first()
        var roll = rng.nextInt(totalWeight)
        for (skill in eligible) {
            roll -= skill.weight
            if (roll < 0) return skill
        }
        return eligible.last()
    }

    /**
     * Selects a non-default spell from the mob's pool using weighted random.
     * Returns null if no spells are eligible (all on cooldown or pool is empty).
     */
    private fun selectMobSpell(mob: MobState, now: Long): MobSpell? {
        if (mob.spells.isEmpty()) return null
        val cooldowns = mobSpellCooldowns[mob.id] ?: emptyMap()
        val eligible = mob.spells.filter { spell ->
            spell.id != mob.defaultAttack && (cooldowns[spell.id] ?: 0L) <= now
        }
        if (eligible.isEmpty()) return null
        val totalWeight = eligible.sumOf { it.weight }
        var roll = rng.nextInt(totalWeight)
        for (spell in eligible) {
            roll -= spell.weight
            if (roll < 0) return spell
        }
        return eligible.last()
    }

    /**
     * Executes a mob spell against a target player (or as a self-heal/buff).
     * Handles dodge checks for offensive spells, damage, healing, and status effects.
     * Returns the damage dealt to the player (0 for dodges, heals, and pure-buff spells) so the
     * caller can resolve lethal-blow racial passives that need to know the killing blow's magnitude.
     */
    private suspend fun executeMobSpell(
        spell: MobSpell,
        mob: MobState,
        target: PlayerState,
        targetSid: SessionId,
        now: Long,
    ): Int {
        var damageDealtToPlayer = 0
        // Record cooldown
        if (spell.cooldownMs > 0L) {
            mobSpellCooldowns.getOrPut(mob.id) { mutableMapOf() }[spell.id] = now + spell.cooldownMs
        }

        val isOffensive = spell.damage != null || (spell.statusEffectId != null && spell.healMin == 0)

        // Dodge check for offensive spells
        if (isOffensive) {
            val targetStats = resolvePlayerStats(target, items, statusEffects, classRegistry)
            val dodgePct =
                ((targetStats[config.bindings.dodgeStat] - PlayerState.BASE_STAT) * config.bindings.dodgePerPoint)
                    .coerceIn(0, config.bindings.maxDodgePercent)
            if (dodgePct > 0 && rng.nextInt(100) < dodgePct) {
                val dodgeText = "You dodge ${mob.name}'s ${spell.displayName}!"
                outbound.send(OutboundEvent.SendText(targetSid, dodgeText))
                onCombatEvent(
                    targetSid,
                    CombatEvent.Dodge(
                        targetName = target.name,
                        targetId = null,
                        sourceIsPlayer = false,
                        text = dodgeText,
                    ),
                )
                return 0
            }
        }

        // Apply damage
        if (spell.damage != null) {
            val spellRoll = rollRange(rng, spell.damage.min, spell.damage.max)
            var spellDamage = spellRoll
            if (statusEffects != null) {
                spellDamage = statusEffects.absorbPlayerDamage(targetSid, spellDamage)
            }
            val shieldAbsorbed = spellRoll - spellDamage
            target.takeDamage(spellDamage)
            damageDealtToPlayer = spellDamage
            dirtyNotifier.playerVitalsDirty(targetSid)

            val msg = spell.message
                .replace("{target}", target.name)
                .replace("{damage}", spellDamage.toString())
            val spellHitText = "$msg for $spellDamage damage."
            outbound.send(OutboundEvent.SendText(targetSid, spellHitText))

            if (spell.roomMessage.isNotEmpty()) {
                broadcastToRoom(
                    players,
                    outbound,
                    target.roomId,
                    spell.roomMessage
                        .replace("{mob}", mob.name)
                        .replace("{target}", target.name)
                        .replace("{damage}", spellDamage.toString()),
                    exclude = targetSid,
                )
            }
            if (shieldAbsorbed > 0) {
                onCombatEvent(
                    targetSid,
                    CombatEvent.ShieldAbsorb(
                        attackerName = mob.name,
                        absorbed = shieldAbsorbed,
                        remaining = statusEffects?.absorbPlayerDamage(targetSid, 0) ?: 0,
                    ),
                )
            }
            if (spellDamage > 0) {
                onCombatEvent(
                    targetSid,
                    CombatEvent.AbilityHit(
                        abilityId = spell.id,
                        abilityName = spell.displayName,
                        targetName = mob.name,
                        targetId = null,
                        damage = spellDamage,
                        sourceIsPlayer = false,
                        text = spellHitText,
                    ),
                )
            }
        } else if (!isOffensive) {
            // Self-targeting spell message (heal/buff)
            val msg = spell.message.replace("{mob}", mob.name)
            outbound.send(OutboundEvent.SendText(targetSid, msg))
            if (spell.roomMessage.isNotEmpty()) {
                broadcastToRoom(
                    players,
                    outbound,
                    mob.roomId,
                    spell.roomMessage.replace("{mob}", mob.name),
                    exclude = targetSid,
                )
            }
        } else {
            // Offensive status-only spell
            val msg = spell.message.replace("{target}", target.name)
            outbound.send(OutboundEvent.SendText(targetSid, msg))
            if (spell.roomMessage.isNotEmpty()) {
                broadcastToRoom(
                    players,
                    outbound,
                    target.roomId,
                    spell.roomMessage
                        .replace("{mob}", mob.name)
                        .replace("{target}", target.name),
                    exclude = targetSid,
                )
            }
        }

        // Apply healing (to mob)
        if (spell.healMin > 0) {
            val healAmount = rollRange(rng, spell.healMin, spell.healMax)
            mob.hp = (mob.hp + healAmount).coerceAtMost(mob.maxHp)
            dirtyNotifier.mobHpDirty(mob.id)
            onCombatEvent(
                targetSid,
                CombatEvent.Heal(
                    abilityName = spell.displayName,
                    targetName = mob.name,
                    amount = healAmount,
                    sourceIsPlayer = false,
                ),
            )
        }

        // Apply status effect — mob is the caster; pass mob level so DOT/HOT
        // ticks scale with mob power. Mobs have no stat bonus, so casterStats is
        // omitted (the snapshot falls back to BASE_STAT).
        if (spell.statusEffectId != null && statusEffects != null) {
            if (isOffensive) {
                statusEffects.applyToPlayer(
                    sessionId = targetSid,
                    effectId = spell.statusEffectId,
                    casterLevel = mob.level,
                )
            } else {
                statusEffects.applyToMob(
                    mobId = mob.id,
                    effectId = spell.statusEffectId,
                    casterLevel = mob.level,
                )
            }
        }
        return damageDealtToPlayer
    }

    /** Standard melee attack — the original mob attack path. Returns the damage dealt (0 on a dodge). */
    private suspend fun executeMobMelee(
        mob: MobState,
        target: PlayerState,
        targetSid: SessionId,
    ): Int {
        val targetStats = resolvePlayerStats(target, items, statusEffects, classRegistry)
        val dodgePct =
            ((targetStats[config.bindings.dodgeStat] - PlayerState.BASE_STAT) * config.bindings.dodgePerPoint)
                .coerceIn(0, config.bindings.maxDodgePercent)
        if (dodgePct > 0 && rng.nextInt(100) < dodgePct) {
            val dodgeText = "You dodge ${mob.name}'s attack!"
            outbound.send(OutboundEvent.SendText(targetSid, dodgeText))
            onCombatEvent(
                targetSid,
                CombatEvent.Dodge(
                    targetName = target.name,
                    targetId = null,
                    sourceIsPlayer = false,
                    text = dodgeText,
                ),
            )
            return 0
        }
        val mobRoll = rollRange(rng, mob.damage.min, mob.damage.max)
        // Equipment armor mitigates incoming mob damage with the same multiplicative
        // curve the player uses against mobs — see applyArmorMitigation.
        val targetArmor = items.equipmentBonuses(targetSid).armor
        val postArmor = applyArmorMitigation(mobRoll, targetArmor, config.bindings.meleeArmorMitigationK)
        val armorAbsorbed = mobRoll - postArmor
        var mobDamage = postArmor
        if (statusEffects != null) {
            mobDamage = statusEffects.absorbPlayerDamage(targetSid, mobDamage)
        }
        val shieldAbsorbed = postArmor - mobDamage
        val mobFeedbackSuffix =
            combatFeedbackSuffix(
                roll = mobRoll,
                armorAbsorbed = armorAbsorbed,
                shieldAbsorbed = shieldAbsorbed,
            )
        target.takeDamage(mobDamage)
        dirtyNotifier.playerVitalsDirty(targetSid)
        val mobHitText =
            if (shieldAbsorbed > 0 && mobDamage == 0) {
                "Your shield absorbs ${mob.name}'s attack$mobFeedbackSuffix."
            } else if (shieldAbsorbed > 0) {
                "${mob.name} hits you for $mobDamage damage (shield absorbed $shieldAbsorbed)$mobFeedbackSuffix."
            } else {
                "${mob.name} hits you for $mobDamage damage$mobFeedbackSuffix."
            }
        outbound.send(OutboundEvent.SendText(targetSid, mobHitText))
        if (shieldAbsorbed > 0) {
            onCombatEvent(
                targetSid,
                CombatEvent.ShieldAbsorb(
                    attackerName = mob.name,
                    absorbed = shieldAbsorbed,
                    remaining = statusEffects?.absorbPlayerDamage(targetSid, 0) ?: 0,
                ),
            )
        }
        if (mobDamage > 0) {
            onCombatEvent(
                targetSid,
                CombatEvent.MeleeHit(
                    targetName = mob.name,
                    targetId = null,
                    damage = mobDamage,
                    sourceIsPlayer = false,
                    text = mobHitText,
                ),
            )
        }
        if (config.detailedFeedbackEnabled && config.detailedFeedbackRoomBroadcastEnabled) {
            broadcastToRoom(
                players,
                outbound,
                target.roomId,
                "[Combat] ${mob.name} hits ${target.name} for $mobDamage damage$mobFeedbackSuffix.",
                exclude = targetSid,
            )
        }
        return mobDamage
    }

    /**
     * Mob attacks a tank pet with a basic melee hit.
     * On pet death: dismiss the pet, remove from threat table, notify the owner.
     */
    private suspend fun executeMobMeleeOnPet(
        mob: MobState,
        pet: MobState,
        petSid: SessionId,
    ) {
        val ownerSid = pet.ownerSessionId ?: return
        val mobRoll = rollRange(rng, mob.damage.min, mob.damage.max)
        val petDamage = (mobRoll - pet.armor).coerceAtLeast(1)
        pet.takeDamage(petDamage)
        dirtyNotifier.mobHpDirty(pet.id)
        val petHurtText = "${mob.name} hits ${pet.name} for $petDamage damage."
        outbound.send(OutboundEvent.SendText(ownerSid, petHurtText))
        onCombatEvent(
            ownerSid,
            CombatEvent.PetHurt(
                petName = pet.name,
                attackerName = mob.name,
                damage = petDamage,
                text = petHurtText,
            ),
        )
        broadcastToRoom(
            players,
            outbound,
            mob.roomId,
            petHurtText,
            exclude = ownerSid,
        )

        if (pet.hp <= 0) {
            outbound.send(
                OutboundEvent.SendText(ownerSid, "${pet.name} has been slain by ${mob.name}!"),
            )
            broadcastToRoom(
                players,
                outbound,
                mob.roomId,
                "${pet.name} has been slain by ${mob.name}!",
                exclude = ownerSid,
            )
            threatTable.removePlayer(petSid)
            val petRoomId = pet.roomId
            petSystem?.dismissOne(pet.id)
            // Mirror the mob-death cleanup so the pet disappears from canvas/sidebar
            // without waiting for a room change. See issue #1069.
            callbacks.onMobRemoved(pet.id, petRoomId)
        }
    }

    suspend fun handleSpellKill(
        killerSessionId: SessionId,
        mob: MobState,
    ) {
        handleMobDeath(killerSessionId, mob)
    }

    /** Returns ms remaining on [skillId]'s cooldown for [petId], or 0 if ready. */
    fun petSkillCooldownRemainingMs(petId: MobId, skillId: String, now: Long = clock.millis()): Long {
        val expiresAt = mobSpellCooldowns[petId]?.get(skillId) ?: return 0L
        return (expiresAt - now).coerceAtLeast(0L)
    }

    /**
     * Invoked after a pet skill is cast (manually or auto-cast) so the engine can refresh the
     * web client's pet-skill cooldowns. Wired by GameEngine after construction.
     */
    var onPetSkillCast: suspend (SessionId) -> Unit = { _ -> }

    /** Outcome of a manual `pet <skill>` trigger — surfaced back to the player via PetHandler. */
    sealed interface PetSkillResult {
        data object Ok : PetSkillResult

        data class Error(
            val message: String,
            val code: String,
        ) : PetSkillResult
    }

    /**
     * Executes a pet skill triggered by the owner via `pet <skill>`.
     * Validates: active pet exists, owner is in combat, skill exists on pet, skill is off cooldown.
     * On success: applies damage / status / threat bonus to the owner's current combat target,
     * stamps the skill cooldown, and records the manual-cast timestamp so auto-cast won't preempt
     * the player's rotation for [PetConfig.manualSkillGraceMs].
     */
    suspend fun triggerPetSkill(
        ownerSid: SessionId,
        skillQuery: String,
    ): PetSkillResult {
        val pets = petSystem ?: return PetSkillResult.Error("Pets are not available.", "NO_PET_SYSTEM")
        val pet = pets.getActivePet(ownerSid)
            ?: return PetSkillResult.Error("You have no active pet.", "NO_ACTIVE_PET")
        if (pet.hp <= 0) {
            return PetSkillResult.Error("${pet.name} is incapacitated.", "PET_DEAD")
        }
        if (pet.spells.isEmpty()) {
            return PetSkillResult.Error("${pet.name} has no skills to use.", "PET_NO_SKILLS")
        }
        val skill = pets.findSkill(pet, skillQuery)
            ?: return PetSkillResult.Error("${pet.name} doesn't know '$skillQuery'.", "UNKNOWN_SKILL")

        val target = getCombatTarget(ownerSid)
            ?: return PetSkillResult.Error("You're not in combat.", "NOT_IN_COMBAT")
        if (target.roomId != pet.roomId) {
            return PetSkillResult.Error("${pet.name} can't reach your target.", "PET_OUT_OF_RANGE")
        }

        val now = clock.millis()
        val cd = mobSpellCooldowns[pet.id]?.get(skill.id) ?: 0L
        if (now < cd) {
            val remainingSec = (cd - now).ceilSeconds()
            return PetSkillResult.Error(
                "${skill.displayName} is on cooldown (${remainingSec}s remaining).",
                "ON_COOLDOWN",
            )
        }

        executePetSkill(pet, skill, target, ownerSid, now)
        pets.recordManualSkillCast(ownerSid, now)
        onPetSkillCast(ownerSid)
        return PetSkillResult.Ok
    }

    /**
     * Applies a pet skill against a target mob. Damage portion adds threat to the pet's synthetic
     * SID (if it has one — tank pets) or the owner (DPS pets, mirroring the auto-attack path).
     * Status effects route through StatusEffectSystem; threatBonus is a flat add for taunt skills.
     */
    private suspend fun executePetSkill(
        pet: MobState,
        skill: MobSpell,
        target: MobState,
        ownerSid: SessionId,
        now: Long,
    ) {
        if (skill.cooldownMs > 0L) {
            mobSpellCooldowns.getOrPut(pet.id) { mutableMapOf() }[skill.id] = now + skill.cooldownMs
        }

        val petSid = petSystem?.getPetSessionId(pet.id)
        val threatSid = petSid ?: ownerSid

        var dealtDamage = 0
        if (skill.damage != null) {
            val roll = rollRange(rng, skill.damage.min, skill.damage.max)
            val reduced = (roll - target.armor).coerceAtLeast(1)
            target.takeDamage(reduced)
            dealtDamage = reduced
            dirtyNotifier.mobHpDirty(target.id)

            val selfMsg = skill.message
                .replace("{pet}", pet.name)
                .replace("{target}", target.name)
                .replace("{damage}", reduced.toString())
                .ifEmpty { "${pet.name} hits ${target.name} with ${skill.displayName} for $reduced damage." }
            outbound.send(OutboundEvent.SendText(ownerSid, selfMsg))
            onCombatEvent(
                ownerSid,
                CombatEvent.PetHit(
                    petName = pet.name,
                    targetName = target.name,
                    targetId = target.id.value,
                    damage = reduced,
                    text = selfMsg,
                ),
            )
            val roomMsg = skill.roomMessage
                .replace("{pet}", pet.name)
                .replace("{target}", target.name)
                .replace("{damage}", reduced.toString())
                .ifEmpty { selfMsg }
            broadcastToRoom(players, outbound, target.roomId, roomMsg, exclude = ownerSid)
        } else if (skill.message.isNotEmpty() || skill.roomMessage.isNotEmpty()) {
            val selfMsg = skill.message
                .replace("{pet}", pet.name)
                .replace("{target}", target.name)
                .ifEmpty { "${pet.name} uses ${skill.displayName} on ${target.name}." }
            outbound.send(OutboundEvent.SendText(ownerSid, selfMsg))
            val roomMsg = skill.roomMessage
                .replace("{pet}", pet.name)
                .replace("{target}", target.name)
                .ifEmpty { selfMsg }
            broadcastToRoom(players, outbound, target.roomId, roomMsg, exclude = ownerSid)
        }

        if (skill.statusEffectId != null && statusEffects != null) {
            // Pet is the caster — pet skills already scale via the pet's
            // damage budget on the authored roll, so we scale the DOT/HOT tick
            // off the pet's level for consistency. No stat bonus.
            statusEffects.applyToMob(
                mobId = target.id,
                effectId = skill.statusEffectId,
                sourceSessionId = threatSid,
                casterLevel = pet.level,
            )
        }

        // Threat: damage portion uses pet's threat multiplier (tank pets hold aggro);
        // explicit threatBonus is a flat add for taunt-style skills like a bear's roar.
        if (activeMobs.containsKey(target.id)) {
            if (dealtDamage > 0) {
                val multiplier = if (pet.threatMultiplier > 0.0) pet.threatMultiplier else 1.0
                threatTable.addThreat(target.id, threatSid, dealtDamage.toDouble() * multiplier)
            }
            if (skill.threatBonus > 0.0) {
                threatTable.addThreat(target.id, threatSid, skill.threatBonus)
            }
        }

        if (target.hp <= 0) {
            handleMobDeath(ownerSid, target)
            outbound.send(OutboundEvent.SendPrompt(ownerSid))
        }
    }

    // --- Private helpers ---

    private suspend fun handlePlayerDeath(
        sessionId: SessionId,
        playerName: String,
        roomId: RoomId,
        deathMessage: String,
        roomMessage: String,
        killerName: String = "unknown",
        killerIsPlayer: Boolean = false,
    ) {
        onCombatEvent(
            sessionId,
            CombatEvent.Death(killerName = killerName, killerIsPlayer = killerIsPlayer),
        )
        outbound.send(OutboundEvent.SendText(sessionId, deathMessage))
        broadcastToRoom(players, outbound, roomId, roomMessage, exclude = sessionId)

        // Clean up cross-system state that should not persist through death
        onPlayerDeath(sessionId)

        val player = players.get(sessionId)
        if (player != null) {
            // Remember where the spirit gate should send them back to.
            player.lastDeathZone = roomId.zone

            // Resolve respawn room: sanctum → zone start → world start (whatever is in roomId now, as a last resort).
            val respawnRoom = sanctumRoomLookup()
                ?: zoneStartRoomLookup(roomId.zone)
                ?: player.roomId
            // Use moveTo (not a direct roomId assignment) so the registry's
            // room→sessions index is updated. A direct assignment left the
            // session indexed in the death room, so it kept receiving that
            // room's mob enter/leave broadcasts after respawning/departing.
            players.moveTo(sessionId, respawnRoom)

            // Restore a fraction of HP/mana. Leaves the player vulnerable so they have to rest in the sanctum.
            player.hp = ((player.maxHp * deathConfig.respawnHpFraction).toInt()).coerceAtLeast(1)
            player.mana = ((player.maxMana * deathConfig.respawnManaFraction).toInt()).coerceAtLeast(0)

            // Optional XP penalty (0 by default — tutorial stays forgiving).
            if (deathConfig.xpPenaltyFraction > 0.0) {
                val penalty = (player.xpTotal * deathConfig.xpPenaltyFraction).toLong()
                player.xpTotal = (player.xpTotal - penalty).coerceAtLeast(0L)
            }

            dirtyNotifier.playerVitalsDirty(sessionId)
            outbound.send(OutboundEvent.SendText(sessionId, deathConfig.messages.arriveSanctum))
            onPlayerRespawned(sessionId)
        }

        outbound.send(OutboundEvent.SendPrompt(sessionId))
    }

    private fun removePlayerFromCombat(sessionId: SessionId) {
        playerTarget.remove(sessionId)
        threatTable.removePlayer(sessionId)
        // Also remove the player's pet from the threat table
        removePetThreat(sessionId)
        dirtyNotifier.playerVitalsDirty(sessionId)
        dirtyNotifier.playerCombatDirty(sessionId)
        cleanupEmptyMobs()
    }

    /** Removes a player's tank pet from the threat table (e.g. on flee, disconnect, death). */
    private fun removePetThreat(ownerSid: SessionId) {
        if (petSystem == null) return
        val pet = petSystem.getActivePet(ownerSid) ?: return
        val petSid = petSystem.getPetSessionId(pet.id) ?: return
        threatTable.removePlayer(petSid)
    }

    private fun removeMobFromCombat(mobId: MobId) {
        activeMobs.remove(mobId)
        mobSpellCooldowns.remove(mobId)
        // Remove all players targeting this mob
        val toRemove = playerTarget.entries.filter { it.value == mobId }.map { it.key }
        for (sid in toRemove) {
            playerTarget.remove(sid)
            dirtyNotifier.playerVitalsDirty(sid)
            dirtyNotifier.playerCombatDirty(sid)
        }
        threatTable.removeMob(mobId)
    }

    private fun cleanupEmptyMobs() {
        val emptyMobs =
            activeMobs.keys.filter { mobId ->
                !threatTable.hasMobEntry(mobId)
            }
        for (mobId in emptyMobs) {
            activeMobs.remove(mobId)
            mobSpellCooldowns.remove(mobId)
        }
    }

    /**
     * Removes threat table entries and spell cooldowns for mobs that no longer
     * exist in the MobRegistry.  Call periodically to prevent unbounded growth.
     */
    fun cleanupStaleThreatEntries(): Int {
        mobSpellCooldowns.keys.removeAll { mobId -> mobs.get(mobId) == null }
        return threatTable.removeStaleEntries { mobId -> mobs.get(mobId) != null }
    }

    private fun threatMultiplier(player: PlayerState): Double =
        classRegistry?.get(player.playerClass)?.threatMultiplier ?: 1.0

    private fun rollPlayerMeleeSwing(
        player: PlayerState,
        stats: StatMap,
        equip: ItemRegistry.EquipmentBonuses,
        enemyArmor: Int,
    ): MeleeSwingResult = computePlayerMeleeSwing(config.bindings, player.level, stats, equip.attack, enemyArmor, rng)

    private fun avgPlayerMeleeDamage(
        player: PlayerState,
        stats: StatMap,
        equip: ItemRegistry.EquipmentBonuses,
        enemyArmor: Int,
    ): Double = expectedPlayerMeleeDamage(config.bindings, player.level, stats, equip.attack, enemyArmor)

    private fun combatFeedbackSuffix(
        roll: Int,
        attackBonus: Int = 0,
        armorAbsorbed: Int,
        clampedToMinimum: Boolean = false,
        shieldAbsorbed: Int = 0,
    ): String {
        if (!config.detailedFeedbackEnabled) return ""
        val parts = mutableListOf<String>()
        var rollSummary = "roll $roll"
        if (attackBonus > 0) {
            rollSummary += " +atk $attackBonus"
        }
        parts += rollSummary
        parts += "armor absorbed $armorAbsorbed"
        if (shieldAbsorbed > 0) {
            parts += "shield absorbed $shieldAbsorbed"
        }
        if (clampedToMinimum) {
            parts += "min 1 applied"
        }
        return " (${parts.joinToString(", ")})"
    }

    private fun findMobsInRoom(
        roomId: RoomId,
        keyword: String,
    ): List<MobState> = mobs.findInRoomByKeyword(roomId, keyword).filter { !it.isPet }

    private suspend fun handleMobDeath(
        killerSessionId: SessionId,
        mob: MobState,
    ) {
        // Collect all players who had threat on this mob (for quest/achievement callbacks).
        // Filter out synthetic pet SessionIds — only real players earn quest/achievement credit.
        val contributors = threatTable.playersThreateningMob(mob.id)
            .filterNot { petSystem?.isPetSession(it) == true }

        // Clean up combat state
        removeMobFromCombat(mob.id)

        mobs.remove(mob.id)
        callbacks.onMobRemoved(mob.id, mob.roomId)
        statusEffects?.onMobRemoved(mob.id)
        val mobCarried = items.dropMobItemsToRoom(mob.id, mob.roomId)
        val rolledDrops = rollDrops(mob)
        callbacks.onRoomItemsChanged(mob.roomId)
        broadcastToRoom(players, outbound, mob.roomId, "${mob.name} dies.")
        val lootedItems = applyAutolootForKiller(killerSessionId, mob.roomId, mobCarried + rolledDrops)
        val goldGained = grantKillGold(killerSessionId, mob)
        grantGroupKillXp(killerSessionId, mob)
        onCombatEvent(
            killerSessionId,
            CombatEvent.Kill(
                targetName = mob.name,
                targetId = mob.id.value,
                xpGained = mob.xpReward,
                goldGained = goldGained,
                lootedItems = lootedItems,
            ),
        )

        // Increment kill counter for the killing blow
        players.get(killerSessionId)?.let { it.mobsKilledTotal += 1 }

        // Fire quest/achievement callbacks for all contributors
        if (mob.templateKey.isNotEmpty()) {
            for (sid in contributors) {
                callbacks.onMobKilledByPlayer(sid, mob.templateKey)
            }
        }
    }

    private suspend fun grantKillGold(
        sessionId: SessionId,
        mob: MobState,
    ): Long {
        if (mob.goldMax <= 0L) return 0L
        val player = players.get(sessionId) ?: return 0L
        val goldDrop =
            if (mob.goldMin >= mob.goldMax) {
                mob.goldMin
            } else {
                mob.goldMin + rng.nextLong(mob.goldMax - mob.goldMin + 1)
            }
        if (goldDrop <= 0L) return 0L
        player.gold += goldDrop
        dirtyNotifier.playerVitalsDirty(sessionId)
        outbound.send(OutboundEvent.SendText(sessionId, "You find $goldDrop gold."))
        onGoldGained(sessionId, goldDrop, mob.name)
        return goldDrop
    }

    /**
     * Fires quest/achievement/faction kill credit for [mob] without combat — the
     * hook the Akathavae illumination path uses so a recorded creature counts as a
     * defeated one for objectives, letting the pledged complete the same quests as
     * everyone else. No XP, gold, or loot here; illumination grants its own awards.
     */
    suspend fun creditIlluminationKill(
        sessionId: SessionId,
        mob: MobState,
    ) {
        if (mob.templateKey.isNotEmpty()) {
            callbacks.onMobKilledByPlayer(sessionId, mob.templateKey)
        }
    }

    private suspend fun grantGroupKillXp(
        killerSessionId: SessionId,
        mob: MobState,
    ) {
        val baseReward = progression.killXpReward(mob)
        if (baseReward <= 0L) return

        val group = groupSystem?.getGroup(killerSessionId)
        val recipients =
            if (group != null) {
                group.members.filter { sid ->
                    val p = players.get(sid)
                    p != null && p.roomId == mob.roomId
                }
            } else {
                listOf(killerSessionId)
            }

        val memberCount = recipients.size
        val groupBonus =
            if (memberCount > 1) {
                1.0 + (memberCount - 1) * config.groupXpBonusPerMember
            } else {
                1.0
            }
        val perPlayerXp = ((baseReward.toDouble() / memberCount) * groupBonus).toLong().coerceAtLeast(1L)

        for (sid in recipients) {
            val player = players.get(sid) ?: continue
            val equipStats = items.equipmentBonuses(sid, classRegistry?.get(player.playerClass)).stats
            val totalBonusStat = player.stats[config.bindings.xpBonusStat] + equipStats[config.bindings.xpBonusStat]
            // Over-levelled kills are penalised; under-levelled kills are rewarded.
            // These are mutually exclusive — only one is ever != 1.0 for a given fight.
            val levelMultiplier =
                progression.diminishingKillXpMultiplier(player.level, mob.level) *
                    progression.underLevelKillXpMultiplier(player.level, mob.level)
            val afterLevel =
                if (levelMultiplier == 1.0) {
                    perPlayerXp
                } else {
                    (perPlayerXp * levelMultiplier).toLong().coerceAtLeast(0L)
                }
            if (afterLevel <= 0L) continue
            val reward = progression.applyCharismaXpBonus(totalBonusStat, afterLevel)

            val result = players.grantXp(sid, reward, progression) ?: continue
            metrics.onXpAwarded(reward, "kill")
            outbound.send(OutboundEvent.SendText(sid, "You gain $reward XP."))
            onXpGained(sid, reward, mob.name)
            dirtyNotifier.playerVitalsDirty(sid)
            if (result.levelsGained > 0) {
                metrics.onLevelUp()
                val levelUpMessage =
                    progression.buildLevelUpMessage(
                        result,
                        player.stats[config.bindings.hpScalingStat],
                        player.stats[config.bindings.manaScalingStat],
                        player.playerClass,
                    )
                outbound.send(OutboundEvent.SendText(sid, levelUpMessage))
                callbacks.onLevelUp(sid, result)
            }
        }
    }

    private fun rollDrops(mob: MobState): List<dev.ambon.domain.items.ItemInstance> {
        val dropped = mutableListOf<dev.ambon.domain.items.ItemInstance>()
        for (drop in mob.drops) {
            if (drop.chance <= 0.0) continue
            val shouldDrop = drop.chance >= 1.0 || rng.nextDouble() < drop.chance
            if (!shouldDrop) continue
            items.placeMobDrop(drop.itemId, mob.roomId)?.let { dropped += it }
        }
        return dropped
    }

    /**
     * If the killing player has auto-loot enabled, transfer the mob's dropped items from the
     * room into the killer's inventory. Skips synthetic pet sessions (pets don't hold inventory)
     * and no-ops when the killer isn't present or auto-loot is disabled.
     *
     * Group-loot rules are not enforced: the engine currently has no active loot-master/round-robin
     * policy (see GroupSystem.lootRobinIndex — declared but unused). Auto-loot follows the same
     * free-for-all policy as manual `get` in the current code path.
     */
    private suspend fun applyAutolootForKiller(
        killerSessionId: SessionId,
        roomId: RoomId,
        candidates: List<dev.ambon.domain.items.ItemInstance>,
    ): List<String> {
        if (candidates.isEmpty()) return emptyList()
        if (petSystem?.isPetSession(killerSessionId) == true) return emptyList()
        val killer = players.get(killerSessionId) ?: return emptyList()
        if (!killer.autolootEnabled) return emptyList()
        if (killer.roomId != roomId) return emptyList()

        val looted = mutableListOf<dev.ambon.domain.items.ItemInstance>()
        for (candidate in candidates) {
            if (!candidate.item.takeable) continue
            // takeFromRoomByInstance matches by object identity so a same-keyword pickup
            // elsewhere can't accidentally grab an unrelated item.
            val taken = items.takeFromRoomByInstance(killerSessionId, roomId, candidate) ?: continue
            looted += taken
            onItemAutoLooted(killerSessionId, taken)
        }
        if (looted.isEmpty()) return emptyList()

        val names = looted.map { it.item.displayName }
        outbound.send(OutboundEvent.SendInfo(killerSessionId, "You loot ${names.joinToString(", ")}."))
        callbacks.onRoomItemsChanged(roomId)
        return names
    }
}

/**
 * Resolved single-swing for a basic melee attack. `raw` is pre-armor damage
 * (what the feedback suffix calls "roll"); `final` is post-armor, clamped ≥ 1.
 */
internal data class MeleeSwingResult(
    val raw: Int,
    val final: Int,
    val attackPower: Int,
    val armorAbsorbed: Int,
    val clampedToMinimum: Boolean,
)

/**
 * Rolls a basic-melee swing using the config-driven formula:
 *
 * ```
 * attackPower = meleeBaseAttackPower + equipAttack
 * statBonus   = (stats[meleeDamageStat] - basePoint) × meleeStatMultiplier
 * levelScale  = meleeLevelScalingRate ^ (level - 1)
 * core        = (attackPower + statBonus) × levelScale
 * raw         = round(core × uniform(varianceMin, varianceMax))
 * mitigation  = enemyArmor / (enemyArmor + meleeArmorMitigationK)
 * final       = round(raw × (1 - mitigation)).coerceAtLeast(1)
 * ```
 *
 * Multiplicative armor mitigation is self-scaling: armor stays meaningful at
 * every level because it reduces a *percentage* of damage rather than a flat
 * amount. Applied symmetrically to mob → player attacks too.
 *
 * File-level so non-CombatSystem callers (duels, future PvP variants) can
 * share the same formula instead of drifting their own copy.
 */
internal fun computePlayerMeleeSwing(
    bindings: StatBindingsConfig,
    level: Int,
    stats: StatMap,
    equipAttack: Int,
    enemyArmor: Int,
    rng: Random,
): MeleeSwingResult {
    val attackPower = bindings.meleeBaseAttackPower + equipAttack
    val statTotal = stats[bindings.meleeDamageStat]
    val statBonus = (statTotal - PlayerState.BASE_STAT) * bindings.meleeStatMultiplier
    val levelScale = bindings.meleeLevelScalingRate.pow((level - 1).coerceAtLeast(0))
    val core = (attackPower + statBonus) * levelScale
    val varianceSpan = bindings.meleeVarianceMax - bindings.meleeVarianceMin
    val variance =
        if (varianceSpan <= 0.0) bindings.meleeVarianceMin else bindings.meleeVarianceMin + rng.nextDouble() * varianceSpan
    val raw = (core * variance).roundToInt().coerceAtLeast(1)
    // Inline so we can detect the "rounded to zero, floor applied" case for feedback —
    // applyArmorMitigation auto-clamps so it would hide that signal.
    val mitigation =
        if (enemyArmor <= 0) 0.0 else enemyArmor.toDouble() / (enemyArmor.toDouble() + bindings.meleeArmorMitigationK)
    val preClamp = (raw * (1.0 - mitigation)).roundToInt()
    val final = preClamp.coerceAtLeast(1)
    return MeleeSwingResult(
        raw = raw,
        final = final,
        attackPower = attackPower,
        armorAbsorbed = (raw - final).coerceAtLeast(0),
        clampedToMinimum = preClamp < 1,
    )
}

/**
 * Expected damage per swing for the same inputs as [computePlayerMeleeSwing]
 * but with the variance roll replaced by its midpoint. Drives the `consider`
 * win-chance estimate.
 */
internal fun expectedPlayerMeleeDamage(
    bindings: StatBindingsConfig,
    level: Int,
    stats: StatMap,
    equipAttack: Int,
    enemyArmor: Int,
): Double {
    val attackPower = bindings.meleeBaseAttackPower + equipAttack
    val statTotal = stats[bindings.meleeDamageStat]
    val statBonus = (statTotal - PlayerState.BASE_STAT) * bindings.meleeStatMultiplier
    val levelScale = bindings.meleeLevelScalingRate.pow((level - 1).coerceAtLeast(0))
    val core = (attackPower + statBonus) * levelScale
    val avgVariance = (bindings.meleeVarianceMin + bindings.meleeVarianceMax) / 2.0
    val rawAvg = core * avgVariance
    val mitigation = enemyArmor / (enemyArmor + bindings.meleeArmorMitigationK)
    return (rawAvg * (1.0 - mitigation)).coerceAtLeast(1.0)
}

/**
 * Multiplicative armor mitigation: `final = round(raw × (1 - armor/(armor+K)))`.
 * Shared by player swings and mob → player attacks so both sides use the same
 * curve. Returns at least 1 (a clean hit always lands) when [raw] > 0.
 */
internal fun applyArmorMitigation(raw: Int, armor: Int, mitigationK: Double): Int {
    if (raw <= 0) return 0
    if (armor <= 0 || mitigationK <= 0.0) return raw
    val mitigation = armor.toDouble() / (armor.toDouble() + mitigationK)
    return (raw * (1.0 - mitigation)).roundToInt().coerceAtLeast(1)
}
