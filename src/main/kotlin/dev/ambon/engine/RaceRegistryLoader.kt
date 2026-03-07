package dev.ambon.engine

import dev.ambon.config.RaceEngineConfig
import dev.ambon.config.RaceStatModsConfig
import dev.ambon.domain.RaceDef
import dev.ambon.domain.StatBlock

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
                    statMods = defConfig.statMods.toStatBlock(),
                ),
            )
        }
    }

    private fun RaceStatModsConfig.toStatBlock(): StatBlock =
        StatBlock(str = str, dex = dex, con = con, int = int, wis = wis, cha = cha)
}
