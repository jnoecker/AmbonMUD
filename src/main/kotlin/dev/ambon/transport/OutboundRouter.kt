package dev.ambon.transport

import dev.ambon.domain.SessionId
import dev.ambon.engine.OutboundEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class OutboundRouter(
    private val engineOutbound: ReceiveChannel<OutboundEvent>,
    private val scope: CoroutineScope,
) {
    private data class SessionSink(
        val queue: Channel<String>,
        val close: (String) -> Unit,
        @Volatile var lastEnqueuedWasPrompt: Boolean = false,
    )

    private val sinks = ConcurrentHashMap<SessionId, SessionSink>()

    // Keep prompt formatting transport-side
    private val promptText = "> "

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
                        sendText(ev.sessionId, ev.text)
                    }

                    is OutboundEvent.SendPrompt -> {
                        sendPrompt(ev.sessionId)
                    }

                    is OutboundEvent.Close -> {
                        // Best-effort goodbye text; then close.
                        sendText(ev.sessionId, ev.reason + "")
                        sinks.remove(ev.sessionId)?.close?.invoke(ev.reason)
                    }
                }
            }
        }

    private fun sendText(
        sessionId: SessionId,
        text: String,
    ) {
        val sink = sinks[sessionId] ?: return
        val framed = text + "\r\n"

        val ok = sink.queue.trySend(framed).isSuccess
        if (ok) {
            sink.lastEnqueuedWasPrompt = false
            return
        }

        sinks.remove(sessionId)
        sink.close("Disconnected: client too slow (outbound backpressure)")
        sink.queue.close()
    }

    private fun sendPrompt(sessionId: SessionId) {
        val sink = sinks[sessionId] ?: return

        // Coalesce prompts
        if (sink.lastEnqueuedWasPrompt) return

        val ok = sink.queue.trySend(promptText).isSuccess
        if (ok) {
            sink.lastEnqueuedWasPrompt = true
            return
        }
        // Queue full: prompts are disposable
    }
}
