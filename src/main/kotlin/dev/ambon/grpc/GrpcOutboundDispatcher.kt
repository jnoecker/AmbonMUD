package dev.ambon.grpc

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.OutboundEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val log = KotlinLogging.logger {}

/**
 * Consumes the single [OutboundBus] channel and dispatches each event to the gRPC stream
 * for the gateway that owns the destination session.
 *
 * In engine mode this replaces [OutboundRouter] (which handles the per-session queuing in
 * standalone/gateway mode).
 */
class GrpcOutboundDispatcher(
    private val outbound: OutboundBus,
    private val serviceImpl: EngineServiceImpl,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null

    fun start() {
        job =
            scope.launch {
                for (event in outbound.asReceiveChannel()) {
                    dispatch(event)
                }
            }
        log.info { "GrpcOutboundDispatcher started" }
    }

    fun stop() {
        runBlocking { job?.cancelAndJoin() }
        log.info { "GrpcOutboundDispatcher stopped" }
    }

    private fun dispatch(event: OutboundEvent) {
        val sid = event.sessionId()
        val streamChannel = serviceImpl.sessionToStream[sid]
        if (streamChannel == null) {
            log.debug { "No gateway stream for session $sid; dropping $event" }
            return
        }
        val proto = event.toProto()
        val result = streamChannel.trySend(proto)
        if (result.isFailure) {
            log.warn { "Gateway stream for session $sid is full or closed; dropping event" }
        }
    }
}

// ── OutboundEvent.sessionId() extension ────────────────────────────────────────

/** Extracts the [SessionId] from any [OutboundEvent] variant. */
fun OutboundEvent.sessionId(): SessionId =
    when (this) {
        is OutboundEvent.SendText -> sessionId
        is OutboundEvent.SendInfo -> sessionId
        is OutboundEvent.SendError -> sessionId
        is OutboundEvent.SendPrompt -> sessionId
        is OutboundEvent.ShowLoginScreen -> sessionId
        is OutboundEvent.SetAnsi -> sessionId
        is OutboundEvent.Close -> sessionId
        is OutboundEvent.ClearScreen -> sessionId
        is OutboundEvent.ShowAnsiDemo -> sessionId
    }
