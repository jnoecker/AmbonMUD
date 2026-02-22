package dev.ambon.gateway

import dev.ambon.bus.GrpcInboundBus
import dev.ambon.bus.GrpcOutboundBus
import dev.ambon.bus.GrpcOutboundFailure
import dev.ambon.bus.InboundBus
import dev.ambon.bus.LocalInboundBus
import dev.ambon.bus.LocalOutboundBus
import dev.ambon.bus.OutboundBus
import dev.ambon.config.AppConfig
import dev.ambon.config.GatewayReconnectConfig
import dev.ambon.grpc.proto.EngineServiceGrpcKt
import dev.ambon.grpc.proto.InboundEventProto
import dev.ambon.metrics.GameMetrics
import dev.ambon.redis.RedisConnectionManager
import dev.ambon.session.GatewayIdLeaseManager
import dev.ambon.session.SessionIdFactory
import dev.ambon.session.SnowflakeSessionIdFactory
import dev.ambon.transport.BlockingSocketTransport
import dev.ambon.transport.KtorWebSocketTransport
import dev.ambon.transport.OutboundRouter
import io.github.oshai.kotlinlogging.KotlinLogging
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.lettuce.core.SetArgs
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

private val log = KotlinLogging.logger {}

/**
 * Gateway-mode composition root.
 *
 * Starts the transports (telnet/WebSocket) and a gRPC client connection to the engine.
 * No local game engine or persistence — all game logic runs remotely.
 *
 * When the engine gRPC stream dies, [attemptReconnect] performs bounded exponential-backoff
 * reconnect. New sessions are blocked (inbound channel closed) during the reconnect window
 * and re-enabled once the new stream has been verified healthy.
 */
