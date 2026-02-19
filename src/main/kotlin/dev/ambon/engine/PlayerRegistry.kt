package dev.ambon.engine

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.PlayerRecord
import dev.ambon.persistence.PlayerRepository
import org.mindrot.jbcrypt.BCrypt
import java.time.Clock

sealed interface LoginResult {
    data object Ok : LoginResult

    data object InvalidName : LoginResult

    data object InvalidPassword : LoginResult

    data object Taken : LoginResult

    data object WrongPassword : LoginResult
}

class PlayerRegistry(
    private val startRoom: RoomId,
    private val repo: PlayerRepository,
    private val items: ItemRegistry,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val players = mutableMapOf<SessionId, PlayerState>()
    private val roomMembers = mutableMapOf<RoomId, MutableSet<SessionId>>()

    // Case-insensitive name index (online only)
    private val sessionByLowerName = mutableMapOf<String, SessionId>()

    suspend fun login(
        sessionId: SessionId,
        nameRaw: String,
        passwordRaw: String,
    ): LoginResult {
        if (players.containsKey(sessionId)) return LoginResult.InvalidName

        val name = nameRaw.trim()
        if (!isValidName(name)) return LoginResult.InvalidName

        if (isNameOnline(name, sessionId)) return LoginResult.Taken

        val password = passwordRaw.trim()
        if (!isValidPassword(password)) return LoginResult.InvalidPassword

        val now = clock.millis()

        val existingRecord: PlayerRecord? = repo.findByName(name)

        val boundRecord =
            if (existingRecord != null) {
                if (existingRecord.passwordHash.isNotBlank()) {
                    val ok =
                        runCatching { BCrypt.checkpw(password, existingRecord.passwordHash) }
                            .getOrDefault(false)
                    if (!ok) return LoginResult.WrongPassword
                    existingRecord.copy(lastSeenEpochMs = now)
                } else {
                    existingRecord.copy(lastSeenEpochMs = now, passwordHash = BCrypt.hashpw(password, BCrypt.gensalt()))
                }
            } else {
                repo.create(name, startRoom, now, BCrypt.hashpw(password, BCrypt.gensalt()))
            }

        val ps =
            PlayerState(
                sessionId = sessionId,
                name = boundRecord.name,
                roomId = boundRecord.roomId,
                playerId = boundRecord.id,
                constitution = boundRecord.constitution,
            )
        players[sessionId] = ps
        roomMembers.getOrPut(ps.roomId) { mutableSetOf() }.add(sessionId)
        sessionByLowerName[ps.name.lowercase()] = sessionId
        items.ensurePlayer(sessionId)

        repo.save(boundRecord.copy(roomId = ps.roomId, lastSeenEpochMs = now))

        return LoginResult.Ok
    }

    suspend fun disconnect(sessionId: SessionId) {
        val ps = players.remove(sessionId) ?: return

        // Persist last seen + room for claimed players
        persistIfClaimed(ps)

        roomMembers[ps.roomId]?.remove(sessionId)
        if (roomMembers[ps.roomId]?.isEmpty() == true) roomMembers.remove(ps.roomId)
        sessionByLowerName.remove(ps.name.lowercase())
        items.removePlayer(sessionId)
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

    fun isNameOnline(
        name: String,
        exclude: SessionId? = null,
    ): Boolean {
        val key = name.trim().lowercase()
        val existing = sessionByLowerName[key]
        return existing != null && existing != exclude
    }

    fun isValidName(name: String): Boolean {
        if (name.length !in 2..16) return false
        if (name[0].isDigit()) return false
        for (c in name) {
            val ok = c.isLetterOrDigit() || c == '_'
            if (!ok) return false
        }
        return true
    }

    private suspend fun persistIfClaimed(ps: PlayerState) {
        val pid = ps.playerId ?: return
        val now = clock.millis()

        val existing = repo.findById(pid) ?: return
        repo.save(
            existing.copy(
                roomId = ps.roomId,
                lastSeenEpochMs = now,
                name = ps.name,
                constitution = ps.constitution,
            ),
        )
    }

    private fun isValidPassword(password: String): Boolean {
        if (password.isBlank()) return false
        if (password.length > 72) return false
        return true
    }
}
