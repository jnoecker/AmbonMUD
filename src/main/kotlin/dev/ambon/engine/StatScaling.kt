package dev.ambon.engine

import dev.ambon.config.StatBindingsConfig
import dev.ambon.domain.StatMap
import java.util.Random
import kotlin.math.roundToInt

/** True when `stats.bindings.statScalingMode` is "multiplicative" (D-20): stats multiply an anchor instead of adding to it. */
val StatBindingsConfig.multiplicativeStats: Boolean
    get() = statScalingMode.trim().equals("multiplicative", ignoreCase = true)

/** True when `stats.bindings.poolStatMode` is "multiplicative": max HP/mana scale by (1 + points x poolPercentPerPoint). */
val StatBindingsConfig.multiplicativePools: Boolean
    get() = poolStatMode.trim().equals("multiplicative", ignoreCase = true)

/**
 * Stat-adjusted core of an output before level scaling and variance.
 *
 * Additive mode (the engine default): `anchor + points x additiveMultiplier`.
 * Multiplicative mode: `anchor x max(1 + points x percentPerPoint, 0)`, so one point is
 * worth the same fraction of output at every level and gear tier.
 */
fun StatBindingsConfig.statAdjustedCore(
    anchor: Double,
    statTotal: Int,
    additiveMultiplier: Double,
    percentPerPoint: Double,
): Double {
    val points = statTotal - PlayerState.BASE_STAT
    return if (multiplicativeStats) {
        anchor * (1.0 + points * percentPerPoint).coerceAtLeast(0.0)
    } else {
        anchor + points * additiveMultiplier
    }
}

/** Dodge chance in percent for resolved [stats]: (dodgeStat - base) x dodgePerPoint, capped at maxDodgePercent. */
fun StatBindingsConfig.dodgePercent(stats: StatMap): Double =
    ((stats[dodgeStat] - PlayerState.BASE_STAT) * dodgePerPoint).coerceIn(0.0, maxDodgePercent.toDouble())

/** Rolls a dodge at tenth-of-a-percent resolution; consumes no randomness at 0%. */
fun rollDodge(
    dodgePct: Double,
    rng: Random,
): Boolean = dodgePct > 0.0 && rng.nextInt(1000) < (dodgePct * 10.0).roundToInt()
