package dev.ambon.bus

import dev.ambon.engine.events.OutboundEvent
import kotlinx.coroutines.channels.Channel

class LocalOutboundBus(
    capacity: Int = Channel.UNLIMITED,
) : LocalBusChannel<OutboundEvent>(capacity),
    OutboundBus {
    override fun depth(): Int = super<LocalBusChannel>.depth()
}
