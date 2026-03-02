package dev.ambon.bus

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * Common fields that every Redis bus envelope must carry for routing and integrity checking.
 */
internal interface RedisEnvelope {
    val instanceId: String
    val type: String
    val signature: String
}

/**
 * Shared subscribe/publish infrastructure for Redis-backed event buses.
 *
 * Subclasses supply the event-type-specific [deserializeEnvelope], [EnvelopeT.toEvent],
 * [EventT.toEnvelope], [EnvelopeT.payloadToSign], and [EnvelopeT.withSignature] implementations.
 * All HMAC signing, instance-ID filtering, and error handling live here.
 */
internal abstract class RedisEventBus<EventT : Any, EnvelopeT : RedisEnvelope>(
    protected val publisher: BusPublisher,
    protected val subscriberSetup: BusSubscriberSetup,
    protected val channelName: String,
    protected val instanceId: String,
    protected val mapper: ObjectMapper,
    protected val sharedSecret: String,
    private val direction: String,
) {
    protected abstract fun deserializeEnvelope(json: String): EnvelopeT

    protected abstract fun EnvelopeT.toEvent(): EventT?

    /** Returns the envelope for [this] event, or null to skip publishing (e.g. control-plane events). */
    protected abstract fun EventT.toEnvelope(): EnvelopeT?

    protected abstract fun EnvelopeT.payloadToSign(): String

    protected abstract fun EnvelopeT.withSignature(sig: String): EnvelopeT

    protected fun startListening(dispatch: (EventT) -> Unit) {
        subscriberSetup.startListening(channelName) { message ->
            try {
                val env = deserializeEnvelope(message)
                if (env.instanceId == instanceId) return@startListening
                if (!isValidHmac(sharedSecret, env.payloadToSign(), env.signature)) {
                    log.warn { "Dropping $direction Redis event with invalid signature (type=${env.type})" }
                    return@startListening
                }
                val event = env.toEvent() ?: return@startListening
                dispatch(event)
            } catch (e: Exception) {
                log.warn(e) { "Failed to deserialize $direction event from Redis" }
            }
        }
        log.info { "Redis $direction bus subscribed to '$channelName' (instanceId=$instanceId)" }
    }

    protected fun publishEvent(event: EventT) {
        try {
            val env = event.toEnvelope() ?: return
            val signed = env.withSignature(hmacSha256(sharedSecret, env.payloadToSign()))
            publisher.publish(channelName, mapper.writeValueAsString(signed))
        } catch (e: Exception) {
            log.warn(e) { "Failed to publish $direction event to Redis" }
        }
    }
}
