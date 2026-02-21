package dev.ambon.bus

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.InboundEvent
import dev.ambon.redis.RedisConnectionManager
import io.github.oshai.kotlinlogging.KotlinLogging
import io.lettuce.core.pubsub.RedisPubSubAdapter
import kotlinx.coroutines.channels.ChannelResult

private val log = KotlinLogging.logger {}

class RedisInboundBus(
    private val delegate: LocalInboundBus,
    private val manager: RedisConnectionManager,
    private val channelName: String,
    private val instanceId: String,
) : InboundBus {
    private data class Envelope(
        val instanceId: String = "",
        val type: String = "",
        val sessionId: Long = 0L,
        val defaultAnsiEnabled: Boolean = false,
        val reason: String = "",
        val line: String = "",
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
                        log.warn(e) { "Failed to deserialize inbound event from Redis" }
                    }
                }
            },
        )
        conn.sync().subscribe(channelName)
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
        val async = manager.asyncCommands ?: return
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
            async.publish(channelName, mapper.writeValueAsString(env))
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
