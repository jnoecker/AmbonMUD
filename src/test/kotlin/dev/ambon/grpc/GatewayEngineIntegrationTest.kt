package dev.ambon.grpc

import dev.ambon.bus.LocalInboundBus
import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.WorldFactory
import dev.ambon.engine.GameEngine
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerProgression
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.events.InboundEvent
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.scheduler.Scheduler
import dev.ambon.grpc.proto.OutboundEventProto
import dev.ambon.metrics.GameMetrics
import dev.ambon.persistence.InMemoryPlayerRepository
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors

/**
 * Full in-process integration test: gateway stub → gRPC → engine → back to gateway.
 *
 * Wires up:
 *   - Real [GameEngine] running on a single-thread executor
 *   - [EngineServiceImpl] + [GrpcOutboundDispatcher] on the engine side
 *   - In-process gRPC server
 *   - gRPC stub on the "gateway" side that collects outbound events
 *
 * Exercises: connect → login (new player) → say → quit
 */
class GatewayEngineIntegrationTest {
    private val serverName = "integration-test-${System.nanoTime()}"

    private lateinit var engineDispatcher: kotlinx.coroutines.ExecutorCoroutineDispatcher
    private lateinit var scope: CoroutineScope
    private lateinit var engineInbound: LocalInboundBus
    private lateinit var engineOutbound: LocalOutboundBus
    private lateinit var serviceImpl: EngineServiceImpl
    private lateinit var dispatcher: GrpcOutboundDispatcher

    @BeforeEach
    fun setUp() {
        engineDispatcher =
            Executors.newSingleThreadExecutor { r -> Thread(r, "engine-test") }.asCoroutineDispatcher()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        engineInbound = LocalInboundBus(capacity = 10_000)
        engineOutbound = LocalOutboundBus(capacity = 10_000)

        serviceImpl =
            EngineServiceImpl(
                inbound = engineInbound,
                outbound = engineOutbound,
                scope = scope,
            )
        dispatcher =
            GrpcOutboundDispatcher(
                outbound = engineOutbound,
                serviceImpl = serviceImpl,
                scope = scope,
            )

        InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(serviceImpl)
            .build()
            .start()

        dispatcher.start()

        // Start the game engine on its own thread.
        val world = WorldFactory.demoWorld()
        val repo = InMemoryPlayerRepository()
        val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
        val items = ItemRegistry()
        val progression = PlayerProgression()
        val players =
            PlayerRegistry(
                startRoom = world.startRoom,
                repo = repo,
                items = items,
                clock = clock,
                progression = progression,
            )
        val engine =
            GameEngine(
                inbound = engineInbound,
                outbound = engineOutbound,
                players = players,
                world = world,
                mobs = MobRegistry(),
                items = items,
                clock = clock,
                tickMillis = 10L,
                scheduler = Scheduler(clock),
                metrics = GameMetrics.noop(),
            )
        scope.launch(engineDispatcher) { engine.run() }
    }

    @AfterEach
    fun tearDown() {
        dispatcher.stop()
        scope.cancel()
        engineDispatcher.close()
    }

    @Test
    fun `connect login say quit flows through gRPC`() =
        runBlocking {
            val grpcChannel =
                InProcessChannelBuilder.forName(serverName)
                    .directExecutor()
                    .build()
            val stub = dev.ambon.grpc.proto.EngineServiceGrpcKt.EngineServiceCoroutineStub(grpcChannel)
            val sid = SessionId(1L)

            val received = Channel<OutboundEventProto>(Channel.UNLIMITED)

            // Gateway stream: emit inbound events then keep stream open for responses.
            val gatewayJob =
                launch {
                    stub.eventStream(
                        flow {
                            emit(InboundEvent.Connected(sessionId = sid, defaultAnsiEnabled = false).toProto())
                            delay(50)
                            emit(InboundEvent.LineReceived(sessionId = sid, line = "Alice").toProto())
                            delay(50)
                            emit(InboundEvent.LineReceived(sessionId = sid, line = "yes").toProto())
                            delay(50)
                            emit(InboundEvent.LineReceived(sessionId = sid, line = "password").toProto())
                            delay(100)
                            emit(InboundEvent.LineReceived(sessionId = sid, line = "say Hello world!").toProto())
                            delay(50)
                            emit(InboundEvent.LineReceived(sessionId = sid, line = "quit").toProto())
                            delay(500)
                        },
                    ).collect { proto ->
                        received.trySend(proto)
                    }
                }

            // Wait for the full flow to complete (or timeout after 5 seconds).
            val events = mutableListOf<OutboundEvent>()
            withTimeout(5000L) {
                while (events.none { it is OutboundEvent.Close && it.sessionId == sid }) {
                    val proto = received.receive()
                    val event = proto.toDomain()
                    if (event != null) events += event
                }
            }

            assertTrue(
                events.any { it is OutboundEvent.ShowLoginScreen },
                "Expected ShowLoginScreen; got=$events",
            )
            assertTrue(
                events.any { it is OutboundEvent.SendText && it.sessionId == sid },
                "Expected SendText (room description or welcome); got=$events",
            )
            assertTrue(
                events.any { it is OutboundEvent.Close && it.sessionId == sid },
                "Expected Close (from quit); got=$events",
            )

            gatewayJob.cancel()
            grpcChannel.shutdownNow()
        }
}
