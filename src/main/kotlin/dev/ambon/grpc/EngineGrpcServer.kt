package dev.ambon.grpc

import dev.ambon.bus.InboundBus
import dev.ambon.bus.OutboundBus
import dev.ambon.metrics.GameMetrics
import io.github.oshai.kotlinlogging.KotlinLogging
import io.grpc.Server
import io.grpc.ServerBuilder
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/**
 * Wraps the gRPC server lifecycle for engine-mode deployments.
 *
 * Owns [EngineServiceImpl] and [GrpcOutboundDispatcher]; both are started when [start] is called
 * and stopped when [stop] is called.
 */
class EngineGrpcServer(
    private val port: Int,
    inbound: InboundBus,
    outbound: OutboundBus,
    scope: CoroutineScope,
    metrics: GameMetrics = GameMetrics.noop(),
    private val gracefulShutdownTimeoutMs: Long = DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT_MS,
    private val forceShutdownTimeoutMs: Long = DEFAULT_FORCE_SHUTDOWN_TIMEOUT_MS,
) {
    init {
        require(gracefulShutdownTimeoutMs > 0L) { "gracefulShutdownTimeoutMs must be > 0" }
        require(forceShutdownTimeoutMs > 0L) { "forceShutdownTimeoutMs must be > 0" }
    }

    private val serviceImpl = EngineServiceImpl(inbound = inbound, outbound = outbound, scope = scope)
    private val dispatcher = GrpcOutboundDispatcher(outbound = outbound, serviceImpl = serviceImpl, scope = scope, metrics = metrics)

    private val server: Server =
        ServerBuilder
            .forPort(port)
            .addService(serviceImpl)
            // Keep idle gRPC streams alive through NAT/firewall timeouts.
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(10, TimeUnit.SECONDS)
            .permitKeepAliveWithoutCalls(true)
            .build()

    fun start() {
        server.start()
        dispatcher.start()
        log.info { "Engine gRPC server listening on port $port" }
    }

    internal fun listeningPort(): Int = server.port

    fun stop() {
        dispatcher.stop()
        server.shutdown()
        try {
            val graceful = server.awaitTermination(gracefulShutdownTimeoutMs, TimeUnit.MILLISECONDS)
            if (!graceful) {
                log.warn {
                    "Engine gRPC server did not stop within ${gracefulShutdownTimeoutMs}ms; forcing shutdownNow()"
                }
                server.shutdownNow()
                val forced = server.awaitTermination(forceShutdownTimeoutMs, TimeUnit.MILLISECONDS)
                if (!forced) {
                    log.error {
                        "Engine gRPC server still running after forced shutdown timeout " +
                            "(${forceShutdownTimeoutMs}ms)"
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.warn(e) { "Interrupted while waiting for engine gRPC server shutdown; forcing shutdownNow()" }
            server.shutdownNow()
        }
        log.info { "Engine gRPC server stopped" }
    }
}

private const val DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT_MS = 5_000L
private const val DEFAULT_FORCE_SHUTDOWN_TIMEOUT_MS = 5_000L
