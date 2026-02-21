package dev.ambon.bus

import dev.ambon.engine.events.InboundEvent
import kotlinx.coroutines.channels.ChannelResult

interface InboundBus {
    suspend fun send(event: InboundEvent)

    fun trySend(event: InboundEvent): ChannelResult<Unit>

    fun tryReceive(): ChannelResult<InboundEvent>

    fun close()
}
