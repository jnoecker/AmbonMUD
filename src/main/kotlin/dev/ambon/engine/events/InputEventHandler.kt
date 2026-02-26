package dev.ambon.engine.events

import dev.ambon.domain.ids.SessionId
import dev.ambon.metrics.GameMetrics

class InputEventHandler<LoginState>(
    private val getLoginState: (SessionId) -> LoginState?,
    private val hasActivePlayer: (SessionId) -> Boolean,
    private val isInTransit: (SessionId) -> Boolean,
    private val handleLoginLine: suspend (SessionId, String, LoginState) -> Unit,
    private val onSessionInTransit: suspend (SessionId) -> Unit,
    private val routeCommandLine: suspend (SessionId, String) -> Unit,
    private val metrics: GameMetrics = GameMetrics.noop(),
) {
    suspend fun onLineReceived(
        sessionId: SessionId,
        line: String,
    ) {
        metrics.onInputHandlerEvent()
        val loginState = getLoginState(sessionId)
        if (loginState != null) {
            handleLoginLine(sessionId, line, loginState)
            return
        }

        if (!hasActivePlayer(sessionId)) return

        if (isInTransit(sessionId)) {
            onSessionInTransit(sessionId)
            return
        }

        routeCommandLine(sessionId, line)
    }
}
