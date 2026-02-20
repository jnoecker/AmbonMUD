package dev.ambon.engine.events

import dev.ambon.domain.ids.SessionId

sealed interface OutboundEvent {
    data class SendText(
        val sessionId: SessionId,
        val text: String,
    ) : OutboundEvent

    data class SendInfo(
        val sessionId: SessionId,
        val text: String,
    ) : OutboundEvent

    data class SendError(
        val sessionId: SessionId,
        val text: String,
    ) : OutboundEvent

    data class SendPrompt(
        val sessionId: SessionId,
    ) : OutboundEvent

    data class ShowLoginScreen(
        val sessionId: SessionId,
    ) : OutboundEvent

    data class SetAnsi(
        val sessionId: SessionId,
        val enabled: Boolean,
    ) : OutboundEvent

    data class Close(
        val sessionId: SessionId,
        val reason: String,
    ) : OutboundEvent

    data class ClearScreen(
        val sessionId: SessionId,
    ) : OutboundEvent

    data class ShowAnsiDemo(
        val sessionId: SessionId,
    ) : OutboundEvent
}
