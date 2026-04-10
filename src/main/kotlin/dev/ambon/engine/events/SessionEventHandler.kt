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
    /** Sessions for which [fullDisconnect] has already run, guarding against double-cleanup. */
    private val fullyDisconnected = mutableSetOf<SessionId>()

    /** Removes all GMCP tracking state for [sessionId]. */
    private fun clearGmcpState(sessionId: SessionId) {
        gmcpSessions.remove(sessionId)
        gmcpDirtyVitals.remove(sessionId)
        gmcpDirtyStatusEffects.remove(sessionId)
        gmcpDirtyGroup.remove(sessionId)
        gmcpDirtyCombat.remove(sessionId)
        gmcpEmitter?.forgetSession(sessionId)
    }

    suspend fun onConnected(
        sessionId: SessionId,
        defaultAnsiEnabled: Boolean,
    ) {
        metrics.onSessionHandlerEvent()
        // A new connection resets the idempotency guard so the session ID
        // can go through a full disconnect cycle if it disconnects again.
        fullyDisconnected.remove(sessionId)
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
            val gmcpPkgs = gmcpSessions[sessionId]?.toSet() ?: emptySet()
            clearGmcpState(sessionId)

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
     *
     * This method is idempotent: if cleanup has already run for [sessionId],
     * the call is a no-op.  This guards against double-disconnect when a
     * grace-period cancellation and expiry race within the same tick.
     */
    suspend fun fullDisconnect(sessionId: SessionId, playerState: PlayerState?) {
        if (!fullyDisconnected.add(sessionId)) {
            log.debug { "fullDisconnect already ran for $sessionId — skipping" }
            return
        }

        // These may already have been cleared by the grace period path,
        // but clear them again in case of direct-disconnect or grace expiry.
        clearGmcpState(sessionId)

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

        // If the player is still in the active map, use normal disconnect.
        // If they were already removed by suspendSession(), use the suspended
        // variant that cleans up residual entries (roomMembers, name index).
        if (players.get(sessionId) != null) {
            players.disconnect(sessionId)
        } else if (playerState != null) {
            players.disconnectSuspended(sessionId, playerState)
        }
    }
}
