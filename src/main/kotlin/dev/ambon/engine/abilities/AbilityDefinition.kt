package dev.ambon.engine.abilities

import dev.ambon.domain.DamageRange
import dev.ambon.engine.status.StatusEffectId

@JvmInline
value class AbilityId(
    val value: String,
)

enum class TargetType {
    ENEMY,
    SELF,
    ALLY,
}

sealed interface AbilityEffect {
    data class DirectDamage(
        val damage: DamageRange,
    ) : AbilityEffect

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
}

data class AbilityDefinition(
    val id: AbilityId,
    val displayName: String,
    val description: String,
    val manaCost: Int,
    val cooldownMs: Long,
    val levelRequired: Int,
    val targetType: TargetType,
    val effect: AbilityEffect,
    val requiredClass: String? = null,
    val image: String? = null,
)
