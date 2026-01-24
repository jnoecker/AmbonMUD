package dev.ambon.transport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TelnetLineDecoderTest {
    @Test
    fun `strips basic IAC negotiation and returns clean line`() {
        val d = TelnetLineDecoder(maxLineLen = 1024)
        val bytes =
            byteArrayOf(
                0xFF.toByte(),
                0xFB.toByte(),
                // IAC WILL ECHO (example)
                0x01.toByte(),
                'H'.code.toByte(),
                'i'.code.toByte(),
                '\r'.code.toByte(),
                '\n'.code.toByte(),
            )
        val lines = d.feed(bytes)
        assertEquals(listOf("Hi"), lines)
    }

    @Test
    fun `handles line split across chunks`() {
        val d = TelnetLineDecoder(maxLineLen = 1024)

        val part1 = "Hello, ".toByteArray(Charsets.ISO_8859_1)
        val part2 = "world\r\n".toByteArray(Charsets.ISO_8859_1)

        val lines1 = d.feed(part1)
        assertTrue(lines1.isEmpty(), "Should not emit a line without newline")

        val lines2 = d.feed(part2)
        assertEquals(listOf("Hello, world"), lines2)
    }

    @Test
    fun `handles multiple lines in one chunk`() {
        val d = TelnetLineDecoder(maxLineLen = 1024)
        val bytes = "a\r\nb\n".toByteArray(Charsets.ISO_8859_1)
        val lines = d.feed(bytes)
        assertEquals(listOf("a", "b"), lines)
    }

    @Test
    fun `throws ProtocolViolation on overlong line`() {
        val d = TelnetLineDecoder(maxLineLen = 8)
        val bytes = "123456789\n".toByteArray(Charsets.ISO_8859_1)
        assertThrows(ProtocolViolation::class.java) { d.feed(bytes) }
    }

    @Test
    fun `does not emit without newline`() {
        val d = TelnetLineDecoder()
        val lines = d.feed("hello".toByteArray(Charsets.ISO_8859_1))
        assertTrue(lines.isEmpty())
    }
}
