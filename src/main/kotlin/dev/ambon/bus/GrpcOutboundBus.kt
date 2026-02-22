package dev.ambon.bus

import dev.ambon.engine.events.OutboundEvent
import dev.ambon.grpc.toDomain
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val log = KotlinLogging.logger {}

/**
 * Gateway-side [OutboundBus] that receives events from the engine via a gRPC stream.
 *
 * Uses the delegate pattern (wraps [LocalOutboundBus]), matching [RedisOutboundBus].
 * A background coroutine reads from [grpcReceiveFlow] (the outbound side of the bidi stream),
 * converts each proto to a domain event via [toDomain], and forwards it to the delegate.
 * [OutboundRouter] then consumes from the delegate as normal.
 */
class GrpcOutboundBus(
    private val delegate: LocalOutboundBus,
    private val grpcReceiveFlow: Flow<dev.ambon.grpc.proto.OutboundEventProto>,
    private val scope: CoroutineScope,
) : OutboundBus {
    private var receiverJob: Job? = null

    /** Start reading from the gRPC stream and forwarding events to the delegate. */
    fun startReceiving() {
        receiverJob =
            scope.launch {
                try {
                    grpcReceiveFlow.collect { proto ->
                        val event = proto.toDomain()
                        if (event == null) {
                            log.warn { "Received unrecognised OutboundEventProto from engine: $proto" }
                            return@collect
                        }
                        delegate.trySend(event)
                    }
                } catch (e: Exception) {
                    log.warn(e) { "Engine gRPC outbound stream ended" }
                }
            }
        log.info { "GrpcOutboundBus receiver started" }
    }

    fun stopReceiving() {
        runBlocking { receiverJob?.cancelAndJoin() }
    }

    override suspend fun send(event: OutboundEvent) = delegate.send(event)

    override fun tryReceive(): ChannelResult<OutboundEvent> = delegate.tryReceive()

    override fun asReceiveChannel(): ReceiveChannel<OutboundEvent> = delegate.asReceiveChannel()

    override fun close() {
        delegate.close()
    }
}
