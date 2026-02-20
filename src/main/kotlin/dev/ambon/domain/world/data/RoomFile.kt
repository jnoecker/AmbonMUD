package dev.ambon.domain.world.data

data class RoomFile(
    val title: String,
    val description: String,
    // "north" -> "street"
    val exits: Map<String, String> = emptyMap(),
)
