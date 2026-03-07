package dev.ambon.engine.abilities

import dev.ambon.config.AbilityEngineConfig
import dev.ambon.domain.DamageRange
import dev.ambon.engine.status.StatusEffectId

object AbilityRegistryLoader {
    fun load(
        config: AbilityEngineConfig,
        registry: AbilityRegistry,
        imagesBaseUrl: String = "/images/",
    ) {
        val imagesBase = if (imagesBaseUrl.endsWith("/")) imagesBaseUrl else "$imagesBaseUrl/"
        for ((key, defConfig) in config.definitions) {
            val targetType =
                when (defConfig.targetType.uppercase()) {
                    "ENEMY" -> TargetType.ENEMY
                    "SELF" -> TargetType.SELF
                    "ALLY" -> TargetType.ALLY
                    else -> continue
                }
            val effect =
                when (defConfig.effect.type.uppercase()) {
                    "DIRECT_DAMAGE" ->
                        AbilityEffect.DirectDamage(
                            damage = DamageRange(defConfig.effect.minDamage, defConfig.effect.maxDamage),
                        )
                    "DIRECT_HEAL" ->
                        AbilityEffect.DirectHeal(
                            minHeal = defConfig.effect.minHeal,
                            maxHeal = defConfig.effect.maxHeal,
                        )
                    "APPLY_STATUS" ->
                        AbilityEffect.ApplyStatus(
                            statusEffectId = StatusEffectId(defConfig.effect.statusEffectId),
                        )
                    "AREA_DAMAGE" ->
                        AbilityEffect.AreaDamage(
                            damage = DamageRange(defConfig.effect.minDamage, defConfig.effect.maxDamage),
                        )
                    "TAUNT" ->
                        AbilityEffect.Taunt(
                            flatThreat = defConfig.effect.flatThreat,
                            margin = defConfig.effect.margin,
                        )
                    else -> continue
                }
            val requiredClass = defConfig.requiredClass.ifBlank { null }
            registry.register(
                AbilityDefinition(
                    id = AbilityId(key),
                    displayName = defConfig.displayName.ifEmpty { key },
                    description = defConfig.description,
                    manaCost = defConfig.manaCost,
                    cooldownMs = defConfig.cooldownMs,
                    levelRequired = defConfig.levelRequired,
                    targetType = targetType,
                    effect = effect,
                    requiredClass = requiredClass,
                    image = defConfig.image.ifBlank { null }?.let { "$imagesBase$it" },
                ),
            )
        }
    }
}
