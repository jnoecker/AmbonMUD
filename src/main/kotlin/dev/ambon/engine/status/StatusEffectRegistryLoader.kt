package dev.ambon.engine.status

import dev.ambon.config.StatusEffectEngineConfig
import dev.ambon.domain.StatMap

object StatusEffectRegistryLoader {
    fun load(
        config: StatusEffectEngineConfig,
        registry: StatusEffectRegistry,
    ) {
        for ((key, defConfig) in config.definitions) {
            val effectType =
                when (defConfig.effectType.uppercase()) {
                    "DOT" -> EffectType.DOT
                    "HOT" -> EffectType.HOT
                    "STAT_BUFF" -> EffectType.STAT_BUFF
                    "STAT_DEBUFF" -> EffectType.STAT_DEBUFF
                    "STUN" -> EffectType.STUN
                    "ROOT" -> EffectType.ROOT
                    "SHIELD" -> EffectType.SHIELD
                    else -> continue
                }
            val stackBehavior =
                when (defConfig.stackBehavior.uppercase()) {
                    "REFRESH" -> StackBehavior.REFRESH
                    "STACK" -> StackBehavior.STACK
                    "NONE" -> StackBehavior.NONE
                    else -> StackBehavior.REFRESH
                }
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
