package dev.ambon.domain.items

import dev.ambon.domain.StatMap

data class ItemUseEffect(
    val healHp: Int = 0,
    val healMana: Int = 0,
    val grantXp: Long = 0L,
) {
    fun hasEffect(): Boolean = healHp > 0 || healMana > 0 || grantXp > 0L

    /**
     * Short human-readable summary of what using the item does, suitable for
     * surfacing in the web client inventory tooltip. Returns null when the
     * effect is a no-op.
     */
    fun describe(): String? {
        val parts = mutableListOf<String>()
        if (healHp > 0) parts.add("Restores $healHp HP")
        if (healMana > 0) parts.add("Restores $healMana mana")
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
    val takeable: Boolean = true,
    /**
     * True for items conjured from an Akathavae's Arcanum wardrobe. Conjured
     * items exist only while equipped — on unequip (or swap) they dissolve back
     * into the journal instead of entering the inventory, so they can never
     * reach trades, shops, banks, or the floor.
     */
    val conjured: Boolean = false,
    /**
     * For [ItemType.MOUNT] items: the mount id this purchase unlocks. Matches
     * the id used by mount-requirement sprite definitions in sprites.yaml and
     * stored in the player's owned-mounts set.
     */
    val mountId: String? = null,
    /**
     * For [ItemType.MOUNT] items: ride-speed multiplier applied to the base
     * travel pace (1.0 = base, 2.0 = twice as fast). Meaningless otherwise.
     */
    val mountSpeed: Double = 1.0,
    /**
     * For [ItemType.MOUNT] items: true when the mount can fly, unlocking
     * cross-zone travel to any explored room. Meaningless otherwise.
     */
    val flying: Boolean = false,
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

    /**
     * True when the item is bound to its owner — it cannot be dropped, sold,
     * given, traded, banked, or mailed. Quest items and keepsakes both bind.
     */
    fun isBound(): Boolean = questItem || itemType == ItemType.KEEPSAKE

    /** Human-readable noun for why a [isBound] item is stuck ("quest item" / "keepsake"). */
    fun boundKind(): String = if (questItem) "quest item" else "keepsake"
}
