package dev.ambon.bus

import dev.ambon.engine.events.OutboundEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ReceiveChannel

class LocalOutboundBus(capacity: Int = Channel.UNLIMITED) : OutboundBus {
    private val channel = Channel<OutboundEvent>(capacity)

    override suspend fun send(event: OutboundEvent) = channel.send(event)

    override fun tryReceive(): ChannelResult<OutboundEvent> = channel.tryReceive()

    override fun asReceiveChannel(): ReceiveChannel<OutboundEvent> = channel

    override fun close() {
        channel.close()
    }
}
