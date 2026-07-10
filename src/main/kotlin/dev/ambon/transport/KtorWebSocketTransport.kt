package dev.ambon.transport

import dev.ambon.bus.InboundBus
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.InboundEvent
import dev.ambon.metrics.GameMetrics
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.matchContentType
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
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
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private val log = KotlinLogging.logger {}

// Maximum number of extra frames to drain per write iteration before flushing the text batch.
// Keeps latency bounded while amortising WebSocket frame overhead across bursts of text output.
internal const val MAX_WS_FRAMES_PER_BATCH = 64
internal const val IMMUTABLE_WEB_CACHE_CONTROL = "public, max-age=31536000, immutable"
internal const val REVALIDATE_WEB_CACHE_CONTROL = "no-cache"
internal const val MUTABLE_MEDIA_CACHE_CONTROL = "public, max-age=3600, stale-while-revalidate=86400"

internal fun webResourceCacheControl(resourcePath: String): String {
    val normalized = resourcePath.replace('\\', '/')
    return when {
        "/assets/" in normalized -> IMMUTABLE_WEB_CACHE_CONTROL
        normalized.endsWith("/index.html") || normalized.endsWith("/sw.js") -> REVALIDATE_WEB_CACHE_CONTROL
        else -> MUTABLE_MEDIA_CACHE_CONTROL
    }
}

/**
 * Drain [outboundQueue] into [send], coalescing consecutive [OutboundFrame.Text] entries into
 * one WebSocket [Frame.Text] per wake-up batch. GMCP envelopes are flushed and sent in their
 * own frame so the client's parser can recognise them.
 *
 * Exits cleanly when the queue is closed; swallows send errors (best-effort).
 */
internal suspend fun writeCoalescedOutboundFrames(
    outboundQueue: ReceiveChannel<OutboundFrame>,
    send: suspend (Frame) -> Unit,
    onFrameConsumed: (OutboundFrame) -> Unit,
    maxBatch: Int = MAX_WS_FRAMES_PER_BATCH,
) {
    val pendingText = StringBuilder()

    suspend fun flushPendingText() {
        if (pendingText.isNotEmpty()) {
            send(Frame.Text(pendingText.toString()))
            pendingText.setLength(0)
        }
    }

    suspend fun process(frame: OutboundFrame) {
        when (frame) {
            is OutboundFrame.Text -> pendingText.append(frame.content)
            is OutboundFrame.Gmcp -> {
                flushPendingText()
                send(Frame.Text("""{"gmcp":"${frame.gmcpPackage}","data":${frame.jsonData}}"""))
            }
        }
        onFrameConsumed(frame)
    }

    try {
        for (frame in outboundQueue) {
            process(frame)
            var drained = 0
            while (drained < maxBatch) {
                val next = outboundQueue.tryReceive().getOrNull() ?: break
                process(next)
                drained++
            }
            flushPendingText()
        }
    } catch (_: Throwable) {
        // best effort
    }
}

/**
 * Bounds concurrent WebSocket connections globally and per source IP. Unauthenticated `/ws`
 * upgrades each allocate a session, channel, and coroutines, so without a cap a single host could
 * exhaust memory / file descriptors. [tryAcquire] reserves a slot atomically (or returns false to
 * reject) and [release] frees it on disconnect.
 */
