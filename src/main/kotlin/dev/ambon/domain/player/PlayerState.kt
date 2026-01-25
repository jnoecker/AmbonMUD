package dev.ambon.domain.player

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId

data class PlayerState(
    val sessionId: SessionId,
    var roomId: RoomId,
)
