package dev.ambon.transport

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.OutboundEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
class OutboundRouterTest {
    @Test
    fun `disconnects slow client when outbound queue is full`() =
        runTest {
            val engineOutbound = LocalOutboundBus()
            val router = OutboundRouter(engineOutbound, this)

            val sessionId = SessionId(1L)
            val perSessionQueue = Channel<String>(capacity = 1)

            val closedReason = AtomicReference<String?>(null)
            router.register(sessionId, perSessionQueue) { reason ->
                closedReason.set(reason)
            }

            val job = router.start()

            // Fill to capacity
            assertTrue(perSessionQueue.trySend("first").isSuccess)

            // Overflow
            engineOutbound.send(OutboundEvent.SendText(sessionId, "second"))
            testScheduler.advanceUntilIdle()

            val reason = closedReason.get()
            assertNotNull(reason, "Expected session to be closed due to backpressure")
            assertTrue(
                reason!!.contains("too slow", ignoreCase = true) ||
                    reason.contains("backpressure", ignoreCase = true),
                "Close reason should mention backpressure/slow client, got: $reason",
            )

            // After removal, further sends should not enqueue anything new.
            engineOutbound.send(OutboundEvent.SendText(sessionId, "third"))
            testScheduler.advanceUntilIdle()

            assertEquals("first", perSessionQueue.tryReceive().getOrNull())
            assertNull(perSessionQueue.tryReceive().getOrNull())

            job.cancel()
            engineOutbound.close()
            perSessionQueue.close()
        }

    @Test
    fun `routes outbound events to correct session queue`() =
        runTest {
            val engineOutbound = LocalOutboundBus()
            val router = OutboundRouter(engineOutbound, this)
            val job = router.start()

            val q1 = Channel<String>(capacity = 10)
            val q2 = Channel<String>(capacity = 10)

            router.register(SessionId(1), q1) { fail("Session 1 should not close") }
            router.register(SessionId(2), q2) { fail("Session 2 should not close") }

            engineOutbound.send(OutboundEvent.SendText(SessionId(1), "one"))
            engineOutbound.send(OutboundEvent.SendText(SessionId(2), "two"))
            testScheduler.advanceUntilIdle()

            assertEquals("one\r\n", q1.tryReceive().getOrNull())
            assertEquals("two\r\n", q2.tryReceive().getOrNull())
            assertNull(q1.tryReceive().getOrNull())
            assertNull(q2.tryReceive().getOrNull())

            job.cancel()
            engineOutbound.close()
            q1.close()
            q2.close()
        }

    @Test
    fun `unregister stops delivery`() =
        runTest {
            val engineOutbound = LocalOutboundBus()
            val router = OutboundRouter(engineOutbound, this)
            val job = router.start()

            val sessionId = SessionId(42)
            val q = Channel<String>(capacity = 10)
            val closes = AtomicInteger(0)

            router.register(sessionId, q) { closes.incrementAndGet() }

            // Unregister, then attempt to deliver
            router.unregister(sessionId)
            engineOutbound.send(OutboundEvent.SendText(sessionId, "should-not-arrive"))
            testScheduler.advanceUntilIdle()

            assertNull(q.tryReceive().getOrNull(), "Queue should not receive messages after unregister")
            assertEquals(0, closes.get(), "Close callback should not be invoked by simple unregister")

            job.cancel()
            engineOutbound.close()
            q.close()
        }

    @Test
    fun `forceDisconnect closes one registered session`() =
        runTest {
            val engineOutbound = LocalOutboundBus()
            val router = OutboundRouter(engineOutbound, this)
            val job = router.start()

            val sessionId = SessionId(84)
            val q = Channel<String>(capacity = 10)
            val closedReason = AtomicReference<String?>(null)
            router.register(sessionId, q) { reason -> closedReason.set(reason) }

            val disconnected = router.forceDisconnect(sessionId, "engine stream lost")
            assertTrue(disconnected)
            assertEquals("engine stream lost", closedReason.get())

            engineOutbound.send(OutboundEvent.SendText(sessionId, "should-not-arrive"))
            testScheduler.advanceUntilIdle()
            assertNull(q.tryReceive().getOrNull(), "Queue should not receive data after forceDisconnect")

            job.cancel()
            engineOutbound.close()
            q.close()
        }

