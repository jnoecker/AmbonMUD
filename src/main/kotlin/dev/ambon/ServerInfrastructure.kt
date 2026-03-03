package dev.ambon

import dev.ambon.bus.DepthAware
import dev.ambon.bus.InboundBus
import dev.ambon.bus.OutboundBus
import dev.ambon.config.AppConfig
import dev.ambon.metrics.GameMetrics
import dev.ambon.redis.RedisConnectionManager
import dev.ambon.transport.BlockingSocketTransport
import dev.ambon.transport.KtorWebSocketTransport
import dev.ambon.transport.OutboundRouter
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.CoroutineScope
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/**
 * Shared factory functions for infrastructure that both [MudServer] and
 * [dev.ambon.gateway.GatewayServer] construct identically.
 */
object ServerInfrastructure {
    fun createPrometheusRegistry(
        config: AppConfig,
        serviceName: String,
    ): PrometheusMeterRegistry? =
        if (config.observability.metricsEnabled) {
            PrometheusMeterRegistry(PrometheusConfig.DEFAULT).also { reg ->
                reg.config().commonTags("service", serviceName)
                config.observability.staticTags.forEach { (k, v) -> reg.config().commonTags(k, v) }
            }
        } else {
            null
        }

    fun createGameMetrics(registry: PrometheusMeterRegistry?): GameMetrics =
        if (registry != null) GameMetrics(registry) else GameMetrics.noop()

    fun generateInstanceId(config: AppConfig): String =
        config.redis.bus.instanceId
            .takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()

    fun createRedisManager(config: AppConfig): RedisConnectionManager? =
        if (config.redis.enabled) RedisConnectionManager(config.redis) else null

    fun createTelnetTransport(
        config: AppConfig,
        inbound: InboundBus,
        outboundRouter: OutboundRouter,
        sessionIdFactory: () -> dev.ambon.domain.ids.SessionId,
        scope: CoroutineScope,
        metrics: GameMetrics,
        sessionDispatcher: CoroutineContext,
    ): BlockingSocketTransport =
        BlockingSocketTransport(
            port = config.server.telnetPort,
            inbound = inbound,
            outboundRouter = outboundRouter,
            sessionIdFactory = sessionIdFactory,
            scope = scope,
            sessionOutboundQueueCapacity = config.server.sessionOutboundQueueCapacity,
            maxLineLen = config.transport.telnet.maxLineLen,
            maxNonPrintablePerLine = config.transport.telnet.maxNonPrintablePerLine,
            maxInboundBackpressureFailures = config.transport.maxInboundBackpressureFailures,
            socketBacklog = config.transport.telnet.socketBacklog,
            metrics = metrics,
            sessionDispatcher = sessionDispatcher,
        )

    fun createWebTransport(
        config: AppConfig,
        inbound: InboundBus,
        outboundRouter: OutboundRouter,
        sessionIdFactory: () -> dev.ambon.domain.ids.SessionId,
        prometheusRegistry: PrometheusMeterRegistry?,
        metrics: GameMetrics,
    ): KtorWebSocketTransport =
        KtorWebSocketTransport(
            port = config.server.webPort,
            inbound = inbound,
            outboundRouter = outboundRouter,
            sessionIdFactory = sessionIdFactory,
            host = config.transport.websocket.host,
            sessionOutboundQueueCapacity = config.server.sessionOutboundQueueCapacity,
            stopGraceMillis = config.transport.websocket.stopGraceMillis,
            stopTimeoutMillis = config.transport.websocket.stopTimeoutMillis,
            maxLineLen = config.transport.telnet.maxLineLen,
            maxNonPrintablePerLine = config.transport.telnet.maxNonPrintablePerLine,
            maxInboundBackpressureFailures = config.transport.maxInboundBackpressureFailures,
            prometheusRegistry = prometheusRegistry,
            metricsEndpoint = config.observability.metricsEndpoint,
            metrics = metrics,
        )

    fun bindQueueMetrics(
        inbound: InboundBus,
        outbound: OutboundBus,
        metrics: GameMetrics,
    ) {
        (inbound as? DepthAware)?.let { metrics.bindInboundBusQueue(it::depth) { it.capacity } }
        (outbound as? DepthAware)?.let { metrics.bindOutboundBusQueue(it::depth) { it.capacity } }
    }
}
