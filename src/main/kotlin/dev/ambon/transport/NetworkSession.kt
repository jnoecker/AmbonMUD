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
import java.io.BufferedWriter
import java.io.OutputStreamWriter
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
    private val maxLineLen: Int = 1024,
    private val maxNonPrintablePerLine: Int = 32,
    private val maxInboundBackpressureFailures: Int = 3,
    private val metrics: GameMetrics = GameMetrics.noop(),
) {
    private val disconnected = AtomicBoolean(false)
    private var inboundBackpressureFailures = 0

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
            )
        try {
            log.debug { "Telnet session connected: sessionId=$sessionId address=${socket.remoteSocketAddress}" }
            inbound.send(InboundEvent.Connected(sessionId))
            metrics.onTelnetConnected()
            val input = socket.getInputStream()
            val buf = ByteArray(4096)

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

    private suspend fun writeLoop() {
        try {
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            for (msg in outboundQueue) {
                writer.write(msg)
                writer.flush()
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
