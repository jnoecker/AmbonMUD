package dev.ambon.engine.status

import dev.ambon.config.StatusEffectEngineConfig

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
                        StatModifiers(
                            str = defConfig.strMod,
                            dex = defConfig.dexMod,
                            con = defConfig.conMod,
                            int = defConfig.intMod,
                            wis = defConfig.wisMod,
                            cha = defConfig.chaMod,
                        ),
                    stackBehavior = stackBehavior,
                    maxStacks = defConfig.maxStacks.coerceAtLeast(1),
                ),
            )
        }
    }
}
