package dev.ambon.engine

import dev.ambon.config.RaceEngineConfig
import dev.ambon.config.RaceStatModsConfig
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
                    statMods = defConfig.statMods.toStatMap(),
                ),
            )
        }
    }

    private fun RaceStatModsConfig.toStatMap(): StatMap =
        StatMap(
            buildMap {
                if (str != 0) put("STR", str)
                if (dex != 0) put("DEX", dex)
                if (con != 0) put("CON", con)
                if (int != 0) put("INT", int)
                if (wis != 0) put("WIS", wis)
                if (cha != 0) put("CHA", cha)
            },
        )
}
