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
    private val fakePublisher = FakePublisher()
    private val fakeSubscriber = FakeSubscriberSetup()
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

    // ── Round-trip tests for every variant ─────────────────────────────────
    // These catch missing deserialize branches (string-matching with else → null).

    @Test
    fun `Connected round-trips through Redis`() {
        roundTrip(
            expected = InboundEvent.Connected(SessionId(5), defaultAnsiEnabled = true),
            sessionId = 5,
            type = "Connected",
            defaultAnsiEnabled = true,
        )
    }

    @Test
    fun `Disconnected round-trips through Redis`() {
        roundTrip(
            expected = InboundEvent.Disconnected(SessionId(6), reason = "timeout"),
            sessionId = 6,
            type = "Disconnected",
            reason = "timeout",
        )
    }

    @Test
    fun `LineReceived round-trips through Redis`() {
        roundTrip(
            expected = InboundEvent.LineReceived(SessionId(7), line = "look"),
            sessionId = 7,
            type = "LineReceived",
            line = "look",
        )
    }

    @Test
    fun `GmcpReceived round-trips through Redis`() {
        roundTrip(
            expected = InboundEvent.GmcpReceived(SessionId(8), gmcpPackage = "Char.Vitals", jsonData = "{\"hp\":10}"),
            sessionId = 8,
            type = "GmcpReceived",
            gmcpPackage = "Char.Vitals",
            jsonData = "{\"hp\":10}",
        )
    }

    private fun roundTrip(
        expected: InboundEvent,
        sessionId: Long,
        type: String,
        defaultAnsiEnabled: Boolean = false,
        reason: String = "",
        line: String = "",
        gmcpPackage: String = "",
        jsonData: String = "",
    ) {
        fakeSubscriber.inject(
            signedInboundJson(
                instanceId = "server-2",
                type = type,
                sessionId = sessionId,
                defaultAnsiEnabled = defaultAnsiEnabled,
                reason = reason,
                line = line,
                gmcpPackage = gmcpPackage,
                jsonData = jsonData,
            ),
        )

        val received = delegate.tryReceive()
        assertTrue(received.isSuccess, "Expected $type to deserialize successfully")
        assertEquals(expected, received.getOrNull())
    }

    @Test
    fun `all InboundEvent variants have a toEnvelope mapping`() =
        runTest {
            val sid = SessionId(100)
            val variantNames =
                InboundEvent::class.sealedSubclasses.map { it.simpleName!! }.toSet()
            val sampleEvents: List<InboundEvent> =
                listOf(
                    InboundEvent.Connected(sid),
                    InboundEvent.Disconnected(sid, "quit"),
                    InboundEvent.LineReceived(sid, "test"),
                    InboundEvent.GmcpReceived(sid, "Core.Hello", "{}"),
                )
            val coveredNames = sampleEvents.map { it::class.simpleName!! }.toSet()
            assertEquals(
                variantNames,
                coveredNames,
                "Missing InboundEvent round-trip coverage for: ${variantNames - coveredNames}",
            )
            // Verify every sample actually produces an envelope (toEnvelope returns non-null)
            for (event in sampleEvents) {
                bus.send(event)
                assertTrue(fakePublisher.messages.isNotEmpty(), "${event::class.simpleName} should produce a Redis envelope")
                fakePublisher.messages.clear()
            }
        }

    @Test
    fun `trySend does not publish when delegate channel is full`() {
        val fullDelegate = LocalInboundBus(capacity = 1)
        fullDelegate.trySend(InboundEvent.Connected(SessionId(99)))
        val localPublisher = FakePublisher()
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
        gmcpPackage: String = "",
        jsonData: String = "",
    ): String {
        val payload = "$instanceId|$type|$sessionId|$defaultAnsiEnabled|$reason|$line|$gmcpPackage|$jsonData"
        val signature = hmacSha256(sharedSecret, payload)
        return mapper.writeValueAsString(
            mapOf(
                "instanceId" to instanceId,
                "type" to type,
                "sessionId" to sessionId,
                "defaultAnsiEnabled" to defaultAnsiEnabled,
                "reason" to reason,
                "line" to line,
                "gmcpPackage" to gmcpPackage,
                "jsonData" to jsonData,
                "signature" to signature,
            ),
        )
    }
}
