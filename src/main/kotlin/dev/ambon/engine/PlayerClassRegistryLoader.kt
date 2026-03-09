package dev.ambon.engine

import dev.ambon.config.ClassEngineConfig
import dev.ambon.domain.PlayerClassDef

object PlayerClassRegistryLoader {
    fun load(
        config: ClassEngineConfig,
        registry: PlayerClassRegistry,
    ) {
        for ((key, defConfig) in config.definitions) {
            registry.register(
                PlayerClassDef(
                    id = key.uppercase(),
                    displayName = defConfig.displayName.ifEmpty { key },
                    hpPerLevel = defConfig.hpPerLevel,
                    manaPerLevel = defConfig.manaPerLevel,
                    description = defConfig.description,
                    backstory = defConfig.backstory,
                    image = defConfig.image,
                    selectable = defConfig.selectable,
                    primaryStat = defConfig.primaryStat.ifBlank { null },
                    startRoom = defConfig.startRoom.ifBlank { null },
                    threatMultiplier = defConfig.threatMultiplier,
                ),
            )
        }
    }
}
