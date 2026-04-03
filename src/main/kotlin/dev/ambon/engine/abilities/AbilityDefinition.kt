package dev.ambon.engine.abilities

import dev.ambon.domain.DamageRange
import dev.ambon.engine.status.StatusEffectId

@JvmInline
value class AbilityId(
    val value: String,
)

sealed interface AbilityEffect {
    data class DirectDamage(
        val damage: DamageRange,
        val damagePerLevel: Double = 0.0,
    ) : AbilityEffect

    data class DirectHeal(
        val minHeal: Int,
        val maxHeal: Int,
        val healPerLevel: Double = 0.0,
    ) : AbilityEffect

    data class ApplyStatus(
        val statusEffectId: StatusEffectId,
    ) : AbilityEffect

    data class AreaDamage(
        val damage: DamageRange,
        val damagePerLevel: Double = 0.0,
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

data class AbilityDefinition(
    val id: AbilityId,
    val displayName: String,
    val description: String,
    val manaCost: Int,
    val cooldownMs: Long,
    val levelRequired: Int,
    val targetType: String,
    val effect: AbilityEffect,
    val requiredClass: String? = null,
    val image: String? = null,
    val prerequisites: Set<AbilityId> = emptySet(),
    val tree: String = "",
    val tier: Int = 0,
)
