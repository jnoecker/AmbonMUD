package dev.ambon.transport

import dev.ambon.bus.InboundBus
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.InboundEvent
import dev.ambon.metrics.GameMetrics
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private val log = KotlinLogging.logger {}

class KtorWebSocketTransport(
    private val port: Int,
    private val inbound: InboundBus,
    private val outboundRouter: OutboundRouter,
    private val sessionIdFactory: () -> SessionId,
    private val host: String = "0.0.0.0",
    private val sessionOutboundQueueCapacity: Int = 200,
    private val stopGraceMillis: Long = 1_000L,
    private val stopTimeoutMillis: Long = 2_000L,
    private val maxLineLen: Int = 1024,
    private val maxNonPrintablePerLine: Int = 32,
    private val maxInboundBackpressureFailures: Int = 3,
    private val prometheusRegistry: PrometheusMeterRegistry? = null,
    private val metricsEndpoint: String = "/metrics",
    private val metrics: GameMetrics = GameMetrics.noop(),
) : Transport {
    private var engine: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

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
                    prometheusRegistry = prometheusRegistry,
                    metricsEndpoint = metricsEndpoint,
                    metrics = metrics,
                )
            }.start(wait = false)
    }

    override suspend fun stop() {
        engine?.stop(gracePeriodMillis = stopGraceMillis, timeoutMillis = stopTimeoutMillis)
        engine = null
    }
}

internal fun Application.ambonMUDWebModule(
    inbound: InboundBus,
    outboundRouter: OutboundRouter,
    sessionIdFactory: () -> SessionId,
    sessionOutboundQueueCapacity: Int = 200,
    maxLineLen: Int = 1024,
    maxNonPrintablePerLine: Int = 32,
    maxInboundBackpressureFailures: Int = 3,
    prometheusRegistry: PrometheusMeterRegistry? = null,
    metricsEndpoint: String = "/metrics",
    metrics: GameMetrics = GameMetrics.noop(),
) {
    install(WebSockets)

    routing {
        if (prometheusRegistry != null) {
            get(metricsEndpoint) {
                call.respondText(
                    contentType = ContentType.parse("text/plain; version=0.0.4; charset=utf-8"),
                    text = prometheusRegistry.scrape(),
                )
            }
        }

        webSocket("/ws") {
            bridgeWebSocketSession(
                inbound = inbound,
                outboundRouter = outboundRouter,
                sessionIdFactory = sessionIdFactory,
                sessionOutboundQueueCapacity = sessionOutboundQueueCapacity,
                maxLineLen = maxLineLen,
                maxNonPrintablePerLine = maxNonPrintablePerLine,
                maxInboundBackpressureFailures = maxInboundBackpressureFailures,
                metrics = metrics,
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
    inbound: InboundBus,
    outboundRouter: OutboundRouter,
    sessionIdFactory: () -> SessionId,
    sessionOutboundQueueCapacity: Int,
    maxLineLen: Int,
    maxNonPrintablePerLine: Int,
    maxInboundBackpressureFailures: Int,
    metrics: GameMetrics = GameMetrics.noop(),
) {
    val sessionId = sessionIdFactory()
    val outboundQueue = Channel<OutboundFrame>(capacity = sessionOutboundQueueCapacity)
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
        queueCapacity = sessionOutboundQueueCapacity,
        transport = "ws",
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
    // Auto-opt WebSocket sessions into GMCP packages
    inbound.trySend(
        InboundEvent.GmcpReceived(
            sessionId,
            "Core.Supports.Set",
            """["Char.Vitals 1","Room.Info 1","Char.StatusVars 1","Char.Items 1","Room.Players 1","Room.Mobs 1","Char.Skills 1","Char.Name 1","Comm.Channel 1","Core.Ping 1"]""",
        ),
    )
    metrics.onWsConnected()
    log.debug { "WebSocket session connected: sessionId=$sessionId" }

    val writerJob =
        launch {
            try {
                for (frame in outboundQueue) {
                    when (frame) {
                        is OutboundFrame.Text -> send(Frame.Text(frame.content))
                        is OutboundFrame.Gmcp -> {
                            val pkg = frame.gmcpPackage
                            val data = frame.jsonData
                            send(Frame.Text("""{"gmcp":"$pkg","data":$data}"""))
                        }
                    }
                    outboundRouter.onSessionQueueFrameConsumed(sessionId)
                }
            } catch (_: Throwable) {
                // best effort
            }
        }

    try {
        for (frame in incoming) {
            when (frame) {
                is Frame.Text -> {
                    val text = frame.readText()
                    // Detect GMCP JSON envelope: {"gmcp":"Package","data":<anything>}
                    val gmcpPair = tryParseGmcpEnvelope(text)
                    if (gmcpPair != null) {
                        inbound.trySend(InboundEvent.GmcpReceived(sessionId, gmcpPair.first, gmcpPair.second))
                        continue
                    }
                    val lines = sanitizeIncomingLines(text, maxLineLen, maxNonPrintablePerLine)
                    for (line in lines) {
                        val sent = inbound.trySend(InboundEvent.LineReceived(sessionId, line)).isSuccess
                        if (sent) {
                            inboundBackpressureFailures = 0
                            metrics.onInboundLineWs()
                            continue
                        }
                        inboundBackpressureFailures++
                        metrics.onInboundBackpressureFailure()
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
        val reason = disconnectReason.get()
        log.debug { "WebSocket session disconnected: sessionId=$sessionId reason=$reason" }
        metrics.onWsDisconnected(reason)
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

/**
 * Parses a WebSocket GMCP envelope of the form `{"gmcp":"Package","data":<json>}`.
 * Returns a Pair(package, jsonData) or null if the text is not a GMCP envelope.
 */
internal fun tryParseGmcpEnvelope(text: String): Pair<String, String>? {
    val trimmed = text.trim()
    if (!trimmed.startsWith('{')) return null
    // Simple regex-free extraction: look for "gmcp" key
    val gmcpKey = "\"gmcp\""
    val dataKey = "\"data\""
    val gmcpIdx = trimmed.indexOf(gmcpKey)
    if (gmcpIdx == -1) return null

    // Extract package name value (string after "gmcp":)
    val colonAfterGmcp = trimmed.indexOf(':', gmcpIdx + gmcpKey.length)
    if (colonAfterGmcp == -1) return null
    val quoteStart = trimmed.indexOf('"', colonAfterGmcp + 1)
    if (quoteStart == -1) return null
    val quoteEnd = trimmed.indexOf('"', quoteStart + 1)
    if (quoteEnd == -1) return null
    val pkg = trimmed.substring(quoteStart + 1, quoteEnd)
    if (pkg.isBlank()) return null

    // Extract data value (everything after "data":)
    val dataIdx = trimmed.indexOf(dataKey)
    val jsonData =
        if (dataIdx == -1) {
            "{}"
        } else {
            val colonAfterData = trimmed.indexOf(':', dataIdx + dataKey.length)
            if (colonAfterData == -1) {
                "{}"
            } else {
                // Take everything from after the colon to the end, trimming trailing `}`
                val raw = trimmed.substring(colonAfterData + 1).trimEnd()
                if (raw.endsWith('}')) raw.dropLast(1).trimEnd() else raw
            }
        }

    return Pair(pkg, jsonData.ifBlank { "{}" })
}

private const val MAX_CLOSE_REASON_LENGTH = 123
