package dev.ambon.bus

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * Generic depth-tracking channel wrapper shared by [LocalInboundBus] and [LocalOutboundBus].
 *
 * Provides [DepthAware] queue metrics and delegates all operations to a [DepthTrackingChannel].
 */
open class LocalBusChannel<T>(
    override val capacity: Int = Channel.UNLIMITED,
) : DepthAware {
    private val channel = DepthTrackingChannel<T>(capacity)

    open suspend fun send(event: T) = channel.send(event)

    open fun trySend(event: T): ChannelResult<Unit> = channel.trySend(event)

    open fun tryReceive(): ChannelResult<T> = channel.tryReceive()

    open fun asReceiveChannel(): ReceiveChannel<T> = channel.asReceiveChannel()

    open fun close() = channel.close()

    override fun depth(): Int = channel.depth()
}
