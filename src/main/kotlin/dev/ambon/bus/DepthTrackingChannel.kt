package dev.ambon.bus

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ReceiveChannel
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [Channel] wrapper that maintains an approximate depth count.
 * Used by [LocalInboundBus] and [LocalOutboundBus] to expose queue depth metrics.
 */
internal class DepthTrackingChannel<T>(
    val capacity: Int = Channel.UNLIMITED,
) {
    private val channel = Channel<T>(capacity)
    private val depth = AtomicInteger(0)

    suspend fun send(event: T) {
        depth.incrementAndGet()
        try {
            channel.send(event)
        } catch (e: Throwable) {
            depth.decrementAndGet()
            throw e
        }
    }

    fun trySend(event: T): ChannelResult<Unit> {
        depth.incrementAndGet()
        val result = channel.trySend(event)
        if (!result.isSuccess) {
            depth.decrementAndGet()
        }
        return result
    }

    fun tryReceive(): ChannelResult<T> {
        val result = channel.tryReceive()
        if (result.isSuccess) {
            depth.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
        }
        return result
    }

    fun asReceiveChannel(): ReceiveChannel<T> = channel

    fun close() {
        channel.close()
    }

    fun depth(): Int = depth.get()
}
