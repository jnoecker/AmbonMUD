package dev.ambon.domain.items

data class Item(
    val keyword: String,
    val displayName: String,
    val description: String = "",
    val slot: ItemSlot? = null,
    val damage: Int = 0,
    val armor: Int = 0,
    val constitution: Int = 0,
)
