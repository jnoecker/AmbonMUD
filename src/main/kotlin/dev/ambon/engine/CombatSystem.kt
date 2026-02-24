package dev.ambon.engine

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.PlayerClass
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
) {
    private data class Fight(
        val sessionId: SessionId,
        val mobId: MobId,
        var nextTickAtMs: Long,
    )

    private val fightsByPlayer = mutableMapOf<SessionId, Fight>()
    private val fightsByMob = mutableMapOf<MobId, Fight>()
    private val defenseByPlayer = mutableMapOf<SessionId, Int>()

    fun isInCombat(sessionId: SessionId): Boolean = fightsByPlayer.containsKey(sessionId)

    fun isMobInCombat(mobId: MobId): Boolean = fightsByMob.containsKey(mobId)

    fun currentTarget(sessionId: SessionId): MobId? = fightsByPlayer[sessionId]?.mobId

    fun getCombatTarget(sessionId: SessionId): MobState? {
        val fight = fightsByPlayer[sessionId] ?: return null
        return mobs.get(fight.mobId)
    }

    fun findMobInRoom(
        roomId: RoomId,
        keyword: String,
    ): MobState? = findMobsInRoom(roomId, keyword).firstOrNull()

    fun syncPlayerDefense(sessionId: SessionId) {
        val player = players.get(sessionId) ?: return
        syncPlayerDefense(player)
    }

    suspend fun startCombat(
        sessionId: SessionId,
        keywordRaw: String,
    ): String? {
        val player = players.get(sessionId) ?: return "You are not connected."
        val keyword = keywordRaw.trim()
        if (keyword.isEmpty()) return "Kill what?"

        val existing = fightsByPlayer[sessionId]
        if (existing != null) {
            val mobName = mobs.get(existing.mobId)?.name ?: "your target"
            return "You are already fighting $mobName."
        }

        val roomId = player.roomId
        val matches = findMobsInRoom(roomId, keyword)
        if (matches.isEmpty()) return "You don't see '$keyword' here."

        val mob =
            matches.firstOrNull { !fightsByMob.containsKey(it.id) }
                ?: return "${matches.first().name} is already fighting someone."

        val fight =
            Fight(
                sessionId = sessionId,
                mobId = mob.id,
                nextTickAtMs = clock.millis() + tickMillis,
            )
        fightsByPlayer[sessionId] = fight
        fightsByMob[mob.id] = fight

        outbound.send(OutboundEvent.SendText(sessionId, "You attack ${mob.name}."))
        broadcastToRoom(roomId, "${player.name} attacks ${mob.name}.", exclude = sessionId)

        return null
    }

    suspend fun flee(
        sessionId: SessionId,
        forced: Boolean = false,
    ): String? {
        val fight = fightsByPlayer[sessionId] ?: return "You are not in combat."
        val mobName = mobs.get(fight.mobId)?.name ?: "your foe"
        endFight(fight)

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
        val fight = fightsByPlayer.remove(oldSid)
        if (fight != null) {
            val newFight = fight.copy(sessionId = newSid)
            fightsByPlayer[newSid] = newFight
            fightsByMob[fight.mobId] = newFight
        }
        defenseByPlayer.remove(oldSid)?.let { defenseByPlayer[newSid] = it }
    }

    fun onPlayerDisconnected(sessionId: SessionId) {
        val fight = fightsByPlayer[sessionId]
        if (fight != null) {
            endFight(fight)
        }
        defenseByPlayer.remove(sessionId)
    }

    fun endCombatFor(sessionId: SessionId) {
        val fight = fightsByPlayer[sessionId] ?: return
        endFight(fight)
    }

    suspend fun onMobRemovedExternally(mobId: MobId) {
        val fight = fightsByMob[mobId] ?: return
        endFight(fight)
        outbound.send(OutboundEvent.SendText(fight.sessionId, "Your opponent vanishes."))
        outbound.send(OutboundEvent.SendPrompt(fight.sessionId))
    }

    suspend fun tick(maxCombatsPerTick: Int = 20): Int {
        val now = clock.millis()
        var ran = 0
        val fights = fightsByPlayer.values.toMutableList()
        fights.shuffle(rng)
        for (fight in fights) {
            if (ran >= maxCombatsPerTick) break
            if (now < fight.nextTickAtMs) continue

            val player = players.get(fight.sessionId)
            val mob = mobs.get(fight.mobId)
            if (player == null || mob == null) {
                endFight(fight)
                continue
            }

            if (player.roomId != mob.roomId) {
                endFight(fight)
                outbound.send(OutboundEvent.SendText(fight.sessionId, "${mob.name} is no longer here."))
                outbound.send(OutboundEvent.SendPrompt(fight.sessionId))
                ran++
                continue
            }

            syncPlayerDefense(player)

            if (player.hp <= 0) {
                metrics.onPlayerDeath()
                endFight(fight)
                outbound.send(OutboundEvent.SendText(fight.sessionId, "You collapse, too wounded to keep fighting."))
                outbound.send(OutboundEvent.SendText(fight.sessionId, "You are safe now — rest and your wounds will mend."))
                broadcastToRoom(player.roomId, "${player.name} has fallen in battle.", exclude = fight.sessionId)
                outbound.send(OutboundEvent.SendPrompt(fight.sessionId))
                ran++
                continue
            }

            // STUN check: stunned players skip their attack but the mob still attacks
            val stunned = statusEffects?.hasPlayerEffect(fight.sessionId, EffectType.STUN) == true
            if (stunned) {
                outbound.send(OutboundEvent.SendText(fight.sessionId, "You are stunned and cannot act!"))
            } else {
                val playerAttack = equippedAttack(player.sessionId)
                val playerStrBonus = strDamageBonus(player)
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
                mob.hp = (mob.hp - effectivePlayerDamage).coerceAtLeast(0)
                markMobHpDirty(mob.id)
                val playerHitText = "You hit ${mob.name} for $effectivePlayerDamage damage$playerFeedbackSuffix."
                outbound.send(OutboundEvent.SendText(fight.sessionId, playerHitText))
                if (detailedFeedbackEnabled && detailedFeedbackRoomBroadcastEnabled) {
                    broadcastToRoom(
                        player.roomId,
                        "[Combat] ${player.name} hits ${mob.name} for $effectivePlayerDamage damage$playerFeedbackSuffix.",
                        exclude = fight.sessionId,
                    )
                }
                if (mob.hp <= 0) {
                    handleMobDeath(fight.sessionId, mob)
                    endFight(fight)
                    outbound.send(OutboundEvent.SendPrompt(fight.sessionId))
                    ran++
                    continue
                }
            }

            val dodgePct = dodgeChance(player)
            if (dodgePct > 0 && rng.nextInt(100) < dodgePct) {
                outbound.send(OutboundEvent.SendText(fight.sessionId, "You dodge ${mob.name}'s attack!"))
            } else {
                val mobRoll = rollDamage(mob.minDamage, mob.maxDamage)
                var mobDamage = mobRoll
                // SHIELD absorption
                if (statusEffects != null) {
                    mobDamage = statusEffects.absorbPlayerDamage(fight.sessionId, mobDamage)
                }
                val shieldAbsorbed = mobRoll - mobDamage
                val mobFeedbackSuffix =
                    combatFeedbackSuffix(
                        roll = mobRoll,
                        armorAbsorbed = 0,
                        shieldAbsorbed = shieldAbsorbed,
                    )
                player.hp = (player.hp - mobDamage).coerceAtLeast(0)
                markVitalsDirty(fight.sessionId)
                val mobHitText =
                    if (shieldAbsorbed > 0 && mobDamage == 0) {
                        "Your shield absorbs ${mob.name}'s attack$mobFeedbackSuffix."
                    } else if (shieldAbsorbed > 0) {
                        "${mob.name} hits you for $mobDamage damage (shield absorbed $shieldAbsorbed)$mobFeedbackSuffix."
                    } else {
                        "${mob.name} hits you for $mobDamage damage$mobFeedbackSuffix."
                    }
                outbound.send(OutboundEvent.SendText(fight.sessionId, mobHitText))
                if (detailedFeedbackEnabled && detailedFeedbackRoomBroadcastEnabled) {
                    broadcastToRoom(
                        player.roomId,
                        "[Combat] ${mob.name} hits ${player.name} for $mobDamage damage$mobFeedbackSuffix.",
                        exclude = fight.sessionId,
                    )
                }
            }

            if (player.hp <= 0) {
                metrics.onPlayerDeath()
                endFight(fight)
                outbound.send(OutboundEvent.SendText(fight.sessionId, "You have been slain by ${mob.name}."))
                outbound.send(OutboundEvent.SendText(fight.sessionId, "You are safe now — rest and your wounds will mend."))
                broadcastToRoom(player.roomId, "${player.name} has been slain by ${mob.name}.", exclude = fight.sessionId)
                outbound.send(OutboundEvent.SendPrompt(fight.sessionId))
                ran++
                continue
            }

            fight.nextTickAtMs = now + tickMillis
            outbound.send(OutboundEvent.SendPrompt(fight.sessionId))
            ran++
        }
        return ran
    }

    private fun endFight(fight: Fight) {
        fightsByPlayer.remove(fight.sessionId)
        fightsByMob.remove(fight.mobId)
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

    private fun equippedAttack(sessionId: SessionId): Int = items.equipment(sessionId).values.sumOf { it.item.damage }

    private fun equippedDefense(sessionId: SessionId): Int = items.equipment(sessionId).values.sumOf { it.item.armor }

    private fun strDamageBonus(player: PlayerState): Int {
        val equipStr = items.equipment(player.sessionId).values.sumOf { it.item.strength }
        val statusStr = statusEffects?.getPlayerStatMods(player.sessionId)?.str ?: 0
        val totalStr = player.strength + equipStr + statusStr
        return (totalStr - PlayerState.BASE_STAT) / strDivisor
    }

    private fun dodgeChance(player: PlayerState): Int {
        val equipDex = items.equipment(player.sessionId).values.sumOf { it.item.dexterity }
        val statusDex = statusEffects?.getPlayerStatMods(player.sessionId)?.dex ?: 0
        val totalDex = player.dexterity + equipDex + statusDex
        val chance = (totalDex - PlayerState.BASE_STAT) * dexDodgePerPoint
        return chance.coerceIn(0, maxDodgePercent)
    }

    private fun applyCharismaXpBonus(
        player: PlayerState,
        baseXp: Long,
    ): Long {
        val equipCha = items.equipment(player.sessionId).values.sumOf { it.item.charisma }
        val totalCha = player.charisma + equipCha
        val chaBonus = totalCha - PlayerState.BASE_STAT
        if (chaBonus <= 0) return baseXp
        val multiplier = 1.0 + chaBonus * 0.005
        return (baseXp * multiplier).toLong().coerceAtLeast(baseXp)
    }

    private fun syncPlayerDefense(player: PlayerState) {
        val sessionId = player.sessionId
        val currentDefense = equippedDefense(sessionId)
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
        mobs.remove(mob.id)
        onMobRemoved(mob.id, mob.roomId)
        statusEffects?.onMobRemoved(mob.id)
        items.dropMobItemsToRoom(mob.id, mob.roomId)
        rollDrops(mob)
        broadcastToRoom(mob.roomId, "${mob.name} dies.")
        grantKillXp(killerSessionId, mob)
    }

    suspend fun handleSpellKill(
        killerSessionId: SessionId,
        mob: MobState,
    ) {
        val fight = fightsByMob[mob.id]
        if (fight != null) endFight(fight)
        handleMobDeath(killerSessionId, mob)
    }

    private fun rollDrops(mob: MobState) {
        for (drop in mob.drops) {
            if (drop.chance <= 0.0) continue
            val shouldDrop = drop.chance >= 1.0 || rng.nextDouble() < drop.chance
            if (!shouldDrop) continue
            items.placeMobDrop(drop.itemId, mob.roomId)
        }
    }

    private suspend fun grantKillXp(
        sessionId: SessionId,
        mob: MobState,
    ) {
        val baseReward = progression.killXpReward(mob)
        if (baseReward <= 0L) return

        val player = players.get(sessionId) ?: return
        val reward = applyCharismaXpBonus(player, baseReward)

        val result = players.grantXp(sessionId, reward, progression) ?: return
        metrics.onXpAwarded(reward, "kill")
        outbound.send(OutboundEvent.SendText(sessionId, "You gain $reward XP."))
        markVitalsDirty(sessionId)
        if (result.levelsGained <= 0) return
        metrics.onLevelUp()

        val con = player.constitution
        val int = player.intelligence
        val pc = PlayerClass.fromString(player.playerClass)
        val classHpPerLevel = pc?.hpPerLevel ?: progression.hpPerLevel
        val classManaPerLevel = pc?.manaPerLevel ?: progression.manaPerLevel
        val oldMaxHp = progression.maxHpForLevel(result.previousLevel, con, classHpPerLevel)
        val newMaxHp = progression.maxHpForLevel(result.newLevel, con, classHpPerLevel)
        val hpGain = (newMaxHp - oldMaxHp).coerceAtLeast(0)
        val oldMaxMana = progression.maxManaForLevel(result.previousLevel, int, classManaPerLevel)
        val newMaxMana = progression.maxManaForLevel(result.newLevel, int, classManaPerLevel)
        val manaGain = (newMaxMana - oldMaxMana).coerceAtLeast(0)
        val bonusParts = mutableListOf<String>()
        if (hpGain > 0) bonusParts += "+$hpGain max HP"
        if (manaGain > 0) bonusParts += "+$manaGain max Mana"
        val levelUpMessage =
            if (bonusParts.isNotEmpty()) {
                "You reached level ${result.newLevel}! (${bonusParts.joinToString(", ")})"
            } else {
                "You reached level ${result.newLevel}!"
            }
        outbound.send(OutboundEvent.SendText(sessionId, levelUpMessage))
        onLevelUp(sessionId, result.newLevel)
    }

    private suspend fun broadcastToRoom(
        roomId: RoomId,
        text: String,
        exclude: SessionId? = null,
    ) {
        for (p in players.playersInRoom(roomId)) {
            if (exclude != null && p.sessionId == exclude) continue
            outbound.send(OutboundEvent.SendText(p.sessionId, text))
        }
    }
}
