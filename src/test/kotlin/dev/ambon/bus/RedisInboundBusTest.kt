package dev.ambon.bus

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.InboundEvent
import dev.ambon.redis.redisObjectMapper
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RedisInboundBusTest {
    private val mapper = redisObjectMapper
    private val sharedSecret = "test-secret"
    private val fakePublisher = FakeInboundPublisher()
    private val fakeSubscriber = FakeInboundSubscriberSetup()
    private val delegate = LocalInboundBus(capacity = 16)
    private val bus =
        RedisInboundBus(
            delegate = delegate,
            publisher = fakePublisher,
            subscriberSetup = fakeSubscriber,
            channelName = "test-inbound",
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
            val event = InboundEvent.LineReceived(SessionId(1), "hello world")
            bus.send(event)

            val parsed = mapper.readTree(fakePublisher.messages.single().second)
            assertEquals("server-1", parsed["instanceId"].asText())
            assertEquals("LineReceived", parsed["type"].asText())
            assertTrue(parsed["signature"].asText().isNotBlank())
        }

    @Test
    fun `received signed message from other instance is forwarded`() {
        fakeSubscriber.inject(
            signedInboundJson(
                instanceId = "server-2",
                type = "LineReceived",
                sessionId = 3,
                line = "from other",
            ),
        )

        val event = delegate.tryReceive()
        assertTrue(event.isSuccess)
        assertEquals(InboundEvent.LineReceived(SessionId(3), "from other"), event.getOrNull())
    }

    @Test
    fun `unsigned message is dropped`() {
        val json = """{"instanceId":"server-2","type":"LineReceived","sessionId":3,"line":"bad"}"""
        fakeSubscriber.inject(json)

        assertFalse(delegate.tryReceive().isSuccess)
    }

    @Test
    fun `message with invalid signature is dropped`() {
        val json =
            signedInboundJson(
                instanceId = "server-2",
                type = "LineReceived",
                sessionId = 3,
                line = "tampered",
            ).replace("tampered", "changed")
        fakeSubscriber.inject(json)

        assertFalse(delegate.tryReceive().isSuccess)
    }

    @Test
    fun `trySend does not publish when delegate channel is full`() {
        val fullDelegate = LocalInboundBus(capacity = 1)
        fullDelegate.trySend(InboundEvent.Connected(SessionId(99)))
        val localPublisher = FakeInboundPublisher()
        val fullBus =
            RedisInboundBus(
                delegate = fullDelegate,
                publisher = localPublisher,
                subscriberSetup = fakeSubscriber,
                channelName = "test-inbound",
                instanceId = "server-1",
                mapper = mapper,
                sharedSecret = sharedSecret,
            )

        val result = fullBus.trySend(InboundEvent.LineReceived(SessionId(1), "hello"))

        assertFalse(result.isSuccess)
        assertTrue(localPublisher.messages.isEmpty())
    }

    private fun signedInboundJson(
        instanceId: String,
        type: String,
        sessionId: Long,
        defaultAnsiEnabled: Boolean = false,
        reason: String = "",
        line: String = "",
    ): String {
        val payload = "$instanceId|$type|$sessionId|$defaultAnsiEnabled|$reason|$line"
        val signature = hmacSha256(sharedSecret, payload)
        return mapper.writeValueAsString(
            mapOf(
                "instanceId" to instanceId,
                "type" to type,
                "sessionId" to sessionId,
                "defaultAnsiEnabled" to defaultAnsiEnabled,
                "reason" to reason,
                "line" to line,
                "signature" to signature,
            ),
        )
    }

    private fun hmacSha256(
        secret: String,
        payload: String,
    ): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

private class FakeInboundPublisher : BusPublisher {
    val messages = mutableListOf<Pair<String, String>>()

    override fun publish(
        channel: String,
        message: String,
    ) {
        messages += channel to message
    }
}

private class FakeInboundSubscriberSetup : BusSubscriberSetup {
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
