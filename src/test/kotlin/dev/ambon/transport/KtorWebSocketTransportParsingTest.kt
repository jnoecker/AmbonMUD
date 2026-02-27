package dev.ambon.transport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KtorWebSocketTransportParsingTest {
    @Test
    fun `splitIncomingLines supports mixed newlines`() {
        assertEquals(
            listOf("alpha", "bravo", "charlie", "delta"),
            splitIncomingLines("alpha\r\nbravo\ncharlie\rdelta"),
        )
        assertEquals(listOf(""), splitIncomingLines(""))
        assertEquals(listOf("line"), splitIncomingLines("line"))
        assertEquals(listOf("", ""), splitIncomingLines("\r\n\r\n"))
    }

    @Test
    fun `sanitizeIncomingLines enforces max length and non-printable limits`() {
        val longLine = "x".repeat(5)
        val longEx =
            assertThrows(ProtocolViolation::class.java) {
                sanitizeIncomingLines(longLine, maxLineLen = 4, maxNonPrintablePerLine = 10)
            }
        assertTrue(longEx.message!!.contains("Line too long"))

        val nonPrintable = "ok\u0001bad"
        val nonPrintableEx =
            assertThrows(ProtocolViolation::class.java) {
                sanitizeIncomingLines(nonPrintable, maxLineLen = 20, maxNonPrintablePerLine = 0)
            }
        assertTrue(nonPrintableEx.message!!.contains("non-printable"))
    }

    @Test
    fun `tryParseGmcpEnvelope parses envelope with object data`() {
        assertEquals(
            Pair("Core.Hello", """{"version":1}"""),
            tryParseGmcpEnvelope("""{"gmcp":"Core.Hello","data":{"version":1}}"""),
        )
    }

    @Test
    fun `tryParseGmcpEnvelope parses envelope without data key`() {
        assertEquals(
            Pair("Core.Ping", "{}"),
            tryParseGmcpEnvelope("""{"gmcp":"Core.Ping"}"""),
        )
    }

    @Test
    fun `tryParseGmcpEnvelope parses envelope with null data`() {
        assertEquals(
            Pair("Core.Ping", "null"),
            tryParseGmcpEnvelope("""{"gmcp":"Core.Ping","data":null}"""),
        )
    }

    @Test
    fun `tryParseGmcpEnvelope parses envelope with array data`() {
        assertEquals(
            Pair("Char.Skills", "[1,2,3]"),
            tryParseGmcpEnvelope("""{"gmcp":"Char.Skills","data":[1,2,3]}"""),
        )
    }

    @Test
    fun `tryParseGmcpEnvelope tolerates surrounding whitespace`() {
        assertEquals(
            Pair("Core.Hello", "null"),
            tryParseGmcpEnvelope("""  { "gmcp" : "Core.Hello" , "data" : null }  """),
        )
    }

    @Test
    fun `tryParseGmcpEnvelope returns null for non-envelope text`() {
        assertNull(tryParseGmcpEnvelope("look north"))
        assertNull(tryParseGmcpEnvelope(""))
        assertNull(tryParseGmcpEnvelope("""{"not":"gmcp"}"""))
    }

    @Test
    fun `tryParseGmcpEnvelope returns null for blank package name`() {
        assertNull(tryParseGmcpEnvelope("""{"gmcp":"","data":{}}"""))
    }

    @Test
    fun `tryParseGmcpEnvelope handles nested JSON data`() {
        assertEquals(
            Pair("Char.Stats", """{"str":18,"dex":14}"""),
            tryParseGmcpEnvelope("""{"gmcp":"Char.Stats","data":{"str":18,"dex":14}}"""),
        )
    }
}
