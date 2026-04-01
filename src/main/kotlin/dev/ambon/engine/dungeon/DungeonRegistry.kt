package dev.ambon.engine.dungeon

import dev.ambon.domain.dungeon.DungeonTemplateDef

/** Holds all loaded dungeon templates, indexed by ID. */
class DungeonRegistry {
    private val templates = mutableMapOf<String, DungeonTemplateDef>()

    fun register(template: DungeonTemplateDef) {
        templates[template.id] = template
    }

    fun get(id: String): DungeonTemplateDef? = templates[id]

    fun findByKeyword(keyword: String): DungeonTemplateDef? {
        val lower = keyword.lowercase()
        return templates.values.firstOrNull { it.id.substringAfter(':').equals(lower, ignoreCase = true) }
            ?: templates.values.firstOrNull { it.name.equals(lower, ignoreCase = true) }
            ?: templates.values.firstOrNull { it.name.lowercase().contains(lower) }
    }

    fun all(): Collection<DungeonTemplateDef> = templates.values

    fun clear() {
        templates.clear()
    }
}
