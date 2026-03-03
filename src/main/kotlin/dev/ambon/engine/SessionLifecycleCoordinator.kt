package dev.ambon.engine

import dev.ambon.domain.ids.SessionId

/**
 * Centralises per-session lifecycle calls that must fan out to every subsystem.
 *
 * Adding a new subsystem that tracks per-session state? Have it implement
 * [GameSystem] and add it to the [systems] list — the disconnect / remap
 * cascades are updated everywhere at once.
 */
class SessionLifecycleCoordinator(
    private val systems: List<GameSystem>,
) {
    /** Cleans up all per-session state across every subsystem. */
    suspend fun onPlayerDisconnected(sessionId: SessionId) {
        for (system in systems) {
            system.onPlayerDisconnected(sessionId)
        }
    }

    /**
     * Transfers all per-session state from [oldSessionId] to [newSessionId]
     * during a session takeover (same account logging in from a second connection).
     */
    fun remapSession(oldSessionId: SessionId, newSessionId: SessionId) {
        for (system in systems) {
            system.remapSession(oldSessionId, newSessionId)
        }
    }
}
