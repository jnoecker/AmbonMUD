package dev.ambon.engine

import dev.ambon.config.RaceEngineConfig
import dev.ambon.domain.RaceDef
import dev.ambon.domain.StatMap

object RaceRegistryLoader {
    fun load(
        config: RaceEngineConfig,
        registry: RaceRegistry,
    ) {
        for ((key, defConfig) in config.definitions) {
            registry.register(
                RaceDef(
                    id = key.uppercase(),
                    displayName = defConfig.displayName.ifEmpty { key },
                    description = defConfig.description,
                    backstory = defConfig.backstory,
                    traits = defConfig.traits,
                    abilities = defConfig.abilities,
                    image = defConfig.image,
                    statMods = StatMap(defConfig.statMods.mapKeys { (k, _) -> k.uppercase() }),
                ),
            )
        }
    }
}
