package dev.ambon.bus

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.InboundEvent
import dev.ambon.grpc.proto.InboundEventProto
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GrpcInboundBusTest {
    private lateinit var delegate: LocalInboundBus
    private lateinit var grpcChannel: Channel<InboundEventProto>
    private lateinit var bus: GrpcInboundBus

    @BeforeEach
    fun setUp() {
        delegate = LocalInboundBus(capacity = 1000)
        grpcChannel = Channel(capacity = 1000)
        bus = GrpcInboundBus(delegate = delegate, grpcSendChannel = grpcChannel)
    }

    @Test
    fun `send forwards event to gRPC channel`() =
        runBlocking {
            val sid = SessionId(1L)
            val event = InboundEvent.Connected(sessionId = sid, defaultAnsiEnabled = true)
            bus.send(event)

            val proto = grpcChannel.tryReceive().getOrNull()
            assertNotNull(proto, "gRPC channel should have received the forwarded proto")
            assertEquals(sid.value, proto!!.sessionId)
        }

    @Test
    fun `trySend forwards event to gRPC channel`() =
        runBlocking {
            val sid = SessionId(2L)
            val event = InboundEvent.LineReceived(sessionId = sid, line = "hello")
            val result = bus.trySend(event)
            assertTrue(result.isSuccess)

            val proto = grpcChannel.tryReceive().getOrNull()
            assertNotNull(proto)
            assertEquals(sid.value, proto!!.sessionId)
        }

    @Test
    fun `send also taps delegate for local tryReceive`() =
        runBlocking {
            val sid = SessionId(3L)
            val event = InboundEvent.Disconnected(sessionId = sid, reason = "bye")
            bus.send(event)

            val localResult = bus.tryReceive().getOrNull()
            assertNotNull(localResult, "Event should also be in the local delegate")
            assertEquals(event, localResult)
        }

    @Test
    fun `Disconnected event is forwarded to gRPC channel`() =
        runBlocking {
            val event = InboundEvent.Disconnected(sessionId = SessionId(5L), reason = "disconnect")
            bus.send(event)

            val proto = grpcChannel.tryReceive().getOrNull()
            assertNotNull(proto)
            assertEquals("disconnect", proto!!.disconnected.reason)
        }

    @Test
    fun `trySend returns failure when gRPC channel is full`() {
        // Rendezvous channel (capacity = 0) — trySend always fails without an active receiver.
        val fullChannel = Channel<InboundEventProto>(capacity = Channel.RENDEZVOUS)
        val busWithFullChannel = GrpcInboundBus(delegate = LocalInboundBus(1000), grpcSendChannel = fullChannel)
        val event = InboundEvent.LineReceived(sessionId = SessionId(99L), line = "backpressure")
        val result = busWithFullChannel.trySend(event)
        assertTrue(result.isFailure, "trySend should return failure when gRPC channel cannot accept")
        fullChannel.close()
    }

    @Test
    fun `implements InboundBus interface`() {
        // Compile-time check — just ensure the type is correct
        val iface: InboundBus = bus
        assertNotNull(iface)
    }
}
