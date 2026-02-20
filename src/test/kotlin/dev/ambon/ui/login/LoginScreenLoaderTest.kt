package dev.ambon.ui.login

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LoginScreenLoaderTest {
    @Test
    fun `loadFromTexts uses fallback welcome line when login text is missing`() {
        val screen =
            LoginScreenLoader.loadFromTexts(
                linesText = null,
                stylesYaml = null,
            )

        assertEquals(listOf("Welcome to AmbonMUD"), screen.lines)
        assertEquals(listOf(""), screen.ansiPrefixesByLine)
        assertTrue(screen.isValid())
    }

    @Test
    fun `loadFromTexts falls back to plain when styles are missing`() {
        val screen =
            LoginScreenLoader.loadFromTexts(
                linesText = "line one\nline two",
                stylesYaml = null,
            )

        assertEquals(listOf("line one", "line two"), screen.lines)
        assertEquals(listOf("", ""), screen.ansiPrefixesByLine)
    }

    @Test
    fun `loadFromTexts pads short lineStyles using default style`() {
        val screen =
            LoginScreenLoader.loadFromTexts(
                linesText = "a\nb\nc",
                stylesYaml =
                    """
                    defaultStyle: body
                    styles:
                      body:
                        ansi:
                          - bright_cyan
                    lineStyles:
                      - body
                    """.trimIndent(),
            )

        assertEquals(listOf("a", "b", "c"), screen.lines)
        assertEquals(listOf("\u001B[96m", "\u001B[96m", "\u001B[96m"), screen.ansiPrefixesByLine)
    }

    @Test
    fun `loadFromTexts falls back to plain when lineStyles has too many entries`() {
        val screen =
            LoginScreenLoader.loadFromTexts(
                linesText = "a\nb",
                stylesYaml =
                    """
                    defaultStyle: body
                    styles:
                      body:
                        ansi:
                          - bright_green
                    lineStyles:
                      - body
                      - body
                      - body
                    """.trimIndent(),
            )

        assertEquals(listOf("a", "b"), screen.lines)
        assertEquals(listOf("", ""), screen.ansiPrefixesByLine)
    }

    @Test
    fun `loadFromTexts throws in strict mode for invalid styles`() {
        assertThrows(IllegalStateException::class.java) {
            LoginScreenLoader.loadFromTexts(
                linesText = "a",
                stylesYaml =
                    """
                    defaultStyle: title
                    styles:
                      title:
                        ansi:
                          - unknown_token
                    lineStyles:
                      - title
                    """.trimIndent(),
                strict = true,
            )
        }
    }
}
