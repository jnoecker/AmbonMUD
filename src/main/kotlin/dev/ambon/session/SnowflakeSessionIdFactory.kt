package dev.ambon.session

import dev.ambon.domain.ids.SessionId
import java.util.concurrent.atomic.AtomicInteger

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
 */
class SnowflakeSessionIdFactory(
    gatewayId: Int,
) : SessionIdFactory {
    init {
        require(gatewayId in 0..0xFFFF) { "gatewayId must be in 0..65535, got $gatewayId" }
    }

    private val gatewayBits = gatewayId.toLong() and 0xFFFFL

    // Packs lastSecond (high 32 bits) and seq (low 32 bits) into a single Long for atomic CAS.
    private val state = AtomicInteger(0) // seq only; second checked via System.currentTimeMillis

    @Volatile
    private var lastSecond: Long = currentSecond()

    @Volatile
    private var seq: Int = 0

    override fun allocate(): SessionId {
        val second: Long
        val sequence: Int

        synchronized(this) {
            val now = currentSecond()
            if (now != lastSecond) {
                lastSecond = now
                seq = 0
            }
            second = lastSecond
            sequence = seq
            seq = (seq + 1) and 0xFFFF
        }

        val id =
            (gatewayBits shl 48) or
                ((second and 0xFFFFFFFFL) shl 16) or
                (sequence.toLong() and 0xFFFFL)
        return SessionId(id)
    }

    private fun currentSecond(): Long = System.currentTimeMillis() / 1000L
}
