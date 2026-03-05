package dev.ambon.engine.events

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.ambon.bus.OutboundBus
import dev.ambon.domain.PlayerClass
import dev.ambon.domain.Race
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.World
import dev.ambon.engine.AchievementRegistry
import dev.ambon.engine.GmcpEmitter
import dev.ambon.engine.GroupSystem
import dev.ambon.engine.GuildSystem
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.SessionLifecycleCoordinator
import dev.ambon.engine.abilities.AbilitySystem
import dev.ambon.engine.broadcastToRoom
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.status.StatusEffectSystem
import dev.ambon.metrics.GameMetrics
import dev.ambon.sharding.HandoffManager
import dev.ambon.sharding.HandoffResult
import dev.ambon.sharding.PlayerLocationIndex
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

private val log = KotlinLogging.logger {}

/** Login session state machine states. */
internal sealed interface LoginState {
    data object AwaitingName : LoginState

    data class AwaitingCreateConfirmation(
        val name: String,
    ) : LoginState

    data class AwaitingExistingPassword(
        val name: String,
        val wrongPasswordAttempts: Int = 0,
    ) : LoginState

    data class AwaitingNewPassword(
        val name: String,
    ) : LoginState

    data class AwaitingRaceSelection(
        val name: String,
        val password: String,
    ) : LoginState

    data class AwaitingClassSelection(
        val name: String,
        val password: String,
        val race: Race,
    ) : LoginState

    /** Name-existence check in-flight; player input is buffered until complete. */
    data class AwaitingNameLookup(
        val name: String,
    ) : LoginState

    /** BCrypt password verification in-flight; player input is ignored until complete. */
    data class AwaitingPasswordAuth(
        val name: String,
        val wrongPasswordAttempts: Int = 0,
    ) : LoginState

    /** Account creation (hash + DB) in-flight; player input is ignored until complete. */
    data class AwaitingCreateAuth(
        val name: String,
        val password: String,
        val race: Race,
        val playerClass: PlayerClass,
    ) : LoginState
}

/** Result posted to [LoginFlowHandler.pendingAuthResults] by a background auth coroutine. */
private sealed interface PendingAuthResult {
    val sessionId: SessionId

    data class NameLookup(
        override val sessionId: SessionId,
        val name: String,
        val exists: Boolean,
    ) : PendingAuthResult

    data class PasswordAuth(
        override val sessionId: SessionId,
        val prep: dev.ambon.engine.LoginCredentialPrep,
        val wrongPasswordAttempts: Int,
        val defaultAnsiEnabled: Boolean,
    ) : PendingAuthResult

    data class NewAccountAuth(
        override val sessionId: SessionId,
        val prep: dev.ambon.engine.CreateAccountPrep,
    ) : PendingAuthResult
}

/**
 * Owns the login state machine and all async authentication flows.
 * Called from the engine tick loop via [drainPendingAuthResults] and dispatched
 * to by [LoginEventHandler] for individual login-state handlers.
 */
