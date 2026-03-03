package dev.ambon.grpc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.withTimeout

/**
 * Standardised outcome of attempting to deliver an event through a gRPC-adjacent channel.
 *
 * Both the engine-side [GrpcOutboundDispatcher] and the gateway-side `GrpcOutboundBus` use
 * [deliverWithBackpressure] to get one of these outcomes, then apply their own side-specific
 * reactions (forced disconnect, failure callback, metrics tagging, etc.).
 */
sealed interface DeliveryOutcome {
    /** Fast-path [trySend] succeeded. */
    data object Delivered : DeliveryOutcome

    /** Control-plane fallback suspending send succeeded after the fast path failed. */
    data object DeliveredWithFallback : DeliveryOutcome

    /** Data-plane event could not be enqueued; safe to drop. */
    data class DroppedDataPlane(
        val channelClosed: Boolean,
    ) : DeliveryOutcome

    /** Control-plane event could not be delivered; caller must handle the failure. */
    data class ControlPlaneFailure(
        val reason: String,
    ) : DeliveryOutcome
}

/**
 * Shared delivery algorithm for gRPC outbound backpressure.
 *
 * 1. Attempts a non-blocking [trySend].
 * 2. If the channel is full and the event is **data-plane**, returns [DeliveryOutcome.DroppedDataPlane].
 * 3. If the channel is full and the event is **control-plane**, falls back to a bounded suspending
 *    [suspendSend] with [controlPlaneSendTimeoutMs]. Returns [DeliveryOutcome.DeliveredWithFallback]
 *    on success, or [DeliveryOutcome.ControlPlaneFailure] on timeout/close.
 */
suspend fun deliverWithBackpressure(
    trySend: () -> ChannelResult<Unit>,
    suspendSend: suspend () -> Unit,
    isControlPlane: Boolean,
    controlPlaneSendTimeoutMs: Long,
): DeliveryOutcome {
    val result = trySend()
    if (result.isSuccess) return DeliveryOutcome.Delivered

    if (!isControlPlane) {
        return DeliveryOutcome.DroppedDataPlane(channelClosed = result.isClosed)
    }

    if (result.isClosed) {
        return DeliveryOutcome.ControlPlaneFailure("stream_closed")
    }

    val delivered =
        try {
            withTimeout(controlPlaneSendTimeoutMs) { suspendSend() }
            true
        } catch (_: TimeoutCancellationException) {
            false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

    return if (delivered) {
        DeliveryOutcome.DeliveredWithFallback
    } else {
        DeliveryOutcome.ControlPlaneFailure("stream_full_timeout")
    }
}
