package dev.ambon.bus

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.InboundEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.ChannelResult

private val log = KotlinLogging.logger {}

class RedisInboundBus(
    private val delegate: LocalInboundBus,
    private val publisher: BusPublisher,
    private val subscriberSetup: BusSubscriberSetup,
    private val channelName: String,
    private val instanceId: String,
    private val mapper: ObjectMapper,
) : InboundBus {
    private data class Envelope(
        val instanceId: String = "",
        val type: String = "",
        val sessionId: Long = 0L,
        val defaultAnsiEnabled: Boolean = false,
        val reason: String = "",
        val line: String = "",
    )

    fun startSubscribing() {
        subscriberSetup.startListening(channelName) { message ->
            try {
                val env = mapper.readValue<Envelope>(message)
                if (env.instanceId == instanceId) return@startListening
                val event = env.toEvent() ?: return@startListening
                delegate.trySend(event)
            } catch (e: Exception) {
                log.warn(e) { "Failed to deserialize inbound event from Redis" }
            }
        }
        log.info { "Redis inbound bus subscribed to '$channelName' (instanceId=$instanceId)" }
    }

    override suspend fun send(event: InboundEvent) {
        delegate.send(event)
        publish(event)
    }

    override fun trySend(event: InboundEvent): ChannelResult<Unit> {
        val result = delegate.trySend(event)
        if (result.isSuccess) {
            publish(event)
        }
        return result
    }

    override fun tryReceive(): ChannelResult<InboundEvent> = delegate.tryReceive()

    override fun close() {
        delegate.close()
    }

    private fun publish(event: InboundEvent) {
        try {
            val env =
                when (event) {
                    is InboundEvent.Connected ->
                        Envelope(
                            instanceId = instanceId,
                            type = "Connected",
                            sessionId = event.sessionId.value,
                            defaultAnsiEnabled = event.defaultAnsiEnabled,
                        )
                    is InboundEvent.Disconnected ->
                        Envelope(
                            instanceId = instanceId,
                            type = "Disconnected",
                            sessionId = event.sessionId.value,
                            reason = event.reason,
                        )
                    is InboundEvent.LineReceived ->
                        Envelope(
                            instanceId = instanceId,
                            type = "LineReceived",
                            sessionId = event.sessionId.value,
                            line = event.line,
                        )
                }
            publisher.publish(channelName, mapper.writeValueAsString(env))
        } catch (e: Exception) {
            log.warn(e) { "Failed to publish inbound event to Redis" }
        }
    }

    private fun Envelope.toEvent(): InboundEvent? =
        when (type) {
            "Connected" ->
                InboundEvent.Connected(
                    sessionId = SessionId(sessionId),
                    defaultAnsiEnabled = defaultAnsiEnabled,
                )
            "Disconnected" ->
                InboundEvent.Disconnected(
                    sessionId = SessionId(sessionId),
                    reason = reason,
                )
            "LineReceived" ->
                InboundEvent.LineReceived(
                    sessionId = SessionId(sessionId),
                    line = line,
                )
            else -> {
                log.warn { "Unknown inbound event type from Redis: $type" }
                null
            }
        }
}
