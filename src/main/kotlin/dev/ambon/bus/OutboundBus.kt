package dev.ambon.bus

import dev.ambon.engine.events.OutboundEvent
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ReceiveChannel

interface OutboundBus {
    suspend fun send(event: OutboundEvent)

    fun tryReceive(): ChannelResult<OutboundEvent>

    fun asReceiveChannel(): ReceiveChannel<OutboundEvent>

    fun close()

    /** Returns the current number of events waiting in the queue. Defaults to 0 for implementations that do not track depth. */
    fun depth(): Int = 0
}
