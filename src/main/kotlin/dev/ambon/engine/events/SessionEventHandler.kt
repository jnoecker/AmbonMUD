package dev.ambon.engine.events

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.GmcpEmitter
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.PlayerState
import dev.ambon.engine.SessionGracePeriodManager
import dev.ambon.engine.SessionLifecycleCoordinator
import dev.ambon.metrics.GameMetrics
import dev.ambon.sharding.HandoffManager
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

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
    private val gmcpDirtyCombat: MutableSet<SessionId>,
    private val gmcpEmitter: GmcpEmitter?,
    private val handoffManager: HandoffManager?,
    private val removePendingWhoRequestsFor: (SessionId) -> Unit,
    private val sessionLifecycle: SessionLifecycleCoordinator,
    private val gracePeriodManager: SessionGracePeriodManager?,
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
        handoffManager?.cancelIfPending(sessionId)
        removePendingWhoRequestsFor(sessionId)

        // If the player is authenticated and grace period is enabled,
        // suspend the session instead of running full disconnect cleanup.
        if (me != null && me.playerId != null && gracePeriodManager != null) {
            val gmcpPkgs = gmcpSessions.remove(sessionId)?.toSet() ?: emptySet()
            gmcpDirtyVitals.remove(sessionId)
            gmcpDirtyStatusEffects.remove(sessionId)
            gmcpDirtyGroup.remove(sessionId)
            gmcpDirtyCombat.remove(sessionId)
            gmcpEmitter?.forgetSession(sessionId)

            val ps = players.suspendSession(sessionId)
            if (ps != null) {
                gracePeriodManager.suspend(ps.sessionId, ps, gmcpPkgs)
                log.info { "Player ${ps.name} entering grace period (${gracePeriodManager.gracePeriodSeconds}s)" }
                return
            }
        }

        // Normal disconnect (unauthenticated session or grace disabled)
        fullDisconnect(sessionId, me)
    }

    /**
     * Run full disconnect cleanup. Called immediately for unauthenticated
     * sessions, or deferred until grace period expiry for authenticated ones.
     */
    suspend fun fullDisconnect(sessionId: SessionId, playerState: PlayerState?) {
        // These may already have been cleared by the grace period path,
        // but clear them again in case of direct-disconnect or grace expiry.
        gmcpSessions.remove(sessionId)
        gmcpDirtyVitals.remove(sessionId)
        gmcpDirtyStatusEffects.remove(sessionId)
        gmcpDirtyGroup.remove(sessionId)
        gmcpDirtyCombat.remove(sessionId)
        gmcpEmitter?.forgetSession(sessionId)

        sessionLifecycle.onPlayerDisconnected(sessionId)

        if (playerState != null) {
            // Release mob possession and invisibility on disconnect
            playerState.invisible = false
            if (playerState.possessedMobId != null) {
                val returnRoom = playerState.prePossessRoomId ?: playerState.roomId
                playerState.possessedMobId = null
                playerState.prePossessRoomId = null
                if (playerState.roomId != returnRoom) {
                    players.moveTo(sessionId, returnRoom)
                }
            }
            onPlayerLoggedOut(playerState, sessionId)
        }

        players.disconnect(sessionId)
    }
}
