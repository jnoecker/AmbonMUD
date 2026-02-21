package dev.ambon.bus

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.OutboundEvent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalOutboundBusTest {
    @Test
    fun `send and tryReceive round-trips events`() =
        runTest {
            val bus = LocalOutboundBus()
            val event = OutboundEvent.SendText(SessionId(1), "hello")
            bus.send(event)

            val result = bus.tryReceive()
            assertTrue(result.isSuccess)
            assertEquals(event, result.getOrNull())
        }

    @Test
    fun `tryReceive returns failure when empty`() {
        val bus = LocalOutboundBus()
        val result = bus.tryReceive()
        assertFalse(result.isSuccess)
    }

    @Test
    fun `asReceiveChannel iterates sent events`() =
        runTest {
            val bus = LocalOutboundBus()
            val event1 = OutboundEvent.SendText(SessionId(1), "hello")
            val event2 = OutboundEvent.SendPrompt(SessionId(1))
            bus.send(event1)
            bus.send(event2)

            val channel = bus.asReceiveChannel()
            assertEquals(event1, channel.tryReceive().getOrNull())
            assertEquals(event2, channel.tryReceive().getOrNull())
        }

    @Test
    fun `close prevents further sends`() =
        runTest {
            val bus = LocalOutboundBus()
            bus.close()
            val result =
                runCatching { bus.send(OutboundEvent.SendText(SessionId(1), "hello")) }
            assertTrue(result.isFailure)
        }
}
