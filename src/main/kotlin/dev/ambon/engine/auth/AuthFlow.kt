package dev.ambon.engine.auth

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.persistence.AccountRecord
import dev.ambon.persistence.AccountRepository
import dev.ambon.persistence.PlayerRecord
import dev.ambon.persistence.PlayerRepository
import dev.ambon.security.PasswordHasher
import kotlinx.coroutines.channels.SendChannel
import java.time.Clock

class AuthFlow(
    private val accounts: AccountRepository,
    private val playersRepo: PlayerRepository,
    private val players: PlayerRegistry,
    private val outbound: SendChannel<OutboundEvent>,
    private val passwordHasher: PasswordHasher,
    private val authRegistry: AuthRegistry,
    private val worldStartRoom: RoomId,
    private val clock: Clock = Clock.systemUTC(),
    private val postAuth: suspend (SessionId) -> Unit = {},
) {
    private var guestCounter = 1L
    private val guestCreateMaxFailures = 5

    suspend fun renderMenu(sessionId: SessionId) {
        authRegistry.set(sessionId, Menu)
        outbound.send(OutboundEvent.SendText(sessionId, "Welcome to AmbonMUD."))
        outbound.send(OutboundEvent.SendText(sessionId, "1) login"))
        outbound.send(OutboundEvent.SendText(sessionId, "2) create"))
        outbound.send(OutboundEvent.SendText(sessionId, "3) guest"))
        outbound.send(OutboundEvent.SendPrompt(sessionId))
    }

    suspend fun handleLine(
        sessionId: SessionId,
        line: String,
    ) {
        when (val state = authRegistry.get(sessionId)) {
            is Authed -> return
            is Unauthed, is Menu -> handleMenu(sessionId, line)
            is LoginUsername -> handleLoginUsername(sessionId, line)
            is LoginPassword -> handleLoginPassword(sessionId, line, state)
            is SignupUsername -> handleSignupUsername(sessionId, line)
            is SignupPassword -> handleSignupPassword(sessionId, line, state)
            is SignupPasswordConfirm -> handleSignupPasswordConfirm(sessionId, line, state)
        }
    }

    private suspend fun handleMenu(
        sessionId: SessionId,
        line: String,
    ) {
        val input = line.trim().lowercase()
        if (input.isEmpty()) {
            outbound.send(OutboundEvent.SendPrompt(sessionId))
            return
        }
        when (input) {
            "1",
            "login",
            -> {
                authRegistry.set(sessionId, LoginUsername)
                prompt(sessionId, "Username:")
            }

            "2",
            "create",
            -> {
                authRegistry.set(sessionId, SignupUsername)
                prompt(sessionId, "Choose a username:")
            }

            "3",
            "guest",
            -> handleGuest(sessionId)

            else -> renderMenu(sessionId)
        }
    }

    private suspend fun handleLoginUsername(
        sessionId: SessionId,
        line: String,
    ) {
        val username = line.trim()
        if (username.isEmpty()) {
            outbound.send(OutboundEvent.SendError(sessionId, "Username cannot be blank."))
            prompt(sessionId, "Username:")
            return
        }
        authRegistry.set(sessionId, LoginPassword(username))
        prompt(sessionId, "Password:")
    }

    private suspend fun handleLoginPassword(
        sessionId: SessionId,
        line: String,
        state: LoginPassword,
    ) {
        val pass = line.trimEnd()
        val usernameLower = state.username.trim().lowercase()
        val account = accounts.findByUsernameLower(usernameLower)
        if (account == null || !passwordHasher.verify(pass, account.passwordHash)) {
            outbound.send(OutboundEvent.SendError(sessionId, "Login failed."))
            renderMenu(sessionId)
            return
        }
        val record = playersRepo.findById(account.playerId)
        if (record == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "Login failed."))
            renderMenu(sessionId)
            return
        }
        val existingOnline = players.findSessionByName(record.name)
        if (existingOnline != null && existingOnline != sessionId) {
            outbound.send(OutboundEvent.SendError(sessionId, "That player is already online."))
            renderMenu(sessionId)
            return
        }
        players.attachExisting(sessionId, record)
        players.setAccountBound(sessionId, true)
        authRegistry.set(sessionId, Authed(record.id))
        postAuth(sessionId)
    }

    private suspend fun handleSignupUsername(
        sessionId: SessionId,
        line: String,
    ) {
        val username = line.trim()
        if (username.isEmpty()) {
            outbound.send(OutboundEvent.SendError(sessionId, "Username cannot be blank."))
            prompt(sessionId, "Choose a username:")
            return
        }
        if (!isValidUsername(username)) {
            outbound.send(OutboundEvent.SendError(sessionId, "Username may only use letters, digits, or _."))
            prompt(sessionId, "Choose a username:")
            return
        }
        val lower = username.lowercase()
        if (accounts.findByUsernameLower(lower) != null) {
            outbound.send(OutboundEvent.SendError(sessionId, "Username already taken."))
            prompt(sessionId, "Choose a username:")
            return
        }
        val existingOnline = players.findSessionByName(username)
        if (existingOnline != null && existingOnline != sessionId) {
            outbound.send(OutboundEvent.SendError(sessionId, "Username already taken."))
            prompt(sessionId, "Choose a username:")
            return
        }
        val existingPlayer = playersRepo.findByName(username)
        if (existingPlayer != null) {
            outbound.send(OutboundEvent.SendError(sessionId, "Username already taken."))
            prompt(sessionId, "Choose a username:")
            return
        }
        authRegistry.set(sessionId, SignupPassword(username))
        prompt(sessionId, "Choose a password:")
    }

    private suspend fun handleSignupPassword(
        sessionId: SessionId,
        line: String,
        state: SignupPassword,
    ) {
        val pass = line.trimEnd()
        if (pass.length < 6) {
            outbound.send(OutboundEvent.SendError(sessionId, "Password must be at least 6 characters."))
            prompt(sessionId, "Choose a password:")
            return
        }
        authRegistry.set(sessionId, SignupPasswordConfirm(state.username, pass))
        prompt(sessionId, "Confirm password:")
    }

    private suspend fun handleSignupPasswordConfirm(
        sessionId: SessionId,
        line: String,
        state: SignupPasswordConfirm,
    ) {
        val confirm = line.trimEnd()
        if (confirm != state.pass1) {
            outbound.send(OutboundEvent.SendError(sessionId, "Passwords do not match."))
            authRegistry.set(sessionId, SignupPassword(state.username))
            prompt(sessionId, "Choose a password:")
            return
        }

        val now = clock.millis()
        val playerRecord =
            try {
                playersRepo.create(state.username, worldStartRoom, now)
            } catch (e: Exception) {
                outbound.send(OutboundEvent.SendError(sessionId, "Username already taken."))
                renderMenu(sessionId)
                return
            }

        val account =
            AccountRecord(
                username = state.username,
                usernameLower = state.username.lowercase(),
                passwordHash = passwordHasher.hash(state.pass1),
                playerId = playerRecord.id,
            )

        try {
            accounts.create(account)
        } catch (e: Exception) {
            try {
                // Best-effort cleanup so failed account writes don't reserve the name.
                playersRepo.delete(playerRecord.id)
            } catch (_: Exception) {
                // Ignore cleanup failures; we'll still report the account error.
            }
            outbound.send(OutboundEvent.SendError(sessionId, "Account creation failed."))
            renderMenu(sessionId)
            return
        }

        players.attachExisting(sessionId, playerRecord)
        players.setAccountBound(sessionId, true)
        authRegistry.set(sessionId, Authed(playerRecord.id))
        postAuth(sessionId)
    }

    private suspend fun handleGuest(sessionId: SessionId) {
        val record =
            try {
                createGuestRecord()
            } catch (_: Exception) {
                outbound.send(OutboundEvent.SendError(sessionId, "Guest login failed."))
                renderMenu(sessionId)
                return
            }
        players.attachExisting(sessionId, record)
        players.setAccountBound(sessionId, false)
        authRegistry.set(sessionId, Authed(record.id))
        postAuth(sessionId)
    }

    private suspend fun createGuestRecord(): PlayerRecord {
        var failures = 0
        var lastError: Exception? = null
        while (failures < guestCreateMaxFailures) {
            val name = "Guest${guestCounter++}"
            if (players.findSessionByName(name) != null) continue
            val now = clock.millis()
            try {
                if (playersRepo.findByName(name) != null) continue
                return playersRepo.create(name, worldStartRoom, now)
            } catch (e: Exception) {
                lastError = e
                failures++
            }
        }
        throw IllegalStateException("Failed to create guest record after $guestCreateMaxFailures attempts.", lastError)
    }

    private suspend fun prompt(
        sessionId: SessionId,
        text: String,
    ) {
        outbound.send(OutboundEvent.SendText(sessionId, text))
        outbound.send(OutboundEvent.SendPrompt(sessionId))
    }

    private fun isValidUsername(name: String): Boolean {
        for (c in name) {
            val ok = c.isLetterOrDigit() || c == '_'
            if (!ok) return false
        }
        return true
    }
}
