package dev.ambon.domain.world.data

import dev.ambon.domain.items.ItemUseEffect

data class ItemFile(
    val displayName: String,
    val description: String = "",
    val keyword: String? = null,
    val slot: String? = null,
    val damage: Int = 0,
    val armor: Int = 0,
    val stats: Map<String, Int> = emptyMap(),
    val consumable: Boolean = false,
    val charges: Int? = null,
    val onUse: ItemUseEffect? = null,
    val room: String? = null,
    val mob: String? = null,
    val matchByKey: Boolean = false,
    val basePrice: Int = 0,
    val image: String? = null,
    val video: String? = null,
)
