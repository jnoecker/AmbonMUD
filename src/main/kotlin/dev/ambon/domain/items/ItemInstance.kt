package dev.ambon.domain.items

import dev.ambon.domain.ids.ItemId

data class ItemInstance(
    val id: ItemId,
    val item: Item,
)
