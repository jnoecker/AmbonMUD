package dev.ambon.engine

import dev.ambon.domain.StatMap
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.ItemSlot
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.PersistenceException
import dev.ambon.persistence.PlayerCreationRequest
import dev.ambon.persistence.PlayerId
import dev.ambon.persistence.PlayerRecord
import dev.ambon.persistence.PlayerRepository
import kotlinx.coroutines.withContext
import java.time.Clock
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

sealed interface LoginResult {
    data object Ok : LoginResult

    data object InvalidName : LoginResult

    data object InvalidPassword : LoginResult

    data object Taken : LoginResult

    data object WrongPassword : LoginResult

    data class Takeover(
        val oldSessionId: SessionId,
    ) : LoginResult
}

sealed interface CreateResult {
    data object Ok : CreateResult

    data object InvalidName : CreateResult

    data object InvalidPassword : CreateResult

    data object Taken : CreateResult
}

/**
 * Result of the async credential-resolution phase of login.
 * No in-memory engine state is modified during this phase.
 */
internal sealed interface LoginCredentialPrep {
    /** Password verified; record ready to be bound to the session. */
    data class Verified(
        val record: PlayerRecord,
    ) : LoginCredentialPrep

    /** Password did not match the stored hash. */
    data object WrongPassword : LoginCredentialPrep

    /** No player record found for the requested name. */
    data object NotFound : LoginCredentialPrep

    /** Name or password failed basic validation. */
    data object InvalidInput : LoginCredentialPrep
}

/**
 * Result of the async account-creation preparation phase.
 * No in-memory engine state is modified during this phase.
 */
internal sealed interface CreateAccountPrep {
    /** Account record created in the repository; ready to be bound to the session. */
    data class Ready(
        val record: PlayerRecord,
    ) : CreateAccountPrep

    /** Name or password failed basic validation. */
    data object InvalidInput : CreateAccountPrep

    /** A player with this name already exists. */
    data object Taken : CreateAccountPrep
}

