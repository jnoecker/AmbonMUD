package dev.ambon.gateway

import dev.ambon.bus.GrpcInboundBus
import dev.ambon.bus.GrpcOutboundBus
import dev.ambon.bus.InboundBus
import dev.ambon.bus.LocalInboundBus
import dev.ambon.bus.LocalOutboundBus
import dev.ambon.bus.OutboundBus
import dev.ambon.config.AppConfig
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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private val log = KotlinLogging.logger {}

/**
 * Gateway-mode composition root.
 *
 * Starts the transports (telnet/WebSocket) and a gRPC client connection to the engine.
 * No local game engine or persistence — all game logic runs remotely.
 */
class GatewayServer(
    private val config: AppConfig,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fatalShutdownTriggered = AtomicBoolean(false)

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
    private val inboundProtoChannel = Channel<InboundEventProto>(capacity = config.server.inboundChannelCapacity)

    private lateinit var grpcInboundBus: GrpcInboundBus
    private lateinit var grpcOutboundBus: GrpcOutboundBus
    private val inbound: InboundBus get() = grpcInboundBus
    private val outbound: OutboundBus get() = grpcOutboundBus

    private lateinit var outboundRouter: OutboundRouter
    private lateinit var telnetTransport: BlockingSocketTransport
    private lateinit var webTransport: KtorWebSocketTransport

    private var leaseManager: GatewayIdLeaseManager? = null

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
                onFatalStreamFailure = { error ->
                    if (fatalShutdownTriggered.compareAndSet(false, true)) {
                        log.error(error) {
                            "Fatal gateway/engine stream failure; stopping gateway to avoid session desync"
                        }
                        scope.launch { stop() }
                    }
                },
            )
        grpcOutboundBus.startReceiving()

        outboundRouter = OutboundRouter(engineOutbound = grpcOutboundBus, scope = scope, metrics = gameMetrics)
        scope.launch { outboundRouter.start() }

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
}
