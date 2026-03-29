package dev.ambon.engine

import dev.ambon.domain.ids.SessionId
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import java.util.UUID

private val log = KotlinLogging.logger {}

/**
 * Manages session grace periods for reconnection.
 *
 * When a player disconnects, instead of immediately cleaning up their state,
 * the session enters a grace period. If the player reconnects within the window
 * (using a resume token), their PlayerState is reattached to the new session
 * seamlessly. If the grace period expires, normal disconnect cleanup runs.
 */
class SessionGracePeriodManager(
    private val gracePeriodMs: Long,
    private val clock: Clock,
) {
    data class SuspendedSession(
        val sessionId: SessionId,
        val playerState: PlayerState,
        val token: String,
        val gmcpPackages: Set<String>,
        val expiresAt: Long,
    )

    /** Token → suspended session (populated on disconnect, cleared on resume/expiry). */
    private val byToken = mutableMapOf<String, SuspendedSession>()

    /** Player name (lowercase) → token, for duplicate detection. */
    private val byName = mutableMapOf<String, String>()

    /** Session → pre-issued token, for linking disconnect to a previously issued token. */
    private val preIssuedTokens = mutableMapOf<SessionId, String>()

    val gracePeriodSeconds: Int get() = (gracePeriodMs / 1000).toInt()

    /**
     * Issue a resume token for a session. Called on login/resume so the
     * client has the token before any potential disconnect.
     */
    fun issueToken(sessionId: SessionId): String {
        // Revoke any previous token for this session (e.g. after resume)
        preIssuedTokens[sessionId]?.let { byToken.remove(it) }
        val token = UUID.randomUUID().toString()
        preIssuedTokens[sessionId] = token
        return token
    }

    /**
     * Suspend a session into the grace period using its pre-issued token.
     * Returns true if successful, false if no token was issued or player
     * cannot be suspended.
     */
    fun suspend(
        sessionId: SessionId,
        playerState: PlayerState,
        gmcpPackages: Set<String>,
    ): Boolean {
        if (playerState.playerId == null) return false
        val token = preIssuedTokens.remove(sessionId) ?: return false
        val name = playerState.name.lowercase()

        // If already suspended (rapid reconnect cycle), cancel the old one.
        byName[name]?.let { oldToken ->
            byToken.remove(oldToken)
        }

        val expiresAt = clock.millis() + gracePeriodMs

        byToken[token] = SuspendedSession(
            sessionId = sessionId,
            playerState = playerState,
            token = token,
            gmcpPackages = gmcpPackages,
            expiresAt = expiresAt,
        )
        byName[name] = token

        log.info { "Session suspended: name=${playerState.name} sid=$sessionId grace=${gracePeriodMs}ms" }
        return true
    }

    /**
     * Attempt to resume a suspended session using a token.
     * Returns the [SuspendedSession] if valid, null if expired/invalid.
     */
    fun resume(token: String): SuspendedSession? {
        val suspended = byToken.remove(token) ?: return null
        byName.remove(suspended.playerState.name.lowercase())

        if (clock.millis() > suspended.expiresAt) {
            log.info { "Resume token expired for ${suspended.playerState.name}" }
            return null
        }
        log.info { "Session resumed: name=${suspended.playerState.name}" }
        return suspended
    }

    /**
     * Called every tick to expire grace periods.
     * Returns the list of [SuspendedSession]s that expired so the caller
     * can run normal disconnect cleanup.
     */
    fun expireSessions(): List<SuspendedSession> {
        val now = clock.millis()
        val expired = byToken.values.filter { now > it.expiresAt }
        for (s in expired) {
            byToken.remove(s.token)
            byName.remove(s.playerState.name.lowercase())
            log.info { "Grace period expired: name=${s.playerState.name}" }
        }
        return expired
    }

    /** Check if a player name is currently in the grace period. */
    fun isSuspended(name: String): Boolean = byName.containsKey(name.lowercase())

    /** Cancel a suspended session by name (used when the player logs in via normal flow). */
    fun cancelByName(name: String): SuspendedSession? {
        val token = byName.remove(name.lowercase()) ?: return null
        val suspended = byToken.remove(token)
        suspended?.let { preIssuedTokens.remove(it.sessionId) }
        return suspended
    }

    /** Clean up any pre-issued token for a session (called on full disconnect). */
    fun forgetSession(sessionId: SessionId) {
        preIssuedTokens.remove(sessionId)
    }

    fun size(): Int = byToken.size
}
