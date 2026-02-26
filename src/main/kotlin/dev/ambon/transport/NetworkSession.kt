package dev.ambon.transport

import dev.ambon.bus.InboundBus
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.InboundEvent
import dev.ambon.metrics.GameMetrics
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

private val log = KotlinLogging.logger {}

// Maximum number of extra frames to drain per write iteration before flushing.
// Keeps latency bounded while amortising flush()/syscall overhead across bursts.
private const val MAX_FRAMES_PER_FLUSH = 64

class NetworkSession(
    val sessionId: SessionId,
    private val socket: Socket,
    private val inbound: InboundBus,
    // per-session
    private val outboundQueue: Channel<OutboundFrame>,
    private val onDisconnected: () -> Unit,
    private val scope: CoroutineScope,
    private val onOutboundFrameWritten: (enqueuedAt: Long) -> Unit = {},
    private val maxLineLen: Int = 1024,
    private val maxNonPrintablePerLine: Int = 32,
    private val maxInboundBackpressureFailures: Int = 3,
    private val metrics: GameMetrics = GameMetrics.noop(),
) {
    private val disconnected = AtomicBoolean(false)
    private var inboundBackpressureFailures = 0

    @Volatile
    var gmcpEnabled = false
        private set

    private val terminalCaps = TerminalCapabilities()
    private val outputLock = Any()

    fun start() {
        scope.launch(Dispatchers.IO) { readLoop() }
        scope.launch(Dispatchers.IO) { writeLoop() }
    }

    fun closeNow(reason: String) {
        // Closing the socket will unblock read/write loops
        runCatching { socket.close() }
        outboundQueue.close()
        notifyDisconnected()
    }

    private suspend fun readLoop() {
        val decoder =
            TelnetLineDecoder(
                maxLineLen = maxLineLen,
                maxNonPrintablePerLine = maxNonPrintablePerLine,
                onControlEvent = ::onTelnetControlEvent,
            )
        try {
            log.debug { "Telnet session connected: sessionId=$sessionId address=${socket.remoteSocketAddress}" }
            inbound.send(InboundEvent.Connected(sessionId))
            metrics.onTelnetConnected()
            val input = socket.getInputStream()
            val buf = ByteArray(4096)

            sendInitialNegotiation()

            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                val lines = decoder.feed(buf, n)
                for (line in lines) {
                    sendInboundLineOrThrow(line)
                }
            }

            log.debug { "Telnet session EOF: sessionId=$sessionId" }
            metrics.onTelnetDisconnected("EOF")
            inbound.send(InboundEvent.Disconnected(sessionId, "EOF"))
        } catch (b: InboundBackpressure) {
            log.warn { "Telnet session disconnected due to backpressure: sessionId=$sessionId" }
            metrics.onTelnetDisconnected("backpressure")
            runCatching { inbound.trySend(InboundEvent.Disconnected(sessionId, b.message ?: "inbound backpressure")) }
        } catch (v: ProtocolViolation) {
            log.warn { "Telnet session disconnected due to protocol violation: sessionId=$sessionId message=${v.message}" }
            metrics.onTelnetDisconnected("error")
            inbound.send(InboundEvent.Disconnected(sessionId, "protocol violation: ${v.message}"))
        } catch (s: java.net.SocketException) {
            log.debug { "Telnet session connection reset: sessionId=$sessionId" }
            metrics.onTelnetDisconnected("connection reset")
            inbound.send(InboundEvent.Disconnected(sessionId, "connection reset"))
        } catch (t: Throwable) {
            log.error(t) { "Unexpected read error: sessionId=$sessionId" }
            metrics.onTelnetDisconnected("error")
            inbound.send(InboundEvent.Disconnected(sessionId, "read error: ${t.message}"))
        } finally {
            runCatching { socket.close() }
            outboundQueue.close()
            notifyDisconnected()
        }
    }

    private fun sendInitialNegotiation() {
        // Phase 1: request terminal type and window size.
        sendTelnetCommand(TelnetProtocol.DO, TelnetProtocol.TTYPE)
        sendTelnetCommand(TelnetProtocol.DO, TelnetProtocol.NAWS)
        // Offer GMCP support.
        sendTelnetCommand(TelnetProtocol.WILL, TelnetProtocol.GMCP)
    }

    private fun onTelnetControlEvent(event: TelnetControlEvent) {
        when (event) {
            is TelnetControlEvent.Command -> Unit
            is TelnetControlEvent.Negotiation -> handleTelnetNegotiation(event)
            is TelnetControlEvent.Subnegotiation -> handleTelnetSubnegotiation(event)
        }
    }

    private fun handleTelnetNegotiation(event: TelnetControlEvent.Negotiation) {
        if (event.command == TelnetProtocol.WILL && event.option == TelnetProtocol.TTYPE) {
            sendTelnetSubnegotiation(
                option = TelnetProtocol.TTYPE,
                payload = byteArrayOf(TelnetProtocol.TTYPE_SEND.toByte()),
            )
            return
        }

        if (event.command == TelnetProtocol.WONT && event.option == TelnetProtocol.TTYPE) {
            terminalCaps.terminalType = null
            return
        }

        if (event.command == TelnetProtocol.WILL && event.option == TelnetProtocol.NAWS) {
            terminalCaps.nawsEnabled = true
            return
        }

        if (event.command == TelnetProtocol.WONT && event.option == TelnetProtocol.NAWS) {
            terminalCaps.nawsEnabled = false
            terminalCaps.columns = null
            terminalCaps.rows = null
        }

        if (event.command == TelnetProtocol.DO && event.option == TelnetProtocol.GMCP) {
            gmcpEnabled = true
            log.debug { "GMCP enabled: sessionId=$sessionId" }
            return
        }

        if (event.command == TelnetProtocol.DONT && event.option == TelnetProtocol.GMCP) {
            gmcpEnabled = false
            return
        }
    }

    private fun handleTelnetSubnegotiation(event: TelnetControlEvent.Subnegotiation) {
        when (event.option) {
            TelnetProtocol.TTYPE -> parseTerminalType(event.payload)
            TelnetProtocol.NAWS -> parseWindowSize(event.payload)
            TelnetProtocol.GMCP -> parseGmcpPayload(event.payload)
        }
    }

    private fun parseGmcpPayload(payload: ByteArray) {
        val raw = payload.toString(Charsets.UTF_8).trim()
        val spaceIdx = raw.indexOf(' ')
        val pkg = if (spaceIdx == -1) raw else raw.substring(0, spaceIdx)
        val jsonData = if (spaceIdx == -1) "{}" else raw.substring(spaceIdx + 1).trim()
        if (pkg.isBlank()) return
        inbound.trySend(InboundEvent.GmcpReceived(sessionId, pkg, jsonData))
    }

    private fun parseTerminalType(payload: ByteArray) {
        if (payload.isEmpty()) return
        if ((payload[0].toInt() and 0xFF) != TelnetProtocol.TTYPE_IS) return

        val term =
            payload
                .drop(1)
                .take(64)
                .map { (it.toInt() and 0xFF).toChar() }
                .joinToString(separator = "")
                .trim()
        if (term.isNotEmpty()) {
            terminalCaps.terminalType = term
            log.debug { "Telnet terminal type: sessionId=$sessionId term=$term" }
        }
    }

    private fun parseWindowSize(payload: ByteArray) {
        if (payload.size < 4) return

        val width = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        val height = ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
        if (width == 0 || height == 0) return

        terminalCaps.columns = width
        terminalCaps.rows = height
        log.debug { "Telnet window size: sessionId=$sessionId cols=$width rows=$height" }
    }

    private fun sendTelnetCommand(
        command: Int,
        option: Int,
    ) {
        val bytes = byteArrayOf(TelnetProtocol.IAC.toByte(), command.toByte(), option.toByte())
        sendRaw(bytes)
    }

    private fun buildTelnetSubnegotiationBytes(
        option: Int,
        payload: ByteArray,
    ): ByteArray {
        val buffer = ByteArray(3 + payload.size + 2)
        buffer[0] = TelnetProtocol.IAC.toByte()
        buffer[1] = TelnetProtocol.SB.toByte()
        buffer[2] = option.toByte()
        payload.copyInto(buffer, destinationOffset = 3)
        buffer[buffer.size - 2] = TelnetProtocol.IAC.toByte()
        buffer[buffer.size - 1] = TelnetProtocol.SE.toByte()
        return buffer
    }

    private fun sendTelnetSubnegotiation(
        option: Int,
        payload: ByteArray,
    ) {
        sendRaw(buildTelnetSubnegotiationBytes(option, payload))
    }

    private fun sendRaw(bytes: ByteArray) {
        try {
            synchronized(outputLock) {
                socket.getOutputStream().write(bytes)
                socket.getOutputStream().flush()
            }
        } catch (t: Throwable) {
            log.debug(t) { "Failed to send telnet negotiation frame: sessionId=$sessionId" }
        }
    }

    private suspend fun writeLoop() {
        try {
            val output = socket.getOutputStream()
            for (frame in outboundQueue) {
                // Write first frame, then drain any immediately available frames before flushing.
                var needFlush = writeFrame(output, frame)
                var drained = 0
                while (drained < MAX_FRAMES_PER_FLUSH) {
                    val next = outboundQueue.tryReceive().getOrNull() ?: break
                    val wrote = writeFrame(output, next)
                    if (wrote) needFlush = true
                    drained++
                }
                if (needFlush) {
                    synchronized(outputLock) { output.flush() }
                }
            }
        } catch (_: Throwable) {
            // ignore; reader loop will close socket
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * Writes a single frame to [output] without flushing.
     * Returns true if bytes were written (i.e. a flush is needed).
     */
    private fun writeFrame(
        output: java.io.OutputStream,
        frame: OutboundFrame,
    ): Boolean {
        onOutboundFrameWritten(frame.enqueuedAt)
        return when (frame) {
            is OutboundFrame.Text -> {
                val bytes = frame.content.toByteArray(Charsets.UTF_8)
                synchronized(outputLock) { output.write(bytes) }
                true
            }
            is OutboundFrame.Gmcp -> {
                if (gmcpEnabled) {
                    val payload = "${frame.gmcpPackage} ${frame.jsonData}".toByteArray(Charsets.UTF_8)
                    val bytes = buildTelnetSubnegotiationBytes(TelnetProtocol.GMCP, payload)
                    synchronized(outputLock) { output.write(bytes) }
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun notifyDisconnected() {
        if (disconnected.compareAndSet(false, true)) {
            onDisconnected()
        }
    }

    private fun sendInboundLineOrThrow(line: String) {
        val result = inbound.trySend(InboundEvent.LineReceived(sessionId, line))
        if (result.isSuccess) {
            inboundBackpressureFailures = 0
            metrics.onInboundLineTelnet()
            return
        }

        inboundBackpressureFailures++
        metrics.onInboundBackpressureFailure()
        log.debug { "Inbound line dropped due to backpressure: sessionId=$sessionId failures=$inboundBackpressureFailures" }
        if (inboundBackpressureFailures >= maxInboundBackpressureFailures) {
            throw InboundBackpressure("inbound backpressure")
        }
        // Drop the line and keep the session open until the threshold is exceeded.
    }
}

private class TerminalCapabilities {
    @Volatile var terminalType: String? = null

    @Volatile var columns: Int? = null

    @Volatile var rows: Int? = null

    @Volatile var nawsEnabled: Boolean = false
}
