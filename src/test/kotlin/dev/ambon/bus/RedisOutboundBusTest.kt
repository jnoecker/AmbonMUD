package dev.ambon.bus

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.redis.redisObjectMapper
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RedisOutboundBusTest {
    private val mapper = redisObjectMapper
    private val sharedSecret = "test-secret"
    private val fakePublisher = FakeOutboundPublisher()
    private val fakeSubscriber = FakeOutboundSubscriberSetup()
    private val delegate = LocalOutboundBus(capacity = 16)
    private val bus =
        RedisOutboundBus(
            delegate = delegate,
            publisher = fakePublisher,
            subscriberSetup = fakeSubscriber,
            channelName = "test-outbound",
            instanceId = "server-1",
            mapper = mapper,
            sharedSecret = sharedSecret,
        )

    @BeforeEach
    fun setUp() {
        bus.startSubscribing()
    }

    @Test
    fun `send publishes signed envelope`() =
        runTest {
            val event = OutboundEvent.SendText(SessionId(1), "hello world")
            bus.send(event)

            val parsed = mapper.readTree(fakePublisher.messages.single().second)
            assertEquals("server-1", parsed["instanceId"].asText())
            assertEquals("SendText", parsed["type"].asText())
            assertTrue(parsed["signature"].asText().isNotBlank())
        }

    @Test
    fun `received signed message from other instance is forwarded`() {
        fakeSubscriber.inject(
            signedOutboundJson(
                instanceId = "server-2",
                type = "SendText",
                sessionId = 3,
                text = "hi",
            ),
        )

        val event = delegate.tryReceive()
        assertTrue(event.isSuccess)
        assertEquals(OutboundEvent.SendText(SessionId(3), "hi"), event.getOrNull())
    }

    @Test
    fun `unsigned message is dropped`() {
        val json = """{"instanceId":"server-2","type":"SendText","sessionId":3,"text":"bad"}"""
        fakeSubscriber.inject(json)

        assertFalse(delegate.tryReceive().isSuccess)
    }

    @Test
    fun `message with invalid signature is dropped`() {
        val json =
            signedOutboundJson(
                instanceId = "server-2",
                type = "SendText",
                sessionId = 3,
                text = "tampered",
            ).replace("tampered", "changed")
        fakeSubscriber.inject(json)

        assertFalse(delegate.tryReceive().isSuccess)
    }

    private fun signedOutboundJson(
        instanceId: String,
        type: String,
        sessionId: Long,
        text: String = "",
        enabled: Boolean = false,
        reason: String = "",
    ): String {
        val payload = "$instanceId|$type|$sessionId|$text|$enabled|$reason"
        val signature = hmacSha256(sharedSecret, payload)
        return mapper.writeValueAsString(
            mapOf(
                "instanceId" to instanceId,
                "type" to type,
                "sessionId" to sessionId,
                "text" to text,
                "enabled" to enabled,
                "reason" to reason,
                "signature" to signature,
            ),
        )
    }
}

private class FakeOutboundPublisher : BusPublisher {
    val messages = mutableListOf<Pair<String, String>>()

    override fun publish(
        channel: String,
        message: String,
    ) {
        messages += channel to message
    }
}

private class FakeOutboundSubscriberSetup : BusSubscriberSetup {
    private var listener: ((String) -> Unit)? = null

    fun inject(message: String) {
        listener?.invoke(message)
    }

    override fun startListening(
        channelName: String,
        onMessage: (String) -> Unit,
    ) {
        listener = onMessage
    }
}
