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
 * Authored overrides always win; only tier-derived fields change with level.
 */
fun resolveMobStats(
    tier: MobTierConfig,
    level: Int,
    overrides: MobStatOverrides = MobStatOverrides(),
): ResolvedMobStats {
    val normalized = level.coerceAtLeast(1)
    val steps = normalized - 1
    val minDamage = overrides.minDamage ?: (tier.baseMinDamage + steps * tier.damagePerLevel)
    val maxDamage = overrides.maxDamage ?: (tier.baseMaxDamage + steps * tier.damagePerLevel)
    return ResolvedMobStats(
        hp = overrides.hp ?: (tier.baseHp + steps * tier.hpPerLevel),
        damage = DamageRange(minDamage, maxDamage),
        armor = overrides.armor ?: tier.baseArmor,
        xpReward = overrides.xpReward ?: (tier.baseXpReward + steps.toLong() * tier.xpRewardPerLevel),
        goldMin = overrides.goldMin ?: (tier.baseGoldMin + steps.toLong() * tier.goldPerLevel),
        goldMax = overrides.goldMax ?: (tier.baseGoldMax + steps.toLong() * tier.goldPerLevel),
    )
}