class GatewayServer(
    private val config: AppConfig,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val reconnecting = AtomicBoolean(false)

    private val prometheusRegistry: PrometheusMeterRegistry? =
        if (config.observability.metricsEnabled) {
            PrometheusMeterRegistry(PrometheusConfig.DEFAULT).also { reg ->
                reg.config().commonTags("service", "ambonmud-gateway")
                config.observability.staticTags.forEach { (k, v) -> reg.config().commonTags(k, v) }
            }
        } else {
            null
        }

    private val gameMetrics: GameMetrics =
        if (prometheusRegistry != null) GameMetrics(prometheusRegistry) else GameMetrics.noop()

    private val instanceId: String =
        config.redis.bus.instanceId.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()

    private val redisManager: RedisConnectionManager? =
        if (config.redis.enabled) RedisConnectionManager(config.redis) else null

    private val sessionIdFactory: SessionIdFactory =
        SnowflakeSessionIdFactory(
            gatewayId = config.gateway.id,
            onSequenceOverflow = gameMetrics::onSessionIdSequenceOverflow,
            onClockRollback = gameMetrics::onSessionIdClockRollback,
        )

    private val grpcManagedChannel: ManagedChannel =
        ManagedChannelBuilder
            .forAddress(config.grpc.client.engineHost, config.grpc.client.enginePort)
            .usePlaintext()
            .build()

    private val engineStub = EngineServiceGrpcKt.EngineServiceCoroutineStub(grpcManagedChannel)

    // Channel that the GrpcInboundBus sends inbound proto events into; collected by the gRPC stream.
    // Declared as var so the reconnect loop can swap in a new channel after the old one is closed.
    private var inboundProtoChannel = Channel<InboundEventProto>(capacity = config.server.inboundChannelCapacity)

    private lateinit var grpcInboundBus: GrpcInboundBus
    private lateinit var grpcOutboundBus: GrpcOutboundBus
    private val inbound: InboundBus get() = grpcInboundBus
    private val outbound: OutboundBus get() = grpcOutboundBus

    private lateinit var outboundRouter: OutboundRouter
    private lateinit var telnetTransport: BlockingSocketTransport
    private lateinit var webTransport: KtorWebSocketTransport

    private var leaseManager: GatewayIdLeaseManager? = null

    private val streamLostDisconnectReason = "Disconnected: engine connection lost. Please reconnect."
    private val localControlDeliveryDisconnectReason = "Disconnected: gateway outbound queue stalled."

    suspend fun start() {
        redisManager?.connect()

        val cmds = redisManager?.commands
        if (config.redis.enabled) {
            if (cmds != null) {
                val mgr =
                    GatewayIdLeaseManager(
                        gatewayId = config.gateway.id,
                        instanceId = instanceId,
                        leaseTtlSeconds = config.gateway.snowflake.idLeaseTtlSeconds,
                        setNxEx = { key, value, ttl ->
                            cmds.set(key, value, SetArgs.Builder.nx().ex(ttl)) == "OK"
                        },
                        getValue = { key -> cmds.get(key) },
                        deleteKey = { key -> cmds.del(key) },
                    )
                leaseManager = mgr
                if (mgr.tryAcquire()) {
                    log.info { "Acquired gateway.id lease for gateway=${config.gateway.id}" }
                } else {
                    throw IllegalStateException(
                        "Duplicate gateway.id detected: gateway=${config.gateway.id} " +
                            "is already claimed by ${mgr.currentOwner()}",
                    )
                }
            } else {
                log.warn {
                    "Redis enabled but connection unavailable — gateway.id duplicate detection skipped"
                }
            }
        } else {
            log.warn { "Redis not enabled — gateway.id duplicate detection unavailable" }
        }

        // Open the bidi gRPC stream to the engine.
        val outboundFlow = engineStub.eventStream(inboundProtoChannel.receiveAsFlow())

        grpcInboundBus =
            GrpcInboundBus(
                delegate = LocalInboundBus(capacity = config.server.inboundChannelCapacity),
                grpcSendChannel = inboundProtoChannel,
            )

        grpcOutboundBus =
            GrpcOutboundBus(
                delegate = LocalOutboundBus(capacity = config.server.outboundChannelCapacity),
                grpcReceiveFlow = outboundFlow,
                scope = scope,
                metrics = gameMetrics,
                onFailure = { failure ->
                    when (failure) {
                        is GrpcOutboundFailure.StreamFailure -> {
                            if (reconnecting.compareAndSet(false, true)) {
                                log.warn(failure.cause) { "Engine gRPC stream lost; scheduling reconnect" }
                                scope.launch { handleStreamFailure() }
                            }
                        }

                        is GrpcOutboundFailure.ControlPlaneDeliveryFailure -> {
                            log.warn {
                                "Gateway local queue stalled for control-plane ${failure.eventType} " +
                                    "(session=${failure.sessionId}, reason=${failure.reason}); disconnecting session"
                            }
                            scope.launch {
                                outboundRouter.forceDisconnect(
                                    sessionId = failure.sessionId,
                                    reason = localControlDeliveryDisconnectReason,
                                )
                            }
                        }
                    }
                },
            )

        outboundRouter = OutboundRouter(engineOutbound = grpcOutboundBus, scope = scope, metrics = gameMetrics)
        scope.launch { outboundRouter.start() }
        grpcOutboundBus.startReceiving()

        telnetTransport =
            BlockingSocketTransport(
                port = config.server.telnetPort,
                inbound = grpcInboundBus,
                outboundRouter = outboundRouter,
                sessionIdFactory = sessionIdFactory::allocate,
                scope = scope,
                sessionOutboundQueueCapacity = config.server.sessionOutboundQueueCapacity,
                maxLineLen = config.transport.telnet.maxLineLen,
                maxNonPrintablePerLine = config.transport.telnet.maxNonPrintablePerLine,
                maxInboundBackpressureFailures = config.transport.maxInboundBackpressureFailures,
                metrics = gameMetrics,
            )
        telnetTransport.start()
        log.info { "Gateway telnet transport bound on port ${config.server.telnetPort}" }

        webTransport =
            KtorWebSocketTransport(
                port = config.server.webPort,
                inbound = grpcInboundBus,
                outboundRouter = outboundRouter,
                sessionIdFactory = sessionIdFactory::allocate,
                host = config.transport.websocket.host,
                sessionOutboundQueueCapacity = config.server.sessionOutboundQueueCapacity,
                stopGraceMillis = config.transport.websocket.stopGraceMillis,
                stopTimeoutMillis = config.transport.websocket.stopTimeoutMillis,
                maxLineLen = config.transport.telnet.maxLineLen,
                maxNonPrintablePerLine = config.transport.telnet.maxNonPrintablePerLine,
                maxInboundBackpressureFailures = config.transport.maxInboundBackpressureFailures,
                prometheusRegistry = prometheusRegistry,
                metricsEndpoint = config.observability.metricsEndpoint,
                metrics = gameMetrics,
            )
        webTransport.start()
        log.info {
            "Gateway WebSocket transport bound on ${config.transport.websocket.host}:${config.server.webPort}"
        }
        log.info {
            "Gateway connected to engine at ${config.grpc.client.engineHost}:${config.grpc.client.enginePort}"
        }
    }

    suspend fun stop() {
        runCatching { telnetTransport.stop() }
        runCatching { webTransport.stop() }
        runCatching { grpcOutboundBus.stopReceiving() }
        runCatching { inboundProtoChannel.close() }
        runCatching { grpcManagedChannel.shutdown() }
        runCatching { leaseManager?.release() }
        runCatching { redisManager?.close() }
        scope.cancel()
        log.info { "Gateway server stopped" }
    }

    private suspend fun handleStreamFailure() {
        val disconnected = outboundRouter.disconnectAll(streamLostDisconnectReason)
        if (disconnected > 0) {
            log.info { "Disconnected $disconnected local session(s) after engine stream loss" }
        }
        attemptReconnect()
    }

    /**
     * Exponential-backoff reconnect loop.
     *
     * 1. Closes the old inbound channel — new-session attempts via [grpcInboundBus] fail
     *    immediately, so the transport disconnects them rather than silently queuing events.
     * 2. Loops up to [GatewayReconnectConfig.maxAttempts], each time:
     *    - waiting the computed backoff delay
     *    - opening a new bidi gRPC stream
     *    - reattaching [grpcOutboundBus] to the new stream
     *    - waiting up to [GatewayReconnectConfig.streamVerifyMs] to see if the stream stays alive
     * 3. On success: reattaches [grpcInboundBus], resets [reconnecting], returns.
     * 4. On budget exhaustion: logs and calls [stop].
     */
    private suspend fun attemptReconnect() {
        log.info { "Starting gateway reconnect sequence" }
        inboundProtoChannel.close()
        val reconnectConfig = config.gateway.reconnect
        for (attempt in 0 until reconnectConfig.maxAttempts) {
            val delayMs = computeBackoffDelay(attempt, reconnectConfig)
            log.info { "Reconnect attempt ${attempt + 1}/${reconnectConfig.maxAttempts} in ${delayMs}ms" }
            gameMetrics.onGatewayReconnectAttempt()
            delay(delayMs)
            val newChannel = Channel<InboundEventProto>(capacity = config.server.inboundChannelCapacity)
            val newFlow = engineStub.eventStream(newChannel.receiveAsFlow())
            grpcOutboundBus.reattach(newFlow)
            // Wait up to streamVerifyMs for the receiver to die (stream failure) or stay alive (healthy).
            val receiverDied =
                withTimeoutOrNull(reconnectConfig.streamVerifyMs) {
                    grpcOutboundBus.awaitReceiverCompletion()
                }
            if (receiverDied == null) {
                // Timeout elapsed — receiver still running, stream is healthy.
                inboundProtoChannel = newChannel
                grpcInboundBus.reattach(newChannel)
                log.info { "Gateway reconnected to engine on attempt ${attempt + 1}" }
                gameMetrics.onGatewayReconnectSuccess()
                reconnecting.set(false)
                return
            }
            log.warn { "New gRPC stream failed immediately on attempt ${attempt + 1}" }
            newChannel.close()
        }
        log.error { "Reconnect budget exhausted after ${reconnectConfig.maxAttempts} attempts; stopping gateway" }
        gameMetrics.onGatewayReconnectBudgetExhausted()
        scope.launch { stop() }
    }
}

/**
 * Computes the reconnect backoff delay for [attempt] (0-based).
 *
 * Formula: `min(initialDelayMs * 2^attempt, maxDelayMs) * (1 + jitterFactor * [-1, +1])`.
 *
 * @param attempt 0-based attempt index (capped at 30 to prevent Long overflow).
 * @param config  reconnect config providing the delay bounds and jitter factor.
 * @param random  a value in [0.0, 1.0] used to compute jitter; defaults to a random value.
 *                Pass a fixed value in tests for deterministic results.
 */
fun computeBackoffDelay(
    attempt: Int,
    config: GatewayReconnectConfig,
    random: Double = Random.nextDouble(),
): Long {
    val cappedAttempt = attempt.coerceAtMost(30)
    val base = minOf(config.initialDelayMs * (1L shl cappedAttempt), config.maxDelayMs)
    val jitterMultiplier = 1.0 + config.jitterFactor * (random * 2.0 - 1.0)
    return (base * jitterMultiplier).toLong().coerceAtLeast(1L)
}
