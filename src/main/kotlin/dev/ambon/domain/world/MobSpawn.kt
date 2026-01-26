package dev.ambon.domain.world

import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId

data class MobSpawn(
    val id: MobId,
    val name: String,
    val roomId: RoomId,
)
