package dev.ambon.engine.events

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.PlayerState
import dev.ambon.engine.SessionLifecycleCoordinator
import dev.ambon.metrics.GameMetrics
import dev.ambon.sharding.HandoffManager

class SessionEventHandler(
    private val players: PlayerRegistry,
    private val markAwaitingName: (SessionId) -> Unit,
    private val clearLoginState: (SessionId) -> Unit,
    private val failedLoginAttempts: MutableMap<SessionId, Int>,
    private val sessionAnsiDefaults: MutableMap<SessionId, Boolean>,
    private val gmcpSessions: MutableMap<SessionId, MutableSet<String>>,
    private val gmcpDirtyVitals: MutableSet<SessionId>,
    private val gmcpDirtyStatusEffects: MutableSet<SessionId>,
    private val gmcpDirtyGroup: MutableSet<SessionId>,
    private val handoffManager: HandoffManager?,
    private val removePendingWhoRequestsFor: (SessionId) -> Unit,
    private val sessionLifecycle: SessionLifecycleCoordinator,
    private val promptForName: suspend (SessionId) -> Unit,
    private val showLoginScreen: suspend (SessionId) -> Unit,
    private val onPlayerLoggedOut: suspend (PlayerState, SessionId) -> Unit,
    private val metrics: GameMetrics = GameMetrics.noop(),
) {
    suspend fun onConnected(
        sessionId: SessionId,
        defaultAnsiEnabled: Boolean,
    ) {
        metrics.onSessionHandlerEvent()
        markAwaitingName(sessionId)
        failedLoginAttempts[sessionId] = 0
        sessionAnsiDefaults[sessionId] = defaultAnsiEnabled
        showLoginScreen(sessionId)
        promptForName(sessionId)
    }

    suspend fun onDisconnected(sessionId: SessionId) {
        metrics.onSessionHandlerEvent()
        val me = players.get(sessionId)

        clearLoginState(sessionId)
        failedLoginAttempts.remove(sessionId)
        sessionAnsiDefaults.remove(sessionId)
        gmcpSessions.remove(sessionId)
        gmcpDirtyVitals.remove(sessionId)
        handoffManager?.cancelIfPending(sessionId)
        removePendingWhoRequestsFor(sessionId)

        sessionLifecycle.onPlayerDisconnected(sessionId)
        gmcpDirtyStatusEffects.remove(sessionId)
        gmcpDirtyGroup.remove(sessionId)

        if (me != null) {
            onPlayerLoggedOut(me, sessionId)
        }

        players.disconnect(sessionId)
    }
}
