package dev.ambon.grpc

import dev.ambon.bus.LocalInboundBus
import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.InboundEvent
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.grpc.proto.OutboundEventProto
import io.grpc.Server
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GrpcOutboundDispatcherTest {
    private val serverName = "dispatcher-test-${System.nanoTime()}"
    private lateinit var grpcServer: Server
    private lateinit var inbound: LocalInboundBus
    private lateinit var outbound: LocalOutboundBus
    private lateinit var scope: CoroutineScope
    private lateinit var serviceImpl: EngineServiceImpl
    private lateinit var dispatcher: GrpcOutboundDispatcher

    @BeforeEach
    fun setUp() {
        inbound = LocalInboundBus(capacity = 1000)
        outbound = LocalOutboundBus(capacity = 1000)
        scope = CoroutineScope(SupervisorJob())
        serviceImpl = EngineServiceImpl(inbound = inbound, outbound = outbound, scope = scope)
        dispatcher =
            GrpcOutboundDispatcher(
                outbound = outbound,
                serviceImpl = serviceImpl,
                scope = scope,
                controlPlaneSendTimeoutMs = 25L,
            )

        grpcServer =
            InProcessServerBuilder
                .forName(serverName)
                .directExecutor()
                .addService(serviceImpl)
                .build()
                .start()

        dispatcher.start()
    }

    @AfterEach
    fun tearDown() {
        dispatcher.stop()
        grpcServer.shutdownNow()
        scope.cancel()
    }

    @Test
    fun `OutboundEvent is delivered to the correct gateway stream`() =
        runBlocking {
            val grpcChannel =
                InProcessChannelBuilder
                    .forName(serverName)
                    .directExecutor()
                    .build()
            val stub =
                dev.ambon.grpc.proto.EngineServiceGrpcKt
                    .EngineServiceCoroutineStub(grpcChannel)
            val sid = SessionId(10L)
            val receivedProtos = Channel<OutboundEventProto>(10)

            // Gateway stream: send Connected then wait for exactly 1 outbound event.
            val gatewayJob =
                launch {
                    stub
                        .eventStream(
                            flow {
                                emit(InboundEvent.Connected(sessionId = sid).toProto())
                                // Keep the inbound flow open long enough for the outbound event to arrive.
                                delay(2000)
                            },
                        ).take(1)
                        .toList()
                        .forEach { proto ->
                            receivedProtos.trySend(proto)
                        }
                }

            try {
                awaitSessionRoute(sid)

                // Engine produces an outbound event.
                outbound.send(OutboundEvent.SendText(sessionId = sid, text = "Hello gateway!"))

                val proto = withTimeout(2_000) { receivedProtos.receive() }
                val event = proto.toDomain()
                assertNotNull(event)
                assertEquals(OutboundEvent.SendText(sessionId = sid, text = "Hello gateway!"), event)

                gatewayJob.join()
            } finally {
                gatewayJob.cancel()
                receivedProtos.close()
                grpcChannel.shutdownNow()
            }
        }

    @Test
    fun `event for unknown session is silently dropped`() =
        runBlocking {
            val unknownSid = SessionId(9999L)
            outbound.send(OutboundEvent.SendText(sessionId = unknownSid, text = "dropped"))
            delay(50)
            // Absence of exception / hang is the assertion
        }

    @Test
    fun `sessionId extension covers all OutboundEvent variants`() {
        val sid = SessionId(1L)
        assertEquals(sid, OutboundEvent.SendText(sid, "").sessionId())
        assertEquals(sid, OutboundEvent.SendInfo(sid, "").sessionId())
        assertEquals(sid, OutboundEvent.SendError(sid, "").sessionId())
        assertEquals(sid, OutboundEvent.SendPrompt(sid).sessionId())
        assertEquals(sid, OutboundEvent.ShowLoginScreen(sid).sessionId())
        assertEquals(sid, OutboundEvent.SetAnsi(sid, true).sessionId())
        assertEquals(sid, OutboundEvent.Close(sid, "").sessionId())
        assertEquals(sid, OutboundEvent.ClearScreen(sid).sessionId())
        assertEquals(sid, OutboundEvent.ShowAnsiDemo(sid).sessionId())
    }

    @Test
    fun `control-plane event on full stream forces synthetic disconnect`() =
        runBlocking {
            val sid = SessionId(5001L)
            val fullStream = Channel<OutboundEventProto>(capacity = 1)
            try {
                serviceImpl.sessionToStream[sid] = fullStream
                fullStream.trySend(OutboundEvent.SendText(sid, "pre-fill").toProto())

                outbound.send(OutboundEvent.Close(sessionId = sid, reason = "bye"))
                delay(100)

                assertNull(serviceImpl.sessionToStream[sid], "Session route should be removed after control-plane failure")
                val events = drainInboundEvents()
                assertNotNull(
                    events.filterIsInstance<InboundEvent.Disconnected>().firstOrNull {
                        it.sessionId == sid && it.reason.startsWith("control-plane-delivery-failed:")
                    },
                    "Expected synthetic disconnect with control-plane failure reason",
                )
            } finally {
                fullStream.close()
            }
        }

    @Test
    fun `data-plane event on full stream is dropped without forced disconnect`() =
        runBlocking {
            val sid = SessionId(5002L)
            val fullStream = Channel<OutboundEventProto>(capacity = 1)
            try {
                serviceImpl.sessionToStream[sid] = fullStream
                fullStream.trySend(OutboundEvent.SendText(sid, "pre-fill").toProto())

                outbound.send(OutboundEvent.SendText(sessionId = sid, text = "drop me"))
                delay(100)

                assertNotNull(serviceImpl.sessionToStream[sid], "Data-plane drop should not remove session route")
                val events = drainInboundEvents()
                val disconnectedForSid = events.filterIsInstance<InboundEvent.Disconnected>().any { it.sessionId == sid }
                assertFalse(disconnectedForSid, "Data-plane drop should not force disconnect")
            } finally {
                fullStream.close()
            }
        }

    private fun drainInboundEvents(limit: Int = 20): List<InboundEvent> {
        val events = mutableListOf<InboundEvent>()
        repeat(limit) {
            val next = inbound.tryReceive().getOrNull() ?: return@repeat
            events += next
        }
        return events
    }

    private suspend fun awaitSessionRoute(
        sid: SessionId,
        timeoutMs: Long = 2_000L,
    ) {
        withTimeout(timeoutMs) {
            while (serviceImpl.sessionToStream[sid] == null) {
                delay(5)
            }
        }
    }
}
