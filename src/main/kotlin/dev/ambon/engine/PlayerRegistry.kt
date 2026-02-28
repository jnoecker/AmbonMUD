package dev.ambon.engine

import dev.ambon.domain.PlayerClass
import dev.ambon.domain.Race
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.PlayerCreationRequest
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
    private val classStartRooms: Map<PlayerClass, RoomId> = emptyMap(),
    private val repo: PlayerRepository,
    private val items: ItemRegistry,
    private val clock: Clock = Clock.systemUTC(),
    private val progression: PlayerProgression = PlayerProgression(),
    // Context for BCrypt operations (CPU-intensive blocking).
    // Production callers should pass Dispatchers.IO to avoid blocking the engine thread.
    // Defaults to EmptyCoroutineContext so tests run synchronously on the test scheduler.
    private val hashingContext: CoroutineContext = EmptyCoroutineContext,
    private val passwordHasher: PasswordHasher = BCryptPasswordHasher,
) {
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
        race: Race,
        playerClass: PlayerClass,
    ): CreateAccountPrep {
        val name = normalizeName(nameRaw)
        val password = normalizePassword(passwordRaw)
        if (!isValidName(name)) return CreateAccountPrep.InvalidInput
        if (!isValidPassword(password)) return CreateAccountPrep.InvalidInput
        if (repo.findByName(name) != null) return CreateAccountPrep.Taken

        val now = clock.millis()
        val hash = withContext(hashingContext) { passwordHasher.hash(password) }
        val baseStat = PlayerState.BASE_STAT
        val record =
            repo.create(
                PlayerCreationRequest(
                    name = name,
                    startRoomId = classStartRooms[playerClass] ?: startRoom,
                    nowEpochMs = now,
                    passwordHash = hash,
                    ansiEnabled = defaultAnsiEnabled,
                    race = race.name,
                    playerClass = playerClass.name,
                    strength = baseStat + race.strMod,
                    dexterity = baseStat + race.dexMod,
                    constitution = baseStat + race.conMod,
                    intelligence = baseStat + race.intMod,
                    wisdom = baseStat + race.wisMod,
                    charisma = baseStat + race.chaMod,
                ),
            )
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

        val now = clock.millis()

        val existingRecord: PlayerRecord? = repo.findByName(name)

        val boundRecord =
            if (existingRecord != null) {
                if (existingRecord.passwordHash.isNotBlank()) {
                    val ok =
                        withContext(hashingContext) {
                            passwordHasher.verify(password, existingRecord.passwordHash)
                        }
                    if (!ok) return LoginResult.WrongPassword
                    existingRecord.copy(lastSeenEpochMs = now)
                } else {
                    val hash = withContext(hashingContext) { passwordHasher.hash(password) }
                    existingRecord.copy(lastSeenEpochMs = now, passwordHash = hash)
                }
            } else {
                return when (create(sessionId, name, password, defaultAnsiEnabled)) {
                    CreateResult.Ok -> LoginResult.Ok
                    CreateResult.InvalidName -> LoginResult.InvalidName
                    CreateResult.InvalidPassword -> LoginResult.InvalidPassword
                    CreateResult.Taken -> LoginResult.Taken
                }
            }

        if (isNameOnline(name, sessionId)) {
            val oldSid = findSessionByName(name)!!
            takeoverSession(oldSid, sessionId, boundRecord, now)
            return LoginResult.Takeover(oldSid)
        }

        bindSession(sessionId, boundRecord, now)
        return LoginResult.Ok
    }

    suspend fun create(
        sessionId: SessionId,
        nameRaw: String,
        passwordRaw: String,
        defaultAnsiEnabled: Boolean = false,
        race: Race = Race.HUMAN,
        playerClass: PlayerClass = PlayerClass.WARRIOR,
    ): CreateResult {
        if (players.containsKey(sessionId)) return CreateResult.InvalidName

        val name = normalizeName(nameRaw)
        if (!isValidName(name)) return CreateResult.InvalidName

        if (isNameOnline(name, sessionId)) return CreateResult.Taken

        val password = normalizePassword(passwordRaw)
        if (!isValidPassword(password)) return CreateResult.InvalidPassword

        if (repo.findByName(name) != null) return CreateResult.Taken

        val baseStat = PlayerState.BASE_STAT
        val now = clock.millis()
        val created =
            repo.create(
                PlayerCreationRequest(
                    name = name,
                    startRoomId = classStartRooms[playerClass] ?: startRoom,
                    nowEpochMs = now,
                    passwordHash = withContext(hashingContext) { passwordHasher.hash(password) },
                    ansiEnabled = defaultAnsiEnabled,
                    race = race.name,
                    playerClass = playerClass.name,
                    strength = baseStat + race.strMod,
                    dexterity = baseStat + race.dexMod,
                    constitution = baseStat + race.conMod,
                    intelligence = baseStat + race.intMod,
                    wisdom = baseStat + race.wisMod,
                    charisma = baseStat + race.chaMod,
                ),
            )
        bindSession(sessionId, created, now)
        return CreateResult.Ok
    }

    private suspend fun bindSession(
        sessionId: SessionId,
        boundRecord: PlayerRecord,
        now: Long,
    ) {
        val xpTotal = boundRecord.xpTotal.coerceAtLeast(0L)
        val level = progression.computeLevel(xpTotal)
        val pc = PlayerClass.fromString(boundRecord.playerClass)
        val maxHp =
            progression.maxHpForLevel(
                level,
                boundRecord.constitution,
                pc?.hpPerLevel ?: progression.hpPerLevel,
            )
        val maxMana =
            progression.maxManaForLevel(
                level,
                boundRecord.intelligence,
                pc?.manaPerLevel ?: progression.manaPerLevel,
            )
        val ps =
            PlayerState(
                sessionId = sessionId,
                name = boundRecord.name,
                roomId = boundRecord.roomId,
                playerId = boundRecord.id,
                baseMaxHp = maxHp,
                hp = maxHp,
                maxHp = maxHp,
                strength = boundRecord.strength,
                dexterity = boundRecord.dexterity,
                constitution = boundRecord.constitution,
                intelligence = boundRecord.intelligence,
                wisdom = boundRecord.wisdom,
                charisma = boundRecord.charisma,
                race = boundRecord.race,
                playerClass = boundRecord.playerClass,
                level = level,
                xpTotal = xpTotal,
                ansiEnabled = boundRecord.ansiEnabled,
                isStaff = boundRecord.isStaff,
                mana = boundRecord.mana.coerceIn(0, maxMana),
                maxMana = maxMana,
                baseMana = maxMana,
                gold = boundRecord.gold,
                createdAtEpochMs = boundRecord.createdAtEpochMs,
                passwordHash = boundRecord.passwordHash,
                activeQuests = boundRecord.activeQuests,
                completedQuestIds = boundRecord.completedQuestIds,
                unlockedAchievementIds = boundRecord.unlockedAchievementIds,
                achievementProgress = boundRecord.achievementProgress,
                activeTitle = boundRecord.activeTitle,
                inbox = boundRecord.inbox.toMutableList(),
            )
        players[sessionId] = ps
        roomMembers.getOrPut(ps.roomId) { mutableSetOf() }.add(sessionId)
        sessionByLowerName[ps.name.lowercase()] = sessionId
        items.ensurePlayer(sessionId)

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

        roomMembers[ps.roomId]?.remove(sessionId)
        if (roomMembers[ps.roomId]?.isEmpty() == true) roomMembers.remove(ps.roomId)
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

        roomMembers[ps.roomId]?.remove(sessionId)
        if (roomMembers[ps.roomId]?.isEmpty() == true) roomMembers.remove(ps.roomId)
        sessionByLowerName.remove(ps.name.lowercase())
        items.removePlayer(sessionId)
        return ps
    }

    fun get(sessionId: SessionId): PlayerState? = players[sessionId]

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

        roomMembers[ps.roomId]?.remove(sessionId)
        if (roomMembers[ps.roomId]?.isEmpty() == true) roomMembers.remove(ps.roomId)

        ps.roomId = newRoom
        roomMembers.getOrPut(newRoom) { mutableSetOf() }.add(sessionId)

        // Save room change if claimed
        persistIfClaimed(ps)
    }

    suspend fun saveQuestState(sessionId: SessionId) {
        val ps = players[sessionId] ?: return
        persistIfClaimed(ps)
    }

    suspend fun saveAchievementState(sessionId: SessionId) {
        val ps = players[sessionId] ?: return
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
        val pid = ps.playerId ?: return
        val now = clock.millis()

        repo.save(
            PlayerRecord(
                id = pid,
                name = ps.name,
                roomId = ps.roomId,
                strength = ps.strength,
                dexterity = ps.dexterity,
                constitution = ps.constitution,
                intelligence = ps.intelligence,
                wisdom = ps.wisdom,
                charisma = ps.charisma,
                race = ps.race,
                playerClass = ps.playerClass,
                level = ps.level,
                xpTotal = ps.xpTotal,
                createdAtEpochMs = ps.createdAtEpochMs,
                lastSeenEpochMs = now,
                passwordHash = ps.passwordHash,
                ansiEnabled = ps.ansiEnabled,
                isStaff = ps.isStaff,
                mana = ps.mana,
                maxMana = ps.maxMana,
                gold = ps.gold,
                activeQuests = ps.activeQuests,
                completedQuestIds = ps.completedQuestIds,
                unlockedAchievementIds = ps.unlockedAchievementIds,
                achievementProgress = ps.achievementProgress,
                activeTitle = ps.activeTitle,
                inbox = ps.inbox.toList(),
            ),
        )
    }

    private fun isValidPassword(password: String): Boolean {
        if (password.isBlank()) return false
        if (password.length > 72) return false
        return true
    }

    private fun normalizeName(nameRaw: String): String = nameRaw.trim()

    private fun normalizePassword(passwordRaw: String): String = passwordRaw
}
