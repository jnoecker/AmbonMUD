package dev.ambon.transport

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.ui.login.LoginScreen
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OutboundRouterAnsiControlsTest {
    @Test
    fun `ClearScreen emits ANSI clear sequence when ansi enabled`() =
        runTest {
            val engineOutbound = LocalOutboundBus()
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
            val engineOutbound = LocalOutboundBus()
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
            val engineOutbound = LocalOutboundBus()
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
            val engineOutbound = LocalOutboundBus()
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
    fun `ShowLoginScreen prints plain text lines when ansi is off`() =
        runTest {
            val engineOutbound = LocalOutboundBus()
            val router =
                OutboundRouter(
                    engineOutbound = engineOutbound,
                    scope = this,
                    loginScreen =
                        LoginScreen(
                            lines = listOf("a", "b"),
                            ansiPrefixesByLine = listOf("\u001B[93m", "\u001B[92m"),
                        ),
                )
            val job = router.start()

            val sid = SessionId(41)
            val q = Channel<String>(capacity = 10)
            router.register(sid, q) { fail("should not close") }

            engineOutbound.send(OutboundEvent.ShowLoginScreen(sid))
            runCurrent()

            assertEquals("a\r\n", q.tryReceive().getOrNull())
            assertEquals("b\r\n", q.tryReceive().getOrNull())

            job.cancel()
            q.close()
            engineOutbound.close()
        }

    @Test
    fun `ShowLoginScreen emits ANSI escapes when ansi enabled`() =
        runTest {
            val engineOutbound = LocalOutboundBus()
            val router =
                OutboundRouter(
                    engineOutbound = engineOutbound,
                    scope = this,
                    loginScreen =
                        LoginScreen(
                            lines = listOf("W E L C O M E", "TIP: help"),
                            ansiPrefixesByLine = listOf("\u001B[93m", "\u001B[92m"),
                        ),
                )
            val job = router.start()

            val sid = SessionId(42)
            val q = Channel<String>(capacity = 10)
            router.register(sid, q) { fail("should not close") }

            engineOutbound.send(OutboundEvent.SetAnsi(sid, enabled = true))
            engineOutbound.send(OutboundEvent.ShowLoginScreen(sid))
            runCurrent()

            val first = q.tryReceive().getOrNull()
            val second = q.tryReceive().getOrNull()

            assertNotNull(first, "Expected first login banner line")
            assertNotNull(second, "Expected second login banner line")
            assertTrue(first!!.contains("\u001B["), "Expected ANSI in first line. got=$first")
            assertTrue(second!!.contains("\u001B["), "Expected ANSI in second line. got=$second")
            assertTrue(first.contains("W E L C O M E"), "Expected banner text in first line. got=$first")
            assertTrue(second.contains("TIP: help"), "Expected banner text in second line. got=$second")

            job.cancel()
            q.close()
            engineOutbound.close()
        }

    @Test
    fun `rejects invalid login screen when style count does not match line count`() =
        runTest {
            val engineOutbound = LocalOutboundBus()

            assertThrows(IllegalArgumentException::class.java) {
                OutboundRouter(
                    engineOutbound = engineOutbound,
                    scope = this,
                    loginScreen =
                        LoginScreen(
                            lines = listOf("line one", "line two"),
                            ansiPrefixesByLine = listOf(""),
                        ),
                )
            }

            engineOutbound.close()
        }

    @Test
    fun `ClearScreen and ShowAnsiDemo do not close the session`() =
        runTest {
            val engineOutbound = LocalOutboundBus()
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
