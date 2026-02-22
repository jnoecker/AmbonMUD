package dev.ambon.bus

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.grpc.isControlPlane
import dev.ambon.grpc.sessionId
import dev.ambon.grpc.toDomain
import dev.ambon.metrics.GameMetrics
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private val log = KotlinLogging.logger {}

sealed interface GrpcOutboundFailure {
    data class StreamFailure(
        val cause: Throwable,
    ) : GrpcOutboundFailure

    data class ControlPlaneDeliveryFailure(
        val sessionId: SessionId,
        val eventType: String,
        val reason: String,
    ) : GrpcOutboundFailure
}

/**
 * Gateway-side [OutboundBus] that receives events from the engine via a gRPC stream.
 *
 * Uses the delegate pattern (wraps [LocalOutboundBus]), matching [RedisOutboundBus].
 * A background coroutine reads from [grpcReceiveFlow] (the outbound side of the bidi stream),
 * converts each proto to a domain event via [toDomain], and forwards it to the delegate.
 * [OutboundRouter] then consumes from the delegate as normal.
 *
 * The flow reference can be swapped via [reattach] to support reconnect without restarting the
 * transport layer.
 */
class GrpcOutboundBus(
    private val delegate: LocalOutboundBus,
    grpcReceiveFlow: Flow<dev.ambon.grpc.proto.OutboundEventProto>,
    private val scope: CoroutineScope,
    private val metrics: GameMetrics = GameMetrics.noop(),
    private val controlPlaneSendTimeoutMs: Long = DEFAULT_CONTROL_PLANE_SEND_TIMEOUT_MS,
    private val onFailure: (GrpcOutboundFailure) -> Unit = {},
) : OutboundBus {
    init {
        require(controlPlaneSendTimeoutMs > 0L) {
            "controlPlaneSendTimeoutMs must be > 0"
        }
    }

    private var currentFlow: Flow<dev.ambon.grpc.proto.OutboundEventProto> = grpcReceiveFlow

    private var receiverJob: Job? = null

    /** Start reading from the gRPC stream and forwarding events to the delegate. */
    fun startReceiving() {
        receiverJob =
            scope.launch {
                try {
                    currentFlow.collect { proto ->
                        val event = proto.toDomain()
                        if (event == null) {
                            log.warn { "Received unrecognised OutboundEventProto from engine: $proto" }
                            return@collect
                        }
                        val fastPath = delegate.trySend(event)
                        if (fastPath.isSuccess) {
                            return@collect
                        }

                        if (!event.isControlPlane()) {
                            metrics.onGrpcDataPlaneDrop("gateway_local_queue_full")
                            when (event) {
                                is OutboundEvent.SendPrompt ->
                                    log.debug { "Dropped SendPrompt for session ${event.sessionId} (gateway local queue full)" }
                                else ->
                                    log.warn {
                                        "Dropped ${event::class.simpleName} for session ${event.sessionId()} " +
                                            "(gateway local queue full)"
                                    }
                            }
                            return@collect
                        }

                        try {
                            withTimeout(controlPlaneSendTimeoutMs) {
                                delegate.send(event)
                            }
                            metrics.onGrpcControlPlaneFallbackSend()
                        } catch (_: TimeoutCancellationException) {
                            metrics.onGrpcControlPlaneDrop("gateway_local_queue_full_timeout")
                            metrics.onGrpcForcedDisconnectDueToControlDeliveryFailure("gateway_local_queue_full_timeout")
                            notifyFailure(
                                GrpcOutboundFailure.ControlPlaneDeliveryFailure(
                                    sessionId = event.sessionId(),
                                    eventType = event::class.simpleName ?: "UnknownOutboundEvent",
                                    reason = "gateway_local_queue_full_timeout",
                                ),
                            )
                            return@collect
                        }
                    }
                    throw IllegalStateException("Engine gRPC outbound stream completed unexpectedly")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error(e) { "Engine gRPC outbound stream ended fatally" }
                    notifyFailure(GrpcOutboundFailure.StreamFailure(e))
                }
            }
        log.info { "GrpcOutboundBus receiver started" }
    }

    private fun notifyFailure(failure: GrpcOutboundFailure) {
        runCatching { onFailure(failure) }
            .onFailure { cbErr ->
                log.error(cbErr) { "GrpcOutboundBus failure callback threw" }
            }
    }

    /**
     * Cancel the current receiver, swap in [newFlow], and start a new receiver.
     *
     * Used by the gateway reconnect loop: after the engine gRPC stream dies, a new bidi stream is
     * opened and this method swaps the bus onto the new stream without touching the transport layer.
     */
    suspend fun reattach(newFlow: Flow<dev.ambon.grpc.proto.OutboundEventProto>) {
        receiverJob?.cancelAndJoin()
        currentFlow = newFlow
        startReceiving()
    }

    /** Suspends until the current receiver job completes (or is already done). */
    suspend fun awaitReceiverCompletion() {
        receiverJob?.join()
    }

    /** Returns true if the receiver coroutine is currently active. */
    fun isReceiverActive(): Boolean = receiverJob?.isActive == true

    fun stopReceiving() {
        runBlocking { receiverJob?.cancelAndJoin() }
    }

    override suspend fun send(event: OutboundEvent) = delegate.send(event)

    override fun tryReceive(): ChannelResult<OutboundEvent> = delegate.tryReceive()

    override fun asReceiveChannel(): ReceiveChannel<OutboundEvent> = delegate.asReceiveChannel()

    override fun close() {
        delegate.close()
    }
}

private const val DEFAULT_CONTROL_PLANE_SEND_TIMEOUT_MS = 250L
