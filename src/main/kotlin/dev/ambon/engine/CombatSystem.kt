package dev.ambon.engine

import dev.ambon.bus.OutboundBus
import dev.ambon.config.StatBindingsConfig
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.engine.events.CombatEvent
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.status.StatusEffectSystem
import dev.ambon.metrics.GameMetrics
import java.time.Clock
import java.util.Random

data class CombatSystemConfig(
    val tickMillis: Long = 1_000L,
    val minDamage: Int = 1,
    val maxDamage: Int = 4,
    val healingThreatMultiplier: Double = 0.5,
    val groupXpBonusPerMember: Double = 0.10,
    val detailedFeedbackEnabled: Boolean = false,
    val detailedFeedbackRoomBroadcastEnabled: Boolean = false,
    val bindings: StatBindingsConfig = StatBindingsConfig(),
)

data class CombatSystemCallbacks(
    val onMobRemoved: suspend (MobId, RoomId) -> Unit = { _, _ -> },
    val onLevelUp: suspend (SessionId, Int) -> Unit = { _, _ -> },
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
) : GameSystem {
    /** Callback for combat events; wired by GameEngine after construction. */
    var onCombatEvent: suspend (SessionId, CombatEvent) -> Unit = { _, _ -> }

    /** Callback for XP gain events; wired by GameEngine after construction. */
    var onXpGained: suspend (SessionId, Long, String) -> Unit = { _, _, _ -> }

    /** Callback for gold gain events; wired by GameEngine after construction. */
    var onGoldGained: suspend (SessionId, Long, String) -> Unit = { _, _, _ -> }

    /** Callback for player death cleanup; wired by GameEngine after construction. */
    var onPlayerDeath: suspend (SessionId) -> Unit = { _ -> }

    /** Callback when a player kills another player in PvP; wired by GameEngine. */
    var onPvpKill: suspend (killerSid: SessionId) -> Unit = { _ -> }

    // Per-mob combat state (tracks tick timing)
    private data class MobCombatState(
        val mobId: MobId,
        var nextTickAtMs: Long,
    )

    // Expose base damage range for display (e.g. score screen) without leaking full config.
    internal val minDamage: Int get() = config.minDamage
    internal val maxDamage: Int get() = config.maxDamage

    // Which mob each player is attacking
    private val playerTarget = mutableMapOf<SessionId, MobId>()

    // Active mobs in combat (with tick timing)
    private val activeMobs = mutableMapOf<MobId, MobCombatState>()

    // Threat table: per-mob, tracks cumulative threat from each player
    internal val threatTable = ThreatTable()

    private val defenseByPlayer = mutableMapOf<SessionId, Int>()

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

    fun syncPlayerDefense(sessionId: SessionId) {
        val player = players.get(sessionId) ?: return
        syncPlayerDefense(player, items.equipmentBonuses(sessionId).armor)
    }

    suspend fun startCombat(
        sessionId: SessionId,
        keywordRaw: String,
    ): String? {
        val player = players.get(sessionId) ?: return ERR_NOT_CONNECTED
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

        val now = clock.millis()
        registerCombatant(sessionId, mob.id, player, now)

        outbound.send(OutboundEvent.SendText(sessionId, "You attack ${mob.name}."))
        broadcastToRoom(players, outbound, roomId, "${player.name} attacks ${mob.name}.", exclude = sessionId)

        return null
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
        outbound.send(OutboundEvent.SendPrompt(sessionId))
        return null
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
        defenseByPlayer.remapKey(oldSid, newSid)

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
        defenseByPlayer.remove(sessionId)
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

        if (attackerSid == targetSid) return "You cannot attack yourself."
        if (target.isStaff) return "You cannot attack staff members."
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
                val attackerStats = resolvePlayerStats(attacker, items, statusEffects)
                val attackerEquip = items.equipmentBonuses(state.attackerSid)
                val strBonus = PlayerState.statBonus(
                    attackerStats[config.bindings.meleeDamageStat],
                    config.bindings.meleeDamageDivisor,
                )
                val roll = rollRange(rng, config.minDamage, config.maxDamage)
                val rawDamage = roll + attackerEquip.attack + strBonus
                val targetEquip = items.equipmentBonuses(state.targetSid)
                val preClampDamage = rawDamage - targetEquip.armor
                var effectiveDamage = preClampDamage.coerceAtLeast(1)

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

        if (playerTarget.containsKey(sessionId)) return false
        if (player.roomId != mob.roomId) return false

        val now = clock.millis()
        registerCombatant(sessionId, mobId, player, now)

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

            val playerBonuses = items.equipmentBonuses(sessionId)
            syncPlayerDefense(player, playerBonuses.armor)

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

            // STUN check
            val stunned = statusEffects?.hasPlayerEffect(sessionId, "stun") == true
            if (!stunned) {
                val playerStats = resolvePlayerStats(player, items, statusEffects)
                val playerAttack = playerBonuses.attack
                val playerStrBonus = PlayerState.statBonus(playerStats[config.bindings.meleeDamageStat], config.bindings.meleeDamageDivisor)
                val playerRoll = rollRange(rng, config.minDamage, config.maxDamage)
                val rawPlayerDamage = playerRoll + playerAttack + playerStrBonus
                val preClampPlayerDamage = rawPlayerDamage - mob.armor
                val effectivePlayerDamage = preClampPlayerDamage.coerceAtLeast(1)
                val playerArmorAbsorbed = (rawPlayerDamage - effectivePlayerDamage).coerceAtLeast(0)
                val playerMinDamageClamped = preClampPlayerDamage < 1
                val playerFeedbackSuffix =
                    combatFeedbackSuffix(
                        roll = playerRoll,
                        attackBonus = playerAttack,
                        armorAbsorbed = playerArmorAbsorbed,
                        clampedToMinimum = playerMinDamageClamped,
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

            // Pick target = highest threat in same room
            val targetSid =
                threatTable.topThreatInRoom(mobState.mobId) { sid ->
                    val p = players.get(sid)
                    p != null && p.roomId == mob.roomId
                }

            if (targetSid == null) {
                // No valid targets — mob exits combat
                removeMobFromCombat(mobState.mobId)
                continue
            }

            val target = players.get(targetSid) ?: continue

            val targetStats = resolvePlayerStats(target, items, statusEffects)
            val dodgePct =
                ((targetStats[config.bindings.dodgeStat] - PlayerState.BASE_STAT) * config.bindings.dodgePerPoint)
                    .coerceIn(0, config.bindings.maxDodgePercent)
            if (dodgePct > 0 && rng.nextInt(100) < dodgePct) {
                outbound.send(OutboundEvent.SendText(targetSid, "You dodge ${mob.name}'s attack!"))
                onCombatEvent(
                    targetSid,
                    CombatEvent.Dodge(
                        targetName = target.name,
                        targetId = null,
                        sourceIsPlayer = false,
                    ),
                )
            } else {
                val mobRoll = rollRange(rng, mob.damage.min, mob.damage.max)
                var mobDamage = mobRoll
                if (statusEffects != null) {
                    mobDamage = statusEffects.absorbPlayerDamage(targetSid, mobDamage)
                }
                val shieldAbsorbed = mobRoll - mobDamage
                val mobFeedbackSuffix =
                    combatFeedbackSuffix(
                        roll = mobRoll,
                        armorAbsorbed = 0,
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
                            targetName = target.name,
                            targetId = null,
                            damage = mobDamage,
                            sourceIsPlayer = false,
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
            }

            if (target.hp <= 0) {
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

            mobState.nextTickAtMs = now + config.tickMillis
            // Send prompt to all players targeting this mob
            for ((sid, mid) in playerTarget) {
                if (mid == mobState.mobId) {
                    outbound.send(OutboundEvent.SendPrompt(sid))
                }
            }
            ran++
        }
        return ran
    }

    suspend fun handleSpellKill(
        killerSessionId: SessionId,
        mob: MobState,
    ) {
        handleMobDeath(killerSessionId, mob)
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
        outbound.send(OutboundEvent.SendText(sessionId, "You are safe now — rest and your wounds will mend."))
        broadcastToRoom(players, outbound, roomId, roomMessage, exclude = sessionId)

        // Clean up cross-system state that should not persist through death
        onPlayerDeath(sessionId)

        outbound.send(OutboundEvent.SendPrompt(sessionId))
    }

    private fun removePlayerFromCombat(sessionId: SessionId) {
        playerTarget.remove(sessionId)
        threatTable.removePlayer(sessionId)
        dirtyNotifier.playerVitalsDirty(sessionId)
        dirtyNotifier.playerCombatDirty(sessionId)
        cleanupEmptyMobs()
    }

    private fun removeMobFromCombat(mobId: MobId) {
        activeMobs.remove(mobId)
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
        }
    }

    /**
     * Removes threat table entries for mobs that no longer exist in the
     * MobRegistry.  Call periodically to prevent unbounded growth.
     */
    fun cleanupStaleThreatEntries(): Int =
        threatTable.removeStaleEntries { mobId -> mobs.get(mobId) != null }

    private fun threatMultiplier(player: PlayerState): Double =
        classRegistry?.get(player.playerClass)?.threatMultiplier ?: 1.0

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

    private fun syncPlayerDefense(player: PlayerState, currentDefense: Int) {
        val sessionId = player.sessionId
        val previousDefense = defenseByPlayer[sessionId] ?: 0
        if (currentDefense == previousDefense) return

        val delta = currentDefense - previousDefense
        val newMaxHp = (player.maxHp + delta).coerceAtLeast(0)
        player.maxHp = newMaxHp
        player.hp =
            if (delta > 0) {
                (player.hp + delta).coerceAtMost(newMaxHp)
            } else {
                player.hp.coerceAtMost(newMaxHp)
            }
        if (player.hp < 0) player.hp = 0

        defenseByPlayer[sessionId] = currentDefense
    }

    private fun findMobsInRoom(
        roomId: RoomId,
        keyword: String,
    ): List<MobState> = mobs.findInRoomByKeyword(roomId, keyword)

    private suspend fun handleMobDeath(
        killerSessionId: SessionId,
        mob: MobState,
    ) {
        // Collect all players who had threat on this mob (for quest/achievement callbacks)
        val contributors = threatTable.playersThreateningMob(mob.id)

        // Clean up combat state
        removeMobFromCombat(mob.id)

        mobs.remove(mob.id)
        callbacks.onMobRemoved(mob.id, mob.roomId)
        statusEffects?.onMobRemoved(mob.id)
        items.dropMobItemsToRoom(mob.id, mob.roomId)
        rollDrops(mob)
        callbacks.onRoomItemsChanged(mob.roomId)
        broadcastToRoom(players, outbound, mob.roomId, "${mob.name} dies.")
        val goldGained = grantKillGold(killerSessionId, mob)
        grantGroupKillXp(killerSessionId, mob)
        onCombatEvent(
            killerSessionId,
            CombatEvent.Kill(
                targetName = mob.name,
                targetId = mob.id.value,
                xpGained = mob.xpReward,
                goldGained = goldGained,
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
            val equipStats = items.equipmentBonuses(sid).stats
            val totalBonusStat = player.stats[config.bindings.xpBonusStat] + equipStats[config.bindings.xpBonusStat]
            val reward = progression.applyCharismaXpBonus(totalBonusStat, perPlayerXp)

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
                callbacks.onLevelUp(sid, result.newLevel)
            }
        }
    }

    private fun rollDrops(mob: MobState) {
        for (drop in mob.drops) {
            if (drop.chance <= 0.0) continue
            val shouldDrop = drop.chance >= 1.0 || rng.nextDouble() < drop.chance
            if (!shouldDrop) continue
            items.placeMobDrop(drop.itemId, mob.roomId)
        }
    }
}