    @Test
    fun `disconnectAll closes all registered sessions`() =
        runTest {
            val engineOutbound = LocalOutboundBus()
            val router = OutboundRouter(engineOutbound, this)
            val job = router.start()

            val sid1 = SessionId(100)
            val sid2 = SessionId(101)
            val q1 = Channel<String>(capacity = 10)
            val q2 = Channel<String>(capacity = 10)
            val reason1 = AtomicReference<String?>(null)
            val reason2 = AtomicReference<String?>(null)
            router.register(sid1, q1) { reason -> reason1.set(reason) }
            router.register(sid2, q2) { reason -> reason2.set(reason) }

            val disconnected = router.disconnectAll("engine restart")
            assertEquals(2, disconnected)
            assertEquals("engine restart", reason1.get())
            assertEquals("engine restart", reason2.get())

            engineOutbound.send(OutboundEvent.SendText(sid1, "nope-1"))
            engineOutbound.send(OutboundEvent.SendText(sid2, "nope-2"))
            testScheduler.advanceUntilIdle()
            assertNull(q1.tryReceive().getOrNull())
            assertNull(q2.tryReceive().getOrNull())

            job.cancel()
            engineOutbound.close()
            q1.close()
            q2.close()
        }

    @Test
    fun `Close event invokes close callback and stops further delivery`() =
        runTest {
            val engineOutbound = LocalOutboundBus()
            val router = OutboundRouter(engineOutbound, this)
            val job = router.start()

            val sessionId = SessionId(7)
            val q = Channel<String>(capacity = 10)
            val closedReason = AtomicReference<String?>(null)

            router.register(sessionId, q) { reason -> closedReason.set(reason) }

            engineOutbound.send(OutboundEvent.Close(sessionId, "bye"))
            testScheduler.advanceUntilIdle()

            assertNotNull(closedReason.get(), "Expected close callback to be invoked")

            // After close, no further messages should be routed.
            engineOutbound.send(OutboundEvent.SendText(sessionId, "nope"))
            testScheduler.advanceUntilIdle()

            // Depending on your router behavior, there may be a best-effort "bye" in the queue.
            // We'll just assert "nope" didn't arrive.
            val drained = mutableListOf<String>()
            while (true) drained += (q.tryReceive().getOrNull() ?: break)
            assertFalse(drained.any { it.contains("nope") }, "Should not deliver after Close; drained=$drained")

            job.cancel()
            engineOutbound.close()
            q.close()
        }

    @Test
    fun `SetAnsi changes prompt rendering`() =
        runTest {
            val engineOutbound = LocalOutboundBus()
            val router = OutboundRouter(engineOutbound, this)
            val job = router.start()

            val sid = SessionId(1)
            val q = Channel<String>(10)
            router.register(sid, q) { fail("should not close") }

            engineOutbound.send(OutboundEvent.SendPrompt(sid))
            runCurrent()
            val plainPrompt = q.tryReceive().getOrNull()
            assertEquals("> ", plainPrompt)

            engineOutbound.send(OutboundEvent.SetAnsi(sid, true))
            engineOutbound.send(OutboundEvent.SendPrompt(sid))
            runCurrent()
            val ansiPrompt = q.tryReceive().getOrNull()
            assertNotNull(ansiPrompt)
            assertTrue(ansiPrompt!!.contains("\u001B["), "Expected ANSI escape: $ansiPrompt")

            job.cancel()
            q.close()
            engineOutbound.close()
        }
}
