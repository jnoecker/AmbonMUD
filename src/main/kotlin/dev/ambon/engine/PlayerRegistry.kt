package dev.ambon.engine

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId

data class PlayerState(
    val sessionId: SessionId,
    var name: String,
    var roomId: RoomId,
)

sealed interface RenameResult {
    data object Ok : RenameResult

    data object Invalid : RenameResult

    data object Taken : RenameResult
}

class PlayerRegistry(
    private val startRoom: RoomId,
) {
    private val players = mutableMapOf<SessionId, PlayerState>()
    private val roomMembers = mutableMapOf<RoomId, MutableSet<SessionId>>()

    // Case-insensitive name index
    private val sessionByLowerName = mutableMapOf<String, SessionId>()

    private var nextPlayerNum = 1

    fun connect(sessionId: SessionId) {
        val defaultName = "Player${nextPlayerNum++}"
        val ps = PlayerState(sessionId, defaultName, startRoom)
        players[sessionId] = ps
        roomMembers.getOrPut(startRoom) { mutableSetOf() }.add(sessionId)
        sessionByLowerName[defaultName.lowercase()] = sessionId
    }

    fun disconnect(sessionId: SessionId) {
        val ps = players.remove(sessionId) ?: return
        roomMembers[ps.roomId]?.remove(sessionId)
        if (roomMembers[ps.roomId]?.isEmpty() == true) roomMembers.remove(ps.roomId)
        sessionByLowerName.remove(ps.name.lowercase())
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

    fun findSessionByName(name: String): SessionId? = sessionByLowerName[name.trim().lowercase()]

    fun rename(
        sessionId: SessionId,
        newNameRaw: String,
    ): RenameResult {
        val ps = players[sessionId] ?: return RenameResult.Invalid

        val newName = newNameRaw.trim()

        // Simple rules for MVP:
        //  - 2..16 chars
        //  - letters/digits/_ only
        //  - cannot start with digit
        if (!isValidName(newName)) return RenameResult.Invalid

        val key = newName.lowercase()
        val existing = sessionByLowerName[key]
        if (existing != null && existing != sessionId) return RenameResult.Taken

        // Update index
        sessionByLowerName.remove(ps.name.lowercase())
        ps.name = newName
        sessionByLowerName[key] = sessionId

        return RenameResult.Ok
    }

    private fun isValidName(name: String): Boolean {
        if (name.length !in 2..16) return false
        if (name[0].isDigit()) return false
        for (c in name) {
            val ok = c.isLetterOrDigit() || c == '_'
            if (!ok) return false
        }
        return true
    }
}
