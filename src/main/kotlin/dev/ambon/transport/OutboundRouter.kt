package dev.ambon.transport

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.ui.login.LoginScreen
import dev.ambon.ui.login.LoginScreenLoader
import dev.ambon.ui.login.LoginScreenRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class OutboundRouter(
    private val engineOutbound: ReceiveChannel<OutboundEvent>,
    private val scope: CoroutineScope,
    private val loginScreen: LoginScreen = LoginScreenLoader.load(),
    private val loginScreenRenderer: LoginScreenRenderer = LoginScreenRenderer(),
) {
    init {
        require(loginScreen.isValid()) {
            "LoginScreen is invalid: lines=${loginScreen.lines.size}, ansiPrefixesByLine=${loginScreen.ansiPrefixesByLine.size}"
        }
    }

    private data class SessionSink(
        val queue: Channel<String>,
        val close: (String) -> Unit,
        @Volatile var lastEnqueuedWasPrompt: Boolean = false,
        @Volatile var renderer: TextRenderer = PlainRenderer(),
    )

    private val sinks = ConcurrentHashMap<SessionId, SessionSink>()

    private val promptSpec = PromptSpec(text = "> ")

    fun register(
        sessionId: SessionId,
        queue: Channel<String>,
        close: (String) -> Unit,
    ) {
        sinks[sessionId] = SessionSink(queue, close)
    }

    fun unregister(sessionId: SessionId) {
        sinks.remove(sessionId)?.queue?.close()
    }

    fun start(): Job =
        scope.launch {
            for (ev in engineOutbound) {
                when (ev) {
                    is OutboundEvent.SendText -> {
                        sendLine(ev.sessionId, ev.text, TextKind.NORMAL)
                    }

                    is OutboundEvent.SendInfo -> {
                        sendLine(ev.sessionId, ev.text, TextKind.INFO)
                    }

                    is OutboundEvent.SendError -> {
                        sendLine(ev.sessionId, ev.text, TextKind.ERROR)
                    }

                    is OutboundEvent.SendPrompt -> {
                        sendPrompt(ev.sessionId)
                    }

                    is OutboundEvent.ShowLoginScreen -> {
                        showLoginScreen(ev.sessionId)
                    }

                    is OutboundEvent.SetAnsi -> {
                        setAnsi(ev.sessionId, ev.enabled)
                    }

                    is OutboundEvent.ClearScreen -> {
                        clearScreen(ev.sessionId)
                    }

                    is OutboundEvent.ShowAnsiDemo -> {
                        showAnsiDemo(ev.sessionId)
                    }

                    is OutboundEvent.Close -> {
                        // Best-effort goodbye text; then close.
                        sendLine(ev.sessionId, ev.reason + "", TextKind.ERROR)
                        sinks.remove(ev.sessionId)?.close?.invoke(ev.reason)
                    }
                }
            }
        }

    private fun sendLine(
        sessionId: SessionId,
        text: String,
        kind: TextKind,
    ) {
        val sink = sinks[sessionId] ?: return
        val framed = sink.renderer.renderLine(text, kind)
        if (enqueueFramed(sessionId, sink, framed)) {
            sink.lastEnqueuedWasPrompt = false
        }
    }

    private fun sendPrompt(sessionId: SessionId) {
        val sink = sinks[sessionId] ?: return

        // Coalesce prompts
        if (sink.lastEnqueuedWasPrompt) return

        val framed = sink.renderer.renderPrompt(promptSpec)
        val ok = sink.queue.trySend(framed).isSuccess
        if (ok) sink.lastEnqueuedWasPrompt = true

        // Queue full: prompts are disposable
    }

    private fun setAnsi(
        sessionId: SessionId,
        enabled: Boolean,
    ) {
        val sink = sinks[sessionId] ?: return
        sink.renderer = if (enabled) AnsiRenderer() else PlainRenderer()
        sink.lastEnqueuedWasPrompt = false
    }

    private fun showLoginScreen(sessionId: SessionId) {
        val sink = sinks[sessionId] ?: return
        val ansiEnabled = sink.renderer is AnsiRenderer
        val frames = loginScreenRenderer.render(loginScreen, ansiEnabled)

        for (frame in frames) {
            if (!enqueueFramed(sessionId, sink, frame)) {
                return
            }
        }

        sink.lastEnqueuedWasPrompt = false
    }

    private fun clearScreen(sessionId: SessionId) {
        val sink = sinks[sessionId] ?: return
        val isAnsi = sink.renderer is AnsiRenderer
        if (!isAnsi) {
            sendLine(sessionId, "----------------", TextKind.NORMAL)
            return
        }
        // ESC[2J clears, ESC[H homes cursor
        if (enqueueFramed(sessionId, sink, "\u001B[2J\u001B[H")) {
            sink.lastEnqueuedWasPrompt = false
        }
    }

    private fun showAnsiDemo(sessionId: SessionId) {
        val sink = sinks[sessionId] ?: return
        val isAnsi = sink.renderer is AnsiRenderer
        if (!isAnsi) {
            sendLine(sessionId, "ANSI is off. Type: ansi on", TextKind.INFO)
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
        val ok = sink.queue.trySend(framed).isSuccess
        if (ok) return true

        disconnectSlowClient(sessionId, sink)
        return false
    }

    private fun disconnectSlowClient(
        sessionId: SessionId,
        sink: SessionSink,
    ) {
        sinks.remove(sessionId)
        sink.close("Disconnected: client too slow (outbound backpressure)")
        sink.queue.close()
    }
}