internal class WsConnectionLimiter(
    private val maxTotal: Int,
    private val maxPerIp: Int,
) {
    private val total = AtomicInteger(0)
    private val perIp = ConcurrentHashMap<String, AtomicInteger>()

    fun tryAcquire(ip: String): Boolean {
        if (total.incrementAndGet() > maxTotal) {
            total.decrementAndGet()
            return false
        }
        if (maxPerIp <= 0) return true
        val counter = perIp.computeIfAbsent(ip) { AtomicInteger(0) }
        if (counter.incrementAndGet() > maxPerIp) {
            counter.decrementAndGet()
            total.decrementAndGet()
            return false
        }
        return true
    }

    fun release(ip: String) {
        total.decrementAndGet()
        if (maxPerIp <= 0) return
        perIp.computeIfPresent(ip) { _, counter ->
            if (counter.decrementAndGet() <= 0) null else counter
        }
    }
}

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
    private val maxConnections: Int = 5000,
    private val maxConnectionsPerIp: Int = 30,
    private val pingPeriodMillis: Long = 15_000L,
    private val pongTimeoutMillis: Long = 30_000L,
    private val maxFrameBytes: Long = 65_536L,
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
                    maxConnections = maxConnections,
                    maxConnectionsPerIp = maxConnectionsPerIp,
                    pingPeriodMillis = pingPeriodMillis,
                    pongTimeoutMillis = pongTimeoutMillis,
                    maxFrameBytes = maxFrameBytes,
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
    maxConnections: Int = 5000,
    maxConnectionsPerIp: Int = 30,
    pingPeriodMillis: Long = 15_000L,
    pongTimeoutMillis: Long = 30_000L,
    maxFrameBytes: Long = 65_536L,
    prometheusRegistry: PrometheusMeterRegistry? = null,
    metricsEndpoint: String = "/metrics",
    metrics: GameMetrics = GameMetrics.noop(),
) {
    install(WebSockets) {
        // Server-driven pings detect dead/slow-loris peers that hold a connection open without
        // sending data, so they are reaped instead of consuming a slot indefinitely.
        if (pingPeriodMillis > 0) this.pingPeriodMillis = pingPeriodMillis
        if (pongTimeoutMillis > 0) this.timeoutMillis = pongTimeoutMillis
        // Cap inbound frame size at the protocol layer as defence-in-depth alongside the per-frame
        // check in bridgeWebSocketSession.
        maxFrameSize = maxFrameBytes
    }

    install(Compression) {
        gzip {
            priority = 1.0
            minimumSize(1_024)
            matchContentType(
                ContentType.Text.Any,
                ContentType.Application.JavaScript,
                ContentType.Application.Json,
            )
        }
    }

    install(securityHeadersPlugin())

    val connectionLimiter = WsConnectionLimiter(maxConnections, maxConnectionsPerIp)

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
            val origin = call.request.headers[HttpHeaders.Origin]
            if (origin != null) {
                val host = call.request.headers[HttpHeaders.Host]
                if (!isOriginAllowedForHost(origin, host)) {
                    log.warn { "WebSocket rejected: origin=$origin host=$host" }
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Origin not allowed"))
                    return@webSocket
                }
            }
            val remoteIp = call.request.origin.remoteHost
            if (!connectionLimiter.tryAcquire(remoteIp)) {
                log.warn { "WebSocket rejected: connection limit reached for remoteIp=$remoteIp" }
                close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Connection limit reached"))
                return@webSocket
            }
            try {
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
            } finally {
                connectionLimiter.release(remoteIp)
            }
        }

        get("/v3") {
            call.respondRedirect("/")
        }

        get("/v3/") {
            call.respondRedirect("/")
        }

        staticResources(
            remotePath = "/images",
            basePackage = "world/images",
        ) {
            modify { _, call ->
                call.response.header(HttpHeaders.CacheControl, MUTABLE_MEDIA_CACHE_CONTROL)
            }
        }

        staticResources(
            remotePath = "/audio",
            basePackage = "world/audio",
        ) {
            modify { _, call ->
                call.response.header(HttpHeaders.CacheControl, MUTABLE_MEDIA_CACHE_CONTROL)
            }
        }

        staticResources(
            remotePath = "/videos",
            basePackage = "world/video",
        ) {
            modify { _, call ->
                call.response.header(HttpHeaders.CacheControl, MUTABLE_MEDIA_CACHE_CONTROL)
            }
        }

        staticResources(
            remotePath = "/",
            basePackage = "web-v3",
            index = "index.html",
        ) {
            modify { resource, call ->
                call.response.header(HttpHeaders.CacheControl, webResourceCacheControl(resource.path))
            }
        }
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
    val backpressureTracker = InboundBackpressureTracker(maxInboundBackpressureFailures)

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
            """["Char.Vitals 1","Room.Info 1","Zone 1","Char.StatusVars 1","Char.Items 1","Char.Equipment 1","Room.Players 1","Room.Mobs 1","Room.Items 1","Char.Skills 1","Char.Name 1","Char.StatusEffects 1","Char.Achievements 1","Char.Sprites 1","Comm.Channel 1","Guild 1","Group.Info 1","Friends 1","Core.Ping 1","Dialogue 1","Shop 1","Char.Combat 1","Char.Combat.Event 1","Char.Stats 1","Quest 1","Char.Cooldown 1","Char.Gain 1","Char.LevelUp 1","Room.MobInfo 1","Mail 1","Crafting 1","Login 1","Server 1","UI.Feedback 1","Staff 1","Housing 1","Session 1","Trade 1","Auction 1","Char.Factions 1","Char.Currencies 1","Char.Pet 1","Char.Pet.Skills 1","Char.Bank 1","Char.Stylist 1","Char.Flight 1","Char.Boat 1","Char.Recall 1","World 1","Leaderboard 1","Trainer 1","Char.Classes 1","Lottery 1","Duel 1","Dungeon 1","Prestige 1","Puzzle 1","Arcanum 1","Jukebox 1","MusicBox 1","Travel 1"]""",
        ),
    )
    metrics.onWsConnected()
    log.debug { "WebSocket session connected: sessionId=$sessionId" }

    val writerJob =
        launch {
            // Consecutive OutboundFrame.Text entries are concatenated into a single WebSocket
            // Frame.Text to reduce per-frame header + send-call overhead. GMCP envelopes stay
            // isolated — the client's message handler parses each incoming WS text frame as
            // either a GMCP envelope or plain text, never mixed (see useMudSocket.ts).
            writeCoalescedOutboundFrames(
                outboundQueue = outboundQueue,
                send = { send(it) },
                onFrameConsumed = { frame ->
                    outboundRouter.onSessionQueueFrameConsumed(sessionId, frame.enqueuedAt)
                },
            )
        }

    try {
        for (frame in incoming) {
            when (frame) {
                is Frame.Text -> {
                    if (frame.data.size > MAX_WS_FRAME_SIZE) {
                        log.warn { "WebSocket frame too large (${frame.data.size} bytes), disconnecting: sessionId=$sessionId" }
                        throw ProtocolViolation("Frame too large (${frame.data.size} > $MAX_WS_FRAME_SIZE)")
                    }
                    val text = frame.readText()
                    // Detect GMCP JSON envelope: {"gmcp":"Package","data":<anything>}
                    val gmcpPair = tryParseGmcpEnvelope(text)
                    if (gmcpPair != null) {
                        inbound.trySend(InboundEvent.GmcpReceived(sessionId, gmcpPair.first, gmcpPair.second))
                        continue
                    }
                    val lines = sanitizeIncomingLines(text, maxLineLen, maxNonPrintablePerLine)
                    for (line in lines) {
                        backpressureTracker.sendLineOrThrow(inbound, sessionId, line, metrics) {
                            metrics.onInboundLineWs()
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
    if (payload.isEmpty()) return listOf("")

    val lines = mutableListOf<String>()
    var start = 0
    var nonPrintableCount = 0
    var i = 0

    while (i < payload.length) {
        val ch = payload[i]
        if (ch == '\r' || ch == '\n') {
            lines.add(payload.substring(start, i))
            if (ch == '\r' && i + 1 < payload.length && payload[i + 1] == '\n') i++
            start = i + 1
            nonPrintableCount = 0
        } else {
            if (i - start >= maxLineLen) throw ProtocolViolation("Line too long (>$maxLineLen)")
            val printable = (ch in ' '..'~') || ch == '\t'
            if (!printable) {
                nonPrintableCount++
                if (nonPrintableCount > maxNonPrintablePerLine) {
                    throw ProtocolViolation("Too many non-printable bytes in line")
                }
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
    val cleaned = reason.filter { it.code in 0x20..0x7E }.trim()
    return when {
        cleaned.isEmpty() -> "closed"
        cleaned.length <= MAX_CLOSE_REASON_LENGTH -> cleaned
        else -> cleaned.take(MAX_CLOSE_REASON_LENGTH)
    }
}

// Matches {"gmcp":"Package"} or {"gmcp":"Package","data":<json>} with optional whitespace.
// Group 1 = package name, Group 2 = data value (absent when no "data" key present).
private val GMCP_ENVELOPE_REGEX = Regex(
    """^\s*\{\s*"gmcp"\s*:\s*"([^"]+)"\s*(?:,\s*"data"\s*:\s*([\s\S]*))?\s*\}\s*$""",
)

/**
 * Parses a WebSocket GMCP envelope of the form `{"gmcp":"Package","data":<json>}`.
 * Returns a Pair(package, jsonData) or null if the text is not a GMCP envelope.
 */
internal fun tryParseGmcpEnvelope(text: String): Pair<String, String>? {
    val match = GMCP_ENVELOPE_REGEX.matchEntire(text) ?: return null
    val pkg = match.groupValues[1]
    if (pkg.isBlank()) return null
    val jsonData = match.groupValues[2].trimEnd().ifBlank { "{}" }
    return Pair(pkg, jsonData)
}

private const val MAX_CLOSE_REASON_LENGTH = 123
private const val MAX_WS_FRAME_SIZE = 65_536

/**
 * Content-Security-Policy for the served web client. `frame-ancestors 'none'` blocks clickjacking,
 * `object-src 'none'` / `base-uri 'self'` close common injection vectors, and `img/media/connect`
 * are constrained to self + HTTPS so painted-art and audio assets (Cloudflare R2) keep working while
 * arbitrary exfiltration origins are disallowed. `script-src`/`style-src` retain `'unsafe-inline'`
 * because the Vite bundle inlines a module-preload polyfill and the React UI uses inline style
 * attributes; tightening these to nonces/hashes is a follow-up. The client itself performs no HTML
 * interpolation of untrusted text (all rendering goes through React escaping / the xterm canvas).
 */
private val WEB_CONTENT_SECURITY_POLICY =
    listOf(
        "default-src 'self'",
        "base-uri 'self'",
        "object-src 'none'",
        "frame-ancestors 'none'",
        "script-src 'self' 'unsafe-inline'",
        "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
        "font-src 'self' https://fonts.gstatic.com data:",
        "img-src 'self' https: data: blob:",
        "media-src 'self' https: data: blob:",
        "connect-src 'self' ws: wss: https:",
        "worker-src 'self' blob:",
        "manifest-src 'self'",
    ).joinToString("; ")

/**
 * Adds defence-in-depth security response headers (CSP, nosniff, frame/referrer policy) to every
 * HTTP response served by the web module.
 */
internal fun securityHeadersPlugin() =
    createApplicationPlugin(name = "AmbonSecurityHeaders") {
        onCall { call ->
            val headers = call.response.headers
            headers.append("Content-Security-Policy", WEB_CONTENT_SECURITY_POLICY)
            headers.append("X-Content-Type-Options", "nosniff")
            headers.append("X-Frame-Options", "DENY")
            headers.append("Referrer-Policy", "no-referrer")
        }
    }

/**
 * Validates that an Origin header is consistent with the request Host.
 * Extracts the hostname from the Origin URL and compares it against
 * the Host header (ignoring port). Returns true if they match or if
 * the Host header is absent (non-browser clients).
 */
internal fun isOriginAllowedForHost(origin: String, host: String?): Boolean {
    if (host.isNullOrBlank()) return true
    val originHost = try {
        val afterScheme = origin.substringAfter("://")
        afterScheme.substringBefore("/").substringBefore(":")
    } catch (_: Exception) {
        return false
    }
    val requestHost = host.substringBefore(":")
    return originHost.equals(requestHost, ignoreCase = true)
}
