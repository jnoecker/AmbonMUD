package dev.ambon.bus

import com.fasterxml.jackson.databind.ObjectMapper
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.InboundEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.ChannelResult

private val log = KotlinLogging.logger {}

internal class RedisInboundBus(
    private val delegate: LocalInboundBus,
    publisher: BusPublisher,
    subscriberSetup: BusSubscriberSetup,
    channelName: String,
    instanceId: String,
    mapper: ObjectMapper,
    sharedSecret: String,
) : RedisEventBus<InboundEvent, RedisInboundBus.Envelope>(
        publisher = publisher,
        subscriberSetup = subscriberSetup,
        channelName = channelName,
        instanceId = instanceId,
        mapper = mapper,
        sharedSecret = sharedSecret,
        direction = "inbound",
    ),
    InboundBus,
    DepthAware {
    internal data class Envelope(
        override val instanceId: String = "",
        override val type: String = "",
        val sessionId: Long = 0L,
        val defaultAnsiEnabled: Boolean = false,
        val reason: String = "",
        val line: String = "",
        val gmcpPackage: String = "",
        val jsonData: String = "",
        override val signature: String = "",
    ) : RedisEnvelope

    fun startSubscribing() {
        startListening { delegate.trySend(it) }
    }

    override fun deserializeEnvelope(json: String): Envelope = mapper.readValue(json, Envelope::class.java)

    override fun Envelope.toEvent(): InboundEvent? {
        val sid = SessionId(sessionId)
        return when (type) {
            "Connected" -> InboundEvent.Connected(sid, defaultAnsiEnabled)
            "Disconnected" -> InboundEvent.Disconnected(sid, reason)
            "LineReceived" -> InboundEvent.LineReceived(sid, line)
            "GmcpReceived" -> InboundEvent.GmcpReceived(sid, gmcpPackage, jsonData)
            else -> {
                log.warn { "Unknown inbound event type from Redis: $type" }
                null
            }
        }
    }

    private fun baseEnvelope(type: String, sessionId: SessionId) =
        Envelope(instanceId = instanceId, type = type, sessionId = sessionId.value)

    override fun InboundEvent.toEnvelope(): Envelope? =
        when (this) {
            is InboundEvent.Connected ->
                baseEnvelope("Connected", sessionId).copy(defaultAnsiEnabled = defaultAnsiEnabled)
            is InboundEvent.Disconnected ->
                baseEnvelope("Disconnected", sessionId).copy(reason = reason)
            is InboundEvent.LineReceived ->
                baseEnvelope("LineReceived", sessionId).copy(line = line)
            is InboundEvent.GmcpReceived ->
                baseEnvelope("GmcpReceived", sessionId).copy(gmcpPackage = gmcpPackage, jsonData = jsonData)
        }

    override fun Envelope.payloadToSign(): String =
        "$instanceId|$type|$sessionId|$defaultAnsiEnabled|$reason|$line|$gmcpPackage|$jsonData"

    override fun Envelope.withSignature(sig: String): Envelope = copy(signature = sig)

    override suspend fun send(event: InboundEvent) {
        delegate.send(event)
        publishEvent(event)
    }

    override fun trySend(event: InboundEvent): ChannelResult<Unit> {
        val result = delegate.trySend(event)
        if (result.isSuccess) publishEvent(event)
        return result
    }

    override fun tryReceive(): ChannelResult<InboundEvent> = delegate.tryReceive()

    override fun close() = delegate.close()

    override fun depth(): Int = delegate.depth()

    override val capacity: Int get() = delegate.capacity
}
