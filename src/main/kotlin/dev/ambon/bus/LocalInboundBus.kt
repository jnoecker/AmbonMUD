package dev.ambon.bus

import dev.ambon.engine.events.InboundEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult

class LocalInboundBus(
    capacity: Int = Channel.UNLIMITED,
) : InboundBus {
    private val channel = Channel<InboundEvent>(capacity)

    override suspend fun send(event: InboundEvent) = channel.send(event)

    override fun trySend(event: InboundEvent): ChannelResult<Unit> = channel.trySend(event)

    override fun tryReceive(): ChannelResult<InboundEvent> = channel.tryReceive()

    override fun close() {
        channel.close()
    }
}
