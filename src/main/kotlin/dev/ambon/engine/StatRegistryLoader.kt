package dev.ambon.engine

import dev.ambon.config.StatsEngineConfig
import dev.ambon.domain.StatDefinition

object StatRegistryLoader {
    fun load(
        config: StatsEngineConfig,
        registry: StatRegistry,
    ) {
        for ((key, defConfig) in config.definitions) {
            registry.register(
                StatDefinition(
                    id = key.uppercase(),
                    displayName = defConfig.displayName.ifEmpty { key },
                    abbreviation = defConfig.abbreviation.ifEmpty { key.uppercase() },
                    description = defConfig.description,
                    baseStat = defConfig.baseStat,
                ),
            )
        }
    }
}
