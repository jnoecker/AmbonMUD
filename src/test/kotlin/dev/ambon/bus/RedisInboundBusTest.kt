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
        )

    @BeforeEach
    fun setUp() {
        bus.startSubscribing()
    }

    @Test
    fun `send delivers event to local delegate`() =
        runTest {
            val event = InboundEvent.LineReceived(SessionId(1), "hello")
            bus.send(event)

            val received = delegate.tryReceive()
            assertTrue(received.isSuccess)
            assertEquals(event, received.getOrNull())
        }

    @Test
    fun `send publishes JSON to Redis with correct fields`() =
        runTest {
            val event = InboundEvent.LineReceived(SessionId(1), "hello world")
            bus.send(event)

            assertEquals(1, fakePublisher.messages.size)
            val (channel, json) = fakePublisher.messages[0]
            assertEquals("test-inbound", channel)
            val parsed = mapper.readTree(json)
            assertEquals("server-1", parsed["instanceId"].asText())
            assertEquals("LineReceived", parsed["type"].asText())
            assertEquals(1L, parsed["sessionId"].asLong())
            assertEquals("hello world", parsed["line"].asText())
        }

    @Test
    fun `send publishes Connected event with defaultAnsiEnabled`() =
        runTest {
            val event = InboundEvent.Connected(SessionId(5), defaultAnsiEnabled = true)
            bus.send(event)

            assertEquals(1, fakePublisher.messages.size)
            val parsed = mapper.readTree(fakePublisher.messages[0].second)
            assertEquals("Connected", parsed["type"].asText())
            assertEquals(5L, parsed["sessionId"].asLong())
            assertEquals(true, parsed["defaultAnsiEnabled"].asBoolean())
        }

    @Test
    fun `send publishes Disconnected event with reason`() =
        runTest {
            val event = InboundEvent.Disconnected(SessionId(2), "timeout")
            bus.send(event)

            assertEquals(1, fakePublisher.messages.size)
            val parsed = mapper.readTree(fakePublisher.messages[0].second)
            assertEquals("Disconnected", parsed["type"].asText())
            assertEquals("timeout", parsed["reason"].asText())
        }

    @Test
    fun `trySend delivers event to local delegate`() {
        val event = InboundEvent.Connected(SessionId(5), defaultAnsiEnabled = false)
        val result = bus.trySend(event)

        assertTrue(result.isSuccess)
        assertEquals(event, delegate.tryReceive().getOrNull())
    }

    @Test
    fun `trySend publishes to Redis on success`() {
        val event = InboundEvent.Disconnected(SessionId(2), "bye")
        bus.trySend(event)

        assertEquals(1, fakePublisher.messages.size)
        val parsed = mapper.readTree(fakePublisher.messages[0].second)
        assertEquals("Disconnected", parsed["type"].asText())
        assertEquals("bye", parsed["reason"].asText())
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
            )

        val result = fullBus.trySend(InboundEvent.LineReceived(SessionId(1), "hello"))

        assertFalse(result.isSuccess)
        assertTrue(localPublisher.messages.isEmpty())
    }

    @Test
    fun `received message from other instance is forwarded to delegate`() {
        val json = """{"instanceId":"server-2","type":"LineReceived","sessionId":3,"line":"from other"}"""
        fakeSubscriber.inject(json)

        val event = delegate.tryReceive()
        assertTrue(event.isSuccess)
        assertEquals(InboundEvent.LineReceived(SessionId(3), "from other"), event.getOrNull())
    }

    @Test
    fun `received message from own instanceId is filtered out`() {
        val json = """{"instanceId":"server-1","type":"LineReceived","sessionId":3,"line":"own"}"""
        fakeSubscriber.inject(json)

        assertFalse(delegate.tryReceive().isSuccess)
    }

    @Test
    fun `all inbound event types round-trip through JSON`() {
        val connectedJson =
            """{"instanceId":"other","type":"Connected","sessionId":10,"defaultAnsiEnabled":true}"""
        fakeSubscriber.inject(connectedJson)
        assertEquals(
            InboundEvent.Connected(SessionId(10), defaultAnsiEnabled = true),
            delegate.tryReceive().getOrNull(),
        )

        val disconnectedJson =
            """{"instanceId":"other","type":"Disconnected","sessionId":11,"reason":"bye"}"""
        fakeSubscriber.inject(disconnectedJson)
        assertEquals(
            InboundEvent.Disconnected(SessionId(11), "bye"),
            delegate.tryReceive().getOrNull(),
        )

        val lineReceivedJson =
            """{"instanceId":"other","type":"LineReceived","sessionId":12,"line":"hello"}"""
        fakeSubscriber.inject(lineReceivedJson)
        assertEquals(
            InboundEvent.LineReceived(SessionId(12), "hello"),
            delegate.tryReceive().getOrNull(),
        )
    }

    @Test
    fun `invalid JSON is handled gracefully without throwing`() {
        fakeSubscriber.inject("not valid json {{{{")

        assertFalse(delegate.tryReceive().isSuccess)
    }

    @Test
    fun `unknown event type is handled gracefully without throwing`() {
        val json = """{"instanceId":"other","type":"UnknownEventType","sessionId":1}"""
        fakeSubscriber.inject(json)

        assertFalse(delegate.tryReceive().isSuccess)
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
