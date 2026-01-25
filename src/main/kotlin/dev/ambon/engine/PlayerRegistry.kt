package dev.ambon.engine

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId

class PlayerRegistry(
    private val startRoom: RoomId,
) {
    private val roomBySession = mutableMapOf<SessionId, RoomId>()

    fun connect(sessionId: SessionId) {
        roomBySession[sessionId] = startRoom
    }

    fun disconnect(sessionId: SessionId) {
        roomBySession.remove(sessionId)
    }

    fun getRoom(sessionId: SessionId): RoomId? = roomBySession[sessionId]

    fun setRoom(
        sessionId: SessionId,
        roomId: RoomId,
    ) {
        roomBySession[sessionId] = roomId
    }
}
