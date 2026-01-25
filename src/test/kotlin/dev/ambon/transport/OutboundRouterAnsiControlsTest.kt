package dev.ambon.transport

import dev.ambon.domain.SessionId
import dev.ambon.engine.events.OutboundEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OutboundRouterAnsiControlsTest {
    @Test
    fun `ClearScreen emits ANSI clear sequence when ansi enabled`() =
        runTest {
            val engineOutbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val router = OutboundRouter(engineOutbound, this)
            val job = router.start()

            val sid = SessionId(1)
            val q = Channel<String>(capacity = 10)
            router.register(sid, q) { fail("should not close") }

            engineOutbound.send(OutboundEvent.SetAnsi(sid, enabled = true))
            engineOutbound.send(OutboundEvent.ClearScreen(sid))
            runCurrent()

            val out = q.tryReceive().getOrNull()
            assertNotNull(out, "Expected output for ClearScreen")
            assertEquals("\u001B[2J\u001B[H", out, "Expected exact clear+home sequence")

            job.cancel()
            q.close()
            engineOutbound.close()
        }

    @Test
    fun `ClearScreen falls back to dashed line when ansi disabled`() =
        runTest {
            val engineOutbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val router = OutboundRouter(engineOutbound, this)
            val job = router.start()

            val sid = SessionId(2)
            val q = Channel<String>(capacity = 10)
            router.register(sid, q) { fail("should not close") }

            engineOutbound.send(OutboundEvent.ClearScreen(sid))
            runCurrent()

            val out = q.tryReceive().getOrNull()
            assertNotNull(out, "Expected output for ClearScreen fallback")

            // sendLine -> renderer.renderLine -> PlainRenderer appends CRLF
            assertTrue(out!!.startsWith("----------------"), "Expected dashed fallback. Got: $out")
            assertTrue(out.endsWith("\r\n"), "Expected CRLF framing. Got: $out")

            job.cancel()
            q.close()
            engineOutbound.close()
        }

    @Test
    fun `ShowAnsiDemo prints info message when ansi is off`() =
        runTest {
            val engineOutbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val router = OutboundRouter(engineOutbound, this)
            val job = router.start()

            val sid = SessionId(3)
            val q = Channel<String>(capacity = 10)
            router.register(sid, q) { fail("should not close") }

            engineOutbound.send(OutboundEvent.ShowAnsiDemo(sid))
            runCurrent()

            val out = q.tryReceive().getOrNull()
            assertNotNull(out, "Expected output for ShowAnsiDemo fallback")
            assertTrue(out!!.contains("ANSI is off. Type: ansi on"), "Unexpected fallback text: $out")
            assertTrue(out.endsWith("\r\n"), "Expected CRLF framing. Got: $out")

            job.cancel()
            q.close()
            engineOutbound.close()
        }

    @Test
    fun `ShowAnsiDemo emits ANSI escapes when ansi enabled`() =
        runTest {
            val engineOutbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val router = OutboundRouter(engineOutbound, this)
            val job = router.start()

            val sid = SessionId(4)
            val q = Channel<String>(capacity = 10)
            router.register(sid, q) { fail("should not close") }

            engineOutbound.send(OutboundEvent.SetAnsi(sid, enabled = true))
            engineOutbound.send(OutboundEvent.ShowAnsiDemo(sid))
            runCurrent()

            val out = q.tryReceive().getOrNull()
            assertNotNull(out, "Expected output for ShowAnsiDemo")
            assertTrue(out!!.contains("\u001B["), "Expected at least one ANSI escape sequence. Got: $out")
            assertTrue(out.contains("bright red"), "Expected demo text content. Got: $out")

            // Your showAnsiDemo() appends "\r\n" and then reset, so it ends with reset.
            assertTrue(out.endsWith("\u001B[0m"), "Expected trailing reset. Got: $out")

            job.cancel()
            q.close()
            engineOutbound.close()
        }

    @Test
    fun `ClearScreen and ShowAnsiDemo do not close the session`() =
        runTest {
            val engineOutbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val router = OutboundRouter(engineOutbound, this)
            val job = router.start()

            val sid = SessionId(5)
            val q = Channel<String>(capacity = 10)

            var closeCalls = 0
            var lastCloseReason: String? = null
            router.register(sid, q) { reason ->
                closeCalls++
                lastCloseReason = reason
            }

            // ANSI on: ClearScreen sends escape sequence; ShowAnsiDemo sends palette.
            engineOutbound.send(OutboundEvent.SetAnsi(sid, enabled = true))
            engineOutbound.send(OutboundEvent.ClearScreen(sid))
            engineOutbound.send(OutboundEvent.ShowAnsiDemo(sid))

            // ANSI off: ClearScreen falls back to dashed line; ShowAnsiDemo falls back to info line.
            engineOutbound.send(OutboundEvent.SetAnsi(sid, enabled = false))
            engineOutbound.send(OutboundEvent.ClearScreen(sid))
            engineOutbound.send(OutboundEvent.ShowAnsiDemo(sid))

            runCurrent()

            assertEquals(0, closeCalls, "Expected no close() calls, got $closeCalls. Last reason=$lastCloseReason")

            job.cancel()
            q.close()
            engineOutbound.close()
        }
}
