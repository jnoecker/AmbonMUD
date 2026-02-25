package dev.ambon.session

import dev.ambon.domain.ids.SessionId
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * Generates globally-unique [SessionId] values using a Snowflake-style bit layout.
 *
 * Bit layout (64 bits total):
 * ```
 * [ 16-bit gatewayId ][ 32-bit timestamp_seconds ][ 16-bit sequence ]
 *   bits 63-48            bits 47-16                  bits 15-0
 * ```
 * - **gatewayId** (16 bits, 0–65535): identifies the gateway process; supplied at construction time.
 * - **timestamp_seconds** (32 bits): Unix timestamp in seconds; wraps after year 2106.
 * - **sequence** (16 bits, 0–65535): per-second counter; allows 65 536 sessions/second per gateway.
 *
 * IDs from two gateways with different [gatewayId] values are guaranteed non-overlapping
 * because the high 16 bits differ, even if the lower 48 bits happen to collide.
 *
 * In [standalone] mode [AtomicSessionIdFactory] is still used (simpler, monotonic, no gateway concept).
 *
 * **Hardening policies:**
 * - *Sequence overflow (WAIT)*: when all 65 536 sequence slots in a second are used, the allocating
 *   thread waits (releasing the monitor) in 1 ms increments until the clock second advances.
 * - *Clock rollback (MONOTONIC_FLOOR)*: if the clock moves backward, [lastSecond] is kept as a
 *   monotonic floor; the sequence continues incrementing.  If the rollback is large enough that
 *   the sequence also overflows, the WAIT policy kicks in automatically.
 */
class SnowflakeSessionIdFactory(
    gatewayId: Int,
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
    private val onSequenceOverflow: () -> Unit = {},
    private val onClockRollback: () -> Unit = {},
) : SessionIdFactory {
    init {
        require(gatewayId in 0..0xFFFF) { "gatewayId must be in 0..65535, got $gatewayId" }
    }

    private val gatewayBits = gatewayId.toLong() and 0xFFFFL

    @Volatile
    private var lastSecond: Long = clockSeconds()

    @Volatile
    private var seq: Int = 0

    override fun allocate(): SessionId {
        val second: Long
        val sequence: Int

        synchronized(this) {
            val now = clockSeconds()
            when {
                now > lastSecond -> {
                    lastSecond = now
                    seq = 0
                }
                now < lastSecond -> {
                    log.warn { "Clock rollback detected: now=$now lastSecond=$lastSecond — applying monotonic floor" }
                    onClockRollback()
                    // Keep lastSecond as monotonic floor; seq continues incrementing.
                }
                // now == lastSecond: same second, normal increment — no action needed
            }

            // Sequence overflow: release the lock, sleep briefly, then re-check.
            if (seq > 0xFFFF) {
                log.warn { "Sequence overflow at second=$lastSecond — waiting for clock to advance" }
                onSequenceOverflow()
                val overflowSecond = lastSecond
                while (true) {
                    // Release the lock while sleeping so other callers are not blocked.
                    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                    (this as java.lang.Object).wait(1)
                    val nowAfterWait = clockSeconds()
                    if (nowAfterWait > overflowSecond) {
                        lastSecond = nowAfterWait
                        seq = 0
                        break
                    }
                }
            }

            second = lastSecond
            sequence = seq
            seq++ // No masking — let seq grow past 0xFFFF so overflow is detected next call
        }

        val id =
            (gatewayBits shl 48) or
                ((second and 0xFFFFFFFFL) shl 16) or
                (sequence.toLong() and 0xFFFFL)
        return SessionId(id)
    }
}
