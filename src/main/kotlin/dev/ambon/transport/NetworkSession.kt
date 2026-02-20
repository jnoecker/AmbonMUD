package dev.ambon.transport

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.InboundEvent
import dev.ambon.metrics.GameMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class NetworkSession(
    val sessionId: SessionId,
    private val socket: Socket,
    private val inbound: SendChannel<InboundEvent>,
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

            metrics.onTelnetDisconnected("EOF")
            inbound.send(InboundEvent.Disconnected(sessionId, "EOF"))
        } catch (b: InboundBackpressure) {
            metrics.onTelnetDisconnected("backpressure")
            runCatching { inbound.trySend(InboundEvent.Disconnected(sessionId, b.message ?: "inbound backpressure")) }
        } catch (v: ProtocolViolation) {
            metrics.onTelnetDisconnected("error")
            inbound.send(InboundEvent.Disconnected(sessionId, "protocol violation: ${v.message}"))
        } catch (t: Throwable) {
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
        if (inboundBackpressureFailures >= maxInboundBackpressureFailures) {
            throw InboundBackpressure("inbound backpressure")
        }
        // Drop the line and keep the session open until the threshold is exceeded.
    }
}
