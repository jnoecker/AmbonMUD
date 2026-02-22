package dev.ambon.sharding

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * In-process inter-engine bus for single-engine mode. All "cross-engine"
 * messages loop back to the same engine. This makes sharding-aware code
 * work correctly even in STANDALONE mode.
 */
class LocalInterEngineBus(
    capacity: Int = 1_000,
) : InterEngineBus {
    private val channel = Channel<InterEngineMessage>(capacity)

    override suspend fun sendTo(
        targetEngineId: String,
        message: InterEngineMessage,
    ) {
        channel.send(message)
    }

    override suspend fun broadcast(message: InterEngineMessage) {
        channel.send(message)
    }

    override fun incoming(): ReceiveChannel<InterEngineMessage> = channel

    override suspend fun start() {
        // No-op for local bus.
    }

    override fun close() {
        channel.close()
    }
}
