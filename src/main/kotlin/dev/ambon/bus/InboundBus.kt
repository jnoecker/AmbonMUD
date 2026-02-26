package dev.ambon.bus

import dev.ambon.engine.events.InboundEvent
import kotlinx.coroutines.channels.ChannelResult

interface InboundBus {
    suspend fun send(event: InboundEvent)

    fun trySend(event: InboundEvent): ChannelResult<Unit>

    fun tryReceive(): ChannelResult<InboundEvent>

    fun close()

    /** Returns the current number of events waiting in the queue. Defaults to 0 for implementations that do not track depth. */
    fun depth(): Int = 0
}
