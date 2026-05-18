package dev.ambon.engine

import dev.ambon.config.ClassEngineConfig
import dev.ambon.domain.PlayerClassDef
import dev.ambon.domain.StarterEquipmentEntry

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
                    hpScalingRate = defConfig.hpScalingRate,
                    manaScalingRate = defConfig.manaScalingRate,
                    description = defConfig.description,
                    backstory = defConfig.backstory,
                    image = defConfig.image,
                    selectable = defConfig.selectable,
                    primaryStat = defConfig.primaryStat.ifBlank { null },
                    startRoom = defConfig.startRoom.ifBlank { null },
                    threatMultiplier = defConfig.threatMultiplier,
                    starterEquipment = defConfig.starterEquipment
                        .filter { it.itemId.isNotBlank() }
                        .map { StarterEquipmentEntry(itemId = it.itemId.trim(), equip = it.equip) },
                ),
            )
        }
    }
}
