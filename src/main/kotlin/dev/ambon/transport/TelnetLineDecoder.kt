package dev.ambon.transport

class TelnetLineDecoder(
    private val maxLineLen: Int = 1024,
    // simple abuse guard
    private val maxNonPrintablePerLine: Int = 32,
    private val maxSubnegotiationLen: Int = 4096,
    private val onControlEvent: (TelnetControlEvent) -> Unit = {},
) {
    private val sb = StringBuilder()
    private var nonPrintableCount = 0

    private val subnegotiationPayload = ArrayList<Int>()
    private var subnegotiationOption: Int = 0
    private var iacCommand: Int = 0

    private enum class State { DATA, IAC, IAC_CMD, IAC_SB_OPTION, IAC_SB_DATA, IAC_SB_DATA_IAC }

    private var state: State = State.DATA

    fun feedByte(b: Int): List<String> {
        val out = mutableListOf<String>()
        val byte = b and 0xFF

        when (state) {
            State.DATA -> {
                when (byte) {
                    TelnetProtocol.IAC -> state = State.IAC
                    0x0A -> {
                        val line = sb.toString().trimEnd('\r')
                        resetLineState()
                        out.add(line)
                    }
                    else -> appendDataByte(byte)
                }
            }

            State.IAC -> {
                when (byte) {
                    TelnetProtocol.IAC -> {
                        // Escaped IAC in data stream.
                        appendDataByte(TelnetProtocol.IAC)
                        state = State.DATA
                    }

                    TelnetProtocol.SB -> {
                        state = State.IAC_SB_OPTION
                    }

                    TelnetProtocol.WILL,
                    TelnetProtocol.WONT,
                    TelnetProtocol.DO,
                    TelnetProtocol.DONT,
                    -> {
                        iacCommand = byte
                        state = State.IAC_CMD
                    }

                    else -> {
                        onControlEvent(TelnetControlEvent.Command(command = byte))
                        state = State.DATA
                    }
                }
            }

            State.IAC_CMD -> {
                onControlEvent(TelnetControlEvent.Negotiation(command = iacCommand, option = byte))
                state = State.DATA
            }

            State.IAC_SB_OPTION -> {
                subnegotiationOption = byte
                subnegotiationPayload.clear()
                state = State.IAC_SB_DATA
            }

            State.IAC_SB_DATA -> {
                when (byte) {
                    TelnetProtocol.IAC -> state = State.IAC_SB_DATA_IAC
                    else -> appendSubnegotiationByte(byte)
                }
            }

            State.IAC_SB_DATA_IAC -> {
                when (byte) {
                    TelnetProtocol.SE -> {
                        onControlEvent(
                            TelnetControlEvent.Subnegotiation(
                                option = subnegotiationOption,
                                payload = subnegotiationPayload.toByteArray(),
                            ),
                        )
                        subnegotiationPayload.clear()
                        state = State.DATA
                    }

                    TelnetProtocol.IAC -> {
                        // Escaped IAC inside subnegotiation data.
                        appendSubnegotiationByte(TelnetProtocol.IAC)
                        state = State.IAC_SB_DATA
                    }

                    else -> {
                        // Invalid sequence, abandon current subnegotiation.
                        subnegotiationPayload.clear()
                        state = State.DATA
                    }
                }
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

    private fun appendDataByte(byte: Int) {
        if (sb.length >= maxLineLen) {
            throw ProtocolViolation("Line too long (>$maxLineLen)")
        }

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

    private fun appendSubnegotiationByte(byte: Int) {
        if (subnegotiationPayload.size >= maxSubnegotiationLen) {
            throw ProtocolViolation("Telnet subnegotiation too long (>$maxSubnegotiationLen)")
        }
        subnegotiationPayload.add(byte)
    }

    private fun resetLineState() {
        sb.setLength(0)
        nonPrintableCount = 0
    }

    private fun List<Int>.toByteArray(): ByteArray {
        val arr = ByteArray(size)
        forEachIndexed { i, value -> arr[i] = value.toByte() }
        return arr
    }
}

sealed interface TelnetControlEvent {
    data class Command(
        val command: Int,
    ) : TelnetControlEvent

    data class Negotiation(
        val command: Int,
        val option: Int,
    ) : TelnetControlEvent

    class Subnegotiation(
        val option: Int,
        val payload: ByteArray,
    ) : TelnetControlEvent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Subnegotiation) return false
            return option == other.option && payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int = 31 * option + payload.contentHashCode()
    }
}

object TelnetProtocol {
    const val SE = 0xF0
    const val SB = 0xFA
    const val WILL = 0xFB
    const val WONT = 0xFC
    const val DO = 0xFD
    const val DONT = 0xFE
    const val IAC = 0xFF

    const val TTYPE = 24
    const val NAWS = 31
    const val GMCP = 201
    const val TTYPE_IS = 0
    const val TTYPE_SEND = 1
}
