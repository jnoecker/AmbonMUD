package dev.ambon.transport

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.InboundEvent
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class KtorWebSocketTransport(
    private val port: Int,
    private val inbound: SendChannel<InboundEvent>,
    private val outboundRouter: OutboundRouter,
    private val sessionIdFactory: () -> SessionId,
    private val host: String = "0.0.0.0",
    private val sessionOutboundQueueCapacity: Int = 200,
    private val stopGraceMillis: Long = 1_000L,
    private val stopTimeoutMillis: Long = 2_000L,
    private val maxLineLen: Int = 1024,
    private val maxNonPrintablePerLine: Int = 32,
    private val maxInboundBackpressureFailures: Int = 3,
) : Transport {
    private var engine: ApplicationEngine? = null

    override suspend fun start() {
        engine =
            embeddedServer(Netty, port = port, host = host) {
                ambonMUDWebModule(
                    inbound = inbound,
                    outboundRouter = outboundRouter,
                    sessionIdFactory = sessionIdFactory,
                    sessionOutboundQueueCapacity = sessionOutboundQueueCapacity,
                    maxLineLen = maxLineLen,
                    maxNonPrintablePerLine = maxNonPrintablePerLine,
                    maxInboundBackpressureFailures = maxInboundBackpressureFailures,
                )
            }.start(wait = false)
    }

    override suspend fun stop() {
        engine?.stop(gracePeriodMillis = stopGraceMillis, timeoutMillis = stopTimeoutMillis)
        engine = null
    }
}

internal fun Application.ambonMUDWebModule(
    inbound: SendChannel<InboundEvent>,
    outboundRouter: OutboundRouter,
    sessionIdFactory: () -> SessionId,
    sessionOutboundQueueCapacity: Int = 200,
    maxLineLen: Int = 1024,
    maxNonPrintablePerLine: Int = 32,
    maxInboundBackpressureFailures: Int = 3,
) {
    install(WebSockets)

    routing {
        webSocket("/ws") {
            bridgeWebSocketSession(
                inbound = inbound,
                outboundRouter = outboundRouter,
                sessionIdFactory = sessionIdFactory,
                sessionOutboundQueueCapacity = sessionOutboundQueueCapacity,
                maxLineLen = maxLineLen,
                maxNonPrintablePerLine = maxNonPrintablePerLine,
                maxInboundBackpressureFailures = maxInboundBackpressureFailures,
            )
        }

        staticResources(
            remotePath = "/",
            basePackage = "web",
            index = "index.html",
        )
    }
}

private suspend fun DefaultWebSocketServerSession.bridgeWebSocketSession(
    inbound: SendChannel<InboundEvent>,
    outboundRouter: OutboundRouter,
    sessionIdFactory: () -> SessionId,
    sessionOutboundQueueCapacity: Int,
    maxLineLen: Int,
    maxNonPrintablePerLine: Int,
    maxInboundBackpressureFailures: Int,
) {
    val sessionId = sessionIdFactory()
    val outboundQueue = Channel<String>(capacity = sessionOutboundQueueCapacity)
    val disconnected = AtomicBoolean(false)
    val disconnectReason = AtomicReference("EOF")
    var inboundBackpressureFailures = 0

    fun noteDisconnectReason(reason: String) {
        if (reason.isBlank()) return
        disconnectReason.compareAndSet("EOF", reason)
    }

    suspend fun disconnect() {
        if (!disconnected.compareAndSet(false, true)) return
        outboundRouter.unregister(sessionId)
        outboundQueue.close()
        runCatching { inbound.send(InboundEvent.Disconnected(sessionId, disconnectReason.get())) }
    }

    outboundRouter.register(
        sessionId = sessionId,
        queue = outboundQueue,
        defaultAnsiEnabled = true,
    ) { reason ->
        this@bridgeWebSocketSession.launch {
            noteDisconnectReason(reason)
            runCatching { this@bridgeWebSocketSession.close(CloseReason(CloseReason.Codes.NORMAL, sanitizeCloseReason(reason))) }
        }
    }

    val connectedOk =
        runCatching {
            inbound.send(InboundEvent.Connected(sessionId, defaultAnsiEnabled = true))
        }.isSuccess
    if (!connectedOk) {
        noteDisconnectReason("inbound closed")
        disconnect()
        return
    }

    val writerJob =
        launch {
            try {
                for (message in outboundQueue) {
                    send(Frame.Text(message))
                }
            } catch (_: Throwable) {
                // best effort
            }
        }

    try {
        for (frame in incoming) {
            when (frame) {
                is Frame.Text -> {
                    val lines = sanitizeIncomingLines(frame.readText(), maxLineLen, maxNonPrintablePerLine)
                    for (line in lines) {
                        val sent = inbound.trySend(InboundEvent.LineReceived(sessionId, line)).isSuccess
                        if (sent) {
                            inboundBackpressureFailures = 0
                            continue
                        }
                        inboundBackpressureFailures++
                        if (inboundBackpressureFailures >= maxInboundBackpressureFailures) {
                            throw InboundBackpressure("inbound backpressure")
                        }
                    }
                }

                is Frame.Close -> {
                    break
                }

                else -> {
                    Unit
                }
            }
        }
    } catch (b: InboundBackpressure) {
        noteDisconnectReason(b.message ?: "inbound backpressure")
    } catch (v: ProtocolViolation) {
        noteDisconnectReason("protocol violation: ${v.message}")
    } catch (t: Throwable) {
        val reason = t.message ?: t::class.simpleName ?: "unknown error"
        noteDisconnectReason("read error: $reason")
    } finally {
        writerJob.cancelAndJoin()
        disconnect()
    }
}

internal fun splitIncomingLines(payload: String): List<String> {
    if (payload.isEmpty()) return listOf("")

    val lines = mutableListOf<String>()
    var start = 0
    var i = 0
    while (i < payload.length) {
        when (payload[i]) {
            '\r' -> {
                lines.add(payload.substring(start, i))
                if (i + 1 < payload.length && payload[i + 1] == '\n') {
                    i++
                }
                start = i + 1
            }

            '\n' -> {
                lines.add(payload.substring(start, i))
                start = i + 1
            }
        }
        i++
    }

    if (start < payload.length) {
        lines.add(payload.substring(start))
    }

    return if (lines.isEmpty()) listOf(payload) else lines
}

internal fun sanitizeIncomingLines(
    payload: String,
    maxLineLen: Int,
    maxNonPrintablePerLine: Int,
): List<String> {
    val lines = splitIncomingLines(payload)
    for (line in lines) {
        if (line.length > maxLineLen) {
            throw ProtocolViolation("Line too long (>$maxLineLen)")
        }

        var nonPrintableCount = 0
        for (ch in line) {
            val printable = (ch in ' '..'~') || ch == '\t'
            if (!printable) {
                nonPrintableCount++
                if (nonPrintableCount > maxNonPrintablePerLine) {
                    throw ProtocolViolation("Too many non-printable bytes in line")
                }
            }
        }
    }
    return lines
}

private fun sanitizeCloseReason(reason: String): String {
    val cleaned =
        reason
            .replace('\r', ' ')
            .replace('\n', ' ')
            .trim()
    return when {
        cleaned.isEmpty() -> "closed"
        cleaned.length <= MAX_CLOSE_REASON_LENGTH -> cleaned
        else -> cleaned.take(MAX_CLOSE_REASON_LENGTH)
    }
}

private const val MAX_CLOSE_REASON_LENGTH = 123
