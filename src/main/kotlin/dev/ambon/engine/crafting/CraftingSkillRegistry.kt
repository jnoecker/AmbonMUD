package dev.ambon.engine.crafting

import dev.ambon.config.CraftingSkillsConfig

data class CraftingSkillDefinition(
    val id: String,
    val displayName: String,
    val isGathering: Boolean,
    val isCrafting: Boolean,
)

class CraftingSkillRegistry(
    config: CraftingSkillsConfig,
) {
    private val skills: Map<String, CraftingSkillDefinition> =
        config.skills.map { (key, cfg) ->
            val id = key.lowercase()
            val type = cfg.type.lowercase()
            id to CraftingSkillDefinition(
                id = id,
                displayName = cfg.displayName.ifBlank { id.replaceFirstChar { it.uppercase() } },
                isGathering = type == "gathering",
                isCrafting = type == "crafting",
            )
        }.toMap()

    fun get(id: String): CraftingSkillDefinition? = skills[id.lowercase()]

    fun isValid(id: String): Boolean = id.lowercase() in skills

    fun allIds(): List<String> = skills.keys.toList()

    fun allDefinitions(): Collection<CraftingSkillDefinition> = skills.values

    fun gatheringSkills(): List<CraftingSkillDefinition> = skills.values.filter { it.isGathering }

    fun craftingSkills(): List<CraftingSkillDefinition> = skills.values.filter { it.isCrafting }
}
