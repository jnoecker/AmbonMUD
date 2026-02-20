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
) : Transport {
    private var engine: ApplicationEngine? = null

    override suspend fun start() {
        engine =
            embeddedServer(Netty, port = port, host = host) {
                quickMudWebModule(
                    inbound = inbound,
                    outboundRouter = outboundRouter,
                    sessionIdFactory = sessionIdFactory,
                    sessionOutboundQueueCapacity = sessionOutboundQueueCapacity,
                )
            }.start(wait = false)
    }

    override suspend fun stop() {
        engine?.stop(gracePeriodMillis = stopGraceMillis, timeoutMillis = stopTimeoutMillis)
        engine = null
    }
}

internal fun Application.quickMudWebModule(
    inbound: SendChannel<InboundEvent>,
    outboundRouter: OutboundRouter,
    sessionIdFactory: () -> SessionId,
    sessionOutboundQueueCapacity: Int = 200,
) {
    install(WebSockets)

    routing {
        webSocket("/ws") {
            bridgeWebSocketSession(
                inbound = inbound,
                outboundRouter = outboundRouter,
                sessionIdFactory = sessionIdFactory,
                sessionOutboundQueueCapacity = sessionOutboundQueueCapacity,
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
) {
    val sessionId = sessionIdFactory()
    val outboundQueue = Channel<String>(capacity = sessionOutboundQueueCapacity)
    val disconnected = AtomicBoolean(false)
    val disconnectReason = AtomicReference("EOF")

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

    outboundRouter.register(sessionId, outboundQueue) { reason ->
        this@bridgeWebSocketSession.launch {
            noteDisconnectReason(reason)
            runCatching { this@bridgeWebSocketSession.close(CloseReason(CloseReason.Codes.NORMAL, sanitizeCloseReason(reason))) }
        }
    }

    val connectedOk = runCatching { inbound.send(InboundEvent.Connected(sessionId)) }.isSuccess
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
                    for (line in splitIncomingLines(frame.readText())) {
                        inbound.send(InboundEvent.LineReceived(sessionId, line))
                    }
                }

                is Frame.Close -> break
                else -> Unit
            }
        }
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