internal class LoginFlowHandler(
    private val outbound: OutboundBus,
    private val players: PlayerRegistry,
    private val world: World,
    private val items: ItemRegistry,
    private val abilitySystem: AbilitySystem,
    private val gmcpEmitter: GmcpEmitter,
    private val statusEffectSystem: StatusEffectSystem,
    private val achievementRegistry: AchievementRegistry,
    private val groupSystem: GroupSystem,
    private val guildSystem: GuildSystem? = null,
    private val sessionLifecycle: SessionLifecycleCoordinator,
    private val router: CommandRouter,
    private val playerLocationIndex: PlayerLocationIndex?,
    private val handoffManager: HandoffManager?,
    private val getEngineScope: () -> CoroutineScope,
    private val metrics: GameMetrics,
    private val availableClasses: List<PlayerClass>,
    maxWrongPasswordRetries: Int,
    maxFailedLoginAttemptsBeforeDisconnect: Int,
    maxConcurrentLogins: Int,
    private val onAfterLogin: suspend (SessionId) -> Unit = {},
) {
    // State exposed by reference so SessionEventHandler can clear it on disconnect.
    internal val pendingLogins = mutableMapOf<SessionId, LoginState>()
    internal val failedLoginAttempts = mutableMapOf<SessionId, Int>()
    internal val sessionAnsiDefaults = mutableMapOf<SessionId, Boolean>()

    // Bounds concurrent sessions in the auth funnel (name lookup → BCrypt → world entry).
    // A session acquires a slot on first name submission and holds it until auth completes
    // or the session disconnects, so retries don't consume extra slots.
    private val loginSemaphore = Semaphore(maxConcurrentLogins)
    private val sessionsHoldingLoginPermit = mutableSetOf<SessionId>()

    private val pendingAuthResults = Channel<PendingAuthResult>(Channel.UNLIMITED)
    private val nameCommandRegex = Regex("^name\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val invalidNameMessage =
        "Invalid name. Use 2-16 chars: letters/digits/_ and cannot start with digit."
    private val invalidPasswordMessage = "Invalid password. Use 1-72 chars."
    private val maxWrongPasswordRetries = maxWrongPasswordRetries
    private val maxFailedLoginAttemptsBeforeDisconnect = maxFailedLoginAttemptsBeforeDisconnect

    /** Drains all immediately-available auth results. Called from the engine tick loop. */
    internal suspend fun drainPendingAuthResults() {
        while (true) {
            val result = pendingAuthResults.tryReceive().getOrNull() ?: break
            handlePendingAuthResult(result)
        }
    }

    internal suspend fun handleLoginName(
        sessionId: SessionId,
        line: String,
    ) {
        val raw = line.trim()
        if (raw.isEmpty()) {
            outbound.send(OutboundEvent.SendError(sessionId, "Please enter a name."))
            emitLoginError(sessionId, "name", "Please enter a name.")
            promptForName(sessionId)
            return
        }

        val name = extractLoginName(raw)
        if (name.isEmpty()) {
            outbound.send(OutboundEvent.SendError(sessionId, "Please enter a name."))
            emitLoginError(sessionId, "name", "Please enter a name.")
            promptForName(sessionId)
            return
        }

        if (!players.isValidName(name)) {
            outbound.send(OutboundEvent.SendError(sessionId, invalidNameMessage))
            emitLoginError(sessionId, "name", invalidNameMessage)
            promptForName(sessionId)
            return
        }

        // Acquire a login slot on the session's first name submission.
        // Retries (returning to AwaitingName after a failed attempt) reuse the slot already held.
        if (!sessionsHoldingLoginPermit.contains(sessionId)) {
            if (!loginSemaphore.tryAcquire()) {
                outbound.send(
                    OutboundEvent.SendError(
                        sessionId,
                        "The server is currently at maximum login capacity. Please try again shortly.",
                    ),
                )
                outbound.send(OutboundEvent.Close(sessionId, "Server at maximum login capacity"))
                return
            }
            sessionsHoldingLoginPermit.add(sessionId)
        }

        pendingLogins[sessionId] = LoginState.AwaitingNameLookup(name)
        getEngineScope().launch {
            val exists = players.hasRegisteredName(name)
            pendingAuthResults.send(PendingAuthResult.NameLookup(sessionId, name, exists))
        }
    }

    /** Releases the login semaphore slot held by [sessionId], if any. Called on disconnect. */
    internal fun releasePermitIfHeld(sessionId: SessionId) {
        if (sessionsHoldingLoginPermit.remove(sessionId)) {
            loginSemaphore.release()
        }
    }

    internal suspend fun handleLoginCreateConfirmation(
        sessionId: SessionId,
        line: String,
        state: LoginState.AwaitingCreateConfirmation,
    ) {
        when (line.trim().lowercase()) {
            "y",
            "yes",
            -> {
                pendingLogins[sessionId] = LoginState.AwaitingNewPassword(state.name)
                promptForNewPassword(sessionId)
            }

            "n",
            "no",
            -> {
                pendingLogins[sessionId] = LoginState.AwaitingName
                promptForName(sessionId)
            }

            else -> {
                outbound.send(OutboundEvent.SendError(sessionId, "Please answer yes or no."))
                emitLoginError(sessionId, "confirmCreate", "Please answer yes or no.")
                promptForCreateConfirmation(sessionId, state.name)
            }
        }
    }

    internal suspend fun handleLoginExistingPassword(
        sessionId: SessionId,
        line: String,
        state: LoginState.AwaitingExistingPassword,
    ) {
        val name = state.name
        val password = line
        if (password.isBlank()) {
            outbound.send(OutboundEvent.SendError(sessionId, "Blank password. Returning to login."))
            emitLoginError(sessionId, "password", "Blank password. Returning to login.")
            if (recordFailedLoginAttemptAndCloseIfNeeded(sessionId)) return
            pendingLogins[sessionId] = LoginState.AwaitingName
            promptForName(sessionId)
            return
        }

        val ansiDefault = sessionAnsiDefaults[sessionId] ?: false
        pendingLogins[sessionId] = LoginState.AwaitingPasswordAuth(name, state.wrongPasswordAttempts)
        getEngineScope().launch {
            val prep = players.prepareLoginCredentials(name, password)
            pendingAuthResults.send(
                PendingAuthResult.PasswordAuth(sessionId, prep, state.wrongPasswordAttempts, ansiDefault),
            )
        }
    }

    internal suspend fun handleLoginNewPassword(
        sessionId: SessionId,
        line: String,
        state: LoginState.AwaitingNewPassword,
    ) {
        val password = line
        if (password.isBlank()) {
            outbound.send(OutboundEvent.SendError(sessionId, "Blank password. Returning to login."))
            emitLoginError(sessionId, "newPassword", "Blank password. Returning to login.")
            if (recordFailedLoginAttemptAndCloseIfNeeded(sessionId)) return
            pendingLogins[sessionId] = LoginState.AwaitingName
            promptForName(sessionId)
            return
        }

        if (password.length > 72) {
            outbound.send(OutboundEvent.SendError(sessionId, invalidPasswordMessage))
            emitLoginError(sessionId, "newPassword", invalidPasswordMessage)
            promptForNewPassword(sessionId)
            return
        }

        pendingLogins[sessionId] = LoginState.AwaitingRaceSelection(state.name, password)
        promptForRaceSelection(sessionId)
    }

    internal suspend fun handleLoginRaceSelection(
        sessionId: SessionId,
        line: String,
        state: LoginState.AwaitingRaceSelection,
    ) {
        val input = line.trim()
        val races = Race.entries
        val race =
            input.toIntOrNull()?.let { num ->
                if (num in 1..races.size) races[num - 1] else null
            } ?: Race.fromString(input)

        if (race == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "Invalid choice. Enter a number or race name."))
            emitLoginError(sessionId, "raceSelection", "Invalid choice. Enter a number or race name.")
            promptForRaceSelection(sessionId)
            return
        }

        pendingLogins[sessionId] = LoginState.AwaitingClassSelection(state.name, state.password, race)
        promptForClassSelection(sessionId)
    }

    internal suspend fun handleLoginClassSelection(
        sessionId: SessionId,
        line: String,
        state: LoginState.AwaitingClassSelection,
    ) {
        val input = line.trim()
        val classes = availableClasses
        val playerClass =
            input.toIntOrNull()?.let { num ->
                if (num in 1..classes.size) classes[num - 1] else null
            } ?: classes.firstOrNull { it.name.equals(input, ignoreCase = true) }

        if (playerClass == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "Invalid choice. Enter a number or class name."))
            emitLoginError(sessionId, "classSelection", "Invalid choice. Enter a number or class name.")
            promptForClassSelection(sessionId)
            return
        }

        val ansiDefault = sessionAnsiDefaults[sessionId] ?: false
        pendingLogins[sessionId] = LoginState.AwaitingCreateAuth(state.name, state.password, state.race, playerClass)
        getEngineScope().launch {
            val prep = players.prepareCreateAccount(state.name, state.password, ansiDefault, state.race, playerClass)
            pendingAuthResults.send(PendingAuthResult.NewAccountAuth(sessionId, prep))
        }
    }

    internal suspend fun promptForName(sessionId: SessionId) {
        outbound.send(OutboundEvent.SendInfo(sessionId, "Enter your name:"))
        outbound.send(OutboundEvent.SendPrompt(sessionId))
        emitLoginPrompt(sessionId, mapOf("state" to "name"))
    }

    private suspend fun handlePendingAuthResult(result: PendingAuthResult) {
        val sid = result.sessionId
        when (result) {
            is PendingAuthResult.NameLookup -> {
                if (pendingLogins[sid] !is LoginState.AwaitingNameLookup) return
                if (result.exists) {
                    pendingLogins[sid] = LoginState.AwaitingExistingPassword(result.name)
                    promptForExistingPassword(sid)
                } else {
                    pendingLogins[sid] = LoginState.AwaitingCreateConfirmation(result.name)
                    promptForCreateConfirmation(sid, result.name)
                }
            }

            is PendingAuthResult.PasswordAuth -> {
                val currentState = pendingLogins[sid] as? LoginState.AwaitingPasswordAuth ?: return
                when (result.prep) {
                    is dev.ambon.engine.LoginCredentialPrep.Verified -> {
                        when (val loginResult = players.applyLoginCredentials(sid, result.prep.record, result.defaultAnsiEnabled)) {
                            dev.ambon.engine.LoginResult.Ok -> finalizeSuccessfulLogin(sid)
                            is dev.ambon.engine.LoginResult.Takeover -> {
                                val oldSid = loginResult.oldSessionId
                                sessionLifecycle.remapSession(oldSid, sid)
                                outbound.send(OutboundEvent.Close(oldSid, "Your account has logged in from another location."))
                                val me = players.get(sid)
                                if (me != null) broadcastToRoom(players, outbound, me.roomId, "${me.name} briefly flickers.", sid)
                                finalizeSuccessfulLogin(sid, suppressEnterBroadcast = true)
                            }
                            dev.ambon.engine.LoginResult.InvalidName -> {
                                outbound.send(OutboundEvent.SendError(sid, invalidNameMessage))
                                if (recordFailedLoginAttemptAndCloseIfNeeded(sid)) return
                                pendingLogins[sid] = LoginState.AwaitingName
                                promptForName(sid)
                            }
                            dev.ambon.engine.LoginResult.InvalidPassword -> {
                                outbound.send(OutboundEvent.SendError(sid, invalidPasswordMessage))
                                if (recordFailedLoginAttemptAndCloseIfNeeded(sid)) return
                                pendingLogins[sid] = LoginState.AwaitingName
                                promptForName(sid)
                            }
                            dev.ambon.engine.LoginResult.Taken -> {
                                outbound.send(OutboundEvent.SendError(sid, "That name is already taken."))
                                pendingLogins[sid] = LoginState.AwaitingName
                                promptForName(sid)
                            }
                            dev.ambon.engine.LoginResult.WrongPassword -> {
                                // Should not happen: WrongPassword is handled by LoginCredentialPrep below.
                            }
                        }
                    }
                    dev.ambon.engine.LoginCredentialPrep.WrongPassword -> {
                        val attempts = currentState.wrongPasswordAttempts + 1
                        if (attempts > maxWrongPasswordRetries) {
                            val msg = "Incorrect password too many times. Returning to login."
                            outbound.send(OutboundEvent.SendError(sid, msg))
                            emitLoginError(sid, "password", msg)
                            if (recordFailedLoginAttemptAndCloseIfNeeded(sid)) return
                            pendingLogins[sid] = LoginState.AwaitingName
                            promptForName(sid)
                            return
                        }
                        val attemptsRemaining = (maxWrongPasswordRetries + 1) - attempts
                        val msg = "Incorrect password. $attemptsRemaining attempt(s) before returning to login."
                        outbound.send(OutboundEvent.SendError(sid, msg))
                        emitLoginError(sid, "password", msg)
                        pendingLogins[sid] = LoginState.AwaitingExistingPassword(currentState.name, attempts)
                        promptForExistingPassword(sid)
                    }
                    dev.ambon.engine.LoginCredentialPrep.NotFound -> {
                        outbound.send(OutboundEvent.SendError(sid, "Account not found. Please try again."))
                        if (recordFailedLoginAttemptAndCloseIfNeeded(sid)) return
                        pendingLogins[sid] = LoginState.AwaitingName
                        promptForName(sid)
                    }
                    dev.ambon.engine.LoginCredentialPrep.InvalidInput -> {
                        outbound.send(OutboundEvent.SendError(sid, invalidNameMessage))
                        if (recordFailedLoginAttemptAndCloseIfNeeded(sid)) return
                        pendingLogins[sid] = LoginState.AwaitingName
                        promptForName(sid)
                    }
                }
            }

            is PendingAuthResult.NewAccountAuth -> {
                if (pendingLogins[sid] !is LoginState.AwaitingCreateAuth) return
                val createState = pendingLogins[sid] as LoginState.AwaitingCreateAuth
                when (result.prep) {
                    is dev.ambon.engine.CreateAccountPrep.Ready -> {
                        when (players.applyCreateAccount(sid, result.prep.record)) {
                            dev.ambon.engine.CreateResult.Ok -> finalizeSuccessfulLogin(sid)
                            dev.ambon.engine.CreateResult.InvalidName -> {
                                outbound.send(OutboundEvent.SendError(sid, invalidNameMessage))
                                if (recordFailedLoginAttemptAndCloseIfNeeded(sid)) return
                                pendingLogins[sid] = LoginState.AwaitingName
                                promptForName(sid)
                            }
                            dev.ambon.engine.CreateResult.InvalidPassword -> {
                                outbound.send(OutboundEvent.SendError(sid, invalidPasswordMessage))
                                pendingLogins[sid] = LoginState.AwaitingNewPassword(createState.name)
                                promptForNewPassword(sid)
                            }
                            dev.ambon.engine.CreateResult.Taken -> {
                                outbound.send(OutboundEvent.SendError(sid, "That name is already taken."))
                                if (recordFailedLoginAttemptAndCloseIfNeeded(sid)) return
                                pendingLogins[sid] = LoginState.AwaitingName
                                promptForName(sid)
                            }
                        }
                    }
                    dev.ambon.engine.CreateAccountPrep.Taken -> {
                        outbound.send(OutboundEvent.SendError(sid, "That name is already taken."))
                        if (recordFailedLoginAttemptAndCloseIfNeeded(sid)) return
                        pendingLogins[sid] = LoginState.AwaitingName
                        promptForName(sid)
                    }
                    dev.ambon.engine.CreateAccountPrep.InvalidInput -> {
                        outbound.send(OutboundEvent.SendError(sid, invalidNameMessage))
                        if (recordFailedLoginAttemptAndCloseIfNeeded(sid)) return
                        pendingLogins[sid] = LoginState.AwaitingName
                        promptForName(sid)
                    }
                }
            }
        }
    }

    private suspend fun recordFailedLoginAttemptAndCloseIfNeeded(sessionId: SessionId): Boolean {
        val nextAttempts = (failedLoginAttempts[sessionId] ?: 0) + 1
        failedLoginAttempts[sessionId] = nextAttempts
        if (nextAttempts < maxFailedLoginAttemptsBeforeDisconnect) return false

        pendingLogins.remove(sessionId)
        releasePermitIfHeld(sessionId)
        outbound.send(OutboundEvent.Close(sessionId, "Too many failed login attempts."))
        return true
    }

    private suspend fun finalizeSuccessfulLogin(
        sessionId: SessionId,
        suppressEnterBroadcast: Boolean = false,
    ) {
        releasePermitIfHeld(sessionId)
        pendingLogins.remove(sessionId)
        failedLoginAttempts.remove(sessionId)

        val me = players.get(sessionId)
        if (me == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "Internal error: player not initialized"))
            outbound.send(OutboundEvent.Close(sessionId, "Internal error"))
            return
        }

        log.info { "Player logged in: name=${me.name} sessionId=$sessionId" }
        onAfterLogin(sessionId)
        playerLocationIndex?.register(me.name)
        abilitySystem.syncAbilities(sessionId, me.level, me.playerClass)
        outbound.send(OutboundEvent.SetAnsi(sessionId, me.ansiEnabled))
        if (!ensureLoginRoomAvailable(sessionId, suppressEnterBroadcast)) return
        if (!suppressEnterBroadcast) {
            broadcastToRoom(players, outbound, me.roomId, "${me.name} enters.", sessionId)
        }
        gmcpEmitter.sendFullCharacterSync(
            sessionId,
            me,
            items,
            abilitySystem,
            statusEffectSystem,
            achievementRegistry,
            groupSystem,
            players,
            guildSystem,
        )
        router.handle(sessionId, Command.Look)

        val unread = me.inbox.count { !it.read }
        if (unread > 0) {
            outbound.send(
                OutboundEvent.SendInfo(
                    sessionId,
                    "You have $unread unread mail message(s). Type 'mail' to read.",
                ),
            )
        }
    }

    private suspend fun ensureLoginRoomAvailable(
        sessionId: SessionId,
        suppressEnterBroadcast: Boolean,
    ): Boolean {
        val me = players.get(sessionId) ?: return false
        if (world.rooms.containsKey(me.roomId)) return true

        val mgr = handoffManager
        if (mgr != null) {
            when (val handoffResult = mgr.initiateHandoff(sessionId, me.roomId)) {
                is HandoffResult.Initiated -> {
                    log.info {
                        "Player ${me.name} logged into remote room ${me.roomId.value}; " +
                            "handoff initiated to ${handoffResult.targetEngine.engineId}"
                    }
                    return false
                }
                HandoffResult.AlreadyInTransit -> {
                    return false
                }
                HandoffResult.PlayerNotFound -> {
                    outbound.send(
                        OutboundEvent.SendError(sessionId, "Internal error: handoff player missing during login."),
                    )
                    outbound.send(OutboundEvent.Close(sessionId, "Internal error"))
                    return false
                }
                HandoffResult.NoEngineForZone -> {
                    log.warn { "Saved login room ${me.roomId.value} is remote and has no engine owner" }
                }
            }
        } else {
            log.warn { "Saved login room ${me.roomId.value} is unavailable in non-sharded world; relocating to start" }
        }

        players.moveTo(sessionId, world.startRoom)
        val relocated = players.get(sessionId) ?: return false
        if (!suppressEnterBroadcast) {
            broadcastToRoom(players, outbound, relocated.roomId, "${relocated.name} enters.", sessionId)
        }
        outbound.send(
            OutboundEvent.SendError(
                sessionId,
                "Your saved location is unavailable. You have been moved to the starting room.",
            ),
        )
        router.handle(sessionId, Command.Look)
        return false
    }

    private suspend fun promptForExistingPassword(sessionId: SessionId) {
        val name = (pendingLogins[sessionId] as? LoginState.AwaitingExistingPassword)?.name ?: ""
        outbound.send(OutboundEvent.SendInfo(sessionId, "Password:"))
        outbound.send(OutboundEvent.SendPrompt(sessionId))
        emitLoginPrompt(sessionId, mapOf("state" to "password", "name" to name))
    }

    private suspend fun promptForNewPassword(sessionId: SessionId) {
        val name = (pendingLogins[sessionId] as? LoginState.AwaitingNewPassword)?.name ?: ""
        outbound.send(OutboundEvent.SendInfo(sessionId, "Create a password:"))
        outbound.send(OutboundEvent.SendPrompt(sessionId))
        emitLoginPrompt(sessionId, mapOf("state" to "newPassword", "name" to name))
    }

    private suspend fun promptForCreateConfirmation(
        sessionId: SessionId,
        name: String,
    ) {
        outbound.send(OutboundEvent.SendInfo(sessionId, "No user named '$name' was found. Create a new user? (yes/no)"))
        outbound.send(OutboundEvent.SendPrompt(sessionId))
        emitLoginPrompt(sessionId, mapOf("state" to "confirmCreate", "name" to name))
    }

    private suspend fun promptForRaceSelection(sessionId: SessionId) {
        val state = pendingLogins[sessionId] as? LoginState.AwaitingRaceSelection
        outbound.send(OutboundEvent.SendInfo(sessionId, "Choose your race:"))
        for ((index, race) in Race.entries.withIndex()) {
            val s = race.statMods
            val mods =
                buildList {
                    if (s.str != 0) add("STR %+d".format(s.str))
                    if (s.dex != 0) add("DEX %+d".format(s.dex))
                    if (s.con != 0) add("CON %+d".format(s.con))
                    if (s.int != 0) add("INT %+d".format(s.int))
                    if (s.wis != 0) add("WIS %+d".format(s.wis))
                    if (s.cha != 0) add("CHA %+d".format(s.cha))
                }.joinToString(", ")
            val desc = if (mods.isNotEmpty()) " ($mods)" else ""
            outbound.send(OutboundEvent.SendInfo(sessionId, "  ${index + 1}. ${race.displayName}$desc"))
        }
        outbound.send(OutboundEvent.SendPrompt(sessionId))
        emitLoginPrompt(
            sessionId,
            mapOf("state" to "raceSelection", "name" to (state?.name ?: ""), "races" to racePayloads()),
        )
    }

    private suspend fun promptForClassSelection(sessionId: SessionId) {
        val state = pendingLogins[sessionId] as? LoginState.AwaitingClassSelection
        outbound.send(OutboundEvent.SendInfo(sessionId, "Choose your class:"))
        for ((index, pc) in availableClasses.withIndex()) {
            outbound.send(
                OutboundEvent.SendInfo(
                    sessionId,
                    "  ${index + 1}. ${pc.displayName} (+${pc.hpPerLevel} HP/lvl, +${pc.manaPerLevel} Mana/lvl)",
                ),
            )
        }
        outbound.send(OutboundEvent.SendPrompt(sessionId))
        emitLoginPrompt(
            sessionId,
            mapOf(
                "state" to "classSelection",
                "name" to (state?.name ?: ""),
                "race" to (state?.race?.name ?: ""),
                "classes" to classPayloads(),
            ),
        )
    }

    private fun extractLoginName(input: String): String {
        if (input.equals("name", ignoreCase = true)) return ""
        val match = nameCommandRegex.matchEntire(input)
        return match?.groupValues?.get(1)?.trim() ?: input
    }

    // ---------- Login GMCP ----------

    private val loginJson = jacksonObjectMapper()

    private suspend fun emitLoginPrompt(
        sessionId: SessionId,
        payload: Map<String, Any>,
    ) {
        outbound.send(
            OutboundEvent.GmcpData(sessionId, "Login.Prompt", loginJson.writeValueAsString(payload)),
        )
    }

    private suspend fun emitLoginError(
        sessionId: SessionId,
        state: String,
        message: String,
    ) {
        outbound.send(
            OutboundEvent.GmcpData(
                sessionId,
                "Login.Error",
                loginJson.writeValueAsString(mapOf("state" to state, "message" to message)),
            ),
        )
    }

    private fun racePayloads(): List<Map<String, String>> =
        Race.entries.map { race ->
            val s = race.statMods
            val mods =
                buildList {
                    if (s.str != 0) add("STR %+d".format(s.str))
                    if (s.dex != 0) add("DEX %+d".format(s.dex))
                    if (s.con != 0) add("CON %+d".format(s.con))
                    if (s.int != 0) add("INT %+d".format(s.int))
                    if (s.wis != 0) add("WIS %+d".format(s.wis))
                    if (s.cha != 0) add("CHA %+d".format(s.cha))
                }.joinToString(", ")
            mapOf("id" to race.name, "name" to race.displayName, "stats" to mods)
        }

    private fun classPayloads(): List<Map<String, String>> =
        availableClasses.map { pc ->
            mapOf(
                "id" to pc.name,
                "name" to pc.displayName,
                "stats" to "+${pc.hpPerLevel} HP/lvl, +${pc.manaPerLevel} Mana/lvl",
            )
        }
}
