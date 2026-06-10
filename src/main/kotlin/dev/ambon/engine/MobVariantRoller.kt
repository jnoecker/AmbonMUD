package dev.ambon.engine

import dev.ambon.config.MobVariantDefinition
import dev.ambon.config.MobVariantsConfig
import dev.ambon.domain.mob.MobRole
import dev.ambon.domain.world.MobTemplateDef
import java.util.Random

/** A variant archetype selected for a specific spawn, paired with its id. */
data class RolledMobVariant(
    val id: String,
    val def: MobVariantDefinition,
)

/**
 * Decides whether a freshly-spawned mob should become a server-generated rare
 * cosmetic variant, and which archetype. Used at every spawn site — cold start,
 * zone reset, post-death respawn, and conditional spawn — so explorers keep
 * finding rich variations even when content authors don't hand-author rare mobs,
 * and a freshly-booted world already holds a few sightings to discover.
 */
class MobVariantRoller(
    private val config: MobVariantsConfig,
    private val rng: Random = Random(),
) {
    /**
     * Rolls a variant for [template], or returns null (the common case) when the
     * feature is disabled, the mob is ineligible, or the rarity roll misses.
     */
    fun roll(template: MobTemplateDef): RolledMobVariant? {
        if (!config.enabled) return null
        if (template.role != MobRole.COMBAT) return null
        if (!template.variantEligible) return null
        if (config.variants.isEmpty()) return null
        if (rng.nextDouble() >= config.chance) return null
        return weightedPick()
    }

    private fun weightedPick(): RolledMobVariant? {
        val entries = config.variants.entries.filter { it.value.weight > 0.0 }
        if (entries.isEmpty()) return null
        val total = entries.sumOf { it.value.weight }
        var roll = rng.nextDouble() * total
        for (entry in entries) {
            roll -= entry.value.weight
            if (roll <= 0.0) return RolledMobVariant(entry.key, entry.value)
        }
        return RolledMobVariant(entries.last().key, entries.last().value)
    }
}
