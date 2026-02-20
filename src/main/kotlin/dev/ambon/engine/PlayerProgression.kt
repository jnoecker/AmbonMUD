package dev.ambon.engine

import dev.ambon.config.ProgressionConfig
import kotlin.math.pow
import kotlin.math.roundToLong

data class LevelUpResult(
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

    fun maxHpForLevel(level: Int): Int {
        val normalizedLevel = level.coerceIn(1, config.maxLevel)
        val bonus = (normalizedLevel - 1).toLong() * config.rewards.hpPerLevel.toLong()
        return (PlayerState.BASE_MAX_HP.toLong() + bonus)
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

    fun grantXp(
        player: PlayerState,
        amount: Long,
    ): LevelUpResult {
        val currentXpTotal = player.xpTotal.coerceAtLeast(0L)
        val currentLevel = computeLevel(currentXpTotal)
        player.xpTotal = currentXpTotal
        player.level = currentLevel

        val safeAmount = amount.coerceAtLeast(0L)
        if (safeAmount == 0L) {
            return LevelUpResult(levelsGained = 0, newLevel = currentLevel, xpTotal = currentXpTotal)
        }

        val previousLevel = currentLevel
        val newXpTotal = safeAdd(currentXpTotal, safeAmount)
        val newLevel = computeLevel(newXpTotal)
        val levelsGained = (newLevel - previousLevel).coerceAtLeast(0)

        player.xpTotal = newXpTotal
        player.level = newLevel

        if (levelsGained > 0) {
            val hpGain = levelsGained * config.rewards.hpPerLevel
            player.maxHp = (player.maxHp.toLong() + hpGain.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            player.hp =
                if (config.rewards.fullHealOnLevelUp) {
                    player.maxHp
                } else {
                    player.hp.coerceIn(0, player.maxHp)
                }
        } else {
            player.hp = player.hp.coerceIn(0, player.maxHp)
        }

        return LevelUpResult(levelsGained = levelsGained, newLevel = newLevel, xpTotal = newXpTotal)
    }

    private fun safeAdd(
        left: Long,
        right: Long,
    ): Long {
        if (left > Long.MAX_VALUE - right) return Long.MAX_VALUE
        return left + right
    }
}