class PlayerRegistry(
    private val startRoom: RoomId,
    private val classStartRooms: Map<String, RoomId> = emptyMap(),
    private val repo: PlayerRepository,
    private val items: ItemRegistry,
    private val clock: Clock = Clock.systemUTC(),
    private val progression: PlayerProgression = PlayerProgression(),
    // Context for BCrypt operations (CPU-intensive blocking).
    // Production callers should pass Dispatchers.IO to avoid blocking the engine thread.
    // Defaults to EmptyCoroutineContext so tests run synchronously on the test scheduler.
    private val hashingContext: CoroutineContext = EmptyCoroutineContext,
    private val passwordHasher: PasswordHasher = BCryptPasswordHasher,
    private val classRegistry: PlayerClassRegistry? = null,
    private val raceRegistry: RaceRegistry? = null,
) {
    val maxLevel: Int get() = progression.maxLevel

    private val players = mutableMapOf<SessionId, PlayerState>()
    private val roomMembers = mutableMapOf<RoomId, MutableSet<SessionId>>()

    // Case-insensitive name index (online only)
    private val sessionByLowerName = mutableMapOf<String, SessionId>()

    /**
     * Async phase 1 of login: look up the player record and verify credentials.
     *
     * Does **not** mutate any in-memory state — safe to call from a background coroutine
     * concurrently with the engine tick loop.
     */
    internal suspend fun prepareLoginCredentials(nameRaw: String, passwordRaw: String): LoginCredentialPrep {
        val name = normalizeName(nameRaw)
        val password = normalizePassword(passwordRaw)
        if (!isValidName(name)) return LoginCredentialPrep.InvalidInput
        if (!isValidPassword(password)) return LoginCredentialPrep.InvalidInput

        val record = repo.findByName(name) ?: return LoginCredentialPrep.NotFound
        val now = clock.millis()

        return if (record.passwordHash.isNotBlank()) {
            val ok =
                withContext(hashingContext) {
                    passwordHasher.verify(password, record.passwordHash)
                }
            if (ok) LoginCredentialPrep.Verified(record.copy(lastSeenEpochMs = now)) else LoginCredentialPrep.WrongPassword
        } else {
            val hash = withContext(hashingContext) { passwordHasher.hash(password) }
            LoginCredentialPrep.Verified(record.copy(lastSeenEpochMs = now, passwordHash = hash))
        }
    }

    /**
     * Sync phase 2 of login: bind a verified record to the session.
     *
     * Mutates in-memory state — must be called on the engine thread.
     */
    internal suspend fun applyLoginCredentials(
        sessionId: SessionId,
        record: PlayerRecord,
        defaultAnsiEnabled: Boolean,
    ): LoginResult {
        if (players.containsKey(sessionId)) return LoginResult.InvalidName
        val now = clock.millis()
        return if (isNameOnline(record.name, sessionId)) {
            val oldSid = findSessionByName(record.name)!!
            takeoverSession(oldSid, sessionId, record, now)
            LoginResult.Takeover(oldSid)
        } else {
            bindSession(sessionId, record, now)
            LoginResult.Ok
        }
    }

    /**
     * Async phase 1 of account creation: hash the password and persist the new record.
     *
     * Does **not** mutate any in-memory state — safe to call from a background coroutine
     * concurrently with the engine tick loop.
     */
    internal suspend fun prepareCreateAccount(
        nameRaw: String,
        passwordRaw: String,
        defaultAnsiEnabled: Boolean,
        raceId: String,
        classId: String,
    ): CreateAccountPrep {
        val name = normalizeName(nameRaw)
        val password = normalizePassword(passwordRaw)
        if (!isValidName(name)) return CreateAccountPrep.InvalidInput
        if (!isValidPassword(password)) return CreateAccountPrep.InvalidInput
        if (repo.findByName(name) != null) return CreateAccountPrep.Taken

        val now = clock.millis()
        val hash = withContext(hashingContext) { passwordHasher.hash(password) }
        val baseStat = PlayerState.BASE_STAT
        val raceMods = raceRegistry?.get(raceId)?.statMods ?: StatMap.EMPTY
        val classStartRoom = classStartRooms[classId.uppercase()]
            ?: classRegistry?.get(classId)?.startRoom?.let { RoomId(it) }
        val record =
            try {
                repo.create(
                    PlayerCreationRequest(
                        name = name,
                        startRoomId = classStartRoom ?: startRoom,
                        nowEpochMs = now,
                        passwordHash = hash,
                        ansiEnabled = defaultAnsiEnabled,
                        race = raceId,
                        playerClass = classId,
                        strength = baseStat + raceMods["STR"],
                        dexterity = baseStat + raceMods["DEX"],
                        constitution = baseStat + raceMods["CON"],
                        intelligence = baseStat + raceMods["INT"],
                        wisdom = baseStat + raceMods["WIS"],
                        charisma = baseStat + raceMods["CHA"],
                    ),
                )
            } catch (_: PersistenceException) {
                return CreateAccountPrep.Taken
            }
        return CreateAccountPrep.Ready(record)
    }

    /**
     * Sync phase 2 of account creation: bind the prepared record to the session.
     *
     * Mutates in-memory state — must be called on the engine thread.
     */
    internal suspend fun applyCreateAccount(sessionId: SessionId, record: PlayerRecord): CreateResult {
        if (players.containsKey(sessionId)) return CreateResult.InvalidName
        if (isNameOnline(record.name, sessionId)) return CreateResult.Taken
        val now = clock.millis()
        bindSession(sessionId, record, now)
        return CreateResult.Ok
    }

    suspend fun login(
        sessionId: SessionId,
        nameRaw: String,
        passwordRaw: String,
        defaultAnsiEnabled: Boolean = false,
    ): LoginResult {
        if (players.containsKey(sessionId)) return LoginResult.InvalidName

        val name = normalizeName(nameRaw)
        if (!isValidName(name)) return LoginResult.InvalidName

        val password = normalizePassword(passwordRaw)
        if (!isValidPassword(password)) return LoginResult.InvalidPassword

        return when (val prep = prepareLoginCredentials(nameRaw, passwordRaw)) {
            is LoginCredentialPrep.Verified -> applyLoginCredentials(sessionId, prep.record, defaultAnsiEnabled)
            LoginCredentialPrep.WrongPassword -> LoginResult.WrongPassword
            LoginCredentialPrep.NotFound -> {
                when (create(sessionId, nameRaw, passwordRaw, defaultAnsiEnabled)) {
                    CreateResult.Ok -> LoginResult.Ok
                    CreateResult.InvalidName -> LoginResult.InvalidName
                    CreateResult.InvalidPassword -> LoginResult.InvalidPassword
                    CreateResult.Taken -> LoginResult.Taken
                }
            }
            LoginCredentialPrep.InvalidInput -> LoginResult.InvalidName
        }
    }

    suspend fun create(
        sessionId: SessionId,
        nameRaw: String,
        passwordRaw: String,
        defaultAnsiEnabled: Boolean = false,
        race: String = "HUMAN",
        playerClass: String = "WARRIOR",
    ): CreateResult {
        val name = normalizeName(nameRaw)
        if (!isValidName(name)) return CreateResult.InvalidName

        val password = normalizePassword(passwordRaw)
        if (!isValidPassword(password)) return CreateResult.InvalidPassword

        return when (val prep = prepareCreateAccount(nameRaw, passwordRaw, defaultAnsiEnabled, race, playerClass)) {
            is CreateAccountPrep.Ready -> applyCreateAccount(sessionId, prep.record)
            CreateAccountPrep.InvalidInput -> CreateResult.InvalidName
            CreateAccountPrep.Taken -> CreateResult.Taken
        }
    }

    private suspend fun bindSession(
        sessionId: SessionId,
        boundRecord: PlayerRecord,
        now: Long,
    ) {
        val xpTotal = boundRecord.xpTotal.coerceAtLeast(0L)
        val level = progression.computeLevel(xpTotal)
        val ps = boundRecord.copy(xpTotal = xpTotal, level = level).toPlayerState(sessionId)
        progression.applyLevelStats(ps, level)
        ps.hp = if (boundRecord.hp <= 0) ps.maxHp else boundRecord.hp.coerceIn(1, ps.maxHp)
        ps.mana = boundRecord.mana.coerceIn(0, ps.maxMana)
        players[sessionId] = ps
        roomMembers.getOrPut(ps.roomId) { mutableSetOf() }.add(sessionId)
        sessionByLowerName[ps.name.lowercase()] = sessionId
        items.ensurePlayer(sessionId)
        for (item in boundRecord.inventoryItems) {
            items.addToInventory(sessionId, item)
        }
        for ((slotName, item) in boundRecord.equippedItems) {
            val slot = ItemSlot.parse(slotName) ?: continue
            items.setEquippedItem(sessionId, slot, item)
        }

        repo.save(
            boundRecord.copy(
                roomId = ps.roomId,
                lastSeenEpochMs = now,
                level = level,
                xpTotal = xpTotal,
            ),
        )
    }

    suspend fun disconnect(sessionId: SessionId) {
        val ps = players.remove(sessionId) ?: return

        // Persist last seen + room for claimed players
        persistIfClaimed(ps)

        roomMembers.removeFromSet(ps.roomId, sessionId)
        sessionByLowerName.remove(ps.name.lowercase())
        items.removePlayer(sessionId)
    }

    /**
     * Add a player from a cross-zone handoff, bypassing the login flow.
     * The player is placed directly into the target room.
     */
    fun bindFromHandoff(
        sessionId: SessionId,
        ps: PlayerState,
    ) {
        players[sessionId] = ps
        roomMembers.getOrPut(ps.roomId) { mutableSetOf() }.add(sessionId)
        sessionByLowerName[ps.name.lowercase()] = sessionId
        items.ensurePlayer(sessionId)
    }

    /**
     * Remove a player for cross-zone handoff without persisting.
     * Persistence should happen before calling this (with the target room saved).
     * Returns the removed PlayerState, or null if not found.
     */
    suspend fun removeForHandoff(sessionId: SessionId): PlayerState? {
        val ps = players.remove(sessionId) ?: return null

        // Persist with target room before removing
        persistIfClaimed(ps)

        roomMembers.removeFromSet(ps.roomId, sessionId)
        sessionByLowerName.remove(ps.name.lowercase())
        items.removePlayer(sessionId)
        return ps
    }

    fun get(sessionId: SessionId): PlayerState? = players[sessionId]

    /** Returns the session id for [playerId] if that player is currently online, or null if offline. */
    fun findSessionByPlayerId(playerId: PlayerId): SessionId? =
        players.entries.firstOrNull { it.value.playerId == playerId }?.key

    /** Returns the online [PlayerState] for [name] (case-insensitive), or null if offline. */
    fun getByName(name: String): PlayerState? {
        val sid = sessionByLowerName[name.lowercase()] ?: return null
        return players[sid]
    }

    /** Persists the current state for [sessionId] if the player is claimed. No-op for unclaimed sessions. */
    suspend fun persistPlayer(sessionId: SessionId) {
        val ps = players[sessionId] ?: return
        persistIfClaimed(ps)
    }

    /**
     * Applies [updater] to the persisted record of an offline player and saves the result.
     * No-op if no record exists for [playerId].
     * Must only be called for players that are confirmed offline.
     */
    suspend fun updateOfflinePlayer(playerId: PlayerId, updater: (PlayerRecord) -> PlayerRecord) {
        val record = repo.findById(playerId) ?: return
        repo.save(updater(record))
    }

    /**
     * Returns the name of an offline player by their [playerId], or null if not found.
     */
    suspend fun findOfflinePlayerName(playerId: PlayerId): String? = repo.findById(playerId)?.name

    /**
     * Appends [message] to the inbox of an offline player identified by [recipientName].
     * Returns `true` if the player record was found and updated, `false` if no such player exists.
     * Must not be called when the recipient is online; use [getByName] to check first.
     */
    suspend fun deliverMailOffline(
        recipientName: String,
        message: dev.ambon.domain.mail.MailMessage,
    ): Boolean {
        val record = repo.findByName(recipientName) ?: return false
        repo.save(record.copy(inbox = record.inbox + message))
        return true
    }

    fun allPlayers(): List<PlayerState> = players.values.toList()

    fun playersInRoom(roomId: RoomId): List<PlayerState> =
        roomMembers[roomId]
            ?.mapNotNull { sessionId -> players[sessionId] }
            ?: emptyList()

    fun playersInZone(zone: String): List<PlayerState> = players.values.filter { it.roomId.zone == zone }

    suspend fun moveTo(
        sessionId: SessionId,
        newRoom: RoomId,
    ) {
        val ps = players[sessionId] ?: return
        if (ps.roomId == newRoom) return

        roomMembers.removeFromSet(ps.roomId, sessionId)

        ps.roomId = newRoom
        roomMembers.getOrPut(newRoom) { mutableSetOf() }.add(sessionId)

        // Save room change if claimed
        persistIfClaimed(ps)
    }

    suspend fun setDisplayTitle(
        sessionId: SessionId,
        title: String?,
    ) {
        val ps = players[sessionId] ?: return
        ps.activeTitle = title
        persistIfClaimed(ps)
    }

    /**
     * Sets [sessionId]'s recall room to [roomId] and persists.
     */
    suspend fun setRecallRoom(
        sessionId: SessionId,
        roomId: RoomId,
    ) {
        val ps = players[sessionId] ?: return
        ps.recallRoomId = roomId
        persistIfClaimed(ps)
    }

    /**
     * Returns the room a player should be sent to on `recall`.
     * Prefers the player's saved recall room, then their class start room, then the world start room.
     */
    fun recallTarget(sessionId: SessionId): RoomId? {
        val ps = players[sessionId] ?: return null
        return ps.recallRoomId
            ?: classStartRooms[ps.playerClass.uppercase()]
            ?: classRegistry?.get(ps.playerClass)?.startRoom?.let { RoomId(it) }
            ?: startRoom
    }

    suspend fun grantXp(
        sessionId: SessionId,
        amount: Long,
        progressionOverride: PlayerProgression? = null,
    ): LevelUpResult? {
        val ps = players[sessionId] ?: return null
        val activeProgression = progressionOverride ?: progression
        val result = activeProgression.grantXp(ps, amount)
        persistIfClaimed(ps)
        return result
    }

    suspend fun setLevel(
        sessionId: SessionId,
        level: Int,
    ) {
        val ps = players[sessionId] ?: return
        val clampedLevel = level.coerceIn(1, progression.maxLevel)
        ps.level = clampedLevel
        ps.xpTotal = progression.totalXpForLevel(clampedLevel)
        progression.applyLevelStats(ps, clampedLevel)
        persistIfClaimed(ps)
    }

    suspend fun setAnsiEnabled(
        sessionId: SessionId,
        enabled: Boolean,
    ) {
        val ps = players[sessionId] ?: return
        if (ps.ansiEnabled == enabled) return
        ps.ansiEnabled = enabled
        persistIfClaimed(ps)
    }

    fun findSessionByName(name: String): SessionId? = sessionByLowerName[normalizeName(name).lowercase()]

    fun isNameOnline(
        name: String,
        exclude: SessionId? = null,
    ): Boolean {
        val key = normalizeName(name).lowercase()
        val existing = sessionByLowerName[key]
        return existing != null && existing != exclude
    }

    suspend fun hasRegisteredName(nameRaw: String): Boolean {
        val name = normalizeName(nameRaw)
        if (!isValidName(name)) return false
        return repo.findByName(name) != null
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

    private suspend fun takeoverSession(
        oldSid: SessionId,
        newSid: SessionId,
        boundRecord: PlayerRecord,
        now: Long,
    ) {
        val oldPs = players[oldSid] ?: return
        val newPs = oldPs.copy(sessionId = newSid, ansiEnabled = boundRecord.ansiEnabled)

        players.remove(oldSid)
        players[newSid] = newPs

        roomMembers[oldPs.roomId]?.let { members ->
            members.remove(oldSid)
            members.add(newSid)
        }

        sessionByLowerName[newPs.name.lowercase()] = newSid

        items.remapPlayer(oldSid, newSid)

        persistIfClaimed(newPs)
    }

    private suspend fun persistIfClaimed(ps: PlayerState) {
        if (ps.playerId == null) return
        val record = ps.toPlayerRecord(lastSeenEpochMs = clock.millis()).copy(
            inventoryItems = items.inventory(ps.sessionId),
            equippedItems = items.equipment(ps.sessionId).mapKeys { (slot, _) -> slot.name },
        )
        repo.save(record)
    }

    private fun isValidPassword(password: String): Boolean {
        if (password.isBlank()) return false
        if (password.length > 72) return false
        return true
    }

    private fun normalizeName(nameRaw: String): String = nameRaw.trim()

    private fun normalizePassword(passwordRaw: String): String = passwordRaw
}
