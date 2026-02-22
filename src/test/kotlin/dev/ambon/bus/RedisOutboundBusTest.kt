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
        )

    @BeforeEach
    fun setUp() {
        bus.startSubscribing()
    }

    @Test
    fun `send delivers event to local delegate`() =
        runTest {
            val event = OutboundEvent.SendText(SessionId(1), "hello")
            bus.send(event)

            val received = delegate.tryReceive()
            assertTrue(received.isSuccess)
            assertEquals(event, received.getOrNull())
        }

    @Test
    fun `send publishes JSON to Redis with correct fields`() =
        runTest {
            val event = OutboundEvent.SendText(SessionId(1), "hello world")
            bus.send(event)

            assertEquals(1, fakePublisher.messages.size)
            val (channel, json) = fakePublisher.messages[0]
            assertEquals("test-outbound", channel)
            val parsed = mapper.readTree(json)
            assertEquals("server-1", parsed["instanceId"].asText())
            assertEquals("SendText", parsed["type"].asText())
            assertEquals(1L, parsed["sessionId"].asLong())
            assertEquals("hello world", parsed["text"].asText())
        }

    @Test
    fun `received message from other instance is forwarded to delegate`() {
        val json = """{"instanceId":"server-2","type":"SendText","sessionId":3,"text":"hi"}"""
        fakeSubscriber.inject(json)

        val event = delegate.tryReceive()
        assertTrue(event.isSuccess)
        assertEquals(OutboundEvent.SendText(SessionId(3), "hi"), event.getOrNull())
    }

    @Test
    fun `received message from own instanceId is filtered out`() {
        val json = """{"instanceId":"server-1","type":"SendText","sessionId":3,"text":"own"}"""
        fakeSubscriber.inject(json)

        assertFalse(delegate.tryReceive().isSuccess)
    }

    @Test
    fun `all outbound event types round-trip through JSON`() {
        val cases =
            listOf(
                """{"instanceId":"o","type":"SendText","sessionId":1,"text":"txt"}""" to
                    OutboundEvent.SendText(SessionId(1), "txt"),
                """{"instanceId":"o","type":"SendInfo","sessionId":2,"text":"info"}""" to
                    OutboundEvent.SendInfo(SessionId(2), "info"),
                """{"instanceId":"o","type":"SendError","sessionId":3,"text":"err"}""" to
                    OutboundEvent.SendError(SessionId(3), "err"),
                """{"instanceId":"o","type":"SendPrompt","sessionId":4}""" to
                    OutboundEvent.SendPrompt(SessionId(4)),
                """{"instanceId":"o","type":"ShowLoginScreen","sessionId":5}""" to
                    OutboundEvent.ShowLoginScreen(SessionId(5)),
                """{"instanceId":"o","type":"SetAnsi","sessionId":6,"enabled":true}""" to
                    OutboundEvent.SetAnsi(SessionId(6), true),
                """{"instanceId":"o","type":"Close","sessionId":7,"reason":"bye"}""" to
                    OutboundEvent.Close(SessionId(7), "bye"),
                """{"instanceId":"o","type":"ClearScreen","sessionId":8}""" to
                    OutboundEvent.ClearScreen(SessionId(8)),
                """{"instanceId":"o","type":"ShowAnsiDemo","sessionId":9}""" to
                    OutboundEvent.ShowAnsiDemo(SessionId(9)),
            )

        for ((json, expected) in cases) {
            fakeSubscriber.inject(json)
            assertEquals(expected, delegate.tryReceive().getOrNull(), "Failed for type ${expected::class.simpleName}")
        }
    }

    @Test
    fun `send publishes SetAnsi with enabled flag`() =
        runTest {
            val event = OutboundEvent.SetAnsi(SessionId(10), enabled = true)
            bus.send(event)

            assertEquals(1, fakePublisher.messages.size)
            val parsed = mapper.readTree(fakePublisher.messages[0].second)
            assertEquals("SetAnsi", parsed["type"].asText())
            assertEquals(true, parsed["enabled"].asBoolean())
        }

    @Test
    fun `send publishes Close with reason`() =
        runTest {
            val event = OutboundEvent.Close(SessionId(11), "kicked")
            bus.send(event)

            assertEquals(1, fakePublisher.messages.size)
            val parsed = mapper.readTree(fakePublisher.messages[0].second)
            assertEquals("Close", parsed["type"].asText())
            assertEquals("kicked", parsed["reason"].asText())
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
