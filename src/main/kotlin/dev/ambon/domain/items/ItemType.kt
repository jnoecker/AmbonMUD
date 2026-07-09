package dev.ambon.domain.items

/**
 * Broad categorization used by the client to group inventory items and by
 * the server to gate certain actions. Both [QUEST] and [KEEPSAKE] items are
 * "bound" — they cannot be dropped, sold, given, traded, banked, or mailed
 * (see [Item.isBound]). Keepsakes are personal souvenirs (e.g. a music-box
 * lyric sheet); they bind the same way but read as keepsakes, not quest items.
 *
 * When an item does not declare [Item.itemType] explicitly, a value is
 * inferred via [Item.resolvedType] from the item's other properties.
 */
enum class ItemType {
    EQUIPMENT,
    CONSUMABLE,
    QUEST,
    TREASURE,
    KEEPSAKE,

    /**
     * A rideable mount sold in shops. Buying one never enters the inventory:
     * it permanently unlocks the mount (sprite + map fast travel) via
     * [Item.mountId].
     */
    MOUNT,
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
