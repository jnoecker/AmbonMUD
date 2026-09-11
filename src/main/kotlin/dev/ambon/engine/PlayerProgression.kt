package dev.ambon.engine

import dev.ambon.config.ProgressionConfig
import dev.ambon.config.QuestDifficulty
import dev.ambon.config.StatBindingsConfig
import dev.ambon.domain.StatMap
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

    /** True when pools count equipment stats (D-20); callers then pass equipment bonuses to the vitals functions. */
    val poolsUseEquipment: Boolean
        get() = bindings.multiplicativePools && bindings.poolsUseEquipment

    /** The stat a pool is computed from: the player's own stat plus equipment when [poolsUseEquipment]. */
    fun poolStat(
        ps: PlayerState,
        statKey: String,
        equipStats: StatMap?,
    ): Int = ps.stats[statKey] + (if (poolsUseEquipment) (equipStats?.get(statKey) ?: 0) else 0)

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
     * Claim-time XP for a repeatable reward.
     *
     * When the source's configured fraction is positive the award is that share of the claimant's own
     * remaining level cost, so one daily slot is worth the same slice of progress at level 2 and at
     * level 29; when it is 0 the authored [flat] number stands. At the cap, where there is no next
     * level, the last level's cost stands in so a capped claimant still earns a sensible amount.
     */
    fun repeatableXp(
        flat: Long,
        xpTotal: Long,
        source: RepeatableXpSource,
    ): Long {
        val fraction = fractionFor(source)
        if (fraction <= 0.0) return flat
        val levelCost = xpToNextLevel(xpTotal) ?: lastLevelCost()
        val scaled = fraction * levelCost.toDouble()
        if (!scaled.isFinite()) return flat
        return scaled.roundToLong().coerceAtLeast(0L)
    }

    private fun fractionFor(source: RepeatableXpSource): Double = when (source) {
        RepeatableXpSource.DAILY -> config.repeatableXp.dailyFractionOfLevel
        RepeatableXpSource.WEEKLY -> config.repeatableXp.weeklyFractionOfLevel
        RepeatableXpSource.AUTO_QUEST -> config.repeatableXp.autoQuestFractionOfLevel
        RepeatableXpSource.GLOBAL_FIRST -> config.repeatableXp.globalFirstFractionOfLevel
        RepeatableXpSource.GLOBAL_SECOND -> config.repeatableXp.globalSecondFractionOfLevel
        RepeatableXpSource.GLOBAL_THIRD -> config.repeatableXp.globalThirdFractionOfLevel
    }

    private fun lastLevelCost(): Long {
        val top = config.maxLevel.coerceAtLeast(2)
        return (totalXpForLevel(top) - totalXpForLevel(top - 1)).coerceAtLeast(0L)
    }

    /**
     * Resolves the effective HP and mana scaling rates for [playerClass]. When
     * the class supplies a rate, the class rate overrides the global progression
     * rate (matches Arcanum's simulateEncounter behavior). Falls back to the
     * configured defaults if [playerClass] is null or unrecognised.
     */
    fun resolveClassScaling(playerClass: String?): ClassScaling {
        val def = playerClass?.let { classRegistry?.get(it) }
        return ClassScaling(
            hpRate = def?.hpScalingRate ?: config.rewards.hpScalingRate,
            manaRate = def?.manaScalingRate ?: config.rewards.manaScalingRate,
            // HP floors at 1 (a 0-HP pool would make applyLevelStats' coerceIn(1, max)
            // throw); mana may legitimately be 0, so it floors at 0.
            baseHp = scaledBase(config.rewards.baseHp, def?.baseHpMultiplier ?: 1.0, floor = 1),
            baseMana = scaledBase(config.rewards.baseMana, def?.baseManaMultiplier ?: 1.0, floor = 0),
        )
    }

    private fun scaledBase(
        base: Int,
        multiplier: Double,
        floor: Int,
    ): Int = if (multiplier == 1.0) base else Math.round(base * multiplier).toInt().coerceAtLeast(floor)

    fun maxHpForLevel(
        level: Int,
        statValue: Int = PlayerState.BASE_STAT,
        hpScalingRate: Double = config.rewards.hpScalingRate,
        baseHp: Int = config.rewards.baseHp,
    ): Int = maxResourceForLevel(level, statValue, hpScalingRate, baseHp, bindings.hpScalingDivisor)

    fun maxManaForLevel(
        level: Int,
        statValue: Int = PlayerState.BASE_STAT,
        manaScalingRate: Double = config.rewards.manaScalingRate,
        baseMana: Int = config.rewards.baseMana,
    ): Int = maxResourceForLevel(level, statValue, manaScalingRate, baseMana, bindings.manaScalingDivisor)

    /**
     * Applies the level-derived base HP/mana stats to [ps], clamping current
     * hp and mana to the new maximums. Callers are responsible for setting
     * [PlayerState.level] and [PlayerState.xpTotal] before or after this call.
     */
    fun applyLevelStats(
        ps: PlayerState,
        level: Int,
        equipStats: StatMap? = null,
    ) {
        val (classHpRate, classManaRate, classBaseHp, classBaseMana) = resolveClassScaling(ps.playerClass)
        val newMaxHp = maxHpForLevel(level, poolStat(ps, bindings.hpScalingStat, equipStats), classHpRate, classBaseHp)
        val newMaxMana = maxManaForLevel(level, poolStat(ps, bindings.manaScalingStat, equipStats), classManaRate, classBaseMana)
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
    fun recomputeVitalCaps(
        ps: PlayerState,
        equipStats: StatMap? = null,
    ) {
        val (classHpRate, classManaRate, classBaseHp, classBaseMana) = resolveClassScaling(ps.playerClass)
        val hpBonus = (ps.maxHp - ps.baseMaxHp).coerceAtLeast(0)
        val manaBonus = (ps.maxMana - ps.baseMana).coerceAtLeast(0)
        val newBaseMaxHp = maxHpForLevel(ps.level, poolStat(ps, bindings.hpScalingStat, equipStats), classHpRate, classBaseHp)
        val newBaseMana = maxManaForLevel(ps.level, poolStat(ps, bindings.manaScalingStat, equipStats), classManaRate, classBaseMana)
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
        if (bindings.multiplicativePools) {
            // D-20: pool x (1 + points x poolPercentPerPoint); the stat passed in already
            // includes equipment when poolsUseEquipment is on.
            val factor = (1.0 + (stat - PlayerState.BASE_STAT) * bindings.poolPercentPerPoint).coerceAtLeast(0.0)
            val scaledPool = floor(scaledBaseLong.toDouble() * factor)
            if (!scaledPool.isFinite() || scaledPool >= Int.MAX_VALUE.toDouble()) return Int.MAX_VALUE
            return scaledPool.toLong().coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
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
     * Engine-computed quest gold, the mirror of [computeQuestXp]: the same difficulty tiers applied to a
     * gold baseline. Returns 0 when the baseline is unset, so a quest then pays only what content authored.
     */
    fun computeQuestGold(
        difficulty: QuestDifficulty?,
        level: Int,
    ): Long {
        if (difficulty == null) return 0L
        val baseline = config.quests.baseline
        if (baseline.goldBase <= 0L && baseline.goldPerLevel <= 0L) return 0L
        val multiplier = config.quests.tiers[difficulty] ?: return 0L
        val steps = (level.coerceAtLeast(1) - 1).toLong()
        val scaled = (baseline.goldBase + baseline.goldPerLevel * steps).toDouble() * multiplier
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
     * Returns the XP multiplier for a kill against a mob ABOVE the player's
     * level — the reward for punching up. Grants
     * [UnderLevelXpBonusConfig.bonusPerLevel] per level the mob out-levels the
     * killer, capped at [UnderLevelXpBonusConfig.maxBonus]. Returns 1.0 when the
     * mob is at or below the player's level, or when the bonus is disabled.
     * Complementary to [diminishingKillXpMultiplier]: that one only ever reduces
     * XP for over-levelled kills, this one only ever increases it for
     * under-levelled kills, so the two never both move a single kill.
     */
    fun underLevelKillXpMultiplier(playerLevel: Int, mobLevel: Int): Double {
        val cfg = config.xp.underLevelBonus
        if (!cfg.enabled || cfg.bonusPerLevel <= 0.0) return 1.0
        val gap = mobLevel - playerLevel
        if (gap <= 0) return 1.0
        val bonus = (cfg.bonusPerLevel * gap).coerceAtMost(cfg.maxBonus)
        return 1.0 + bonus
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
        val rawBonus = statBonus * bindings.xpBonusPerPoint
        val bonus = if (bindings.xpBonusCap > 0.0) minOf(rawBonus, bindings.xpBonusCap) else rawBonus
        val multiplier = 1.0 + bonus
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
        val (classHpRate, classManaRate, classBaseHp, classBaseMana) = resolveClassScaling(playerClass)
        val newMaxHp = maxHpForLevel(result.newLevel, hpStatValue, classHpRate, classBaseHp)
        val oldMaxHp = maxHpForLevel(result.previousLevel, hpStatValue, classHpRate, classBaseHp)
        val hpGain = (newMaxHp - oldMaxHp).coerceAtLeast(0)
        val newMaxMana = maxManaForLevel(result.newLevel, manaStatValue, classManaRate, classBaseMana)
        val oldMaxMana = maxManaForLevel(result.previousLevel, manaStatValue, classManaRate, classBaseMana)
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
        equipStats: StatMap? = null,
    ): LevelUpResult {
        val hpStat = poolStat(player, bindings.hpScalingStat, equipStats)
        val manaStat = poolStat(player, bindings.manaScalingStat, equipStats)
        val (classHpRate, classManaRate, classBaseHp, classBaseMana) = resolveClassScaling(player.playerClass)
        val currentXpTotal = player.xpTotal.coerceAtLeast(0L)
        val currentLevel = computeLevel(currentXpTotal)
        val currentBaseMaxHp = maxHpForLevel(currentLevel, hpStat, classHpRate, classBaseHp)
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

        val previousBaseMaxHp = maxHpForLevel(previousLevel, hpStat, classHpRate, classBaseHp)
        val newBaseMaxHp = maxHpForLevel(newLevel, hpStat, classHpRate, classBaseHp)
        val nonProgressionBonus = (player.maxHp - previousBaseMaxHp).coerceAtLeast(0)
        val newEffectiveMaxHp = safeAddInt(newBaseMaxHp, nonProgressionBonus)
        player.baseMaxHp = newBaseMaxHp
        player.maxHp = newEffectiveMaxHp

        val newMaxMana = maxManaForLevel(newLevel, manaStat, classManaRate, classBaseMana)
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

/**
 * Per-class progression scaling: shared-or-class rates plus the level-1
 * pool bases after the class multipliers. Destructures as
 * `(hpRate, manaRate, baseHp, baseMana)`.
 */
data class ClassScaling(
    val hpRate: Double,
    val manaRate: Double,
    val baseHp: Int,
    val baseMana: Int,
)

/** The repeatable reward sources that can be paid as a fraction of the claimant's level (D-28). */
enum class RepeatableXpSource {
    DAILY,
    WEEKLY,
    AUTO_QUEST,
    GLOBAL_FIRST,
    GLOBAL_SECOND,
    GLOBAL_THIRD,
}
