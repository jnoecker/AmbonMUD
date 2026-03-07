package dev.ambon.domain.items

import dev.ambon.domain.StatMap

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
    val stats: StatMap = StatMap.EMPTY,
    val consumable: Boolean = false,
    val charges: Int? = null,
    val onUse: ItemUseEffect? = null,
    val matchByKey: Boolean = false,
    val basePrice: Int = 0,
    val image: String? = null,
    val video: String? = null,
)
