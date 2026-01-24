package dev.ambon.engine

import dev.ambon.domain.SessionId

sealed interface InboundEvent {
    data class Connected(
        val sessionId: SessionId,
    ) : InboundEvent

    data class Disconnected(
        val sessionId: SessionId,
        val reason: String,
    ) : InboundEvent

    data class LineReceived(
        val sessionId: SessionId,
        val line: String,
    ) : InboundEvent
}

sealed interface OutboundEvent {
    data class SendText(
        val sessionId: SessionId,
        val text: String,
    ) : OutboundEvent

    data class SendPrompt(
        val sessionId: SessionId,
    ) : OutboundEvent

    data class Close(
        val sessionId: SessionId,
        val reason: String,
    ) : OutboundEvent
}
