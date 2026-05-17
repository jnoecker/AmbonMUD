package dev.ambon.engine.abilities

import dev.ambon.domain.DamageRange
import dev.ambon.engine.status.StatusEffectId

@JvmInline
value class AbilityId(
    val value: String,
)

sealed interface AbilityEffect {
    /**
     * Authored damage anchor for a spell. The min/max range is averaged to
     * produce the formula's anchor; multiplicative variance from
     * [dev.ambon.config.StatBindingsConfig.spellVarianceMin]/Max provides the
     * roll-to-roll spread. Per-ability level scaling is no longer authored —
     * `spellLevelScalingRate` drives the curve globally.
     */
    data class DirectDamage(
        val damage: DamageRange,
    ) : AbilityEffect

    /**
     * Authored heal anchor. min/max averaged; variance and level scaling are
     * driven by `healVarianceMin/Max` and `healLevelScalingRate` bindings.
     */
    data class DirectHeal(
        val minHeal: Int,
        val maxHeal: Int,
    ) : AbilityEffect

    data class ApplyStatus(
        val statusEffectId: StatusEffectId,
    ) : AbilityEffect

    data class AreaDamage(
        val damage: DamageRange,
    ) : AbilityEffect

    data class Taunt(
        val flatThreat: Double,
        val margin: Double,
    ) : AbilityEffect

    data class SummonPet(
        val petTemplateKey: String,
        val durationMs: Long = 0L,
    ) : AbilityEffect
}

fun AbilityEffect.toEffectType(): String = when (this) {
    is AbilityEffect.DirectDamage -> "DIRECT_DAMAGE"
    is AbilityEffect.DirectHeal -> "DIRECT_HEAL"
    is AbilityEffect.ApplyStatus -> "APPLY_STATUS"
    is AbilityEffect.AreaDamage -> "AREA_DAMAGE"
    is AbilityEffect.Taunt -> "TAUNT"
    is AbilityEffect.SummonPet -> "SUMMON_PET"
}

/**
 * High-level visual archetype that tells the web client how to render the
 * cast moment in the combat scene. Authors may set this explicitly in the
 * ability YAML (`visual.archetype`); otherwise it is auto-derived from the
 * effect type, target type, and class.
 */
enum class AbilityVisualArchetype {
    RANGED_PROJECTILE,
    MELEE_STRIKE,
    HEAL_AURA,
    BUFF_AURA,
    DEBUFF_AURA,
    AREA_BURST,
    SUMMON_POOF,
}

/**
 * Per-ability visual customization sent to the web client via Char.Skills GMCP.
 * `projectileImage` overrides the default (which is the ability `image`).
 * Colors are hex strings ("#c8a8f0") consumed verbatim by Pixi tints.
 */
data class AbilityVisual(
    val archetype: AbilityVisualArchetype,
    val projectileImage: String? = null,
    val color: String? = null,
    val accentColor: String? = null,
)

data class AbilityDefinition(
    val id: AbilityId,
    val displayName: String,
    val description: String,
    /**
     * Mana cost expressed as a percentage of the player's level/class base mana
     * pool (computed with default stat — i.e. INT-independent). The absolute
     * cost is resolved per-cast by [dev.ambon.engine.abilities.AbilitySystem.computeManaCost]
     * so the effective spend scales with leveling alongside spell damage, while
     * stat investment in the casting stat increases the player's total casts
     * rather than discounting individual spells.
     */
    val manaCostPct: Double,
    val cooldownMs: Long,
    val levelRequired: Int,
    val targetType: String,
    val effect: AbilityEffect,
    val requiredClass: String? = null,
    val image: String? = null,
    val prerequisites: Set<AbilityId> = emptySet(),
    val tree: String = "",
    val tier: Int = 0,
    val skillPointCost: Int = 1,
    val visual: AbilityVisual = deriveDefaultVisual(effect, targetType, requiredClass),
) {
    init {
        require(skillPointCost >= 0) { "skillPointCost must be >= 0, got $skillPointCost" }
    }
}

/**
 * Auto-derive a sensible archetype when authors do not specify `visual` in YAML.
 * - DIRECT_DAMAGE: WARRIOR/ROGUE get MELEE_STRIKE; others (or unspecified) RANGED_PROJECTILE.
 * - DIRECT_HEAL: HEAL_AURA.
 * - APPLY_STATUS: ENEMY → DEBUFF_AURA; SELF/ALLY/GROUP → BUFF_AURA.
 * - AREA_DAMAGE: AREA_BURST.
 * - TAUNT: BUFF_AURA on the caster.
 * - SUMMON_PET: SUMMON_POOF.
 */
fun deriveDefaultVisual(
    effect: AbilityEffect,
    targetType: String,
    requiredClass: String?,
): AbilityVisual {
    val tt = targetType.uppercase()
    val cls = requiredClass?.uppercase()
    val archetype = when (effect) {
        is AbilityEffect.DirectDamage -> {
            if (cls == "WARRIOR" || cls == "ROGUE") {
                AbilityVisualArchetype.MELEE_STRIKE
            } else {
                AbilityVisualArchetype.RANGED_PROJECTILE
            }
        }
        is AbilityEffect.DirectHeal -> AbilityVisualArchetype.HEAL_AURA
        is AbilityEffect.ApplyStatus -> {
            if (tt == "ENEMY" || tt == "ALL_ENEMIES") {
                AbilityVisualArchetype.DEBUFF_AURA
            } else {
                AbilityVisualArchetype.BUFF_AURA
            }
        }
        is AbilityEffect.AreaDamage -> AbilityVisualArchetype.AREA_BURST
        is AbilityEffect.Taunt -> AbilityVisualArchetype.BUFF_AURA
        is AbilityEffect.SummonPet -> AbilityVisualArchetype.SUMMON_POOF
    }
    return AbilityVisual(archetype = archetype)
}
