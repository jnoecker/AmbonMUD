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
    // Only the anchored path clamps: independently extrapolated bounds can cross
    // above the top anchor. Authored overrides and the formula stay untouched so
    // WorldLoader still rejects an authored maxDamage below minDamage.
    val maxDamage =
        overrides.maxDamage
            ?: if (anchors.isEmpty()) {
                scaleInt(tier.baseMaxDamage, tier.damageScalingRate, steps)
            } else {
                interpolateAnchors(anchors, normalized) { it.maxDamage }.coerceAtLeast(minDamage)
            }
    // XP is anchored only when every anchor declares it (validation enforces all-or-none); otherwise the
    // tier's geometric formula stands. A pacing curve that saturates cannot be expressed as a rate.
    val xpAnchored = anchors.isNotEmpty() && anchors.all { it.second.xpReward > 0L }
    return ResolvedMobStats(
        hp = overrides.hp ?: anchored({ it.hp }) { scaleInt(tier.baseHp, tier.hpScalingRate, steps) },
        damage = DamageRange(minDamage, maxDamage),
        armor = overrides.armor ?: tier.baseArmor,
        xpReward =
            overrides.xpReward
                ?: if (xpAnchored) {
                    interpolateAnchorsLong(anchors, normalized) { it.xpReward }
                } else {
                    scaleLong(tier.baseXpReward, tier.xpScalingRate, steps)
                },
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
        .distinctBy { it.first }
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
    // Single pow keeps integer multiples of a representable ratio exact
    // (20 * 2^(8/4) == 80.0); the epsilon covers products that still land a
    // few ulps below a whole number.
    val t = (level - lo.first).toDouble() / (hi.first - lo.first)
    val scaled = floor(vLo * (vHi / vLo).pow(t) + INTERPOLATION_EPSILON)
    return when {
        !scaled.isFinite() -> Int.MAX_VALUE
        scaled >= Int.MAX_VALUE.toDouble() -> Int.MAX_VALUE
        else -> scaled.toInt().coerceAtLeast(1)
    }
}

/**
 * [interpolateAnchors] for a Long field. Same piecewise shape; floors at 0 rather than 1, because an
 * anchored XP curve may legitimately pay nothing (a trivial tier below its first anchor).
 */
private fun interpolateAnchorsLong(
    anchors: List<Pair<Int, MobTierAnchorConfig>>,
    level: Int,
    select: (MobTierAnchorConfig) -> Long,
): Long {
    anchors.firstOrNull { it.first == level }?.let { return select(it.second).coerceAtLeast(0L) }
    if (level < anchors.first().first || anchors.size == 1) return select(anchors.first().second).coerceAtLeast(0L)
    val (lo, hi) =
        if (level > anchors.last().first) {
            anchors[anchors.size - 2] to anchors.last()
        } else {
            anchors.zipWithNext().first { (a, b) -> a.first < level && level < b.first }
        }
    val vLo = select(lo.second).coerceAtLeast(1L).toDouble()
    val vHi = select(hi.second).coerceAtLeast(1L).toDouble()
    val t = (level - lo.first).toDouble() / (hi.first - lo.first)
    val scaled = floor(vLo * (vHi / vLo).pow(t) + INTERPOLATION_EPSILON)
    return when {
        !scaled.isFinite() -> Long.MAX_VALUE
        scaled >= Long.MAX_VALUE.toDouble() -> Long.MAX_VALUE
        else -> scaled.toLong().coerceAtLeast(0L)
    }
}

private const val INTERPOLATION_EPSILON = 1e-9
