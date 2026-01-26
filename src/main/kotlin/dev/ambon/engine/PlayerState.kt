package dev.ambon.engine

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.persistence.PlayerId

data class PlayerState(
    val sessionId: SessionId,
    var name: String,
    var roomId: RoomId,
    var playerId: PlayerId? = null,
    val inventory: MutableList<Item> = mutableListOf(),
)
