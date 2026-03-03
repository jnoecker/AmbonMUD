package dev.ambon.engine.events

import dev.ambon.domain.ids.SessionId

/**
 * Adding a new [InboundEvent] variant? Update these files:
 *  1. [dev.ambon.bus.RedisInboundBus]   — toEnvelope() + toEvent() + Envelope fields + payloadToSign()
 *  2. [dev.ambon.grpc.ProtoMapper]      — toProto() + toDomain() + per-variant proto helper
 *  3. `src/main/proto/ambonmud/v1/events.proto` — add proto message + oneof field
 *  4. Tests: RedisInboundBusTest (round-trip), ProtoMapperTest (round-trip)
 */
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
