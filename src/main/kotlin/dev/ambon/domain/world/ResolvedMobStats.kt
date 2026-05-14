package dev.ambon.domain.world

import dev.ambon.config.MobTierConfig
import dev.ambon.domain.DamageRange
import kotlin.math.roundToInt
import kotlin.math.roundToLong

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
 * Resolution order per stat:
 *   1. Start with tier × level baseline (e.g. `baseHp + steps * hpPerLevel`).
 *   2. Apply the relevant multiplier ([MobStatOverrides.hpMult], etc.) if set.
 *   3. Replace with the absolute override (e.g. [MobStatOverrides.hp]) if set.
 *
 * Multipliers express "noticeably tougher than baseline" without typing raw
 * numbers; absolute overrides are the escape hatch for bespoke mobs.
 */
fun resolveMobStats(
    tier: MobTierConfig,
    level: Int,
    overrides: MobStatOverrides = MobStatOverrides(),
): ResolvedMobStats {
    val normalized = level.coerceAtLeast(1)
    val steps = normalized - 1
    val hpMult = overrides.hpMult ?: 1.0
    val dmgMult = overrides.dmgMult ?: 1.0
    val xpMult = overrides.xpMult ?: 1.0
    val goldMult = overrides.goldMult ?: 1.0

    val hpRaw = (tier.baseHp + steps * tier.hpPerLevel) * hpMult
    val minRaw = (tier.baseMinDamage + steps * tier.damagePerLevel) * dmgMult
    val maxRaw = (tier.baseMaxDamage + steps * tier.damagePerLevel) * dmgMult
    val xpRaw = (tier.baseXpReward + steps.toLong() * tier.xpRewardPerLevel).toDouble() * xpMult
    val goldMinRaw = (tier.baseGoldMin + steps.toLong() * tier.goldPerLevel).toDouble() * goldMult
    val goldMaxRaw = (tier.baseGoldMax + steps.toLong() * tier.goldPerLevel).toDouble() * goldMult

    val baseHp = hpRaw.roundToInt().coerceAtLeast(1)
    val baseMin = minRaw.roundToInt().coerceAtLeast(1)
    val baseMax = maxRaw.roundToInt().coerceAtLeast(baseMin)
    val baseXp = xpRaw.roundToLong().coerceAtLeast(0L)
    val baseGoldMin = goldMinRaw.roundToLong().coerceAtLeast(0L)
    val baseGoldMax = goldMaxRaw.roundToLong().coerceAtLeast(baseGoldMin)

    val minDamage = overrides.minDamage ?: baseMin
    val maxDamage = overrides.maxDamage ?: baseMax
    return ResolvedMobStats(
        hp = overrides.hp ?: baseHp,
        damage = DamageRange(minDamage, maxDamage),
        armor = overrides.armor ?: tier.baseArmor,
        xpReward = overrides.xpReward ?: baseXp,
        goldMin = overrides.goldMin ?: baseGoldMin,
        goldMax = overrides.goldMax ?: baseGoldMax,
    )
}
