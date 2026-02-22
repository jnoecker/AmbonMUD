package dev.ambon.grpc

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.InboundEvent
import dev.ambon.engine.events.OutboundEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ProtoMapperTest {
    private val sid = SessionId(42L)

    // ── InboundEvent round-trips ───────────────────────────────────────────

    @Test
    fun `Connected round-trips`() {
        val event = InboundEvent.Connected(sessionId = sid, defaultAnsiEnabled = true)
        assertEquals(event, event.toProto().toDomain())
    }

    @Test
    fun `Connected with ansi false round-trips`() {
        val event = InboundEvent.Connected(sessionId = sid, defaultAnsiEnabled = false)
        assertEquals(event, event.toProto().toDomain())
    }

    @Test
    fun `Disconnected round-trips`() {
        val event = InboundEvent.Disconnected(sessionId = sid, reason = "timeout")
        assertEquals(event, event.toProto().toDomain())
    }

    @Test
    fun `LineReceived round-trips`() {
        val event = InboundEvent.LineReceived(sessionId = sid, line = "look around")
        assertEquals(event, event.toProto().toDomain())
    }

    @Test
    fun `Empty InboundEventProto toDomain returns null`() {
        val proto = dev.ambon.grpc.proto.InboundEventProto.getDefaultInstance()
        assertNull(proto.toDomain())
    }

    // ── OutboundEvent round-trips ──────────────────────────────────────────

    @Test
    fun `SendText round-trips`() {
        val event = OutboundEvent.SendText(sessionId = sid, text = "You see a goblin.")
        assertEquals(event, event.toProto().toDomain())
    }

    @Test
    fun `SendInfo round-trips`() {
        val event = OutboundEvent.SendInfo(sessionId = sid, text = "Welcome!")
        assertEquals(event, event.toProto().toDomain())
    }

    @Test
    fun `SendError round-trips`() {
        val event = OutboundEvent.SendError(sessionId = sid, text = "Invalid command.")
        assertEquals(event, event.toProto().toDomain())
    }

    @Test
    fun `SendPrompt round-trips`() {
        val event = OutboundEvent.SendPrompt(sessionId = sid)
        assertEquals(event, event.toProto().toDomain())
    }

    @Test
    fun `ShowLoginScreen round-trips`() {
        val event = OutboundEvent.ShowLoginScreen(sessionId = sid)
        assertEquals(event, event.toProto().toDomain())
    }

    @Test
    fun `SetAnsi enabled round-trips`() {
        val event = OutboundEvent.SetAnsi(sessionId = sid, enabled = true)
        assertEquals(event, event.toProto().toDomain())
    }

    @Test
    fun `SetAnsi disabled round-trips`() {
        val event = OutboundEvent.SetAnsi(sessionId = sid, enabled = false)
        assertEquals(event, event.toProto().toDomain())
    }

    @Test
    fun `Close round-trips`() {
        val event = OutboundEvent.Close(sessionId = sid, reason = "kicked")
        assertEquals(event, event.toProto().toDomain())
    }

    @Test
    fun `ClearScreen round-trips`() {
        val event = OutboundEvent.ClearScreen(sessionId = sid)
        assertEquals(event, event.toProto().toDomain())
    }

    @Test
    fun `ShowAnsiDemo round-trips`() {
        val event = OutboundEvent.ShowAnsiDemo(sessionId = sid)
        assertEquals(event, event.toProto().toDomain())
    }

    @Test
    fun `Empty OutboundEventProto toDomain returns null`() {
        val proto = dev.ambon.grpc.proto.OutboundEventProto.getDefaultInstance()
        assertNull(proto.toDomain())
    }

    // ── sessionId preservation ─────────────────────────────────────────────

    @Test
    fun `sessionId is preserved in InboundEvent proto round-trip`() {
        val event = InboundEvent.Connected(sessionId = SessionId(Long.MAX_VALUE))
        val roundTripped = event.toProto().toDomain() as InboundEvent.Connected
        assertEquals(Long.MAX_VALUE, roundTripped.sessionId.value)
    }

    @Test
    fun `sessionId is preserved in OutboundEvent proto round-trip`() {
        val event = OutboundEvent.SendText(sessionId = SessionId(Long.MAX_VALUE), text = "hi")
        val roundTripped = event.toProto().toDomain() as OutboundEvent.SendText
        assertEquals(Long.MAX_VALUE, roundTripped.sessionId.value)
    }
}
