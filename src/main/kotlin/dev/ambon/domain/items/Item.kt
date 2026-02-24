package dev.ambon.domain.items

data class ItemUseEffect(
    val healHp: Int = 0,
    val grantXp: Long = 0L,
) {
    fun hasEffect(): Boolean = healHp > 0 || grantXp > 0L
}

data class Item(
    val keyword: String,
    val displayName: String,
    val description: String = "",
    val slot: ItemSlot? = null,
    val damage: Int = 0,
    val armor: Int = 0,
    val strength: Int = 0,
    val dexterity: Int = 0,
    val constitution: Int = 0,
    val intelligence: Int = 0,
    val wisdom: Int = 0,
    val charisma: Int = 0,
    val consumable: Boolean = false,
    val charges: Int? = null,
    val onUse: ItemUseEffect? = null,
    val matchByKey: Boolean = false,
    val basePrice: Int = 0,
)
