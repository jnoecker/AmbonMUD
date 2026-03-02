package dev.ambon.engine.events

import dev.ambon.domain.ids.SessionId

sealed interface OutboundEvent {
    val sessionId: SessionId

    data class SendText(
        override val sessionId: SessionId,
        val text: String,
    ) : OutboundEvent

    data class SendInfo(
        override val sessionId: SessionId,
        val text: String,
    ) : OutboundEvent

    data class SendError(
        override val sessionId: SessionId,
        val text: String,
    ) : OutboundEvent

    data class SendPrompt(
        override val sessionId: SessionId,
    ) : OutboundEvent

    data class ShowLoginScreen(
        override val sessionId: SessionId,
    ) : OutboundEvent

    data class SetAnsi(
        override val sessionId: SessionId,
        val enabled: Boolean,
    ) : OutboundEvent

    data class Close(
        override val sessionId: SessionId,
        val reason: String,
    ) : OutboundEvent

    data class ClearScreen(
        override val sessionId: SessionId,
    ) : OutboundEvent

    data class ShowAnsiDemo(
        override val sessionId: SessionId,
    ) : OutboundEvent

    /** Redirect a session to a different engine (sent during cross-zone handoff). */
    data class SessionRedirect(
        override val sessionId: SessionId,
        val newEngineId: String,
        val newEngineHost: String,
        val newEnginePort: Int,
    ) : OutboundEvent

    /** Structured GMCP data (package name + raw JSON payload). */
    data class GmcpData(
        override val sessionId: SessionId,
        val gmcpPackage: String,
        val jsonData: String,
    ) : OutboundEvent
}
