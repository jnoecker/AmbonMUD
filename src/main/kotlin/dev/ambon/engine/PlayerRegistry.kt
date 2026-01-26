package dev.ambon.engine

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.persistence.PlayerRecord
import dev.ambon.persistence.PlayerRepository
import java.time.Clock

sealed interface RenameResult {
    data object Ok : RenameResult

    data object Invalid : RenameResult

    data object Taken : RenameResult
}

class PlayerRegistry(
    private val startRoom: RoomId,
    private val repo: PlayerRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val players = mutableMapOf<SessionId, PlayerState>()
    private val roomMembers = mutableMapOf<RoomId, MutableSet<SessionId>>()

    // Case-insensitive name index (online only)
    private val sessionByLowerName = mutableMapOf<String, SessionId>()

    private var nextPlayerNum = 1

    fun connect(sessionId: SessionId): PlayerState {
        val defaultName = "Player${nextPlayerNum++}"
        val ps = PlayerState(sessionId, defaultName, startRoom, playerId = null)
        players[sessionId] = ps
        roomMembers.getOrPut(startRoom) { mutableSetOf() }.add(sessionId)
        sessionByLowerName[defaultName.lowercase()] = sessionId
        return ps
    }

    suspend fun disconnect(sessionId: SessionId) {
        val ps = players.remove(sessionId) ?: return

        // Persist last seen + room for claimed players
        persistIfClaimed(ps)

        roomMembers[ps.roomId]?.remove(sessionId)
        if (roomMembers[ps.roomId]?.isEmpty() == true) roomMembers.remove(ps.roomId)
        sessionByLowerName.remove(ps.name.lowercase())
    }

    fun get(sessionId: SessionId): PlayerState? = players[sessionId]

    fun allPlayers(): List<PlayerState> = players.values.toList()

    fun playersInRoom(roomId: RoomId): Set<PlayerState> =
        roomMembers[roomId]
            ?.mapNotNull { sessionId -> players[sessionId] }
            ?.toSet()
            ?: emptySet()

    suspend fun moveTo(
        sessionId: SessionId,
        newRoom: RoomId,
    ) {
        val ps = players[sessionId] ?: return
        if (ps.roomId == newRoom) return

        roomMembers[ps.roomId]?.remove(sessionId)
        if (roomMembers[ps.roomId]?.isEmpty() == true) roomMembers.remove(ps.roomId)

        ps.roomId = newRoom
        roomMembers.getOrPut(newRoom) { mutableSetOf() }.add(sessionId)

        // Save room change if claimed
        persistIfClaimed(ps)
    }

    fun findSessionByName(name: String): SessionId? = sessionByLowerName[name.trim().lowercase()]

    /**
     * name acts as "claim name" and also "login by name" for now:
     * - If name exists on disk AND not online -> bind this session to that record and teleport to saved room.
     * - Else create new record.
     */
    suspend fun rename(
        sessionId: SessionId,
        newNameRaw: String,
    ): RenameResult {
        val ps = players[sessionId] ?: return RenameResult.Invalid
        val newName = newNameRaw.trim()
        if (!isValidName(newName)) return RenameResult.Invalid

        val key = newName.lowercase()

        // Online collision?
        val existingOnline = sessionByLowerName[key]
        if (existingOnline != null && existingOnline != sessionId) return RenameResult.Taken

        val now = clock.millis()

        // If a persisted player with this name exists, treat as login (unless it belongs to someone online)
        val existingRecord: PlayerRecord? = repo.findByName(newName)

        val boundRecord =
            if (existingRecord != null) {
                // If that record is already "online" under the same name, we'd have returned Taken above.
                existingRecord.copy(lastSeenEpochMs = now)
            } else {
                repo.create(newName, ps.roomId, now)
            }

        // Update room membership if login changed room
        if (ps.roomId != boundRecord.roomId) {
            roomMembers[ps.roomId]?.remove(sessionId)
            if (roomMembers[ps.roomId]?.isEmpty() == true) roomMembers.remove(ps.roomId)

            ps.roomId = boundRecord.roomId
            roomMembers.getOrPut(ps.roomId) { mutableSetOf() }.add(sessionId)
        }

        // Update in-memory identity + name index
        sessionByLowerName.remove(ps.name.lowercase())
        ps.name = boundRecord.name
        ps.playerId = boundRecord.id
        sessionByLowerName[key] = sessionId

        // Save (covers the “login” case, updates lastSeen)
        repo.save(boundRecord.copy(roomId = ps.roomId, lastSeenEpochMs = now))

        return RenameResult.Ok
    }

    private suspend fun persistIfClaimed(ps: PlayerState) {
        val pid = ps.playerId ?: return
        val now = clock.millis()

        val existing = repo.findById(pid) ?: return
        repo.save(existing.copy(roomId = ps.roomId, lastSeenEpochMs = now, name = ps.name))
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
