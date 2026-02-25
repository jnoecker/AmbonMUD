package dev.ambon.engine

import dev.ambon.config.ProgressionConfig
import dev.ambon.domain.PlayerClass
import dev.ambon.domain.mob.MobState
import kotlin.math.pow
import kotlin.math.roundToLong

data class LevelUpResult(
    val previousLevel: Int,
    val levelsGained: Int,
    val newLevel: Int,
    val xpTotal: Long,
)

class PlayerProgression(
    private val config: ProgressionConfig = ProgressionConfig(),
) {
    val maxLevel: Int
        get() = config.maxLevel

    val hpPerLevel: Int
        get() = config.rewards.hpPerLevel

    val manaPerLevel: Int
        get() = config.rewards.manaPerLevel

    fun totalXpForLevel(level: Int): Long {
        if (level <= 1) return 0L

        val normalizedLevel = level.coerceIn(1, config.maxLevel)
        val steps = (normalizedLevel - 1).toDouble()
        val curved = config.xp.baseXp.toDouble() * steps.pow(config.xp.exponent)
        val linear = config.xp.linearXp.toDouble() * steps
        val total = curved + linear

        if (!total.isFinite()) return Long.MAX_VALUE
        return total.roundToLong().coerceAtLeast(0L)
    }

    fun computeLevel(xpTotal: Long): Int {
        val clampedXp = xpTotal.coerceAtLeast(0L)
        if (config.maxLevel <= 1) return 1
        if (clampedXp >= totalXpForLevel(config.maxLevel)) return config.maxLevel

        var low = 1
        var high = config.maxLevel
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (totalXpForLevel(mid) <= clampedXp) {
                low = mid
            } else {
                high = mid - 1
            }
        }
        return low
    }

    fun xpIntoLevel(xpTotal: Long): Long {
        val clampedXp = xpTotal.coerceAtLeast(0L)
        val level = computeLevel(clampedXp)
        return (clampedXp - totalXpForLevel(level)).coerceAtLeast(0L)
    }

    fun xpToNextLevel(xpTotal: Long): Long? {
        val level = computeLevel(xpTotal)
        if (level >= config.maxLevel) return null
        val currentFloor = totalXpForLevel(level)
        val nextFloor = totalXpForLevel(level + 1)
        return (nextFloor - currentFloor).coerceAtLeast(0L)
    }

    fun maxHpForLevel(
        level: Int,
        constitution: Int = PlayerState.BASE_STAT,
        hpPerLevel: Int = config.rewards.hpPerLevel,
    ): Int {
        val normalizedLevel = level.coerceIn(1, config.maxLevel)
        val levels = (normalizedLevel - 1).toLong()
        val baseBonus = levels * hpPerLevel.toLong()
        val conBonus = ((constitution - PlayerState.BASE_STAT) / 5).toLong() * levels
        return (PlayerState.BASE_MAX_HP.toLong() + baseBonus + conBonus)
            .coerceAtLeast(PlayerState.BASE_MAX_HP.toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    fun maxManaForLevel(
        level: Int,
        intelligence: Int = PlayerState.BASE_STAT,
        manaPerLevel: Int = config.rewards.manaPerLevel,
    ): Int {
        val normalizedLevel = level.coerceIn(1, config.maxLevel)
        val levels = (normalizedLevel - 1).toLong()
        val baseBonus = levels * manaPerLevel.toLong()
        val intBonus = ((intelligence - PlayerState.BASE_STAT) / 5).toLong() * levels
        return (PlayerState.BASE_MANA.toLong() + baseBonus + intBonus)
            .coerceAtLeast(PlayerState.BASE_MANA.toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    fun scaledXp(amount: Long): Long {
        if (amount <= 0L) return 0L
        val total = amount.toDouble() * config.xp.multiplier
        if (!total.isFinite()) return Long.MAX_VALUE
        return total.roundToLong().coerceAtLeast(0L)
    }

    fun defaultKillXpReward(): Long = scaledXp(config.xp.defaultKillXp)

    fun killXpReward(mob: MobState): Long = scaledXp(mob.xpReward)

    /**
     * Applies a charisma-based XP multiplier (+0.5% per point above [PlayerState.BASE_STAT])
     * to [baseXp] and returns the adjusted amount. Returns [baseXp] unchanged if there is no bonus.
     */
    fun applyCharismaXpBonus(
        totalCha: Int,
        baseXp: Long,
    ): Long {
        val chaBonus = totalCha - PlayerState.BASE_STAT
        if (chaBonus <= 0) return baseXp
        val multiplier = 1.0 + chaBonus * 0.005
        return (baseXp * multiplier).toLong().coerceAtLeast(baseXp)
    }

    /**
     * Builds the level-up congratulation message shown to the player,
     * including HP and mana gains derived from the level transition.
     */
    fun buildLevelUpMessage(
        result: LevelUpResult,
        constitution: Int,
        intelligence: Int,
        playerClass: String?,
    ): String {
        val pc = PlayerClass.fromString(playerClass ?: "")
        val classHpPerLevel = pc?.hpPerLevel ?: hpPerLevel
        val classManaPerLevel = pc?.manaPerLevel ?: manaPerLevel
        val newMaxHp = maxHpForLevel(result.newLevel, constitution, classHpPerLevel)
        val oldMaxHp = maxHpForLevel(result.previousLevel, constitution, classHpPerLevel)
        val hpGain = (newMaxHp - oldMaxHp).coerceAtLeast(0)
        val newMaxMana = maxManaForLevel(result.newLevel, intelligence, classManaPerLevel)
        val oldMaxMana = maxManaForLevel(result.previousLevel, intelligence, classManaPerLevel)
        val manaGain = (newMaxMana - oldMaxMana).coerceAtLeast(0)
        val bonusParts = mutableListOf<String>()
        if (hpGain > 0) bonusParts += "+$hpGain max HP"
        if (manaGain > 0) bonusParts += "+$manaGain max Mana"
        return if (bonusParts.isNotEmpty()) {
            "You reached level ${result.newLevel}! (${bonusParts.joinToString(", ")})"
        } else {
            "You reached level ${result.newLevel}!"
        }
    }

    fun grantXp(
        player: PlayerState,
        amount: Long,
    ): LevelUpResult {
        val con = player.constitution
        val int = player.intelligence
        val pc = PlayerClass.fromString(player.playerClass)
        val classHpPerLevel = pc?.hpPerLevel ?: config.rewards.hpPerLevel
        val classManaPerLevel = pc?.manaPerLevel ?: config.rewards.manaPerLevel
        val currentXpTotal = player.xpTotal.coerceAtLeast(0L)
        val currentLevel = computeLevel(currentXpTotal)
        val currentBaseMaxHp = maxHpForLevel(currentLevel, con, classHpPerLevel)
        val existingBonus = (player.maxHp - player.baseMaxHp).coerceAtLeast(0)
        player.xpTotal = currentXpTotal
        player.level = currentLevel
        player.baseMaxHp = currentBaseMaxHp
        player.maxHp = safeAddInt(currentBaseMaxHp, existingBonus)

        val safeAmount = amount.coerceAtLeast(0L)
        if (safeAmount == 0L) {
            player.hp = player.hp.coerceIn(0, player.maxHp)
            return LevelUpResult(
                previousLevel = currentLevel,
                levelsGained = 0,
                newLevel = currentLevel,
                xpTotal = currentXpTotal,
            )
        }

        val previousLevel = currentLevel
        val newXpTotal = safeAdd(currentXpTotal, safeAmount)
        val newLevel = computeLevel(newXpTotal)
        val levelsGained = (newLevel - previousLevel).coerceAtLeast(0)

        player.xpTotal = newXpTotal
        player.level = newLevel

        val previousBaseMaxHp = maxHpForLevel(previousLevel, con, classHpPerLevel)
        val newBaseMaxHp = maxHpForLevel(newLevel, con, classHpPerLevel)
        val nonProgressionBonus = (player.maxHp - previousBaseMaxHp).coerceAtLeast(0)
        val newEffectiveMaxHp = safeAddInt(newBaseMaxHp, nonProgressionBonus)
        player.baseMaxHp = newBaseMaxHp
        player.maxHp = newEffectiveMaxHp

        val newMaxMana = maxManaForLevel(newLevel, int, classManaPerLevel)
        player.baseMana = newMaxMana
        player.maxMana = newMaxMana

        if (levelsGained > 0) {
            player.hp =
                if (config.rewards.fullHealOnLevelUp) {
                    newEffectiveMaxHp
                } else {
                    player.hp.coerceIn(0, newEffectiveMaxHp)
                }
            player.mana =
                if (config.rewards.fullManaOnLevelUp) {
                    newMaxMana
                } else {
                    player.mana.coerceIn(0, newMaxMana)
                }
        } else {
            player.hp = player.hp.coerceIn(0, newEffectiveMaxHp)
            player.mana = player.mana.coerceIn(0, newMaxMana)
        }

        return LevelUpResult(
            previousLevel = previousLevel,
            levelsGained = levelsGained,
            newLevel = newLevel,
            xpTotal = newXpTotal,
        )
    }

    private fun safeAdd(
        left: Long,
        right: Long,
    ): Long {
        if (left > Long.MAX_VALUE - right) return Long.MAX_VALUE
        return left + right
    }

    private fun safeAddInt(
        left: Int,
        right: Int,
    ): Int = (left.toLong() + right.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
