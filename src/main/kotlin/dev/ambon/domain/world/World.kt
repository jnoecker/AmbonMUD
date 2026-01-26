package dev.ambon.domain.world

import dev.ambon.domain.ids.RoomId

class World(
    val rooms: Map<RoomId, Room>,
    val startRoom: RoomId,
    val mobSpawns: List<MobSpawn> = emptyList(),
)
