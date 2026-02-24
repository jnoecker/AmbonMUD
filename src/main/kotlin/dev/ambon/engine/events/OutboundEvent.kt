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

    /** Redirect a session to a different engine (sent during cross-zone handoff). */
    data class SessionRedirect(
        val sessionId: SessionId,
        val newEngineId: String,
        val newEngineHost: String,
        val newEnginePort: Int,
    ) : OutboundEvent

    /** Structured GMCP data (package name + raw JSON payload). */
    data class GmcpData(
        val sessionId: SessionId,
        val gmcpPackage: String,
        val jsonData: String,
    ) : OutboundEvent
}
