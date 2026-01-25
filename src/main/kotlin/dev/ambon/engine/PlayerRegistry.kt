package dev.ambon.engine

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId

data class PlayerState(
    val sessionId: SessionId,
    val name: String,
    var roomId: RoomId,
)

class PlayerRegistry(
    private val startRoom: RoomId,
) {
    private val players = mutableMapOf<SessionId, PlayerState>()
    private val roomMembers = mutableMapOf<RoomId, MutableSet<SessionId>>()
    private var nextPlayerNum = 1

    fun connect(sessionId: SessionId) {
        val name = "Player${nextPlayerNum++}"
        val ps = PlayerState(sessionId, name, startRoom)
        players[sessionId] = ps
        roomMembers.getOrPut(startRoom) { mutableSetOf() }.add(sessionId)
    }

    fun disconnect(sessionId: SessionId) {
        val ps = players.remove(sessionId) ?: return
        roomMembers[ps.roomId]?.remove(sessionId)
        if (roomMembers[ps.roomId]?.isEmpty() == true) roomMembers.remove(ps.roomId)
    }

    fun get(sessionId: SessionId): PlayerState? = players[sessionId]

    fun allPlayers(): List<PlayerState> = players.values.toList()

    fun membersInRoom(roomId: RoomId): Set<SessionId> = roomMembers[roomId]?.toSet() ?: emptySet()

    fun moveTo(
        sessionId: SessionId,
        newRoom: RoomId,
    ) {
        val ps = players[sessionId] ?: return
        if (ps.roomId == newRoom) return

        roomMembers[ps.roomId]?.remove(sessionId)
        if (roomMembers[ps.roomId]?.isEmpty() == true) roomMembers.remove(ps.roomId)

        ps.roomId = newRoom
        roomMembers.getOrPut(newRoom) { mutableSetOf() }.add(sessionId)
    }
}
