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

    @Test
    fun `color tag is translated to ANSI escape`() {
        val r = AnsiRenderer()
        val line = r.renderLine("alpha {c:quest}(!){/c} omega", TextKind.NORMAL)
        // {c:quest} -> bright yellow; {/c} -> base prefix (NORMAL = reset)
        assertTrue(line.contains("\u001B[93m(!)"), "Expected quest color around (!): $line")
        // Tag literals must not leak
        assertFalse(line.contains("{c:quest}"), "Open tag should not leak: $line")
        assertFalse(line.contains("{/c}"), "Close tag should not leak: $line")
    }

    @Test
    fun `unknown color tag name is stripped without leaking`() {
        val r = AnsiRenderer()
        val line = r.renderLine("hello {c:bogus}world{/c}", TextKind.NORMAL)
        assertTrue(line.contains("hello world"), "Wrapped text must survive: $line")
        assertFalse(line.contains("{c:bogus}"), "Unknown open tag must be stripped: $line")
        assertFalse(line.contains("{/c}"), "Close tag must be stripped: $line")
    }

    @Test
    fun `close tag restores INFO base color`() {
        val r = AnsiRenderer()
        val line = r.renderLine("a{c:aggro}[A]{/c}b", TextKind.INFO)
        // After {/c}, the INFO base color (dim + bright cyan) should be re-asserted so 'b' keeps it.
        val infoPrefix = "\u001B[2m\u001B[96m"
        assertTrue(line.contains("[A]" + infoPrefix + "b"), "Expected INFO prefix restored after {/c}: $line")
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
