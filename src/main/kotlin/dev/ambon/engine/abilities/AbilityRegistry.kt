package dev.ambon.engine.abilities

import dev.ambon.engine.DefinitionRegistry

class AbilityRegistry : DefinitionRegistry<AbilityId, AbilityDefinition>({ it.id }) {
    fun findByKeyword(keyword: String): AbilityDefinition? {
        val lower = keyword.lowercase()
        val values = all()
        // Exact id match first
        get(AbilityId(lower))?.let { return it }
        // Exact displayName match (case-insensitive)
        values.firstOrNull { it.displayName.equals(keyword, ignoreCase = true) }?.let { return it }
        // Prefix match on id
        values.firstOrNull { it.id.value.startsWith(lower) }?.let { return it }
        // Substring match on displayName (case-insensitive, min 3 chars)
        if (lower.length >= 3) {
            values.firstOrNull { it.displayName.lowercase().contains(lower) }?.let { return it }
        }
        return null
    }

    fun abilitiesForLevel(level: Int): List<AbilityDefinition> =
        all()
            .filter { it.levelRequired <= level }
            .sortedBy { it.levelRequired }

    fun abilitiesForLevelAndClass(
        level: Int,
        playerClass: String?,
    ): List<AbilityDefinition> =
        all()
            .filter {
                it.levelRequired <= level &&
                    (it.requiredClass == null || it.requiredClass.equals(playerClass, ignoreCase = true))
            }
            .sortedBy { it.levelRequired }

    /** Returns all abilities belonging to [className]'s trainer, sorted by level required. */
    fun abilitiesForClass(className: String): List<AbilityDefinition> =
        all()
            .filter { it.requiredClass.equals(className, ignoreCase = true) }
            .sortedBy { it.levelRequired }
}
