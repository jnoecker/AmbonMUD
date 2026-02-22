package dev.ambon.sharding

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.ambon.bus.BusPublisher
import dev.ambon.bus.BusSubscriberSetup
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

private val log = KotlinLogging.logger {}

/**
 * Redis pub/sub envelope. Carries the inter-engine message, the sender's
 * engine ID, and an optional target engine ID (null = broadcast).
 */
data class InterEngineEnvelope(
    val senderEngineId: String = "",
    // null = broadcast
    val targetEngineId: String? = null,
    // JSON-serialized InterEngineMessage
    val payload: String = "",
)

/**
 * Inter-engine bus backed by Redis pub/sub.
 *
 * - Targeted messages: published to `<channelPrefix>:<targetEngineId>`
 * - Broadcast messages: published to `<channelPrefix>:broadcast`
 *
 * Each engine subscribes to both its own targeted channel and the broadcast channel.
 */
class RedisInterEngineBus(
    private val engineId: String,
    private val publisher: BusPublisher,
    private val subscriberSetup: BusSubscriberSetup,
    private val mapper: ObjectMapper,
    private val channelPrefix: String = "ambon:engine",
    capacity: Int = 1_000,
) : InterEngineBus {
    private val channel = Channel<InterEngineMessage>(capacity)
    private val broadcastChannel = "$channelPrefix:broadcast"
    private val targetedChannel = "$channelPrefix:$engineId"

    override suspend fun sendTo(
        targetEngineId: String,
        message: InterEngineMessage,
    ) {
        val payloadJson = mapper.writeValueAsString(message)
        val envelope =
            InterEngineEnvelope(
                senderEngineId = engineId,
                targetEngineId = targetEngineId,
                payload = payloadJson,
            )
        val json = mapper.writeValueAsString(envelope)
        publisher.publish("$channelPrefix:$targetEngineId", json)
    }

    override suspend fun broadcast(message: InterEngineMessage) {
        val payloadJson = mapper.writeValueAsString(message)
        val envelope =
            InterEngineEnvelope(
                senderEngineId = engineId,
                targetEngineId = null,
                payload = payloadJson,
            )
        val json = mapper.writeValueAsString(envelope)
        publisher.publish(broadcastChannel, json)
    }

    override fun incoming(): ReceiveChannel<InterEngineMessage> = channel

    override suspend fun start() {
        // Subscribe to broadcast channel
        subscriberSetup.startListening(broadcastChannel) { json -> handleIncoming(json) }
        // Subscribe to this engine's targeted channel
        subscriberSetup.startListening(targetedChannel) { json -> handleIncoming(json) }
        log.info { "RedisInterEngineBus started (engineId=$engineId, channels=[$broadcastChannel, $targetedChannel])" }
    }

    private fun handleIncoming(json: String) {
        try {
            val envelope = mapper.readValue<InterEngineEnvelope>(json)

            // Skip our own broadcast messages (but not targeted messages — those
            // shouldn't be self-sent in practice, but handle gracefully).
            if (envelope.targetEngineId == null && envelope.senderEngineId == engineId) return

            val message = mapper.readValue<InterEngineMessage>(envelope.payload)
            val sent = channel.trySend(message)
            if (sent.isFailure) {
                log.warn { "InterEngineBus channel full, dropping message: ${message::class.simpleName}" }
            }
        } catch (e: Exception) {
            log.warn(e) { "Failed to deserialize inter-engine message" }
        }
    }

    override fun close() {
        channel.close()
    }
}
