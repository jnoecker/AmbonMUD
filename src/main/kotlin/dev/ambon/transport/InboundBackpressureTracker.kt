package dev.ambon.transport

import dev.ambon.bus.InboundBus
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.InboundEvent
import dev.ambon.metrics.GameMetrics
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * Manages inbound backpressure counters for a single transport session.
 *
 * Both telnet ([NetworkSession]) and WebSocket ([KtorWebSocketTransport])
 * transports share the same try-send / counter-reset / counter-increment /
 * threshold-throw pattern.  This class encapsulates that logic so neither
 * transport has to duplicate it.
 */
class InboundBackpressureTracker(
    private val maxFailures: Int,
) {
    private var failures = 0

    /**
     * Try-sends a [InboundEvent.LineReceived] to [bus].
     *
     * On success the failure counter resets and [onSuccess] is invoked
     * (typically a metrics call).  On failure the counter increments, a
     * backpressure-failure metric is recorded, and once [maxFailures] is
     * reached an [InboundBackpressure] exception is thrown.
     */
    fun sendLineOrThrow(
        bus: InboundBus,
        sessionId: SessionId,
        line: String,
        metrics: GameMetrics,
        onSuccess: () -> Unit,
    ) {
        val result = bus.trySend(InboundEvent.LineReceived(sessionId, line))
        if (result.isSuccess) {
            failures = 0
            onSuccess()
            return
        }

        failures++
        metrics.onInboundBackpressureFailure()
        log.debug { "Inbound line dropped due to backpressure: sessionId=$sessionId failures=$failures" }
        if (failures >= maxFailures) {
            throw InboundBackpressure("inbound backpressure")
        }
    }
}
