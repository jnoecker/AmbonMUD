package dev.ambon.engine

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.persistence.PlayerId

data class PlayerState(
    val sessionId: SessionId,
    var name: String,
    var roomId: RoomId,
    var playerId: PlayerId? = null,
    var hp: Int = 10,
    var maxHp: Int = 10,
    var constitution: Int = 0,
)
