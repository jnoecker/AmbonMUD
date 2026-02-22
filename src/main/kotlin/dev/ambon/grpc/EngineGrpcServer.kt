package dev.ambon.grpc

import dev.ambon.bus.InboundBus
import dev.ambon.bus.OutboundBus
import dev.ambon.metrics.GameMetrics
import io.github.oshai.kotlinlogging.KotlinLogging
import io.grpc.Server
import io.grpc.ServerBuilder
import kotlinx.coroutines.CoroutineScope

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
) {
    private val serviceImpl = EngineServiceImpl(inbound = inbound, outbound = outbound, scope = scope)
    private val dispatcher = GrpcOutboundDispatcher(outbound = outbound, serviceImpl = serviceImpl, scope = scope, metrics = metrics)

    private val server: Server =
        ServerBuilder.forPort(port)
            .addService(serviceImpl)
            .build()

    fun start() {
        server.start()
        dispatcher.start()
        log.info { "Engine gRPC server listening on port $port" }
    }

    fun stop() {
        dispatcher.stop()
        server.shutdown()
        server.awaitTermination()
        log.info { "Engine gRPC server stopped" }
    }
}
