package dev.ambon.grpc

import dev.ambon.bus.OutboundBus
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.metrics.GameMetrics
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
    private val controlPlaneSendTimeoutMs: Long = GrpcTimeouts.DEFAULT_CONTROL_PLANE_SEND_TIMEOUT_MS,
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
        cancelJobWithTimeout(job, GrpcTimeouts.STOP_TIMEOUT_MS, "GrpcOutboundDispatcher job")
        log.info { "GrpcOutboundDispatcher stopped" }
    }

    private suspend fun dispatch(event: OutboundEvent) {
        val sid = event.sessionId
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
        when (
            val outcome =
                deliverWithBackpressure(
                    trySend = { streamChannel.trySend(proto) },
                    suspendSend = { streamChannel.send(proto) },
                    isControlPlane = event.isControlPlane(),
                    controlPlaneSendTimeoutMs = controlPlaneSendTimeoutMs,
                )
        ) {
            DeliveryOutcome.Delivered -> {}
            DeliveryOutcome.DeliveredWithFallback -> metrics.onGrpcControlPlaneFallbackSend()
            is DeliveryOutcome.DroppedDataPlane -> {
                if (outcome.channelClosed) {
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
            }
            is DeliveryOutcome.ControlPlaneFailure -> {
                metrics.onGrpcControlPlaneDrop(outcome.reason)
                metrics.onGrpcForcedDisconnectDueToControlDeliveryFailure(outcome.reason)
                log.warn {
                    "Control-plane delivery failed for session $sid (${event::class.simpleName}, " +
                        "reason=${outcome.reason}); forcing disconnect"
                }
                serviceImpl.forceDisconnectSession(
                    sessionId = sid,
                    reason = "control-plane-delivery-failed:${outcome.reason}",
                )
            }
        }
    }
}
