package dev.ambon.grpc

import dev.ambon.bus.LocalInboundBus
import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.InboundEvent
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * In-process gRPC tests for [EngineServiceImpl].
 *
 * Flows complete naturally (no keep-alive delays) so [toList] can be used.
 * Session-tracking assertions are done after [toList] returns (i.e. after the stream closed).
 */
class EngineServiceImplTest {
    private val serverName = "engine-test-${System.nanoTime()}"
    private lateinit var grpcServer: Server
    private lateinit var inbound: LocalInboundBus
    private lateinit var outbound: LocalOutboundBus
    private lateinit var scope: CoroutineScope
    private lateinit var serviceImpl: EngineServiceImpl
    private lateinit var channel: ManagedChannel

    @BeforeEach
    fun setUp() {
        inbound = LocalInboundBus(capacity = 1000)
        outbound = LocalOutboundBus(capacity = 1000)
        scope = CoroutineScope(SupervisorJob())
        serviceImpl = EngineServiceImpl(inbound = inbound, outbound = outbound, scope = scope)

        grpcServer =
            InProcessServerBuilder
                .forName(serverName)
                .directExecutor()
                .addService(serviceImpl)
                .build()
                .start()

        channel =
            InProcessChannelBuilder
                .forName(serverName)
                .directExecutor()
                .build()
    }

    @AfterEach
    fun tearDown() {
        channel.shutdownNow()
        grpcServer.shutdownNow()
        scope.cancel()
    }

    private fun stub() =
        dev.ambon.grpc.proto.EngineServiceGrpcKt
            .EngineServiceCoroutineStub(channel)

    @Test
    fun `Connected event is forwarded to InboundBus`() =
        runBlocking {
            val sid = SessionId(99L)

            // Flow completes immediately after emitting — stream closes cleanly.
            val job =
                launch {
                    stub()
                        .eventStream(
                            flow {
                                emit(InboundEvent.Connected(sessionId = sid, defaultAnsiEnabled = false).toProto())
                            },
                        ).toList()
                }
            job.join()

            val received = inbound.tryReceive()
            assert(received.isSuccess) { "InboundBus should have received a Connected event" }
            val event = received.getOrNull() as? InboundEvent.Connected
            assertNotNull(event)
            assertEquals(sid, event!!.sessionId)
        }

    @Test
    fun `Connected sets defaultAnsiEnabled correctly`() =
        runBlocking {
            val sid = SessionId(12L)

            val job =
                launch {
                    stub()
                        .eventStream(
                            flow {
                                emit(InboundEvent.Connected(sessionId = sid, defaultAnsiEnabled = true).toProto())
                            },
                        ).toList()
                }
            job.join()

            val event = inbound.tryReceive().getOrNull() as? InboundEvent.Connected
            assertNotNull(event)
            assertEquals(true, event!!.defaultAnsiEnabled)
        }

    @Test
    fun `session is removed from sessionToStream after stream closes`() =
        runBlocking {
            val sid = SessionId(77L)

            // Stream opens and immediately closes — after join, session should be gone.
            val job =
                launch {
                    stub()
                        .eventStream(
                            flow {
                                emit(InboundEvent.Connected(sessionId = sid).toProto())
                                emit(InboundEvent.Disconnected(sessionId = sid, reason = "quit").toProto())
                            },
                        ).toList()
                }
            job.join()

            assertNull(serviceImpl.sessionToStream[sid], "Session should be removed after stream closed")
        }

    @Test
    fun `LineReceived event is forwarded to InboundBus`() =
        runBlocking {
            val sid = SessionId(55L)

            val job =
                launch {
                    stub()
                        .eventStream(
                            flow {
                                emit(InboundEvent.Connected(sessionId = sid).toProto())
                                emit(InboundEvent.LineReceived(sessionId = sid, line = "look").toProto())
                            },
                        ).toList()
                }
            job.join()

            val events = mutableListOf<InboundEvent>()
            repeat(10) {
                val r = inbound.tryReceive()
                if (r.isSuccess) events += r.getOrNull()!!
            }
            val lineEvent = events.filterIsInstance<InboundEvent.LineReceived>().firstOrNull()
            assertNotNull(lineEvent, "Should have received LineReceived event")
            assertEquals("look", lineEvent!!.line)
        }
}
