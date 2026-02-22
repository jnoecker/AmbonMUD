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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
        dispatcher = GrpcOutboundDispatcher(outbound = outbound, serviceImpl = serviceImpl, scope = scope)

        grpcServer =
            InProcessServerBuilder.forName(serverName)
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
                InProcessChannelBuilder.forName(serverName)
                    .directExecutor()
                    .build()
            val stub = dev.ambon.grpc.proto.EngineServiceGrpcKt.EngineServiceCoroutineStub(grpcChannel)
            val sid = SessionId(10L)
            val receivedProtos = Channel<OutboundEventProto>(10)

            // Gateway stream: send Connected then wait for exactly 1 outbound event.
            val gatewayJob =
                launch {
                    stub.eventStream(
                        flow {
                            emit(InboundEvent.Connected(sessionId = sid).toProto())
                            // Keep the inbound flow open long enough for the outbound event to arrive.
                            delay(2000)
                        },
                    ).take(1).toList().forEach { proto ->
                        receivedProtos.trySend(proto)
                    }
                }

            // Wait for the Connected event to be processed and the session registered.
            delay(100)

            // Engine produces an outbound event.
            outbound.send(OutboundEvent.SendText(sessionId = sid, text = "Hello gateway!"))

            // Wait for the event to be dispatched to the stream and received.
            delay(100)

            val proto = receivedProtos.tryReceive().getOrNull()
            assertNotNull(proto, "Gateway stream should have received the outbound event")
            val event = proto!!.toDomain()
            assertNotNull(event)
            assertEquals(OutboundEvent.SendText(sessionId = sid, text = "Hello gateway!"), event)

            gatewayJob.join()
            grpcChannel.shutdownNow()
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
}
