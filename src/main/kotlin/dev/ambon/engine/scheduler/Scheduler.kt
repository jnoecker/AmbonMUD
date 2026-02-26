package dev.ambon.engine.scheduler

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.util.PriorityQueue

private val log = KotlinLogging.logger {}

data class ScheduledAction(
    val dueAtEpochMs: Long,
    val action: suspend () -> Unit,
)

class Scheduler(
    private val clock: Clock,
) {
    private val pq =
        PriorityQueue<ScheduledAction>(compareBy { it.dueAtEpochMs })

    fun scheduleIn(
        delayMs: Long,
        action: suspend () -> Unit,
    ) {
        require(delayMs >= 0) { "delayMs must be >= 0" }
        val due = clock.millis() + delayMs
        pq.add(ScheduledAction(due, action))
    }

    fun scheduleAt(
        epochMs: Long,
        action: suspend () -> Unit,
    ) {
        pq.add(ScheduledAction(epochMs, action))
    }

    /**
     * Run all actions due as of now, but cap execution so a bad schedule can't
     * starve the engine. Returns Pair(actionsRan, actionsDeferred).
     *
     * When the cap is reached, remaining overdue actions are counted in O(k) time
     * (where k = overdue count) by draining from the min-heap head, rather than
     * scanning the entire queue in O(n). Deferred actions remain in the queue and
     * run on the next tick.
     */
    suspend fun runDue(maxActions: Int = 100): Pair<Int, Int> {
        val now = clock.millis()
        var ran = 0
        while (ran < maxActions) {
            val next = pq.peek() ?: break
            if (next.dueAtEpochMs > now) break
            pq.poll()
            try {
                next.action()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log.error(t) { "Scheduled action failed" }
            }
            ran++
        }
        // Count deferred-due items in O(k) by draining overdue entries from the
        // heap head (stopping at the first future entry), then re-inserting them.
        // This avoids the O(n) full-queue scan that was previously used, which ran
        // exactly when the server was already under load.
        var deferred = 0
        if (ran >= maxActions) {
            val overflow = ArrayList<ScheduledAction>()
            while (true) {
                val next = pq.peek() ?: break
                if (next.dueAtEpochMs > now) break
                overflow.add(pq.poll()!!)
            }
            deferred = overflow.size
            pq.addAll(overflow)
        }
        if (deferred > 0) log.warn { "Scheduler cap reached: $deferred overdue actions deferred to next tick (cap=$maxActions)" }
        return Pair(ran, deferred)
    }

    fun size(): Int = pq.size
}
