package dev.ambon.bus

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.OutboundEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ReceiveChannel

private val log = KotlinLogging.logger {}

class RedisOutboundBus(
    private val delegate: LocalOutboundBus,
    private val publisher: BusPublisher,
    private val subscriberSetup: BusSubscriberSetup,
    private val channelName: String,
    private val instanceId: String,
    private val mapper: ObjectMapper,
) : OutboundBus {
    private data class Envelope(
        val instanceId: String = "",
        val type: String = "",
        val sessionId: Long = 0L,
        val text: String = "",
        val enabled: Boolean = false,
        val reason: String = "",
    )

    fun startSubscribing() {
        subscriberSetup.startListening(channelName) { message ->
            try {
                val env = mapper.readValue<Envelope>(message)
                if (env.instanceId == instanceId) return@startListening
                val event = env.toEvent() ?: return@startListening
                delegate.trySend(event)
            } catch (e: Exception) {
                log.warn(e) { "Failed to deserialize outbound event from Redis" }
            }
        }
        log.info { "Redis outbound bus subscribed to '$channelName' (instanceId=$instanceId)" }
    }

    override suspend fun send(event: OutboundEvent) {
        delegate.send(event)
        publish(event)
    }

    override fun tryReceive(): ChannelResult<OutboundEvent> = delegate.tryReceive()

    override fun asReceiveChannel(): ReceiveChannel<OutboundEvent> = delegate.asReceiveChannel()

    override fun close() {
        delegate.close()
    }

    fun delegateForMetrics(): LocalOutboundBus = delegate

    private fun publish(event: OutboundEvent) {
        try {
            val env =
                when (event) {
                    is OutboundEvent.SendText ->
                        Envelope(
                            instanceId = instanceId,
                            type = "SendText",
                            sessionId = event.sessionId.value,
                            text = event.text,
                        )
                    is OutboundEvent.SendInfo ->
                        Envelope(
                            instanceId = instanceId,
                            type = "SendInfo",
                            sessionId = event.sessionId.value,
                            text = event.text,
                        )
                    is OutboundEvent.SendError ->
                        Envelope(
                            instanceId = instanceId,
                            type = "SendError",
                            sessionId = event.sessionId.value,
                            text = event.text,
                        )
                    is OutboundEvent.SendPrompt ->
                        Envelope(
                            instanceId = instanceId,
                            type = "SendPrompt",
                            sessionId = event.sessionId.value,
                        )
                    is OutboundEvent.ShowLoginScreen ->
                        Envelope(
                            instanceId = instanceId,
                            type = "ShowLoginScreen",
                            sessionId = event.sessionId.value,
                        )
                    is OutboundEvent.SetAnsi ->
                        Envelope(
                            instanceId = instanceId,
                            type = "SetAnsi",
                            sessionId = event.sessionId.value,
                            enabled = event.enabled,
                        )
                    is OutboundEvent.Close ->
                        Envelope(
                            instanceId = instanceId,
                            type = "Close",
                            sessionId = event.sessionId.value,
                            reason = event.reason,
                        )
                    is OutboundEvent.ClearScreen ->
                        Envelope(
                            instanceId = instanceId,
                            type = "ClearScreen",
                            sessionId = event.sessionId.value,
                        )
                    is OutboundEvent.ShowAnsiDemo ->
                        Envelope(
                            instanceId = instanceId,
                            type = "ShowAnsiDemo",
                            sessionId = event.sessionId.value,
                        )
                    is OutboundEvent.SessionRedirect ->
                        return // control-plane event, not published to Redis
                }
            publisher.publish(channelName, mapper.writeValueAsString(env))
        } catch (e: Exception) {
            log.warn(e) { "Failed to publish outbound event to Redis" }
        }
    }

    private fun Envelope.toEvent(): OutboundEvent? =
        when (type) {
            "SendText" ->
                OutboundEvent.SendText(
                    sessionId = SessionId(sessionId),
                    text = text,
                )
            "SendInfo" ->
                OutboundEvent.SendInfo(
                    sessionId = SessionId(sessionId),
                    text = text,
                )
            "SendError" ->
                OutboundEvent.SendError(
                    sessionId = SessionId(sessionId),
                    text = text,
                )
            "SendPrompt" ->
                OutboundEvent.SendPrompt(sessionId = SessionId(sessionId))
            "ShowLoginScreen" ->
                OutboundEvent.ShowLoginScreen(sessionId = SessionId(sessionId))
            "SetAnsi" ->
                OutboundEvent.SetAnsi(
                    sessionId = SessionId(sessionId),
                    enabled = enabled,
                )
            "Close" ->
                OutboundEvent.Close(
                    sessionId = SessionId(sessionId),
                    reason = reason,
                )
            "ClearScreen" ->
                OutboundEvent.ClearScreen(sessionId = SessionId(sessionId))
            "ShowAnsiDemo" ->
                OutboundEvent.ShowAnsiDemo(sessionId = SessionId(sessionId))
            else -> {
                log.warn { "Unknown outbound event type from Redis: $type" }
                null
            }
        }
}
