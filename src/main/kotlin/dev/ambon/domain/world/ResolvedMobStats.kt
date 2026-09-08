package dev.ambon.domain.world

import dev.ambon.config.MobTierAnchorConfig
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
    val anchors = tier.anchorTable()

    fun anchored(
        select: (MobTierAnchorConfig) -> Int,
        formula: () -> Int,
    ): Int = if (anchors.isEmpty()) formula() else interpolateAnchors(anchors, normalized, select)

    val minDamage =
        overrides.minDamage
            ?: anchored({ it.minDamage }) { scaleInt(tier.baseMinDamage, tier.damageScalingRate, steps) }
    val maxDamage =
        overrides.maxDamage
            ?: anchored({ it.maxDamage }) { scaleInt(tier.baseMaxDamage, tier.damageScalingRate, steps) }
    return ResolvedMobStats(
        hp = overrides.hp ?: anchored({ it.hp }) { scaleInt(tier.baseHp, tier.hpScalingRate, steps) },
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

/** Parsed, sorted `(level, anchor)` pairs; malformed keys are dropped (validation rejects them at load). */
private fun MobTierConfig.anchorTable(): List<Pair<Int, MobTierAnchorConfig>> =
    levelAnchors
        .mapNotNull { (key, anchor) -> key.trim().toIntOrNull()?.takeIf { it >= 1 }?.let { it to anchor } }
        .sortedBy { it.first }

/**
 * Piecewise value at [level]: exact on an anchor, geometric interpolation
 * between the neighbouring anchors, last-segment growth extended above the
 * highest anchor, and the first anchor's value below the lowest.
 */
private fun interpolateAnchors(
    anchors: List<Pair<Int, MobTierAnchorConfig>>,
    level: Int,
    select: (MobTierAnchorConfig) -> Int,
): Int {
    anchors.firstOrNull { it.first == level }?.let { return select(it.second).coerceAtLeast(1) }
    if (level < anchors.first().first || anchors.size == 1) return select(anchors.first().second).coerceAtLeast(1)
    val (lo, hi) =
        if (level > anchors.last().first) {
            anchors[anchors.size - 2] to anchors.last()
        } else {
            anchors.zipWithNext().first { (a, b) -> a.first < level && level < b.first }
        }
    val vLo = select(lo.second).coerceAtLeast(1).toDouble()
    val vHi = select(hi.second).coerceAtLeast(1).toDouble()
    val rate = (vHi / vLo).pow(1.0 / (hi.first - lo.first))
    val scaled = floor(vLo * rate.pow(level - lo.first))
    return when {
        !scaled.isFinite() -> Int.MAX_VALUE
        scaled >= Int.MAX_VALUE.toDouble() -> Int.MAX_VALUE
        else -> scaled.toInt().coerceAtLeast(1)
    }
}
