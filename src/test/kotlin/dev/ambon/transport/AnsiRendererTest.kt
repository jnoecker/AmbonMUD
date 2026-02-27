package dev.ambon.transport

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.OutboundEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

class AnsiRendererTest {
    @Test
    fun `prompt contains ansi escape codes`() {
        val r = AnsiRenderer()
        val p = r.renderPrompt(PromptSpec("> "))
        assertTrue(p.contains("\u001B["), "Expected ANSI escape in prompt: $p")
        assertTrue(p.contains("> "), "Prompt should include > : $p")
    }

    @Test
    fun `renderLine appends CRLF`() {
        val r = AnsiRenderer()
        val line = r.renderLine("Hello", TextKind.INFO)
        assertTrue(line.endsWith("\r\n"))
    }

    @Test
    fun `renderLine normalizes embedded newlines to CRLF`() {
        val r = AnsiRenderer()
        val line = r.renderLine("a\nb\rc\r\nd", TextKind.INFO)
        assertTrue(line.contains("a\r\nb\r\nc\r\nd"))
        assertTrue(line.endsWith("\r\n"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `prompt rendering uses PromptSpec text not toString`() =
        runTest {
            val engineOutbound = LocalOutboundBus()
            val router = OutboundRouter(engineOutbound, this)
            val job = router.start()

            val sid = SessionId(1)
            val q = Channel<OutboundFrame>(10)
            router.register(sid, q) { fail("should not close") }

            engineOutbound.send(OutboundEvent.SendPrompt(sid))
            runCurrent()

            val ansiPrompt = (q.tryReceive().getOrNull() as? OutboundFrame.Text)?.content
            assertTrue(ansiPrompt!!.contains("> "))
            assertFalse(ansiPrompt.contains("PromptSpec"))

            job.cancel()
            q.close()
            engineOutbound.close()
        }
}
