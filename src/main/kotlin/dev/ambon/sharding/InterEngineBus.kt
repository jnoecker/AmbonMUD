package dev.ambon.sharding

import kotlinx.coroutines.channels.ReceiveChannel

/**
 * Message bus for communication between engine instances.
 *
 * Implementations:
 * - [LocalInterEngineBus]: in-process loopback for single-engine mode
 * - [RedisInterEngineBus]: Redis pub/sub for multi-engine deployments
 */
interface InterEngineBus {
    /** Send a message targeted at a specific engine (by engineId). */
    suspend fun sendTo(
        targetEngineId: String,
        message: InterEngineMessage,
    )

    /** Broadcast a message to ALL engines (including self). */
    suspend fun broadcast(message: InterEngineMessage)

    /** Receive messages targeted at this engine or broadcast. */
    fun incoming(): ReceiveChannel<InterEngineMessage>

    /** Start listening for messages (call once at startup). */
    suspend fun start()

    /** Stop the bus and release resources. */
    fun close()
}
