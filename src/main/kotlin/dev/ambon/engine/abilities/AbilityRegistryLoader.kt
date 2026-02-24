package dev.ambon.engine.abilities

import dev.ambon.config.AbilityEngineConfig
import dev.ambon.domain.PlayerClass

object AbilityRegistryLoader {
    fun load(
        config: AbilityEngineConfig,
        registry: AbilityRegistry,
    ) {
        for ((key, defConfig) in config.definitions) {
            val targetType =
                when (defConfig.targetType.uppercase()) {
                    "ENEMY" -> TargetType.ENEMY
                    "SELF" -> TargetType.SELF
                    else -> continue
                }
            val effect =
                when (defConfig.effect.type.uppercase()) {
                    "DIRECT_DAMAGE" ->
                        AbilityEffect.DirectDamage(
                            minDamage = defConfig.effect.minDamage,
                            maxDamage = defConfig.effect.maxDamage,
                        )
                    "DIRECT_HEAL" ->
                        AbilityEffect.DirectHeal(
                            minHeal = defConfig.effect.minHeal,
                            maxHeal = defConfig.effect.maxHeal,
                        )
                    else -> continue
                }
            val requiredClass =
                if (defConfig.requiredClass.isNotBlank()) {
                    PlayerClass.fromString(defConfig.requiredClass)
                } else {
                    null
                }
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
                ),
            )
        }
    }
}
