package dev.ambon.domain.world

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.items.Item

data class Room(
    val id: RoomId,
    val title: String,
    val description: String,
    val exits: Map<Direction, RoomId>,
    val items: MutableList<Item> = mutableListOf(),
)
