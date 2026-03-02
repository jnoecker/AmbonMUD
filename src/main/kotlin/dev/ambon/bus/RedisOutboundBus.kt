package dev.ambon.bus

import com.fasterxml.jackson.databind.ObjectMapper
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.OutboundEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ReceiveChannel

private val log = KotlinLogging.logger {}

internal class RedisOutboundBus(
    private val delegate: LocalOutboundBus,
    publisher: BusPublisher,
    subscriberSetup: BusSubscriberSetup,
    channelName: String,
    instanceId: String,
    mapper: ObjectMapper,
    sharedSecret: String,
) : RedisEventBus<OutboundEvent, RedisOutboundBus.Envelope>(
        publisher = publisher,
        subscriberSetup = subscriberSetup,
        channelName = channelName,
        instanceId = instanceId,
        mapper = mapper,
        sharedSecret = sharedSecret,
        direction = "outbound",
    ),
    OutboundBus {
    internal data class Envelope(
        override val instanceId: String = "",
        override val type: String = "",
        val sessionId: Long = 0L,
        val text: String = "",
        val enabled: Boolean = false,
        val reason: String = "",
        val gmcpPackage: String = "",
        val jsonData: String = "",
        override val signature: String = "",
    ) : RedisEnvelope

    fun startSubscribing() {
        startListening { delegate.trySend(it) }
    }

    override fun deserializeEnvelope(json: String): Envelope = mapper.readValue(json, Envelope::class.java)

    override fun Envelope.toEvent(): OutboundEvent? {
        val sid = SessionId(sessionId)
        return when (type) {
            "SendText" -> OutboundEvent.SendText(sid, text)
            "SendInfo" -> OutboundEvent.SendInfo(sid, text)
            "SendError" -> OutboundEvent.SendError(sid, text)
            "SendPrompt" -> OutboundEvent.SendPrompt(sid)
            "ShowLoginScreen" -> OutboundEvent.ShowLoginScreen(sid)
            "SetAnsi" -> OutboundEvent.SetAnsi(sid, enabled)
            "Close" -> OutboundEvent.Close(sid, reason)
            "ClearScreen" -> OutboundEvent.ClearScreen(sid)
            "ShowAnsiDemo" -> OutboundEvent.ShowAnsiDemo(sid)
            "GmcpData" -> OutboundEvent.GmcpData(sid, gmcpPackage, jsonData)
            else -> {
                log.warn { "Unknown outbound event type from Redis: $type" }
                null
            }
        }
    }

    override fun OutboundEvent.toEnvelope(): Envelope? =
        when (this) {
            is OutboundEvent.SendText ->
                Envelope(instanceId = instanceId, type = "SendText", sessionId = sessionId.value, text = text)
            is OutboundEvent.SendInfo ->
                Envelope(instanceId = instanceId, type = "SendInfo", sessionId = sessionId.value, text = text)
            is OutboundEvent.SendError ->
                Envelope(instanceId = instanceId, type = "SendError", sessionId = sessionId.value, text = text)
            is OutboundEvent.SendPrompt ->
                Envelope(instanceId = instanceId, type = "SendPrompt", sessionId = sessionId.value)
            is OutboundEvent.ShowLoginScreen ->
                Envelope(instanceId = instanceId, type = "ShowLoginScreen", sessionId = sessionId.value)
            is OutboundEvent.SetAnsi ->
                Envelope(instanceId = instanceId, type = "SetAnsi", sessionId = sessionId.value, enabled = enabled)
            is OutboundEvent.Close ->
                Envelope(instanceId = instanceId, type = "Close", sessionId = sessionId.value, reason = reason)
            is OutboundEvent.ClearScreen ->
                Envelope(instanceId = instanceId, type = "ClearScreen", sessionId = sessionId.value)
            is OutboundEvent.ShowAnsiDemo ->
                Envelope(instanceId = instanceId, type = "ShowAnsiDemo", sessionId = sessionId.value)
            is OutboundEvent.SessionRedirect -> null // control-plane event, not published to Redis
            is OutboundEvent.GmcpData ->
                Envelope(
                    instanceId = instanceId,
                    type = "GmcpData",
                    sessionId = sessionId.value,
                    gmcpPackage = gmcpPackage,
                    jsonData = jsonData,
                )
        }

    override fun Envelope.payloadToSign(): String =
        "$instanceId|$type|$sessionId|$text|$enabled|$reason|$gmcpPackage|$jsonData"

    override fun Envelope.withSignature(sig: String): Envelope = copy(signature = sig)

    override suspend fun send(event: OutboundEvent) {
        delegate.send(event)
        publishEvent(event)
    }

    override fun tryReceive(): ChannelResult<OutboundEvent> = delegate.tryReceive()

    override fun asReceiveChannel(): ReceiveChannel<OutboundEvent> = delegate.asReceiveChannel()

    override fun close() = delegate.close()

    fun delegateForMetrics(): LocalOutboundBus = delegate
}
