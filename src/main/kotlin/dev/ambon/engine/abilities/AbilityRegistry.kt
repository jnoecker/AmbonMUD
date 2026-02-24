package dev.ambon.engine.abilities

import dev.ambon.domain.PlayerClass

class AbilityRegistry {
    private val abilities = mutableMapOf<AbilityId, AbilityDefinition>()

    fun register(ability: AbilityDefinition) {
        abilities[ability.id] = ability
    }

    fun get(id: AbilityId): AbilityDefinition? = abilities[id]

    fun findByKeyword(keyword: String): AbilityDefinition? {
        val lower = keyword.lowercase()
        // Exact id match first
        abilities[AbilityId(lower)]?.let { return it }
        // Exact displayName match (case-insensitive)
        abilities.values.firstOrNull { it.displayName.equals(keyword, ignoreCase = true) }?.let { return it }
        // Prefix match on id
        abilities.values.firstOrNull { it.id.value.startsWith(lower) }?.let { return it }
        // Substring match on displayName (case-insensitive, min 3 chars)
        if (lower.length >= 3) {
            abilities.values.firstOrNull { it.displayName.lowercase().contains(lower) }?.let { return it }
        }
        return null
    }

    fun abilitiesForLevel(level: Int): List<AbilityDefinition> =
        abilities.values
            .filter { it.levelRequired <= level }
            .sortedBy { it.levelRequired }

    fun abilitiesForLevelAndClass(
        level: Int,
        playerClass: PlayerClass?,
    ): List<AbilityDefinition> =
        abilities.values
            .filter { it.levelRequired <= level && (it.requiredClass == null || it.requiredClass == playerClass) }
            .sortedBy { it.levelRequired }

    fun all(): Collection<AbilityDefinition> = abilities.values
}
