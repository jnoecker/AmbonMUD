package dev.ambon.domain.world

import dev.ambon.config.MobTierConfig
import dev.ambon.domain.DamageRange
import kotlin.math.floor
import kotlin.math.pow

/** Fully-resolved combat stats for a mob at a specific level. */
data class ResolvedMobStats(
    val hp: Int,
    val damage: DamageRange,
    val armor: Int,
    val xpReward: Long,
    val goldMin: Long,
    val goldMax: Long,
)

/**
 * Resolves a mob's combat stats from (tier, level, author overrides).
 * Mirrors the baking that `WorldLoader` does at load time so spawn-time
 * rescaling for [ZoneScaling] can replay the same math at a different
 * level without re-reading world YAML.
 *
 * Authored overrides always win; only tier-derived fields change with level.
 * Tier-derived fields scale multiplicatively: `floor(base × rate^(level - 1))`.
 */
fun resolveMobStats(
    tier: MobTierConfig,
    level: Int,
    overrides: MobStatOverrides = MobStatOverrides(),
): ResolvedMobStats {
    val normalized = level.coerceAtLeast(1)
    val steps = normalized - 1
    val minDamage = overrides.minDamage ?: scaleInt(tier.baseMinDamage, tier.damageScalingRate, steps)
    val maxDamage = overrides.maxDamage ?: scaleInt(tier.baseMaxDamage, tier.damageScalingRate, steps)
    return ResolvedMobStats(
        hp = overrides.hp ?: scaleInt(tier.baseHp, tier.hpScalingRate, steps),
        damage = DamageRange(minDamage, maxDamage),
        armor = overrides.armor ?: tier.baseArmor,
        xpReward = overrides.xpReward ?: scaleLong(tier.baseXpReward, tier.xpScalingRate, steps),
        goldMin = overrides.goldMin ?: scaleLong(tier.baseGoldMin, tier.goldScalingRate, steps),
        goldMax = overrides.goldMax ?: scaleLong(tier.baseGoldMax, tier.goldScalingRate, steps),
    )
}

private fun scaleInt(base: Int, rate: Double, steps: Int): Int {
    if (steps <= 0) return base
    val scaled = floor(base.toDouble() * rate.pow(steps))
    return when {
        !scaled.isFinite() -> Int.MAX_VALUE
        scaled >= Int.MAX_VALUE.toDouble() -> Int.MAX_VALUE
        scaled <= Int.MIN_VALUE.toDouble() -> Int.MIN_VALUE
        else -> scaled.toInt()
    }
}

private fun scaleLong(base: Long, rate: Double, steps: Int): Long {
    if (steps <= 0) return base
    val scaled = floor(base.toDouble() * rate.pow(steps))
    return when {
        !scaled.isFinite() -> Long.MAX_VALUE
        scaled >= Long.MAX_VALUE.toDouble() -> Long.MAX_VALUE
        scaled <= Long.MIN_VALUE.toDouble() -> Long.MIN_VALUE
        else -> scaled.toLong()
    }
}
