package dev.ambon.grpc

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.metrics.GameMetrics
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

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
    private val metrics: GameMetrics = GameMetrics.noop(),
    private val controlPlaneSendTimeoutMs: Long = DEFAULT_CONTROL_PLANE_SEND_TIMEOUT_MS,
) {
    init {
        require(controlPlaneSendTimeoutMs > 0L) {
            "controlPlaneSendTimeoutMs must be > 0"
        }
    }

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
        runBlocking {
            try {
                withTimeout(STOP_TIMEOUT_MS) { job?.cancelAndJoin() }
            } catch (_: TimeoutCancellationException) {
                log.warn { "GrpcOutboundDispatcher job did not stop within ${STOP_TIMEOUT_MS}ms; forcing cancel" }
                job?.cancel()
            }
        }
        log.info { "GrpcOutboundDispatcher stopped" }
    }

    private suspend fun dispatch(event: OutboundEvent) {
        val sid = event.sessionId()
        val streamChannel = serviceImpl.sessionToStream[sid]
        if (streamChannel == null) {
            log.debug { "No gateway stream for session $sid; dropping $event" }
            if (event.isControlPlane()) {
                metrics.onGrpcControlPlaneDrop("no_stream")
            } else {
                metrics.onGrpcDataPlaneDrop("no_stream")
            }
            return
        }
        val proto = event.toProto()
        val result = streamChannel.trySend(proto)
        if (result.isSuccess) {
            return
        }

        if (!event.isControlPlane()) {
            if (result.isClosed) {
                serviceImpl.sessionToStream.remove(sid)
                metrics.onGrpcDataPlaneDrop("stream_closed")
                log.info { "Gateway stream for session $sid is closed; removed from routing table" }
            } else {
                metrics.onGrpcDataPlaneDrop("stream_full")
                when (event) {
                    is OutboundEvent.SendPrompt ->
                        log.debug { "Dropped SendPrompt for session $sid (gateway stream full)" }
                    else ->
                        log.warn { "Gateway stream for session $sid is full; dropped ${event::class.simpleName}" }
                }
            }
            return
        }

        val controlFailureReason =
            if (result.isClosed) {
                "stream_closed"
            } else {
                val delivered =
                    try {
                        withTimeout(controlPlaneSendTimeoutMs) {
                            streamChannel.send(proto)
                        }
                        true
                    } catch (_: TimeoutCancellationException) {
                        false
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        false
                    }

                if (delivered) {
                    metrics.onGrpcControlPlaneFallbackSend()
                    return
                }
                "stream_full_timeout"
            }

        metrics.onGrpcControlPlaneDrop(controlFailureReason)
        metrics.onGrpcForcedDisconnectDueToControlDeliveryFailure(controlFailureReason)
        log.warn {
            "Control-plane delivery failed for session $sid (${event::class.simpleName}, " +
                "reason=$controlFailureReason); forcing disconnect"
        }
        serviceImpl.forceDisconnectSession(
            sessionId = sid,
            reason = "control-plane-delivery-failed:$controlFailureReason",
        )
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
        is OutboundEvent.SessionRedirect -> sessionId
    }

private const val DEFAULT_CONTROL_PLANE_SEND_TIMEOUT_MS = 250L
private const val STOP_TIMEOUT_MS = 5_000L
