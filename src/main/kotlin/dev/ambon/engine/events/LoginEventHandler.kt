package dev.ambon.engine.events

import dev.ambon.domain.ids.SessionId
import dev.ambon.metrics.GameMetrics

class LoginEventHandler<
    S,
    AN,
    CC,
    EP,
    NP,
    RS,
    CS,
>(
    private val onAwaitingName: suspend (SessionId, String) -> Unit,
    private val onAwaitingCreateConfirmation: suspend (SessionId, String, CC) -> Unit,
    private val onAwaitingExistingPassword: suspend (SessionId, String, EP) -> Unit,
    private val onAwaitingNewPassword: suspend (SessionId, String, NP) -> Unit,
    private val onAwaitingRaceSelection: suspend (SessionId, String, RS) -> Unit,
    private val onAwaitingClassSelection: suspend (SessionId, String, CS) -> Unit,
    private val asAwaitingCreateConfirmation: (S) -> CC?,
    private val asAwaitingExistingPassword: (S) -> EP?,
    private val asAwaitingNewPassword: (S) -> NP?,
    private val asAwaitingRaceSelection: (S) -> RS?,
    private val asAwaitingClassSelection: (S) -> CS?,
    private val isAwaitingName: (S) -> Boolean,
    private val metrics: GameMetrics = GameMetrics.noop(),
) {
    suspend fun onLoginLine(
        sessionId: SessionId,
        line: String,
        state: S,
    ) {
        metrics.onLoginHandlerEvent()
        if (isAwaitingName(state)) {
            onAwaitingName(sessionId, line)
            return
        }

        asAwaitingCreateConfirmation(state)?.let {
            onAwaitingCreateConfirmation(sessionId, line, it)
            return
        }
        asAwaitingExistingPassword(state)?.let {
            onAwaitingExistingPassword(sessionId, line, it)
            return
        }
        asAwaitingNewPassword(state)?.let {
            onAwaitingNewPassword(sessionId, line, it)
            return
        }
        asAwaitingRaceSelection(state)?.let {
            onAwaitingRaceSelection(sessionId, line, it)
            return
        }
        asAwaitingClassSelection(state)?.let {
            onAwaitingClassSelection(sessionId, line, it)
            return
        }
    }
}
