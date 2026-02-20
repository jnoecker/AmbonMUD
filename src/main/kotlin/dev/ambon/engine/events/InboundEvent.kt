package dev.ambon.engine.events

import dev.ambon.domain.ids.SessionId

sealed interface InboundEvent {
    data class Connected(
        val sessionId: SessionId,
        val defaultAnsiEnabled: Boolean = false,
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
