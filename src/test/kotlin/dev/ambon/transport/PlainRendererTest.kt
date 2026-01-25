package dev.ambon.transport

import dev.ambon.domain.SessionId
import dev.ambon.engine.events.OutboundEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

class PlainRendererTest {
    @Test
    fun `prompt doesn't contain ansi escape codes`() {
        val r = PlainRenderer()
        val p = r.renderPrompt(PromptSpec("> "))
        assertFalse(p.contains("\u001B["), "Did not expect ANSI escape in prompt: $p")
        assertTrue(p.contains("> "), "Prompt should include > : $p")
    }

    @Test
    fun `renderLine appends CRLF`() {
        val r = PlainRenderer()
        val line = r.renderLine("Hello", TextKind.INFO)
        assertTrue(line.endsWith("\r\n"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `prompt rendering uses PromptSpec text not toString`() =
        runTest {
            val engineOutbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val router = OutboundRouter(engineOutbound, this)
            val job = router.start()

            val sid = SessionId(1)
            val q = Channel<String>(10)
            router.register(sid, q) { fail("should not close") }

            engineOutbound.send(OutboundEvent.SendPrompt(sid))
            runCurrent()

            val prompt = q.tryReceive().getOrNull()
            assertEquals("> ", prompt)
            assertFalse(prompt!!.contains("PromptSpec"))

            job.cancel()
            q.close()
            engineOutbound.close()
        }
}
