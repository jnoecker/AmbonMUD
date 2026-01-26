package dev.ambon.domain.world

import dev.ambon.domain.ids.RoomId

data class Room(
    val id: RoomId,
    val title: String,
    val description: String,
    val exits: Map<Direction, RoomId>,
)
