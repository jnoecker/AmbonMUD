package dev.ambon.domain.world

import dev.ambon.domain.ids.RoomId

data class TrainerDefinition(
    val id: String,
    val name: String,
    val className: String,
    val roomId: RoomId,
    val image: String? = null,
)
