package dev.ambon.transport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ScreenReaderFilterTest {
    @Test
    fun `box-drawing corners replaced with plus`() {
        val input = "\u2554\u2550\u2550\u2550\u2557"
        val result = ScreenReaderFilter.filter(input)
        assertEquals("+---+", result)
    }

    @Test
    fun `vertical pipes replaced`() {
        val input = "\u2551hello\u2551"
        val result = ScreenReaderFilter.filter(input)
        assertEquals("|hello|", result)
    }

    @Test
    fun `long separator runs collapsed to three`() {
        val input = "Title\n" + "\u2550".repeat(40) + "\nBody"
        val result = ScreenReaderFilter.filter(input)
        assertEquals("Title\n---\nBody", result)
    }

    @Test
    fun `block characters replaced with spaces`() {
        val input = "\u2588\u2591\u2592\u2593"
        val result = ScreenReaderFilter.filter(input)
        assertEquals("    ", result)
    }

    @Test
    fun `decorative bullets replaced`() {
        val input = "\u2022 item one"
        val result = ScreenReaderFilter.filter(input)
        assertEquals("* item one", result)
    }

    @Test
    fun `plain text passes through unchanged`() {
        val input = "You see a goblin here."
        val result = ScreenReaderFilter.filter(input)
        assertEquals(input, result)
    }

    @Test
    fun `short separator runs are not collapsed`() {
        val input = "a--b"
        val result = ScreenReaderFilter.filter(input)
        assertEquals("a--b", result)
    }

    @Test
    fun `mixed box-drawing and text`() {
        val input = "\u250C\u2500\u2500\u2500\u2510 Score \u2514\u2500\u2500\u2500\u2518"
        val result = ScreenReaderFilter.filter(input)
        assertEquals("+---+ Score +---+", result)
    }

    @Test
    fun `arrow characters replaced`() {
        val input = "\u25BA Forward \u25C4 Back \u25B2 Up \u25BC Down"
        val result = ScreenReaderFilter.filter(input)
        assertEquals("> Forward < Back ^ Up v Down", result)
    }

    @Test
    fun `empty string returns empty`() {
        assertEquals("", ScreenReaderFilter.filter(""))
    }

    @Test
    fun `no box-drawing characters does not alter text`() {
        val input = "HP: 50/100 Mana: 30/80 Gold: 1500"
        assertEquals(input, ScreenReaderFilter.filter(input))
    }
}
