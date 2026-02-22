package dev.ambon.bus

import dev.ambon.engine.events.InboundEvent
import dev.ambon.grpc.proto.InboundEventProto
import dev.ambon.grpc.toProto
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch

private val log = KotlinLogging.logger {}

/**
 * Gateway-side [InboundBus] that forwards every event to the engine via a gRPC stream.
 *
 * Uses the delegate pattern (wraps [LocalInboundBus]), matching [RedisInboundBus].
 * On [send] / [trySend], the event is placed in the local delegate and also forwarded
 * fire-and-forget to the engine's gRPC stream via [grpcSendChannel].
 *
 * [tryReceive] delegates to the local buffer — in gateway mode this is called only if
 * there is a local consumer (e.g. integration tests).
 */
class GrpcInboundBus(
    private val delegate: LocalInboundBus,
    private val grpcSendChannel: SendChannel<InboundEventProto>,
    private val scope: CoroutineScope,
) : InboundBus {
    override suspend fun send(event: InboundEvent) {
        delegate.send(event)
        forwardToEngine(event)
    }

    override fun trySend(event: InboundEvent): ChannelResult<Unit> {
        val result = delegate.trySend(event)
        if (result.isSuccess) {
            forwardToEngine(event)
        }
        return result
    }

    override fun tryReceive(): ChannelResult<InboundEvent> = delegate.tryReceive()

    override fun close() {
        delegate.close()
    }

    private fun forwardToEngine(event: InboundEvent) {
        scope.launch {
            try {
                grpcSendChannel.send(event.toProto())
            } catch (e: Exception) {
                log.warn(e) { "Failed to forward inbound event to engine gRPC stream" }
            }
        }
    }
}
