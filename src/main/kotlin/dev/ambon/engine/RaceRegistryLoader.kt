package dev.ambon.engine

import dev.ambon.config.RaceEngineConfig
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
                    statMods = StatBlock(
                        str = defConfig.statMods.str,
                        dex = defConfig.statMods.dex,
                        con = defConfig.statMods.con,
                        int = defConfig.statMods.int,
                        wis = defConfig.statMods.wis,
                        cha = defConfig.statMods.cha,
                    ),
                ),
            )
        }
    }
}
