package dev.ambon.bus

import dev.ambon.engine.events.OutboundEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ReceiveChannel

class LocalOutboundBus(
    val capacity: Int = Channel.UNLIMITED,
) : OutboundBus {
    private val channel = DepthTrackingChannel<OutboundEvent>(capacity)

    override suspend fun send(event: OutboundEvent) = channel.send(event)

    fun trySend(event: OutboundEvent): ChannelResult<Unit> = channel.trySend(event)

    override fun tryReceive(): ChannelResult<OutboundEvent> = channel.tryReceive()

    override fun asReceiveChannel(): ReceiveChannel<OutboundEvent> = channel.asReceiveChannel()

    override fun close() = channel.close()

    fun depth(): Int = channel.depth()
}
