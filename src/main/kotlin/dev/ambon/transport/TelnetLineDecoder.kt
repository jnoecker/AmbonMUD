package dev.ambon.transport

class TelnetLineDecoder(
    private val maxLineLen: Int = 1024,
    // simple abuse guard
    private val maxNonPrintablePerLine: Int = 32,
) {
    private val sb = StringBuilder()
    private var nonPrintableCount = 0

    private enum class State { DATA, IAC, IAC_CMD }

    private var state: State = State.DATA

    fun feedByte(b: Int): List<String> {
        val out = mutableListOf<String>()
        val byte = b and 0xFF

        when (state) {
            State.DATA -> {
                when (byte) {
                    // IAC
                    0xFF -> {
                        state = State.IAC
                    }

                    // '\n'
                    0x0A -> {
                        val line = sb.toString().trimEnd('\r')
                        resetLineState()
                        out.add(line)
                    }

                    else -> {
                        if (sb.length >= maxLineLen) {
                            throw ProtocolViolation("Line too long (>$maxLineLen)")
                        }

                        // Very basic “printable” check (telnet is byte-oriented)
                        val ch = byte.toChar()
                        val printable = (byte in 0x20..0x7E) || byte == 0x0D || byte == 0x09
                        if (!printable) {
                            nonPrintableCount++
                            if (nonPrintableCount > maxNonPrintablePerLine) {
                                throw ProtocolViolation("Too many non-printable bytes in line")
                            }
                        }

                        sb.append(ch)
                    }
                }
            }

            State.IAC -> {
                // Telnet command byte (WILL/WONT/DO/DONT/SB/SE/etc.)
                state = State.IAC_CMD
            }

            State.IAC_CMD -> {
                // MVP: ignore the option byte and return to DATA
                state = State.DATA
            }
        }

        return out
    }

    fun feed(
        bytes: ByteArray,
        len: Int = bytes.size,
    ): List<String> {
        val out = mutableListOf<String>()
        for (i in 0 until len) out += feedByte(bytes[i].toInt())
        return out
    }

    private fun resetLineState() {
        sb.setLength(0)
        nonPrintableCount = 0
    }
}
