package dev.ambon.domain.world

import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.RoomId

data class ShopDefinition(
    val id: String,
    val name: String,
    val roomId: RoomId,
    val itemIds: List<ItemId>,
)
