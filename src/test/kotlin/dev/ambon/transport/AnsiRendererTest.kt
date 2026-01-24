package dev.ambon.transport

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
        val line = r.renderLine("Hello")
        assertTrue(line.endsWith("\r\n"))
    }
}
