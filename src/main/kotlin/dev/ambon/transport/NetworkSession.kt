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

class NetworkSession(
    val sessionId: SessionId,
    private val socket: Socket,
    private val inbound: InboundBus,
    // per-session
    private val outboundQueue: Channel<String>,
    private val onDisconnected: () -> Unit,
    private val scope: CoroutineScope,
    private val onOutboundFrameWritten: () -> Unit = {},
    private val maxLineLen: Int = 1024,
    private val maxNonPrintablePerLine: Int = 32,
    private val maxInboundBackpressureFailures: Int = 3,
    private val metrics: GameMetrics = GameMetrics.noop(),
) {
    private val disconnected = AtomicBoolean(false)
    private var inboundBackpressureFailures = 0

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
    }

    private fun handleTelnetSubnegotiation(event: TelnetControlEvent.Subnegotiation) {
        when (event.option) {
            TelnetProtocol.TTYPE -> parseTerminalType(event.payload)
            TelnetProtocol.NAWS -> parseWindowSize(event.payload)
        }
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

    private fun sendTelnetSubnegotiation(
        option: Int,
        payload: ByteArray,
    ) {
        val buffer = ByteArray(3 + payload.size + 2)
        buffer[0] = TelnetProtocol.IAC.toByte()
        buffer[1] = TelnetProtocol.SB.toByte()
        buffer[2] = option.toByte()
        payload.copyInto(buffer, destinationOffset = 3)
        buffer[buffer.size - 2] = TelnetProtocol.IAC.toByte()
        buffer[buffer.size - 1] = TelnetProtocol.SE.toByte()
        sendRaw(buffer)
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
            for (msg in outboundQueue) {
                val bytes = msg.toByteArray(Charsets.UTF_8)
                synchronized(outputLock) {
                    output.write(bytes)
                    output.flush()
                }
                onOutboundFrameWritten()
            }
        } catch (_: Throwable) {
            // ignore; reader loop will close socket
        } finally {
            runCatching { socket.close() }
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
