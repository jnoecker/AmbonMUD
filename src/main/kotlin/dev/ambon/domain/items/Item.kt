package dev.ambon.domain.items

import dev.ambon.domain.StatMap

data class ItemUseEffect(
    val healHp: Int = 0,
    val grantXp: Long = 0L,
) {
    fun hasEffect(): Boolean = healHp > 0 || grantXp > 0L

    /**
     * Short human-readable summary of what using the item does, suitable for
     * surfacing in the web client inventory tooltip. Returns null when the
     * effect is a no-op.
     */
    fun describe(): String? {
        val parts = mutableListOf<String>()
        if (healHp > 0) parts.add("Restores $healHp HP")
        if (grantXp > 0L) parts.add("Grants $grantXp XP")
        return if (parts.isEmpty()) null else parts.joinToString(", ")
    }
}

data class Item(
    val keyword: String,
    val displayName: String,
    val description: String = "",
    val slot: ItemSlot? = null,
    val damage: Int = 0,
    val armor: Int = 0,
    val stats: StatMap = StatMap.EMPTY,
    val consumable: Boolean = false,
    val charges: Int? = null,
    val onUse: ItemUseEffect? = null,
    val matchByKey: Boolean = false,
    val basePrice: Int = 0,
    val image: String? = null,
    val video: String? = null,
    val itemType: ItemType? = null,
    val questItem: Boolean = false,
) {
    /**
     * Effective item type for categorization. Uses [itemType] when set,
     * otherwise infers from slot/consumable/basePrice. Quest items always
     * resolve to [ItemType.QUEST] regardless of declared type.
     */
    fun resolvedType(): ItemType {
        if (questItem) return ItemType.QUEST
        itemType?.let { return it }
        return when {
            slot != null -> ItemType.EQUIPMENT
            consumable -> ItemType.CONSUMABLE
            basePrice > 0 -> ItemType.TREASURE
            else -> ItemType.MISC
        }
    }
}
