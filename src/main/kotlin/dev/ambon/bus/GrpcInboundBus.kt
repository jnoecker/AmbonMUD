package dev.ambon.bus

import dev.ambon.engine.events.InboundEvent
import dev.ambon.grpc.proto.InboundEventProto
import dev.ambon.grpc.toProto
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.withTimeout

private val log = KotlinLogging.logger {}

/** Bounded wait for [send] before treating the gRPC channel as overloaded. */
private const val FORWARD_SEND_TIMEOUT_MS = 5_000L

/**
 * Gateway-side [InboundBus] that forwards every event to the engine via a gRPC stream.
 *
 * Uses the delegate pattern (wraps [LocalInboundBus]), matching [RedisInboundBus].
 *
 * **Forwarding semantics:**
 * - [trySend] — non-blocking. Returns failure if the gRPC writer channel is full or closed.
 *   The existing "N backpressure failures → disconnect" policy in the transport applies.
 * - [send] — suspends up to [FORWARD_SEND_TIMEOUT_MS] waiting for space in the gRPC channel.
 *   Throws [IllegalStateException] if the channel stays full past the deadline, propagating
 *   backpressure to the caller so the session is torn down.
 *
 * The delegate is a best-effort tap in both paths. A full or empty delegate never blocks or
 * prevents forwarding — it exists solely so [tryReceive] works for local consumers in tests.
 */
class GrpcInboundBus(
    private val delegate: LocalInboundBus,
    grpcSendChannel: SendChannel<InboundEventProto>,
) : InboundBus {
    @Volatile
    private var grpcSendChannel: SendChannel<InboundEventProto> = grpcSendChannel

    /** Swap in a new gRPC send channel (called by the reconnect loop after a new stream is open). */
    fun reattach(newChannel: SendChannel<InboundEventProto>) {
        grpcSendChannel = newChannel
    }

    override suspend fun send(event: InboundEvent) {
        delegate.trySend(event) // best-effort tap; never suspends
        val proto = event.toProto()
        try {
            withTimeout(FORWARD_SEND_TIMEOUT_MS) {
                grpcSendChannel.send(proto)
            }
        } catch (e: TimeoutCancellationException) {
            log.warn { "Engine gRPC channel unresponsive for ${FORWARD_SEND_TIMEOUT_MS}ms; backpressure limit reached" }
            throw IllegalStateException(
                "Engine gRPC channel did not accept event within ${FORWARD_SEND_TIMEOUT_MS}ms",
                e,
            )
        }
    }

    override fun trySend(event: InboundEvent): ChannelResult<Unit> {
        delegate.trySend(event) // best-effort tap; ignore result
        val forwarded = grpcSendChannel.trySend(event.toProto())
        if (forwarded.isFailure) {
            log.warn { "Engine gRPC channel full or closed; dropping ${event::class.simpleName}" }
        }
        return forwarded
    }

    override fun tryReceive(): ChannelResult<InboundEvent> = delegate.tryReceive()

    override fun close() {
        delegate.close()
    }

    fun delegateForMetrics(): LocalInboundBus = delegate
}
