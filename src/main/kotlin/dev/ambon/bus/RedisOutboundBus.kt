package dev.ambon.bus

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.redis.RedisConnectionManager
import io.github.oshai.kotlinlogging.KotlinLogging
import io.lettuce.core.pubsub.RedisPubSubAdapter
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ReceiveChannel

private val log = KotlinLogging.logger {}

class RedisOutboundBus(
    private val delegate: LocalOutboundBus,
    private val manager: RedisConnectionManager,
    private val channelName: String,
    private val instanceId: String,
) : OutboundBus {
    private data class Envelope(
        val instanceId: String = "",
        val type: String = "",
        val sessionId: Long = 0L,
        val text: String = "",
        val enabled: Boolean = false,
        val reason: String = "",
    )

    private val mapper: ObjectMapper =
        ObjectMapper()
            .registerModule(KotlinModule.Builder().build())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun startSubscribing() {
        val conn = manager.connectPubSub() ?: return
        conn.addListener(
            object : RedisPubSubAdapter<String, String>() {
                override fun message(
                    channel: String,
                    message: String,
                ) {
                    try {
                        val env = mapper.readValue<Envelope>(message)
                        if (env.instanceId == instanceId) return
                        val event = env.toEvent() ?: return
                        delegate.trySend(event)
                    } catch (e: Exception) {
                        log.warn(e) { "Failed to deserialize outbound event from Redis" }
                    }
                }
            },
        )
        conn.sync().subscribe(channelName)
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

    private fun publish(event: OutboundEvent) {
        val async = manager.asyncCommands ?: return
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
                }
            async.publish(channelName, mapper.writeValueAsString(env))
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
