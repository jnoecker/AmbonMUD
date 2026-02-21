package dev.ambon.bus

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.InboundEvent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalInboundBusTest {
    @Test
    fun `send and tryReceive round-trips events`() =
        runTest {
            val bus = LocalInboundBus()
            val event = InboundEvent.Connected(SessionId(1))
            bus.send(event)

            val result = bus.tryReceive()
            assertTrue(result.isSuccess)
            assertEquals(event, result.getOrNull())
        }

    @Test
    fun `trySend succeeds on unbounded bus`() {
        val bus = LocalInboundBus()
        val event = InboundEvent.LineReceived(SessionId(1), "hello")
        val result = bus.trySend(event)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `trySend fails when capacity exhausted`() {
        val bus = LocalInboundBus(capacity = 1)
        val event = InboundEvent.LineReceived(SessionId(1), "hello")
        bus.trySend(event)
        val result = bus.trySend(InboundEvent.LineReceived(SessionId(2), "world"))
        assertFalse(result.isSuccess)
    }

    @Test
    fun `tryReceive returns failure when empty`() {
        val bus = LocalInboundBus()
        val result = bus.tryReceive()
        assertFalse(result.isSuccess)
    }

    @Test
    fun `close prevents further sends`() =
        runTest {
            val bus = LocalInboundBus()
            bus.close()
            val result = bus.trySend(InboundEvent.Connected(SessionId(1)))
            assertFalse(result.isSuccess)
        }
}
