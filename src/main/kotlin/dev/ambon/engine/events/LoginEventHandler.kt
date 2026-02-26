package dev.ambon.engine.events

import dev.ambon.domain.ids.SessionId
import dev.ambon.metrics.GameMetrics

class LoginEventHandler<
    LoginState,
    AwaitingName,
    AwaitingCreateConfirmation,
    AwaitingExistingPassword,
    AwaitingNewPassword,
    AwaitingRaceSelection,
    AwaitingClassSelection,
>(
    private val onAwaitingName: suspend (SessionId, String) -> Unit,
    private val onAwaitingCreateConfirmation: suspend (SessionId, String, AwaitingCreateConfirmation) -> Unit,
    private val onAwaitingExistingPassword: suspend (SessionId, String, AwaitingExistingPassword) -> Unit,
    private val onAwaitingNewPassword: suspend (SessionId, String, AwaitingNewPassword) -> Unit,
    private val onAwaitingRaceSelection: suspend (SessionId, String, AwaitingRaceSelection) -> Unit,
    private val onAwaitingClassSelection: suspend (SessionId, String, AwaitingClassSelection) -> Unit,
    private val asAwaitingCreateConfirmation: (LoginState) -> AwaitingCreateConfirmation?,
    private val asAwaitingExistingPassword: (LoginState) -> AwaitingExistingPassword?,
    private val asAwaitingNewPassword: (LoginState) -> AwaitingNewPassword?,
    private val asAwaitingRaceSelection: (LoginState) -> AwaitingRaceSelection?,
    private val asAwaitingClassSelection: (LoginState) -> AwaitingClassSelection?,
    private val isAwaitingName: (LoginState) -> Boolean,
    private val metrics: GameMetrics = GameMetrics.noop(),
) {
    suspend fun onLoginLine(
        sessionId: SessionId,
        line: String,
        state: LoginState,
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
