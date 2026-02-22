package dev.ambon.bus

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.grpc.toProto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GrpcOutboundBusTest {
    private lateinit var scope: CoroutineScope
    private lateinit var delegate: LocalOutboundBus
    private lateinit var bus: GrpcOutboundBus

    @BeforeEach
    fun setUp() {
        scope = CoroutineScope(SupervisorJob())
        delegate = LocalOutboundBus(capacity = 1000)
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        bus.stopReceiving()
    }

    @Test
    fun `outbound event from gRPC stream is forwarded to delegate`() =
        runBlocking {
            val sid = SessionId(10L)
            val event = OutboundEvent.SendText(sessionId = sid, text = "from engine")
            val grpcFlow = flow { emit(event.toProto()) }

            bus = GrpcOutboundBus(delegate = delegate, grpcReceiveFlow = grpcFlow, scope = scope)
            bus.startReceiving()
            delay(50)

            val received = bus.tryReceive().getOrNull()
            assertNotNull(received, "Delegate should have received the event from gRPC flow")
            assertEquals(event, received)
        }

    @Test
    fun `multiple events from gRPC stream are all delivered`() =
        runBlocking {
            val sid = SessionId(20L)
            val events =
                listOf(
                    OutboundEvent.SendText(sid, "line 1"),
                    OutboundEvent.SendInfo(sid, "info"),
                    OutboundEvent.SendPrompt(sid),
                )
            val grpcFlow = flow { events.forEach { emit(it.toProto()) } }

            bus = GrpcOutboundBus(delegate = delegate, grpcReceiveFlow = grpcFlow, scope = scope)
            bus.startReceiving()
            delay(50)

            val received = mutableListOf<OutboundEvent>()
            repeat(10) {
                val r = bus.tryReceive()
                if (r.isSuccess) received += r.getOrNull()!!
            }
            assertEquals(events, received)
        }

    @Test
    fun `unknown proto variant is silently dropped`() =
        runBlocking {
            val emptyProto = dev.ambon.grpc.proto.OutboundEventProto.getDefaultInstance()
            val grpcFlow = flow { emit(emptyProto) }

            bus = GrpcOutboundBus(delegate = delegate, grpcReceiveFlow = grpcFlow, scope = scope)
            bus.startReceiving()
            delay(50)

            // Nothing in the delegate
            val result = bus.tryReceive()
            assert(result.isFailure || result.getOrNull() == null)
        }

    @Test
    fun `asReceiveChannel delegates to local bus`() =
        runBlocking {
            bus =
                GrpcOutboundBus(
                    delegate = delegate,
                    grpcReceiveFlow = flow {},
                    scope = scope,
                )
            bus.startReceiving()

            assertNotNull(bus.asReceiveChannel())
        }

    @Test
    fun `implements OutboundBus interface`() {
        bus =
            GrpcOutboundBus(
                delegate = delegate,
                grpcReceiveFlow = flow {},
                scope = scope,
            )
        val iface: OutboundBus = bus
        assertNotNull(iface)
    }

    @Test
    fun `control-plane enqueue failure triggers fatal callback`() =
        runBlocking {
            val fatal = CompletableDeferred<Throwable>()
            val sid = SessionId(30L)
            val fullDelegate = LocalOutboundBus(capacity = Channel.RENDEZVOUS)
            val grpcFlow =
                flow {
                    emit(OutboundEvent.Close(sessionId = sid, reason = "disconnect").toProto())
                    delay(2_000L)
                }

            bus =
                GrpcOutboundBus(
                    delegate = fullDelegate,
                    grpcReceiveFlow = grpcFlow,
                    scope = scope,
                    controlPlaneSendTimeoutMs = 25L,
                    onFatalStreamFailure = { error -> fatal.complete(error) },
                )
            bus.startReceiving()

            val error = withTimeout(1_000L) { fatal.await() }
            assertTrue(error is IllegalStateException)
        }

    @Test
    fun `data-plane enqueue failure is dropped without fatal callback`() =
        runBlocking {
            val fatal = CompletableDeferred<Throwable>()
            val sid = SessionId(31L)
            val fullDelegate = LocalOutboundBus(capacity = Channel.RENDEZVOUS)
            val grpcFlow =
                flow {
                    emit(OutboundEvent.SendText(sessionId = sid, text = "drop").toProto())
                    delay(2_000L)
                }

            bus =
                GrpcOutboundBus(
                    delegate = fullDelegate,
                    grpcReceiveFlow = grpcFlow,
                    scope = scope,
                    onFatalStreamFailure = { error -> fatal.complete(error) },
                )
            bus.startReceiving()

            delay(100L)
            assertFalse(fatal.isCompleted, "Data-plane drops should not trigger fatal callback")
            val result = bus.tryReceive()
            assertTrue(result.isFailure || result.getOrNull() == null)
        }
}
