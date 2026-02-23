package dev.ambon.grpc

import dev.ambon.bus.GrpcOutboundBus
import dev.ambon.bus.GrpcOutboundFailure
import dev.ambon.bus.LocalInboundBus
import dev.ambon.bus.LocalOutboundBus
import dev.ambon.config.GatewayReconnectConfig
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
import dev.ambon.gateway.computeBackoffDelay
import dev.ambon.grpc.proto.InboundEventProto
import dev.ambon.grpc.proto.OutboundEventProto
import dev.ambon.metrics.GameMetrics
import dev.ambon.persistence.InMemoryPlayerRepository
import io.grpc.Server
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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

    private lateinit var grpcServer: Server
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

        grpcServer =
            InProcessServerBuilder
                .forName(serverName)
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
        grpcServer.shutdownNow()
        scope.cancel()
        engineDispatcher.close()
    }

    @Test
    fun `connect login say quit flows through gRPC`() =
        runBlocking {
            val grpcChannel =
                InProcessChannelBuilder
                    .forName(serverName)
                    .directExecutor()
                    .build()
            val stub =
                dev.ambon.grpc.proto.EngineServiceGrpcKt
                    .EngineServiceCoroutineStub(grpcChannel)
            val sid = SessionId(1L)

            val received = Channel<OutboundEventProto>(Channel.UNLIMITED)

            // Gateway stream: emit inbound events then keep stream open for responses.
            val gatewayJob =
                launch {
                    stub
                        .eventStream(
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

            try {
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
            } finally {
                gatewayJob.cancel()
                received.close()
                grpcChannel.shutdownNow()
            }
        }

    // ── Reconnect: backoff delay computation ────────────────────────────────────

    @Test
    fun `backoff delay computation`() {
        val cfg =
            GatewayReconnectConfig(
                maxAttempts = 10,
                initialDelayMs = 1_000L,
                maxDelayMs = 30_000L,
                jitterFactor = 0.2,
                streamVerifyMs = 2_000L,
            )

        // Neutral jitter (random = 0.5 → multiplier = 1.0): pure exponential growth.
        assertEquals(1_000L, computeBackoffDelay(0, cfg, 0.5))
        assertEquals(2_000L, computeBackoffDelay(1, cfg, 0.5))
        assertEquals(4_000L, computeBackoffDelay(2, cfg, 0.5))
        assertEquals(8_000L, computeBackoffDelay(3, cfg, 0.5))
        assertEquals(16_000L, computeBackoffDelay(4, cfg, 0.5))

        // Max-delay cap.
        assertEquals(30_000L, computeBackoffDelay(10, cfg, 0.5))
        assertEquals(30_000L, computeBackoffDelay(50, cfg, 0.5))

        // Jitter range at attempt 0 (base = 1000ms, jitterFactor = 0.2).
        // random = 0.0 → multiplier = 1 + 0.2 * (0.0 * 2 - 1) = 0.8 → 800ms
        assertEquals(800L, computeBackoffDelay(0, cfg, 0.0))
        // random = 1.0 → multiplier = 1 + 0.2 * (1.0 * 2 - 1) = 1.2 → 1200ms
        assertEquals(1_200L, computeBackoffDelay(0, cfg, 1.0))
    }

    // ── Reconnect: GrpcOutboundBus reattach ─────────────────────────────────────

    @Test
    fun `GrpcOutboundBus reattach delivers events from new flow`() =
        runBlocking {
            val busScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val failureLatch = Channel<Throwable>(1)
            val delegate = LocalOutboundBus(capacity = 100)
            var workingChannel: Channel<OutboundEventProto>? = null

            // Flow that throws immediately (simulates a broken stream).
            val failingFlow =
                flow<dev.ambon.grpc.proto.OutboundEventProto> {
                    throw IllegalStateException("Simulated stream failure")
                }

            val bus =
                GrpcOutboundBus(
                    delegate = delegate,
                    grpcReceiveFlow = failingFlow,
                    scope = busScope,
                    onFailure = { failure ->
                        if (failure is GrpcOutboundFailure.StreamFailure) {
                            failureLatch.trySend(failure.cause)
                        }
                    },
                )
            bus.startReceiving()

            try {
                // Confirm stream-failure callback is called.
                withTimeout(2_000L) { failureLatch.receive() }

                // Replace with a working flow backed by a channel.
                val ch = Channel<OutboundEventProto>(10)
                workingChannel = ch
                bus.reattach(ch.receiveAsFlow())

                // Push a ShowLoginScreen event through the new flow.
                val proto = OutboundEvent.ShowLoginScreen(SessionId(1L)).toProto()
                ch.send(proto)

                // Verify the event reaches the delegate.
                withTimeout(2_000L) {
                    while (delegate.tryReceive().isFailure) {
                        delay(10)
                    }
                }
            } finally {
                busScope.cancel()
                workingChannel?.close()
            }
        }

    // ── Reconnect: end-to-end stream loss and recovery ──────────────────────────

    @Test
    fun `reconnects after engine stream loss`() =
        runBlocking {
            var newGrpcServer: Server? = null
            val busScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val grpcChannel =
                    InProcessChannelBuilder
                        .forName(serverName)
                        .directExecutor()
                        .build()
                val stub =
                    dev.ambon.grpc.proto.EngineServiceGrpcKt
                        .EngineServiceCoroutineStub(grpcChannel)
                val sid = SessionId(2L)
                val failureLatch = Channel<Throwable>(1)
                val delegate = LocalOutboundBus(capacity = 1_000)

                // Open initial bidi stream.
                val inboundChannel = Channel<InboundEventProto>(100)
                val outboundFlow = stub.eventStream(inboundChannel.receiveAsFlow())

                val bus =
                    GrpcOutboundBus(
                        delegate = delegate,
                        grpcReceiveFlow = outboundFlow,
                        scope = busScope,
                        onFailure = { failure ->
                            if (failure is GrpcOutboundFailure.StreamFailure) {
                                failureLatch.trySend(failure.cause)
                            }
                        },
                    )
                bus.startReceiving()

                // Send a Connected event so the engine produces at least one outbound event.
                inboundChannel.send(
                    InboundEvent.Connected(sessionId = sid, defaultAnsiEnabled = false).toProto(),
                )

                // Wait for at least one outbound event — confirms stream is live.
                withTimeout(3_000L) {
                    while (delegate.tryReceive().isFailure) {
                        delay(10)
                    }
                }

                // Simulate engine death: shut down the gRPC server and close inbound.
                grpcServer.shutdownNow()
                grpcServer.awaitTermination(2L, TimeUnit.SECONDS)
                inboundChannel.close()

                // Verify stream-failure callback fires.
                withTimeout(3_000L) { failureLatch.receive() }

                // Restart the gRPC server with the same in-process name.
                newGrpcServer =
                    InProcessServerBuilder
                        .forName(serverName)
                        .directExecutor()
                        .addService(serviceImpl)
                        .build()
                        .start()

                // Create a new bidi stream on the still-open managed channel.
                val newInboundChannel = Channel<InboundEventProto>(100)
                val newOutboundFlow = stub.eventStream(newInboundChannel.receiveAsFlow())

                // Reattach the bus to the new stream.
                bus.reattach(newOutboundFlow)

                // Send another Connected event for a new session and verify events flow.
                val sid2 = SessionId(3L)
                newInboundChannel.send(
                    InboundEvent.Connected(sessionId = sid2, defaultAnsiEnabled = false).toProto(),
                )
                withTimeout(3_000L) {
                    while (delegate.tryReceive().isFailure) {
                        delay(10)
                    }
                }

                newInboundChannel.close()
                grpcChannel.shutdownNow()
            } finally {
                busScope.cancel()
                newGrpcServer?.shutdownNow()
            }
        }
}
