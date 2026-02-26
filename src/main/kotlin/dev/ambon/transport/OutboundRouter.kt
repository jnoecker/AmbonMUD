package dev.ambon.transport

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.metrics.GameMetrics
import dev.ambon.ui.login.LoginScreen
import dev.ambon.ui.login.LoginScreenLoader
import dev.ambon.ui.login.LoginScreenRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class OutboundRouter(
    private val engineOutbound: OutboundBus,
    private val scope: CoroutineScope,
    private val loginScreen: LoginScreen = LoginScreenLoader.load(),
    private val loginScreenRenderer: LoginScreenRenderer = LoginScreenRenderer(),
    private val metrics: GameMetrics = GameMetrics.noop(),
) {
    init {
        require(loginScreen.isValid()) {
            "LoginScreen is invalid: lines=${loginScreen.lines.size}, ansiPrefixesByLine=${loginScreen.ansiPrefixesByLine.size}"
        }
        metrics.bindSessionOutboundQueueAggregate(
            totalDepthSupplier = { sinks.values.sumOf { it.queueDepth.get() } },
            maxDepthSupplier = { sinks.values.maxOfOrNull { it.queueDepth.get() } ?: 0 },
        )
    }

    private data class SessionSink(
        val queue: Channel<OutboundFrame>,
        val close: (String) -> Unit,
        val transport: String = "unknown",
        val queueCapacity: Int = 0,
        val queueDepth: AtomicInteger = AtomicInteger(0),
        @Volatile var lastEnqueuedWasPrompt: Boolean = false,
        @Volatile var renderer: TextRenderer = PlainRenderer(),
    )

    private val sinks = ConcurrentHashMap<SessionId, SessionSink>()

    private val promptSpec = PromptSpec(text = "> ")

    fun register(
        sessionId: SessionId,
        queue: Channel<OutboundFrame>,
        queueCapacity: Int = 0,
        transport: String = "unknown",
        defaultAnsiEnabled: Boolean = false,
        close: (String) -> Unit,
    ) {
        val sink =
            SessionSink(
                queue = queue,
                close = close,
                transport = transport,
                queueCapacity = queueCapacity,
                renderer = if (defaultAnsiEnabled) AnsiRenderer() else PlainRenderer(),
            )
        sinks[sessionId] = sink
    }

    fun unregister(sessionId: SessionId) {
        sinks.remove(sessionId)?.queue?.close()
    }

    /**
     * Forcibly closes one active session through its registered close callback.
     *
     * Returns true if an active session sink existed and was closed.
     */
    fun forceDisconnect(
        sessionId: SessionId,
        reason: String,
    ): Boolean {
        val sink = sinks.remove(sessionId) ?: return false
        sink.close(reason)
        sink.queue.close()
        return true
    }

    /** Forcibly disconnects all currently registered sessions. */
    fun disconnectAll(reason: String): Int {
        val sessionIds = sinks.keys.toList()
        var disconnected = 0
        for (sessionId in sessionIds) {
            if (forceDisconnect(sessionId, reason)) {
                disconnected++
            }
        }
        return disconnected
    }

    fun start(): Job =
        scope.launch {
            for (ev in engineOutbound.asReceiveChannel()) {
                // Single map lookup per event — resolved sink is passed directly to helpers.
                when (ev) {
                    is OutboundEvent.SendText -> {
                        val sink = sinks[ev.sessionId] ?: continue
                        sendLine(ev.sessionId, sink, ev.text, TextKind.NORMAL)
                    }

                    is OutboundEvent.SendInfo -> {
                        val sink = sinks[ev.sessionId] ?: continue
                        sendLine(ev.sessionId, sink, ev.text, TextKind.INFO)
                    }

                    is OutboundEvent.SendError -> {
                        val sink = sinks[ev.sessionId] ?: continue
                        sendLine(ev.sessionId, sink, ev.text, TextKind.ERROR)
                    }

                    is OutboundEvent.SendPrompt -> {
                        val sink = sinks[ev.sessionId] ?: continue
                        sendPrompt(ev.sessionId, sink)
                    }

                    is OutboundEvent.ShowLoginScreen -> {
                        val sink = sinks[ev.sessionId] ?: continue
                        showLoginScreen(ev.sessionId, sink)
                    }

                    is OutboundEvent.SetAnsi -> {
                        val sink = sinks[ev.sessionId] ?: continue
                        setAnsi(sink, ev.enabled)
                    }

                    is OutboundEvent.ClearScreen -> {
                        val sink = sinks[ev.sessionId] ?: continue
                        clearScreen(ev.sessionId, sink)
                    }

                    is OutboundEvent.ShowAnsiDemo -> {
                        val sink = sinks[ev.sessionId] ?: continue
                        showAnsiDemo(ev.sessionId, sink)
                    }

                    is OutboundEvent.Close -> {
                        // Remove first so no further events route to this sink.
                        val sink = sinks.remove(ev.sessionId) ?: continue
                        // Best-effort goodbye text; then close.
                        sendLine(ev.sessionId, sink, ev.reason, TextKind.ERROR)
                        sink.close(ev.reason)
                    }

                    is OutboundEvent.SessionRedirect -> {
                        // Handled at the gateway routing layer; ignored here.
                    }

                    is OutboundEvent.GmcpData -> {
                        val sink = sinks[ev.sessionId] ?: continue
                        enqueueGmcp(sink, ev.gmcpPackage, ev.jsonData)
                    }
                }
            }
        }

    private fun sendLine(
        sessionId: SessionId,
        sink: SessionSink,
        text: String,
        kind: TextKind,
    ) {
        val framed = sink.renderer.renderLine(text, kind)
        if (enqueueFramed(sessionId, sink, framed)) {
            sink.lastEnqueuedWasPrompt = false
        }
    }

    private fun sendPrompt(
        sessionId: SessionId,
        sink: SessionSink,
    ) {
        // Coalesce prompts
        if (sink.lastEnqueuedWasPrompt) return

        val framed = sink.renderer.renderPrompt(promptSpec)
        val ok = sink.queue.trySend(OutboundFrame.Text(framed)).isSuccess
        if (ok) {
            sink.queueDepth.incrementAndGet()
            sink.lastEnqueuedWasPrompt = true
        }

        // Queue full: prompts are disposable
    }

    private fun setAnsi(
        sink: SessionSink,
        enabled: Boolean,
    ) {
        sink.renderer = if (enabled) AnsiRenderer() else PlainRenderer()
        sink.lastEnqueuedWasPrompt = false
    }

    private fun showLoginScreen(
        sessionId: SessionId,
        sink: SessionSink,
    ) {
        val ansiEnabled = sink.renderer is AnsiRenderer
        val frames = loginScreenRenderer.render(loginScreen, ansiEnabled)

        for (frame in frames) {
            if (!enqueueFramed(sessionId, sink, frame)) {
                return
            }
        }

        sink.lastEnqueuedWasPrompt = false
    }

    private fun clearScreen(
        sessionId: SessionId,
        sink: SessionSink,
    ) {
        val isAnsi = sink.renderer is AnsiRenderer
        if (!isAnsi) {
            sendLine(sessionId, sink, "----------------", TextKind.NORMAL)
            return
        }
        // ESC[2J clears, ESC[H homes cursor
        if (enqueueFramed(sessionId, sink, "\u001B[2J\u001B[H")) {
            sink.lastEnqueuedWasPrompt = false
        }
    }

    private fun showAnsiDemo(
        sessionId: SessionId,
        sink: SessionSink,
    ) {
        val isAnsi = sink.renderer is AnsiRenderer
        if (!isAnsi) {
            sendLine(sessionId, sink, "ANSI is off. Type: ansi on", TextKind.INFO)
            return
        }

        val demo =
            buildString {
                append("\u001B[90mbright black\u001B[0m  ")
                append("\u001B[91mbright red\u001B[0m  ")
                append("\u001B[92mbright green\u001B[0m  ")
                append("\u001B[93mbright yellow\u001B[0m  ")
                append("\u001B[94mbright blue\u001B[0m  ")
                append("\u001B[95mbright magenta\u001B[0m  ")
                append("\u001B[96mbright cyan\u001B[0m  ")
                append("\u001B[97mbright white\u001B[0m")
                append("\r\n")
                append("\u001B[0m")
            }
        if (enqueueFramed(sessionId, sink, demo)) {
            sink.lastEnqueuedWasPrompt = false
        }
    }

    private fun enqueueFramed(
        sessionId: SessionId,
        sink: SessionSink,
        framed: String,
    ): Boolean {
        val ok = sink.queue.trySend(OutboundFrame.Text(framed)).isSuccess
        if (ok) {
            sink.queueDepth.incrementAndGet()
            metrics.onOutboundFrameEnqueued()
            return true
        }
        metrics.onOutboundEnqueueFailed()
        disconnectSlowClient(sessionId)
        return false
    }

    private fun enqueueGmcp(
        sink: SessionSink,
        gmcpPackage: String,
        jsonData: String,
    ) {
        // GMCP frames are best-effort; dropped silently on backpressure (no disconnect)
        val ok = sink.queue.trySend(OutboundFrame.Gmcp(gmcpPackage, jsonData)).isSuccess
        if (ok) {
            sink.queueDepth.incrementAndGet()
            metrics.onOutboundFrameEnqueued()
        }
    }

    fun onSessionQueueFrameConsumed(sessionId: SessionId) {
        sinks[sessionId]?.queueDepth?.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
    }

    private fun disconnectSlowClient(sessionId: SessionId) {
        metrics.onOutboundBackpressureDisconnect()
        forceDisconnect(sessionId, "Disconnected: client too slow (outbound backpressure)")
    }
}
