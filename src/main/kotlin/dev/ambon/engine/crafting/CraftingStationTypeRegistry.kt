package dev.ambon.engine.crafting

import dev.ambon.config.CraftingStationTypesConfig

class CraftingStationTypeRegistry(
    config: CraftingStationTypesConfig,
) {
    private val types: Map<String, String> =
        config.stationTypes.mapValues { (key, cfg) ->
            cfg.displayName.ifBlank { key.replaceFirstChar { it.uppercase() } }
        }

    fun displayName(typeId: String): String =
        types[typeId] ?: typeId.replaceFirstChar { it.uppercase() }

    fun isValid(typeId: String): Boolean = typeId in types

    fun allTypeIds(): List<String> = types.keys.toList()
}
