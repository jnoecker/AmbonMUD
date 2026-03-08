package dev.ambon.engine

import dev.ambon.config.GendersConfig

data class GenderDefinition(
    val id: String,
    val displayName: String,
    val spriteCode: String,
)

class GenderRegistry(
    config: GendersConfig,
) {
    private val genders: Map<String, GenderDefinition> =
        config.genders.map { (key, cfg) ->
            val id = key.lowercase()
            id to GenderDefinition(
                id = id,
                displayName = cfg.displayName.ifBlank { id.replaceFirstChar { it.uppercase() } },
                spriteCode = cfg.spriteCode.ifBlank { id },
            )
        }.toMap()

    fun get(id: String): GenderDefinition? = genders[id.lowercase()]

    fun isValid(id: String): Boolean = id.lowercase() in genders

    fun allIds(): List<String> = genders.keys.toList()

    fun allDefinitions(): Collection<GenderDefinition> = genders.values

    fun defaultSpriteCode(): String = genders.values.lastOrNull()?.spriteCode ?: "enby"
}
