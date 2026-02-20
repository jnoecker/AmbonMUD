package dev.ambon.ui.login

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LoginScreenRendererTest {
    private val renderer = LoginScreenRenderer()

    @Test
    fun `render outputs plain frames when ansi is disabled`() {
        val screen =
            LoginScreen(
                lines = listOf("a", "b"),
                ansiPrefixesByLine = listOf("\u001B[93m", "\u001B[92m"),
            )

        val frames = renderer.render(screen, ansiEnabled = false)

        assertEquals(listOf("a\r\n", "b\r\n"), frames)
    }

    @Test
    fun `render preserves spaces-only lines`() {
        val screen =
            LoginScreen(
                lines = listOf("   "),
                ansiPrefixesByLine = listOf("\u001B[93m"),
            )

        val frames = renderer.render(screen, ansiEnabled = true)

        assertTrue(frames[0].contains("   "), "Expected spaces to be preserved. frame=${frames[0]}")
        assertTrue(frames[0].endsWith("\r\n"), "Expected CRLF framing. frame=${frames[0]}")
    }

    @Test
    fun `render emits newline for empty lines`() {
        val screen =
            LoginScreen(
                lines = listOf(""),
                ansiPrefixesByLine = listOf("\u001B[93m"),
            )

        val frames = renderer.render(screen, ansiEnabled = true)

        assertEquals(listOf("\r\n"), frames)
    }
}
