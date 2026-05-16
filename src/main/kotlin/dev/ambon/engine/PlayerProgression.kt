package dev.ambon.engine

import dev.ambon.config.ProgressionConfig
import dev.ambon.config.QuestDifficulty
import dev.ambon.config.StatBindingsConfig
import dev.ambon.domain.mob.MobState
import kotlin.math.floor
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
    private val classRegistry: PlayerClassRegistry? = null,
    val bindings: StatBindingsConfig = StatBindingsConfig(),
) {
    val maxLevel: Int
        get() = config.maxLevel

    val hpScalingRate: Double
        get() = config.rewards.hpScalingRate

    val manaScalingRate: Double
        get() = config.rewards.manaScalingRate

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

    /**
     * Resolves the effective HP and mana scaling rates for [playerClass]. When
     * the class supplies a rate, the class rate overrides the global progression
     * rate (matches Arcanum's simulateEncounter behavior). Falls back to the
     * configured defaults if [playerClass] is null or unrecognised.
     */
    fun resolveClassScaling(playerClass: String?): Pair<Double, Double> {
        val def = playerClass?.let { classRegistry?.get(it) }
        return Pair(
            def?.hpScalingRate ?: config.rewards.hpScalingRate,
            def?.manaScalingRate ?: config.rewards.manaScalingRate,
        )
    }

    fun maxHpForLevel(
        level: Int,
        statValue: Int = PlayerState.BASE_STAT,
        hpScalingRate: Double = config.rewards.hpScalingRate,
    ): Int = maxResourceForLevel(level, statValue, hpScalingRate, config.rewards.baseHp, bindings.hpScalingDivisor)

    fun maxManaForLevel(
        level: Int,
        statValue: Int = PlayerState.BASE_STAT,
        manaScalingRate: Double = config.rewards.manaScalingRate,
    ): Int = maxResourceForLevel(level, statValue, manaScalingRate, config.rewards.baseMana, bindings.manaScalingDivisor)

    /**
     * Applies the level-derived base HP/mana stats to [ps], clamping current
     * hp and mana to the new maximums. Callers are responsible for setting
     * [PlayerState.level] and [PlayerState.xpTotal] before or after this call.
     */
    fun applyLevelStats(ps: PlayerState, level: Int) {
        val (classHpRate, classManaRate) = resolveClassScaling(ps.playerClass)
        val newMaxHp = maxHpForLevel(level, ps.stats[bindings.hpScalingStat], classHpRate)
        val newMaxMana = maxManaForLevel(level, ps.stats[bindings.manaScalingStat], classManaRate)
        ps.baseMaxHp = newMaxHp
        ps.maxHp = newMaxHp
        ps.hp = ps.hp.coerceIn(1, newMaxHp)
        ps.baseMana = newMaxMana
        ps.maxMana = newMaxMana
        ps.mana = ps.mana.coerceIn(0, newMaxMana)
    }

    /**
     * Recomputes the level-derived HP/mana caps for [ps] from its current stats,
     * preserving any bonus delta above [PlayerState.baseMaxHp]/[PlayerState.baseMana]
     * (e.g. from prestige perks or status effects). Clamps current hp/mana to the
     * new caps. Used when stats change outside of a level transition — for
     * example, when a player swaps race at a stylist.
     */
    fun recomputeVitalCaps(ps: PlayerState) {
        val (classHpRate, classManaRate) = resolveClassScaling(ps.playerClass)
        val hpBonus = (ps.maxHp - ps.baseMaxHp).coerceAtLeast(0)
        val manaBonus = (ps.maxMana - ps.baseMana).coerceAtLeast(0)
        val newBaseMaxHp = maxHpForLevel(ps.level, ps.stats[bindings.hpScalingStat], classHpRate)
        val newBaseMana = maxManaForLevel(ps.level, ps.stats[bindings.manaScalingStat], classManaRate)
        ps.baseMaxHp = newBaseMaxHp
        ps.maxHp = safeAddInt(newBaseMaxHp, hpBonus)
        ps.hp = ps.hp.coerceIn(1, ps.maxHp)
        ps.baseMana = newBaseMana
        ps.maxMana = safeAddInt(newBaseMana, manaBonus)
        ps.mana = ps.mana.coerceIn(0, ps.maxMana)
    }

    private fun maxResourceForLevel(level: Int, stat: Int, scalingRate: Double, baseValue: Int, divisor: Int = 5): Int {
        val normalizedLevel = level.coerceIn(1, config.maxLevel)
        val steps = (normalizedLevel - 1).coerceAtLeast(0)
        val scaledBase = if (steps == 0) {
            baseValue.toDouble()
        } else {
            floor(baseValue.toDouble() * scalingRate.pow(steps))
        }
        if (!scaledBase.isFinite() || scaledBase >= Int.MAX_VALUE.toDouble()) return Int.MAX_VALUE
        val scaledBaseLong = scaledBase.toLong().coerceAtLeast(baseValue.toLong())
        val statBonus = if (divisor > 0) ((stat - PlayerState.BASE_STAT) / divisor).toLong() * steps.toLong() else 0L
        return (scaledBaseLong + statBonus)
            .coerceAtLeast(baseValue.toLong())
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
     * Computes the engine-authored XP reward for a quest based on its tier and
     * intended level. `(baseline.baseXp + baseline.xpPerLevel * (level - 1))`
     * scaled by the tier multiplier; the result is clamped to non-negative.
     * Returns 0 when [difficulty] is null (no tier declared) or when the
     * tier has no configured multiplier.
     */
    fun computeQuestXp(difficulty: QuestDifficulty?, level: Int): Long {
        if (difficulty == null) return 0L
        val multiplier = config.quests.tiers[difficulty] ?: return 0L
        val steps = (level.coerceAtLeast(1) - 1).toLong()
        val baseline = config.quests.baseline.baseXp + config.quests.baseline.xpPerLevel * steps
        val scaled = baseline.toDouble() * multiplier
        if (!scaled.isFinite()) return Long.MAX_VALUE
        return scaled.roundToLong().coerceAtLeast(0L)
    }

    /**
     * Returns the XP multiplier for a kill given the player's level and the
     * mob's level. When the player out-levels the mob by enough to match a
     * configured diminishing-returns threshold, the corresponding multiplier is
     * applied. The threshold with the largest `levelsBelow` that still fits
     * the gap wins.
     */
    fun diminishingKillXpMultiplier(playerLevel: Int, mobLevel: Int): Double {
        val cfg = config.xp.diminishing
        if (!cfg.enabled || cfg.thresholds.isEmpty()) return 1.0
        val gap = playerLevel - mobLevel
        if (gap <= 0) return 1.0
        var best: Double? = null
        var bestLevelsBelow = -1
        for (t in cfg.thresholds) {
            if (gap >= t.levelsBelow && t.levelsBelow > bestLevelsBelow) {
                best = t.multiplier
                bestLevelsBelow = t.levelsBelow
            }
        }
        return best ?: 1.0
    }

    /**
     * Applies an XP multiplier based on the configured xpBonusStat (+[bindings.xpBonusPerPoint] per
     * point above [PlayerState.BASE_STAT]) to [baseXp]. Returns [baseXp] unchanged if there is no bonus.
     */
    fun applyCharismaXpBonus(
        totalBonusStat: Int,
        baseXp: Long,
    ): Long {
        val statBonus = totalBonusStat - PlayerState.BASE_STAT
        if (statBonus <= 0) return baseXp
        val multiplier = 1.0 + statBonus * bindings.xpBonusPerPoint
        return (baseXp * multiplier).toLong().coerceAtLeast(baseXp)
    }

    /**
     * Builds the level-up congratulation message shown to the player,
     * including HP and mana gains derived from the level transition.
     */
    fun buildLevelUpMessage(
        result: LevelUpResult,
        hpStatValue: Int,
        manaStatValue: Int,
        playerClass: String?,
    ): String {
        val (classHpRate, classManaRate) = resolveClassScaling(playerClass)
        val newMaxHp = maxHpForLevel(result.newLevel, hpStatValue, classHpRate)
        val oldMaxHp = maxHpForLevel(result.previousLevel, hpStatValue, classHpRate)
        val hpGain = (newMaxHp - oldMaxHp).coerceAtLeast(0)
        val newMaxMana = maxManaForLevel(result.newLevel, manaStatValue, classManaRate)
        val oldMaxMana = maxManaForLevel(result.previousLevel, manaStatValue, classManaRate)
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
        val hpStat = player.stats[bindings.hpScalingStat]
        val manaStat = player.stats[bindings.manaScalingStat]
        val (classHpRate, classManaRate) = resolveClassScaling(player.playerClass)
        val currentXpTotal = player.xpTotal.coerceAtLeast(0L)
        val currentLevel = computeLevel(currentXpTotal)
        val currentBaseMaxHp = maxHpForLevel(currentLevel, hpStat, classHpRate)
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

        val previousBaseMaxHp = maxHpForLevel(previousLevel, hpStat, classHpRate)
        val newBaseMaxHp = maxHpForLevel(newLevel, hpStat, classHpRate)
        val nonProgressionBonus = (player.maxHp - previousBaseMaxHp).coerceAtLeast(0)
        val newEffectiveMaxHp = safeAddInt(newBaseMaxHp, nonProgressionBonus)
        player.baseMaxHp = newBaseMaxHp
        player.maxHp = newEffectiveMaxHp

        val newMaxMana = maxManaForLevel(newLevel, manaStat, classManaRate)
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
