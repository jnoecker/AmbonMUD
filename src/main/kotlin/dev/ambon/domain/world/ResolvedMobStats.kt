package dev.ambon.domain.world

import dev.ambon.config.MobTierConfig
import dev.ambon.domain.DamageRange

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

    val baseHp = ((tier.baseHp + steps * tier.hpPerLevel) * hpMult).toInt().coerceAtLeast(1)
    val baseMin = ((tier.baseMinDamage + steps * tier.damagePerLevel) * dmgMult).toInt().coerceAtLeast(1)
    val baseMax = ((tier.baseMaxDamage + steps * tier.damagePerLevel) * dmgMult).toInt().coerceAtLeast(baseMin)
    val baseXp = ((tier.baseXpReward + steps.toLong() * tier.xpRewardPerLevel).toDouble() * xpMult).toLong().coerceAtLeast(0L)
    val baseGoldMin = ((tier.baseGoldMin + steps.toLong() * tier.goldPerLevel).toDouble() * goldMult).toLong().coerceAtLeast(0L)
    val baseGoldMax = ((tier.baseGoldMax + steps.toLong() * tier.goldPerLevel).toDouble() * goldMult).toLong().coerceAtLeast(baseGoldMin)

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
