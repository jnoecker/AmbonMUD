package dev.ambon.bus

import dev.ambon.engine.events.InboundEvent
import kotlinx.coroutines.channels.Channel

class LocalInboundBus(
    capacity: Int = Channel.UNLIMITED,
) : LocalBusChannel<InboundEvent>(capacity),
    InboundBus {
    override fun depth(): Int = super<LocalBusChannel>.depth()
}
