package dev.ambon.engine.scheduler

import java.time.Clock
import java.util.PriorityQueue

data class ScheduledAction(
    val dueAtEpochMs: Long,
    val action: suspend () -> Unit,
)

class Scheduler(
    private val clock: Clock,
    private val defaultMaxActionsPerRun: Int = 100,
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
     * starve the engine.
     */
    suspend fun runDue(maxActions: Int = defaultMaxActionsPerRun) {
        val now = clock.millis()
        var ran = 0
        while (ran < maxActions) {
            val next = pq.peek() ?: break
            if (next.dueAtEpochMs > now) break
            pq.poll()
            next.action()
            ran++
        }
    }

    fun size(): Int = pq.size
}
