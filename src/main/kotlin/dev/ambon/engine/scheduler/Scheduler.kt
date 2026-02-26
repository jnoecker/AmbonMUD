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

/**
 * Tick-based scheduler that runs actions when their due time has arrived.
 *
 * Uses two queues to avoid the O(n) full-queue scan that the previous single-queue
 * design needed to count overdue items after hitting the per-tick cap:
 *
 * - [futureQueue]: items not yet due, sorted by due time (min-heap).
 * - [dueQueue]:    items whose due time has arrived and are waiting to run.
 *
 * At the start of [runDue] we drain the front of [futureQueue] into [dueQueue] in
 * O(k log n) time, where k is the number of newly-due items (typically a small
 * constant per tick).  The overdue count after capping is then [dueQueue].size —
 * an O(1) read instead of an O(n) scan of the full queue.
 */
class Scheduler(
    private val clock: Clock,
) {
    private val futureQueue =
        PriorityQueue<ScheduledAction>(compareBy { it.dueAtEpochMs })
    private val dueQueue =
        PriorityQueue<ScheduledAction>(compareBy { it.dueAtEpochMs })

    fun scheduleIn(
        delayMs: Long,
        action: suspend () -> Unit,
    ) {
        require(delayMs >= 0) { "delayMs must be >= 0" }
        scheduleAt(clock.millis() + delayMs, action)
    }

    fun scheduleAt(
        epochMs: Long,
        action: suspend () -> Unit,
    ) {
        val item = ScheduledAction(epochMs, action)
        if (epochMs <= clock.millis()) {
            dueQueue.add(item)
        } else {
            futureQueue.add(item)
        }
    }

    /**
     * Run all actions due as of now, but cap execution so a bad schedule can't
     * starve the engine. Returns Pair(actionsRan, actionsDropped).
     *
     * "Dropped" means actions that were already due but could not be run within
     * the cap this tick; they remain in [dueQueue] and will be retried next tick.
     */
    suspend fun runDue(maxActions: Int = 100): Pair<Int, Int> {
        val now = clock.millis()

        // O(k log n): promote newly-due items from futureQueue to dueQueue.
        while (true) {
            val next = futureQueue.peek() ?: break
            if (next.dueAtEpochMs > now) break
            futureQueue.poll()
            dueQueue.add(next)
        }

        var ran = 0
        while (ran < maxActions) {
            val next = dueQueue.poll() ?: break
            try {
                next.action()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log.error(t) { "Scheduled action failed" }
            }
            ran++
        }

        // O(1): remaining dueQueue entries are all overdue but unrun this tick.
        val dropped = dueQueue.size
        if (dropped > 0) log.warn { "Scheduler dropped $dropped overdue actions (cap=$maxActions)" }
        return Pair(ran, dropped)
    }

    /** Total number of pending actions (due + future). */
    fun size(): Int = dueQueue.size + futureQueue.size

    /** Number of actions that are already due but not yet run (overdue backlog). */
    fun overdueSize(): Int = dueQueue.size
}
