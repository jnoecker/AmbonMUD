package dev.ambon.engine.events

import dev.ambon.domain.ids.SessionId

sealed interface InboundEvent {
    val enqueuedAt: Long

    data class Connected(
        val sessionId: SessionId,
        val defaultAnsiEnabled: Boolean = false,
    ) : InboundEvent {
        override val enqueuedAt: Long = System.currentTimeMillis()
    }

    data class Disconnected(
        val sessionId: SessionId,
        val reason: String,
    ) : InboundEvent {
        override val enqueuedAt: Long = System.currentTimeMillis()
    }

    data class LineReceived(
        val sessionId: SessionId,
        val line: String,
    ) : InboundEvent {
        override val enqueuedAt: Long = System.currentTimeMillis()
    }

    /** Structured GMCP data received from the client. */
    data class GmcpReceived(
        val sessionId: SessionId,
        val gmcpPackage: String,
        val jsonData: String,
    ) : InboundEvent {
        override val enqueuedAt: Long = System.currentTimeMillis()
    }
}
