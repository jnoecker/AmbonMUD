package dev.ambon.engine

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.status.EffectType
import dev.ambon.engine.status.StatusEffectSystem
import dev.ambon.metrics.GameMetrics
import java.time.Clock
import java.util.Random

class CombatSystem(
    private val players: PlayerRegistry,
    private val mobs: MobRegistry,
    private val items: ItemRegistry,
    private val outbound: OutboundBus,
    private val clock: Clock = Clock.systemUTC(),
    private val rng: Random = Random(),
    private val tickMillis: Long = 1_000L,
    internal val minDamage: Int = 1,
    internal val maxDamage: Int = 4,
    private val detailedFeedbackEnabled: Boolean = false,
    private val detailedFeedbackRoomBroadcastEnabled: Boolean = false,
    private val onMobRemoved: suspend (MobId, RoomId) -> Unit = { _, _ -> },
    private val progression: PlayerProgression = PlayerProgression(),
    private val metrics: GameMetrics = GameMetrics.noop(),
    private val onLevelUp: suspend (SessionId, Int) -> Unit = { _, _ -> },
    private val strDivisor: Int = 3,
    private val dexDodgePerPoint: Int = 2,
    private val maxDodgePercent: Int = 30,
    private val markVitalsDirty: (SessionId) -> Unit = {},
    private val markMobHpDirty: (MobId) -> Unit = {},
    private val statusEffects: StatusEffectSystem? = null,
    private val onMobKilledByPlayer: suspend (SessionId, String) -> Unit = { _, _ -> },
    private val groupSystem: GroupSystem? = null,
    private val groupXpBonusPerMember: Double = 0.10,
    private val threatMultiplierWarrior: Double = 1.5,
    private val threatMultiplierDefault: Double = 1.0,
    private val healingThreatMultiplier: Double = 0.5,
    private val onRoomItemsChanged: suspend (RoomId) -> Unit = {},
) {
    // Per-mob combat state (tracks tick timing)
    private data class MobCombatState(
        val mobId: MobId,
        var nextTickAtMs: Long,
    )

    // Which mob each player is attacking
    private val playerTarget = mutableMapOf<SessionId, MobId>()

    // Active mobs in combat (with tick timing)
    private val activeMobs = mutableMapOf<MobId, MobCombatState>()

    // Threat table: per-mob, tracks cumulative threat from each player
    internal val threatTable = ThreatTable()

    private val defenseByPlayer = mutableMapOf<SessionId, Int>()

    fun isInCombat(sessionId: SessionId): Boolean = playerTarget.containsKey(sessionId)

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
        val player = players.get(sessionId) ?: return "You are not connected."
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

    fun remapSession(
        oldSid: SessionId,
        newSid: SessionId,
    ) {
        val mobId = playerTarget.remove(oldSid)
        if (mobId != null) {
            playerTarget[newSid] = mobId
        }
        threatTable.remapSession(oldSid, newSid)
        defenseByPlayer.remove(oldSid)?.let { defenseByPlayer[newSid] = it }
    }

    fun onPlayerDisconnected(sessionId: SessionId) {
        removePlayerFromCombat(sessionId)
        defenseByPlayer.remove(sessionId)
    }

    fun endCombatFor(sessionId: SessionId) {
        removePlayerFromCombat(sessionId)
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
        markVitalsDirty(sessionId)
        if (!activeMobs.containsKey(mobId)) {
            activeMobs[mobId] = MobCombatState(mobId = mobId, nextTickAtMs = now + tickMillis)
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
        val threat = healAmount.toDouble() * healingThreatMultiplier
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
                outbound.send(OutboundEvent.SendText(sessionId, "You collapse, too wounded to keep fighting."))
                outbound.send(OutboundEvent.SendText(sessionId, "You are safe now — rest and your wounds will mend."))
                broadcastToRoom(players, outbound, player.roomId, "${player.name} has fallen in battle.", exclude = sessionId)
                outbound.send(OutboundEvent.SendPrompt(sessionId))
                ran++
                continue
            }

            // STUN check
            val stunned = statusEffects?.hasPlayerEffect(sessionId, EffectType.STUN) == true
            if (!stunned) {
                val playerAttack = playerBonuses.attack
                val playerStrBonus = strDamageBonus(player, playerBonuses.strength)
                val playerRoll = rollDamage()
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
                markMobHpDirty(mob.id)

                // Add threat (damage * class multiplier)
                val multiplier = threatMultiplier(player)
                threatTable.addThreat(mob.id, sessionId, effectivePlayerDamage.toDouble() * multiplier)

                val playerHitText = "You hit ${mob.name} for $effectivePlayerDamage damage$playerFeedbackSuffix."
                outbound.send(OutboundEvent.SendText(sessionId, playerHitText))
                if (detailedFeedbackEnabled && detailedFeedbackRoomBroadcastEnabled) {
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

            val targetBonuses = items.equipmentBonuses(targetSid)
            val dodgePct = dodgeChance(target, targetBonuses.dexterity)
            if (dodgePct > 0 && rng.nextInt(100) < dodgePct) {
                outbound.send(OutboundEvent.SendText(targetSid, "You dodge ${mob.name}'s attack!"))
            } else {
                val mobRoll = rollDamage(mob.minDamage, mob.maxDamage)
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
                markVitalsDirty(targetSid)
                val mobHitText =
                    if (shieldAbsorbed > 0 && mobDamage == 0) {
                        "Your shield absorbs ${mob.name}'s attack$mobFeedbackSuffix."
                    } else if (shieldAbsorbed > 0) {
                        "${mob.name} hits you for $mobDamage damage (shield absorbed $shieldAbsorbed)$mobFeedbackSuffix."
                    } else {
                        "${mob.name} hits you for $mobDamage damage$mobFeedbackSuffix."
                    }
                outbound.send(OutboundEvent.SendText(targetSid, mobHitText))
                if (detailedFeedbackEnabled && detailedFeedbackRoomBroadcastEnabled) {
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
                outbound.send(OutboundEvent.SendText(targetSid, "You have been slain by ${mob.name}."))
                outbound.send(OutboundEvent.SendText(targetSid, "You are safe now — rest and your wounds will mend."))
                broadcastToRoom(
                    players,
                    outbound,
                    target.roomId,
                    "${target.name} has been slain by ${mob.name}.",
                    exclude = targetSid,
                )
                outbound.send(OutboundEvent.SendPrompt(targetSid))
            }

            mobState.nextTickAtMs = now + tickMillis
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

    private fun removePlayerFromCombat(sessionId: SessionId) {
        playerTarget.remove(sessionId)
        threatTable.removePlayer(sessionId)
        markVitalsDirty(sessionId)
        cleanupEmptyMobs()
    }

    private fun removeMobFromCombat(mobId: MobId) {
        activeMobs.remove(mobId)
        // Remove all players targeting this mob
        val toRemove = playerTarget.entries.filter { it.value == mobId }.map { it.key }
        for (sid in toRemove) {
            playerTarget.remove(sid)
            markVitalsDirty(sid)
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

    private fun threatMultiplier(player: PlayerState): Double =
        if (player.playerClass.equals("WARRIOR", ignoreCase = true)) {
            threatMultiplierWarrior
        } else {
            threatMultiplierDefault
        }

    private fun rollDamage(
        min: Int = minDamage,
        max: Int = maxDamage,
    ): Int {
        require(min > 0) { "min damage must be > 0" }
        require(max >= min) { "max damage must be >= min damage" }
        val range = (max - min) + 1
        return min + rng.nextInt(range)
    }

    private fun combatFeedbackSuffix(
        roll: Int,
        attackBonus: Int = 0,
        armorAbsorbed: Int,
        clampedToMinimum: Boolean = false,
        shieldAbsorbed: Int = 0,
    ): String {
        if (!detailedFeedbackEnabled) return ""
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

    private fun strDamageBonus(player: PlayerState, equipStr: Int): Int {
        val statusStr = statusEffects?.getPlayerStatMods(player.sessionId)?.str ?: 0
        val totalStr = player.strength + equipStr + statusStr
        return PlayerState.statBonus(totalStr, strDivisor)
    }

    private fun dodgeChance(player: PlayerState, equipDex: Int): Int {
        val statusDex = statusEffects?.getPlayerStatMods(player.sessionId)?.dex ?: 0
        val totalDex = player.dexterity + equipDex + statusDex
        val chance = PlayerState.statBonus(totalDex, 1) * dexDodgePerPoint
        return chance.coerceIn(0, maxDodgePercent)
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
    ): List<MobState> {
        val lower = keyword.lowercase()
        return mobs
            .mobsInRoom(roomId)
            .filter { it.name.lowercase().contains(lower) }
            .sortedBy { it.name }
    }

    private suspend fun handleMobDeath(
        killerSessionId: SessionId,
        mob: MobState,
    ) {
        // Collect all players who had threat on this mob (for quest/achievement callbacks)
        val contributors = threatTable.playersThreateningMob(mob.id)

        // Clean up combat state
        removeMobFromCombat(mob.id)

        mobs.remove(mob.id)
        onMobRemoved(mob.id, mob.roomId)
        statusEffects?.onMobRemoved(mob.id)
        items.dropMobItemsToRoom(mob.id, mob.roomId)
        rollDrops(mob)
        onRoomItemsChanged(mob.roomId)
        broadcastToRoom(players, outbound, mob.roomId, "${mob.name} dies.")
        grantKillGold(killerSessionId, mob)
        grantGroupKillXp(killerSessionId, mob)

        // Fire quest/achievement callbacks for all contributors
        if (mob.templateKey.isNotEmpty()) {
            for (sid in contributors) {
                onMobKilledByPlayer(sid, mob.templateKey)
            }
        }
    }

    private suspend fun grantKillGold(
        sessionId: SessionId,
        mob: MobState,
    ) {
        if (mob.goldMax <= 0L) return
        val player = players.get(sessionId) ?: return
        val goldDrop =
            if (mob.goldMin >= mob.goldMax) {
                mob.goldMin
            } else {
                mob.goldMin + rng.nextLong(mob.goldMax - mob.goldMin + 1)
            }
        if (goldDrop <= 0L) return
        player.gold += goldDrop
        markVitalsDirty(sessionId)
        outbound.send(OutboundEvent.SendText(sessionId, "You find $goldDrop gold."))
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
                1.0 + (memberCount - 1) * groupXpBonusPerMember
            } else {
                1.0
            }
        val perPlayerXp = ((baseReward.toDouble() / memberCount) * groupBonus).toLong().coerceAtLeast(1L)

        for (sid in recipients) {
            val player = players.get(sid) ?: continue
            val equipCha = items.equipmentBonuses(sid).charisma
            val reward = progression.applyCharismaXpBonus(player.charisma + equipCha, perPlayerXp)

            val result = players.grantXp(sid, reward, progression) ?: continue
            metrics.onXpAwarded(reward, "kill")
            outbound.send(OutboundEvent.SendText(sid, "You gain $reward XP."))
            markVitalsDirty(sid)
            if (result.levelsGained > 0) {
                metrics.onLevelUp()
                val levelUpMessage =
                    progression.buildLevelUpMessage(result, player.constitution, player.intelligence, player.playerClass)
                outbound.send(OutboundEvent.SendText(sid, levelUpMessage))
                onLevelUp(sid, result.newLevel)
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
