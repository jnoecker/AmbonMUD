package dev.ambon.engine.status

import dev.ambon.config.StatusEffectEngineConfig
import dev.ambon.domain.StatMap

object StatusEffectRegistryLoader {
    fun load(
        config: StatusEffectEngineConfig,
        registry: StatusEffectRegistry,
    ) {
        for ((key, defConfig) in config.definitions) {
            val effectType = defConfig.effectType.trim().lowercase()
            val stackBehavior = defConfig.stackBehavior.trim().lowercase().ifEmpty { "refresh" }
            registry.register(
                StatusEffectDefinition(
                    id = StatusEffectId(key),
                    displayName = defConfig.displayName.ifEmpty { key },
                    effectType = effectType,
                    durationMs = defConfig.durationMs,
                    tickIntervalMs = defConfig.tickIntervalMs,
                    tickMinValue = defConfig.tickMinValue,
                    tickMaxValue = defConfig.tickMaxValue,
                    shieldAmount = defConfig.shieldAmount,
                    statMods =
                        StatMap(
                            buildMap {
                                if (defConfig.strMod != 0) put("STR", defConfig.strMod)
                                if (defConfig.dexMod != 0) put("DEX", defConfig.dexMod)
                                if (defConfig.conMod != 0) put("CON", defConfig.conMod)
                                if (defConfig.intMod != 0) put("INT", defConfig.intMod)
                                if (defConfig.wisMod != 0) put("WIS", defConfig.wisMod)
                                if (defConfig.chaMod != 0) put("CHA", defConfig.chaMod)
                            },
                        ),
                    stackBehavior = stackBehavior,
                    maxStacks = defConfig.maxStacks.coerceAtLeast(1),
                ),
            )
        }
    }
}
