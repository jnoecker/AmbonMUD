package dev.ambon.bus

import dev.ambon.engine.events.OutboundEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ReceiveChannel
import java.util.concurrent.atomic.AtomicInteger

class LocalOutboundBus(
    val capacity: Int = Channel.UNLIMITED,
) : OutboundBus {
    private val channel = Channel<OutboundEvent>(capacity)
    private val depth = AtomicInteger(0)

    override suspend fun send(event: OutboundEvent) {
        depth.incrementAndGet()
        try {
            channel.send(event)
        } catch (e: Throwable) {
            depth.decrementAndGet()
            throw e
        }
    }

    fun trySend(event: OutboundEvent): ChannelResult<Unit> {
        depth.incrementAndGet()
        val result = channel.trySend(event)
        if (!result.isSuccess) {
            depth.decrementAndGet()
        }
        return result
    }

    override fun tryReceive(): ChannelResult<OutboundEvent> {
        val result = channel.tryReceive()
        if (result.isSuccess) {
            depth.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
        }
        return result
    }

    override fun asReceiveChannel(): ReceiveChannel<OutboundEvent> = channel

    override fun close() {
        channel.close()
    }

    fun depth(): Int = depth.get()
}
