package dev.ambon.domain.world.data

data class WorldFile(
    val zone: String,
    val startRoom: String,
    val rooms: Map<String, RoomFile>,
    val mobs: Map<String, MobFile> = emptyMap(),
    val items: Map<String, ItemFile> = emptyMap(),
)
