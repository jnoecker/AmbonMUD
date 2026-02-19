package dev.ambon.persistence

import dev.ambon.domain.ids.RoomId

@JvmInline
value class PlayerId(
    val value: Long,
)

data class PlayerRecord(
    val id: PlayerId,
    val name: String,
    val roomId: RoomId,
    val createdAtEpochMs: Long,
    val lastSeenEpochMs: Long,
    val passwordHash: String = "",
    val ansiEnabled: Boolean = true,
)
