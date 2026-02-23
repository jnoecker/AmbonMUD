package dev.ambon.bus

import dev.ambon.engine.events.InboundEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import java.util.concurrent.atomic.AtomicInteger

class LocalInboundBus(
    val capacity: Int = Channel.UNLIMITED,
) : InboundBus {
    private val channel = Channel<InboundEvent>(capacity)
    private val depth = AtomicInteger(0)

    override suspend fun send(event: InboundEvent) {
        channel.send(event)
        depth.incrementAndGet()
    }

    override fun trySend(event: InboundEvent): ChannelResult<Unit> {
        val result = channel.trySend(event)
        if (result.isSuccess) {
            depth.incrementAndGet()
        }
        return result
    }

    override fun tryReceive(): ChannelResult<InboundEvent> {
        val result = channel.tryReceive()
        if (result.isSuccess) {
            depth.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
        }
        return result
    }

    override fun close() {
        channel.close()
    }

    fun depth(): Int = depth.get()
}
