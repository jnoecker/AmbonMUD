package dev.ambon.engine

import dev.ambon.domain.StarterEquipmentEntry
import dev.ambon.domain.StatMap
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.ItemInstance
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
 * Result of converting a demo (unclaimed) character into a real account
 * via [PlayerRegistry.claim].
 */
sealed interface ClaimResult {
    data class Ok(
        val playerId: PlayerId,
        val name: String,
    ) : ClaimResult

    /** Session has no player bound, or the player is already claimed. */
    data object NotDemo : ClaimResult

    data object InvalidName : ClaimResult

    data object InvalidPassword : ClaimResult

    data object Taken : ClaimResult
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
    startRoom: RoomId,
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
    private val statRegistry: StatRegistry? = null,
    private val startingGold: Long = 0L,
    private val defaultGender: String = "enby",
) {
    val maxLevel: Int get() = progression.maxLevel

    /** Default start room for new characters / recall fallback. Updatable via hot reload. */
    var startRoom: RoomId = startRoom
        internal set

    /**
     * Optional callback to cancel a grace period by player name.
     * Set by GameEngine after construction so that normal login clears
     * any suspended session for the same player before binding.
     */
    var cancelGracePeriod: (suspend (String) -> Unit)? = null

    private val players = mutableMapOf<SessionId, PlayerState>()
    private val roomMembers = mutableMapOf<RoomId, MutableSet<SessionId>>()

    // Case-insensitive name index (online only)
    private val sessionByLowerName = mutableMapOf<String, SessionId>()

    /**
     * Immutable snapshot of [players] values, rebuilt every time the map
     * membership changes. Reads (including [allPlayers]) return this reference
     * without allocating — `players.values.toList()` per call was a measurable
     * allocation hotspot at 1k+ online sessions, showing up as GC pressure in
     * the tick loop's ZGC concurrent-cycle budget.
     *
     * Volatile for visibility to off-engine-thread readers (AdminHttpServer
     * endpoints, metrics scrapes). All writes happen on the engine thread
     * inside [rebuildAllPlayersSnapshot], which is only called from mutation
     * paths — no need for a lock since mutations are single-threaded.
     */
    @Volatile
    private var allPlayersSnapshot: List<PlayerState> = emptyList()

    private fun rebuildAllPlayersSnapshot() {
        allPlayersSnapshot = players.values.toList()
    }

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

        // If the player is in the grace period (suspended), cancel it and
        // clean up the stale session mapping so bindSession works cleanly.
        cancelGracePeriod?.invoke(record.name)

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

        fun base(id: String): Int = statRegistry?.get(id)?.baseStat ?: PlayerState.BASE_STAT

        val raceMods = raceRegistry?.get(raceId)?.statMods ?: StatMap.EMPTY
        val statKeys = statRegistry?.all()?.map { it.id } ?: listOf("STR", "DEX", "CON", "INT", "WIS", "CHA")
        val resolvedStats = statKeys.associateWith { id -> base(id) + raceMods[id] }
        val classDef = classRegistry?.get(classId)
        val classStartRoom = classStartRooms[classId.uppercase()]
            ?: classDef?.startRoom?.let { RoomId(it) }
        val (starterInventory, starterEquipped) = resolveStarterEquipment(classDef?.starterEquipment.orEmpty())
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
                        gender = defaultGender,
                        stats = resolvedStats,
                        gold = startingGold,
                        inventoryItems = starterInventory,
                        equippedItems = starterEquipped,
                    ),
                )
            } catch (_: PersistenceException) {
                return CreateAccountPrep.Taken
            }
        return CreateAccountPrep.Ready(record)
    }

    /**
     * Resolve a class's starter equipment list into concrete [ItemInstance]s by
     * looking each entry up in the [ItemRegistry] templates. Entries marked
     * `equip = true` whose item has a slot are routed to the equipped map;
     * everything else (including unslotted items) goes into inventory. Unknown
     * item IDs are skipped silently — operators may reference IDs from a world
     * that isn't currently loaded, and we don't want creation to fail.
     */
    private fun resolveStarterEquipment(
        entries: List<StarterEquipmentEntry>,
    ): Pair<List<ItemInstance>, Map<String, ItemInstance>> {
        if (entries.isEmpty()) return emptyList<ItemInstance>() to emptyMap()
        val inventory = mutableListOf<ItemInstance>()
        val equipped = mutableMapOf<String, ItemInstance>()
        for (entry in entries) {
            val instance = items.createFromTemplate(ItemId(entry.itemId)) ?: continue
            val slot = instance.item.slot
            if (entry.equip && slot != null && !equipped.containsKey(slot.name)) {
                equipped[slot.name] = instance
            } else {
                inventory.add(instance)
            }
        }
        return inventory to equipped
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

    /**
     * Creates an ephemeral demo character and binds it to [sessionId].
     *
     * The character is **not** persisted to the repository — it lives only in
     * memory and disappears when the session disconnects. Internally this builds
     * a [PlayerRecord] just like account creation but skips the `repo.create`
     * call and sets [PlayerState.playerId] to `null`, so [persistIfClaimed] is
     * a no-op for the lifetime of the session.
     *
     * If the player later runs `/claim` they will be upgraded to a real account
     * via [claim], at which point a real [PlayerRecord] is written.
     */
    suspend fun createDemo(
        sessionId: SessionId,
        name: String,
        defaultAnsiEnabled: Boolean = false,
        race: String = "HUMAN",
        playerClass: String = "WARRIOR",
    ): CreateResult {
        if (players.containsKey(sessionId)) return CreateResult.InvalidName
        if (!isValidName(name)) return CreateResult.InvalidName
        if (isNameOnline(name, sessionId)) return CreateResult.Taken

        fun base(id: String): Int = statRegistry?.get(id)?.baseStat ?: PlayerState.BASE_STAT
        val raceMods = raceRegistry?.get(race)?.statMods ?: StatMap.EMPTY
        val statKeys = statRegistry?.all()?.map { it.id } ?: listOf("STR", "DEX", "CON", "INT", "WIS", "CHA")
        val resolvedStats = statKeys.associateWith { id -> base(id) + raceMods[id] }
        val classDef = classRegistry?.get(playerClass)
        val classStartRoom = classStartRooms[playerClass.uppercase()]
            ?: classDef?.startRoom?.let { RoomId(it) }
        val (starterInventory, starterEquipped) = resolveStarterEquipment(classDef?.starterEquipment.orEmpty())

        val now = clock.millis()
        // PlayerId(0L) is a sentinel — the PlayerState's playerId is overwritten
        // to null below so this record id is never used.
        val record = PlayerRecord(
            id = PlayerId(0L),
            name = name,
            roomId = classStartRoom ?: startRoom,
            stats = resolvedStats,
            gender = defaultGender,
            race = race,
            playerClass = playerClass,
            createdAtEpochMs = now,
            lastSeenEpochMs = now,
            passwordHash = "",
            ansiEnabled = defaultAnsiEnabled,
            gold = startingGold,
            autolootEnabled = true,
            autopeekEnabled = true,
            inventoryItems = starterInventory,
            equippedItems = starterEquipped,
        )
        bindDemoSession(sessionId, record)
        return CreateResult.Ok
    }

    /**
     * Converts the demo character bound to [sessionId] into a real account.
     *
     * Validates [newName] (or keeps the current demo name if null) and [password],
     * checks the name isn't taken, persists a fresh [PlayerRecord] via
     * `repo.create`, and updates the in-memory [PlayerState] (`name`, `playerId`,
     * `passwordHash`) plus the session-by-name index. After this returns
     * successfully, [persistIfClaimed] writes the player's full current state
     * (inventory, gold, xp, etc.) and the character is indistinguishable from
     * one created through the normal login flow.
     */
    suspend fun claim(
        sessionId: SessionId,
        newNameRaw: String?,
        passwordRaw: String,
    ): ClaimResult {
        val ps = players[sessionId] ?: return ClaimResult.NotDemo
        if (ps.playerId != null) return ClaimResult.NotDemo

        val targetName = normalizeName(newNameRaw ?: ps.name)
        val password = normalizePassword(passwordRaw)
        if (!isValidName(targetName)) return ClaimResult.InvalidName
        if (!isValidPassword(password)) return ClaimResult.InvalidPassword
        // Allow keeping the same name (case-insensitive match against self),
        // otherwise the name must be free both online and in the repo.
        val keepingSameName = targetName.equals(ps.name, ignoreCase = true)
        if (!keepingSameName) {
            if (isNameOnline(targetName, sessionId)) return ClaimResult.Taken
            if (repo.findByName(targetName) != null) return ClaimResult.Taken
        }

        val hash = withContext(hashingContext) { passwordHasher.hash(password) }
        val now = clock.millis()

        val record = try {
            repo.create(
                PlayerCreationRequest(
                    name = targetName,
                    startRoomId = ps.roomId,
                    nowEpochMs = now,
                    passwordHash = hash,
                    ansiEnabled = ps.ansiEnabled,
                    race = ps.race,
                    playerClass = ps.playerClass,
                    gender = ps.gender,
                    stats = ps.stats.values,
                    gold = ps.gold,
                    // Starter inventory/equipment lives in ItemRegistry and is
                    // persisted by the trailing persistIfClaimed call below.
                ),
            )
        } catch (_: PersistenceException) {
            return ClaimResult.Taken
        }

        // Re-index by name if it changed
        if (!keepingSameName) {
            sessionByLowerName.remove(ps.name.lowercase())
        }
        ps.name = targetName
        ps.playerId = record.id
        ps.passwordHash = hash
        sessionByLowerName[targetName.lowercase()] = sessionId

        // Persist current runtime state (xp, hp, inventory, equipment, etc.).
        persistIfClaimed(ps)
        return ClaimResult.Ok(record.id, targetName)
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

    /**
     * Ephemeral variant of [bindSession] for demo characters. Skips the
     * trailing `repo.save` and nullifies [PlayerState.playerId] so the player
     * is never persisted (see [persistIfClaimed]).
     */
    private fun bindDemoSession(
        sessionId: SessionId,
        boundRecord: PlayerRecord,
    ) {
        val xpTotal = boundRecord.xpTotal.coerceAtLeast(0L)
        val level = progression.computeLevel(xpTotal)
        val ps = boundRecord.copy(xpTotal = xpTotal, level = level).toPlayerState(sessionId)
        ps.playerId = null
        progression.applyLevelStats(ps, level)
        ps.hp = ps.maxHp
        ps.mana = ps.maxMana
        players[sessionId] = ps
        rebuildAllPlayersSnapshot()
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
        ps.hp = ps.maxHp
        ps.mana = ps.maxMana
        players[sessionId] = ps
        rebuildAllPlayersSnapshot()
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
        rebuildAllPlayersSnapshot()

        // Persist last seen + room for claimed players
        persistIfClaimed(ps)

        // Evict from the repository cache so the next login reads fresh
        // from disk, picking up any manual edits made while offline.
        ps.playerId?.let { repo.evict(it) }

        roomMembers.removeFromSet(ps.roomId, sessionId)
        sessionByLowerName.remove(ps.name.lowercase())
        items.removePlayer(sessionId)
    }

    /**
     * Clean up a suspended session's residual entries (roomMembers, name index).
     * Used when the grace period expires or is cancelled. The PlayerState is not
     * in the `players` map (it was removed by [suspendSession]), so we accept it
     * directly and persist + clean up its dangling references.
     */
    suspend fun disconnectSuspended(sessionId: SessionId, playerState: PlayerState) {
        persistIfClaimed(playerState)
        playerState.playerId?.let { repo.evict(it) }
        roomMembers.removeFromSet(playerState.roomId, sessionId)
        sessionByLowerName.remove(playerState.name.lowercase())
        items.removePlayer(sessionId)
    }

    /**
     * Detach a player's session binding for grace period suspension.
     * The PlayerState is removed from the active registry but NOT persisted
     * or evicted — it will be reattached on resume or cleaned up on expiry.
     */
    fun suspendSession(sessionId: SessionId): PlayerState? {
        val ps = players.remove(sessionId) ?: return null
        rebuildAllPlayersSnapshot()
        // Keep the player in roomMembers and sessionByLowerName so they
        // remain "visible" during the grace period. The sessionId will be
        // stale but that's fine — outbound messages to it are dropped.
        return ps
    }

    /**
     * Reattach a suspended PlayerState to a new session after reconnect.
     * Uses the same remap logic as session takeover.
     * All map mutations are non-suspending to prevent interleaved coroutine
     * access on the single-threaded engine dispatcher.
     */
    suspend fun resumeSession(
        oldSessionId: SessionId,
        newSessionId: SessionId,
        playerState: PlayerState,
    ) {
        val resumed = playerState.copy(sessionId = newSessionId)

        // --- begin atomic map mutations (no suspend points) ---
        players[newSessionId] = resumed
        rebuildAllPlayersSnapshot()

        // Remap room membership, cleaning up empty sets
        roomMembers.removeFromSet(resumed.roomId, oldSessionId)
        roomMembers.getOrPut(resumed.roomId) { mutableSetOf() }.add(newSessionId)

        sessionByLowerName[resumed.name.lowercase()] = newSessionId
        items.remapPlayer(oldSessionId, newSessionId)
        // --- end atomic map mutations ---
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
        rebuildAllPlayersSnapshot()
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
        rebuildAllPlayersSnapshot()

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

    fun allPlayers(): List<PlayerState> = allPlayersSnapshot

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
        // Any room change is a teleport at this layer; directional walks repopulate this
        // immediately afterwards via movePlayerWithNotify. Clearing here keeps flee from
        // retracing a stale direction after admin teleports, dungeon entry, housing,
        // guild-hall entry, death/respawn, etc.
        ps.lastEnterDirection = null
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

    suspend fun setScreenReaderEnabled(
        sessionId: SessionId,
        enabled: Boolean,
    ) {
        val ps = players[sessionId] ?: return
        if (ps.screenReaderEnabled == enabled) return
        ps.screenReaderEnabled = enabled
        persistIfClaimed(ps)
    }

    suspend fun setAutolootEnabled(
        sessionId: SessionId,
        enabled: Boolean,
    ) {
        val ps = players[sessionId] ?: return
        if (ps.autolootEnabled == enabled) return
        ps.autolootEnabled = enabled
        persistIfClaimed(ps)
    }

    suspend fun setAutopeekEnabled(
        sessionId: SessionId,
        enabled: Boolean,
    ) {
        val ps = players[sessionId] ?: return
        if (ps.autopeekEnabled == enabled) return
        ps.autopeekEnabled = enabled
        persistIfClaimed(ps)
    }

    /** Sets the wimpy auto-flee HP-percent threshold. Caller is responsible for clamping to 0..95. */
    suspend fun setWimpyThresholdPct(
        sessionId: SessionId,
        pct: Int,
    ) {
        val ps = players[sessionId] ?: return
        if (ps.wimpyThresholdPct == pct) return
        ps.wimpyThresholdPct = pct
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

    /**
     * Remap a player from [oldSid] to [newSid] atomically.
     * All map mutations are non-suspending to prevent interleaved coroutine
     * access on the single-threaded engine dispatcher.
     * The only suspend call ([persistIfClaimed]) happens after all maps are consistent.
     */
    private suspend fun takeoverSession(
        oldSid: SessionId,
        newSid: SessionId,
        boundRecord: PlayerRecord,
        now: Long,
    ) {
        val oldPs = players[oldSid] ?: return
        val newPs = oldPs.copy(sessionId = newSid, ansiEnabled = boundRecord.ansiEnabled)

        // --- begin atomic map mutations (no suspend points) ---
        players.remove(oldSid)
        players[newSid] = newPs
        rebuildAllPlayersSnapshot()

        roomMembers.removeFromSet(oldPs.roomId, oldSid)
        roomMembers.getOrPut(newPs.roomId) { mutableSetOf() }.add(newSid)

        sessionByLowerName[newPs.name.lowercase()] = newSid

        items.remapPlayer(oldSid, newSid)
        // --- end atomic map mutations ---

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
