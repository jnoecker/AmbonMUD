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
    fun `captures NAWS subnegotiation payload`() {
        val controls = mutableListOf<TelnetControlEvent>()
        val d = TelnetLineDecoder(onControlEvent = { controls += it })

        val bytes =
            byteArrayOf(
                TelnetProtocol.IAC.toByte(),
                TelnetProtocol.SB.toByte(),
                TelnetProtocol.NAWS.toByte(),
                0x00,
                0x78,
                0x00,
                0x30,
                TelnetProtocol.IAC.toByte(),
                TelnetProtocol.SE.toByte(),
            )

        val lines = d.feed(bytes)

        assertTrue(lines.isEmpty())
        assertEquals(1, controls.size)
        val event = controls.single() as TelnetControlEvent.Subnegotiation
        assertEquals(TelnetProtocol.NAWS, event.option)
        assertEquals(listOf(0x00, 0x78, 0x00, 0x30), event.payload.map { it.toInt() and 0xFF })
    }

    @Test
    fun `captures TTYPE negotiation and subnegotiation`() {
        val controls = mutableListOf<TelnetControlEvent>()
        val d = TelnetLineDecoder(onControlEvent = { controls += it })

        val bytes =
            byteArrayOf(
                TelnetProtocol.IAC.toByte(),
                TelnetProtocol.WILL.toByte(),
                TelnetProtocol.TTYPE.toByte(),
                TelnetProtocol.IAC.toByte(),
                TelnetProtocol.SB.toByte(),
                TelnetProtocol.TTYPE.toByte(),
                TelnetProtocol.TTYPE_IS.toByte(),
                'x'.code.toByte(),
                't'.code.toByte(),
                'e'.code.toByte(),
                'r'.code.toByte(),
                'm'.code.toByte(),
                TelnetProtocol.IAC.toByte(),
                TelnetProtocol.SE.toByte(),
            )

        d.feed(bytes)

        assertEquals(TelnetControlEvent.Negotiation(TelnetProtocol.WILL, TelnetProtocol.TTYPE), controls[0])
        val sub = controls[1] as TelnetControlEvent.Subnegotiation
        assertEquals(TelnetProtocol.TTYPE, sub.option)
        assertEquals(
            listOf(TelnetProtocol.TTYPE_IS, 'x'.code, 't'.code, 'e'.code, 'r'.code, 'm'.code),
            sub.payload.map { it.toInt() and 0xFF },
        )
    }

    @Test
    fun `handles escaped IAC inside subnegotiation payload`() {
        val controls = mutableListOf<TelnetControlEvent>()
        val d = TelnetLineDecoder(onControlEvent = { controls += it })

        // NAWS with high byte of width = 0xFF, encoded as IAC IAC in the payload.
        val bytes =
            byteArrayOf(
                TelnetProtocol.IAC.toByte(),
                TelnetProtocol.SB.toByte(),
                TelnetProtocol.NAWS.toByte(),
                TelnetProtocol.IAC.toByte(), // escaped 0xFF — first byte of IAC IAC pair
                TelnetProtocol.IAC.toByte(), // second byte of IAC IAC pair → literal 0xFF in payload
                0x00, // low byte of width
                0x00, // high byte of height
                0x18, // low byte of height (24)
                TelnetProtocol.IAC.toByte(),
                TelnetProtocol.SE.toByte(),
            )

        d.feed(bytes)

        assertEquals(1, controls.size)
        val sub = controls.single() as TelnetControlEvent.Subnegotiation
        assertEquals(TelnetProtocol.NAWS, sub.option)
        // IAC IAC inside subnegotiation decodes to a single 0xFF byte in the payload.
        assertEquals(listOf(0xFF, 0x00, 0x00, 0x18), sub.payload.map { it.toInt() and 0xFF })
    }

    @Test
    fun `abandons subnegotiation on invalid IAC sequence and resumes in DATA`() {
        val controls = mutableListOf<TelnetControlEvent>()
        val d = TelnetLineDecoder(onControlEvent = { controls += it })

        // IAC followed by a non-SE, non-IAC byte inside subnegotiation is invalid.
        // The state machine should abandon the subnegotiation, return to DATA,
        // and continue decoding normally so the trailing line is emitted.
        val bytes =
            byteArrayOf(
                TelnetProtocol.IAC.toByte(),
                TelnetProtocol.SB.toByte(),
                TelnetProtocol.NAWS.toByte(),
                0x00,
                0x50,
                TelnetProtocol.IAC.toByte(),
                0x01, // not SE, not IAC — invalid; subneg abandoned here
                'H'.code.toByte(), // decoder is back in DATA state
                'i'.code.toByte(),
                '\n'.code.toByte(),
            )

        val lines = d.feed(bytes)

        // No control event — subnegotiation was abandoned before SE.
        assertTrue(controls.isEmpty())
        // Data following the invalid sequence is decoded normally.
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
    fun `throws ProtocolViolation on overlong subnegotiation`() {
        val d = TelnetLineDecoder(maxSubnegotiationLen = 4)
        val bytes =
            byteArrayOf(
                TelnetProtocol.IAC.toByte(),
                TelnetProtocol.SB.toByte(),
                TelnetProtocol.TTYPE.toByte(),
                0x00,
                0x01,
                0x02,
                0x03,
                0x04,
            )
        assertThrows(ProtocolViolation::class.java) { d.feed(bytes) }
    }

    @Test
    fun `does not emit without newline`() {
        val d = TelnetLineDecoder()
        val lines = d.feed("hello".toByteArray(Charsets.ISO_8859_1))
        assertTrue(lines.isEmpty())
    }
}
