package dev.ambon.domain.items

/**
 * Broad categorization used by the client to group inventory items and by
 * the server to gate certain actions (quest items cannot be dropped, sold,
 * given, or stored in containers).
 *
 * When an item does not declare [Item.itemType] explicitly, a value is
 * inferred via [Item.resolvedType] from the item's other properties.
 */
enum class ItemType {
    EQUIPMENT,
    CONSUMABLE,
    QUEST,
    TREASURE,
    MISC,
    ;

    fun label(): String = name.lowercase()

    companion object {
        fun parse(raw: String): ItemType? {
            val value = raw.trim().uppercase().ifEmpty { return null }
            return entries.firstOrNull { it.name == value }
        }
    }
}
