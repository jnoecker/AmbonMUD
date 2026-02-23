package dev.ambon.engine.abilities

class AbilityRegistry {
    private val byId = mutableMapOf<AbilityId, AbilityDefinition>()

    fun register(ability: AbilityDefinition) {
        byId[ability.id] = ability
    }

    fun get(id: AbilityId): AbilityDefinition? = byId[id]

    fun findByKeyword(keyword: String): AbilityDefinition? {
        val lower = keyword.lowercase()
        // Exact id match first
        byId[AbilityId(lower)]?.let { return it }
        // Display name exact match (case-insensitive)
        byId.values.firstOrNull { it.displayName.equals(keyword, ignoreCase = true) }?.let { return it }
        // Display name contains
        byId.values.firstOrNull { it.displayName.lowercase().contains(lower) }?.let { return it }
        // Id contains
        byId.values.firstOrNull { it.id.value.contains(lower) }?.let { return it }
        return null
    }

    fun abilitiesForLevel(level: Int): List<AbilityDefinition> =
        byId.values
            .filter { it.levelRequired <= level }
            .sortedBy { it.levelRequired }

    fun all(): List<AbilityDefinition> = byId.values.sortedBy { it.levelRequired }
}
