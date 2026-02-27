package dev.ambon.transport

import dev.ambon.bus.LocalInboundBus
import dev.ambon.domain.ids.SessionId
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.Socket

/**
 * Verifies that GMCP frames are written into the write-loop's batch buffer (instead of being
 * flushed immediately via sendRaw) so that GMCP and text frames coalesce into a single flush
 * per batch cycle.
 */
@Tag("integration")
class NetworkSessionGmcpBatchTest {
    /** Reads exactly [n] bytes from [input], blocking until all arrive. */
    private fun readNBytes(
        input: java.io.InputStream,
        n: Int,
    ): ByteArray {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r == -1) break
            read += r
        }
        return buf.copyOf(read)
    }

    /** Builds the expected telnet subnegotiation byte sequence for a GMCP frame. */
    private fun buildExpectedGmcpBytes(
        pkg: String,
        json: String,
    ): ByteArray {
        val payload = "$pkg $json".toByteArray(Charsets.UTF_8)
        val buf = ByteArray(3 + payload.size + 2)
        buf[0] = TelnetProtocol.IAC.toByte()
        buf[1] = TelnetProtocol.SB.toByte()
        buf[2] = TelnetProtocol.GMCP.toByte()
        payload.copyInto(buf, destinationOffset = 3)
        buf[buf.size - 2] = TelnetProtocol.IAC.toByte()
        buf[buf.size - 1] = TelnetProtocol.SE.toByte()
        return buf
    }

    // Initial negotiation = IAC DO TTYPE (3) + IAC DO NAWS (3) + IAC WILL GMCP (3) = 9 bytes
    private val initNegotiationSize = 9

    @Test
    fun `GMCP frame produces correct telnet subneg bytes when gmcp is enabled`(): Unit =
        runBlocking {
            val serverSock = ServerSocket(0)
            val clientSock = Socket("localhost", serverSock.localPort)
            val sessionSock = serverSock.accept()
            serverSock.close()
            try {
                val inbound = LocalInboundBus()
                val queue = Channel<OutboundFrame>(capacity = Channel.UNLIMITED)
                val session =
                    NetworkSession(
                        sessionId = SessionId(1),
                        socket = sessionSock,
                        inbound = inbound,
                        outboundQueue = queue,
                        onDisconnected = {},
                        scope = this,
                    )
                session.start()

                val clientIn = clientSock.getInputStream()
                val clientOut = clientSock.getOutputStream()

                // Consume server's initial negotiation.
                readNBytes(clientIn, initNegotiationSize)

                // Enable GMCP: send IAC DO GMCP from client.
                clientOut.write(
                    byteArrayOf(
                        TelnetProtocol.IAC.toByte(),
                        TelnetProtocol.DO.toByte(),
                        TelnetProtocol.GMCP.toByte(),
                    ),
                )
                clientOut.flush()

                withTimeout(2_000) {
                    while (!session.gmcpEnabled) delay(10)
                }

                val pkg = "Char.Vitals"
                val json = """{"hp":100,"mp":50}"""
                queue.send(OutboundFrame.Gmcp(pkg, json))

                val expected = buildExpectedGmcpBytes(pkg, json)
                val received = readNBytes(clientIn, expected.size)

                assertArrayEquals(expected, received)
            } finally {
                clientSock.close()
                runCatching { sessionSock.close() }
            }
        }

    @Test
    fun `GMCP and text frames are batched together in correct order`(): Unit =
        runBlocking {
            val serverSock = ServerSocket(0)
            val clientSock = Socket("localhost", serverSock.localPort)
            val sessionSock = serverSock.accept()
            serverSock.close()
            try {
                val inbound = LocalInboundBus()
                val queue = Channel<OutboundFrame>(capacity = Channel.UNLIMITED)
                val session =
                    NetworkSession(
                        sessionId = SessionId(1),
                        socket = sessionSock,
                        inbound = inbound,
                        outboundQueue = queue,
                        onDisconnected = {},
                        scope = this,
                    )
                session.start()

                val clientIn = clientSock.getInputStream()
                val clientOut = clientSock.getOutputStream()

                readNBytes(clientIn, initNegotiationSize)

                clientOut.write(
                    byteArrayOf(
                        TelnetProtocol.IAC.toByte(),
                        TelnetProtocol.DO.toByte(),
                        TelnetProtocol.GMCP.toByte(),
                    ),
                )
                clientOut.flush()

                withTimeout(2_000) {
                    while (!session.gmcpEnabled) delay(10)
                }

                val pkg = "Room.Info"
                val json = """{"id":"hub:town_square"}"""
                val text = "You are in the town square.\r\n"

                // Queue both frames — they should arrive as a single batch.
                queue.send(OutboundFrame.Gmcp(pkg, json))
                queue.send(OutboundFrame.Text(text))

                val gmcpBytes = buildExpectedGmcpBytes(pkg, json)
                val textBytes = text.toByteArray(Charsets.UTF_8)
                val expected = gmcpBytes + textBytes
                val received = readNBytes(clientIn, expected.size)

                assertArrayEquals(expected, received)
            } finally {
                clientSock.close()
                runCatching { sessionSock.close() }
            }
        }

    @Test
    fun `GMCP frame produces no bytes when gmcp is disabled`(): Unit =
        runBlocking {
            val serverSock = ServerSocket(0)
            val clientSock = Socket("localhost", serverSock.localPort)
            val sessionSock = serverSock.accept()
            serverSock.close()
            try {
                val inbound = LocalInboundBus()
                val queue = Channel<OutboundFrame>(capacity = Channel.UNLIMITED)
                val session =
                    NetworkSession(
                        sessionId = SessionId(1),
                        socket = sessionSock,
                        inbound = inbound,
                        outboundQueue = queue,
                        onDisconnected = {},
                        scope = this,
                    )
                session.start()

                // GMCP is disabled by default — do not send IAC DO GMCP.
                assert(!session.gmcpEnabled)

                val clientIn = clientSock.getInputStream()

                readNBytes(clientIn, initNegotiationSize)

                // Queue a GMCP frame (discarded) then a text frame (arrives).
                queue.send(OutboundFrame.Gmcp("Char.Vitals", """{"hp":100}"""))
                val marker = "marker\r\n"
                queue.send(OutboundFrame.Text(marker))

                // Only the text frame bytes should arrive; no GMCP subneg bytes.
                val markerBytes = marker.toByteArray(Charsets.UTF_8)
                val received = readNBytes(clientIn, markerBytes.size)
                assertArrayEquals(markerBytes, received)
            } finally {
                clientSock.close()
                runCatching { sessionSock.close() }
            }
        }
}
