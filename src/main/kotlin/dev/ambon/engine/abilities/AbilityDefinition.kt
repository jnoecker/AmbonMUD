package dev.ambon.engine.abilities

@JvmInline
value class AbilityId(val value: String)

enum class TargetType {
    ENEMY,
    SELF,
}

sealed interface AbilityEffect {
    data class DirectDamage(
        val minDamage: Int,
        val maxDamage: Int,
    ) : AbilityEffect

    data class DirectHeal(
        val minHeal: Int,
        val maxHeal: Int,
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
)
