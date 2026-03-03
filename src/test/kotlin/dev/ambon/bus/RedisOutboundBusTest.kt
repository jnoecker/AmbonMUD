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
    private val fakePublisher = FakePublisher()
    private val fakeSubscriber = FakeSubscriberSetup()
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

    // ── Round-trip tests for every variant ─────────────────────────────────
    // These catch missing deserialize branches (string-matching with else → null).

    @Test
    fun `SendText round-trips through Redis`() {
        roundTrip(
            expected = OutboundEvent.SendText(SessionId(10), text = "hello"),
            sessionId = 10,
            type = "SendText",
            text = "hello",
        )
    }

    @Test
    fun `SendInfo round-trips through Redis`() {
        roundTrip(
            expected = OutboundEvent.SendInfo(SessionId(11), text = "info msg"),
            sessionId = 11,
            type = "SendInfo",
            text = "info msg",
        )
    }

    @Test
    fun `SendError round-trips through Redis`() {
        roundTrip(
            expected = OutboundEvent.SendError(SessionId(12), text = "error msg"),
            sessionId = 12,
            type = "SendError",
            text = "error msg",
        )
    }

    @Test
    fun `SendPrompt round-trips through Redis`() {
        roundTrip(
            expected = OutboundEvent.SendPrompt(SessionId(13)),
            sessionId = 13,
            type = "SendPrompt",
        )
    }

    @Test
    fun `ShowLoginScreen round-trips through Redis`() {
        roundTrip(
            expected = OutboundEvent.ShowLoginScreen(SessionId(14)),
            sessionId = 14,
            type = "ShowLoginScreen",
        )
    }

    @Test
    fun `SetAnsi round-trips through Redis`() {
        roundTrip(
            expected = OutboundEvent.SetAnsi(SessionId(15), enabled = true),
            sessionId = 15,
            type = "SetAnsi",
            enabled = true,
        )
    }

    @Test
    fun `Close round-trips through Redis`() {
        roundTrip(
            expected = OutboundEvent.Close(SessionId(16), reason = "kicked"),
            sessionId = 16,
            type = "Close",
            reason = "kicked",
        )
    }

    @Test
    fun `ClearScreen round-trips through Redis`() {
        roundTrip(
            expected = OutboundEvent.ClearScreen(SessionId(17)),
            sessionId = 17,
            type = "ClearScreen",
        )
    }

    @Test
    fun `ShowAnsiDemo round-trips through Redis`() {
        roundTrip(
            expected = OutboundEvent.ShowAnsiDemo(SessionId(18)),
            sessionId = 18,
            type = "ShowAnsiDemo",
        )
    }

    @Test
    fun `GmcpData round-trips through Redis`() {
        roundTrip(
            expected = OutboundEvent.GmcpData(SessionId(19), gmcpPackage = "Room.Info", jsonData = "{\"id\":\"z:r\"}"),
            sessionId = 19,
            type = "GmcpData",
            gmcpPackage = "Room.Info",
            jsonData = "{\"id\":\"z:r\"}",
        )
    }

    @Test
    fun `SessionRedirect is not published to Redis`() =
        runTest {
            val event = OutboundEvent.SessionRedirect(SessionId(20), "engine-2", "host2", 9092)
            bus.send(event)

            // SessionRedirect is control-plane only — should not appear on Redis
            assertTrue(fakePublisher.messages.isEmpty())
        }

    private fun roundTrip(
        expected: OutboundEvent,
        sessionId: Long,
        type: String,
        text: String = "",
        enabled: Boolean = false,
        reason: String = "",
        gmcpPackage: String = "",
        jsonData: String = "",
    ) {
        fakeSubscriber.inject(
            signedOutboundJson(
                instanceId = "server-2",
                type = type,
                sessionId = sessionId,
                text = text,
                enabled = enabled,
                reason = reason,
                gmcpPackage = gmcpPackage,
                jsonData = jsonData,
            ),
        )

        val received = delegate.tryReceive()
        assertTrue(received.isSuccess, "Expected $type to deserialize successfully")
        assertEquals(expected, received.getOrNull())
    }

    private fun signedOutboundJson(
        instanceId: String,
        type: String,
        sessionId: Long,
        text: String = "",
        enabled: Boolean = false,
        reason: String = "",
        gmcpPackage: String = "",
        jsonData: String = "",
    ): String {
        val payload = "$instanceId|$type|$sessionId|$text|$enabled|$reason|$gmcpPackage|$jsonData"
        val signature = hmacSha256(sharedSecret, payload)
        return mapper.writeValueAsString(
            mapOf(
                "instanceId" to instanceId,
                "type" to type,
                "sessionId" to sessionId,
                "text" to text,
                "enabled" to enabled,
                "reason" to reason,
                "gmcpPackage" to gmcpPackage,
                "jsonData" to jsonData,
                "signature" to signature,
            ),
        )
    }
}
