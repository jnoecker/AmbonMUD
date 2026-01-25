package dev.ambon.domain.world.data

data class WorldFile(
    val startRoom: String,
    val rooms: Map<String, RoomFile>,
)
