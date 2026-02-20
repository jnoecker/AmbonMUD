package dev.ambon.engine.scheduler

import dev.ambon.test.MutableClock
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SchedulerDropsTest {
    @Test
    fun `runDue returns correct ran and dropped counts`() =
        runTest {
            val clock = MutableClock(0)
            val s = Scheduler(clock)

            repeat(5) { s.scheduleAt(0) { } }

            val (ran, dropped) = s.runDue(maxActions = 3)

            assertEquals(3, ran)
            assertEquals(2, dropped)
        }

    @Test
    fun `runDue returns zero dropped when all actions fit within cap`() =
        runTest {
            val clock = MutableClock(0)
            val s = Scheduler(clock)

            repeat(3) { s.scheduleAt(0) { } }

            val (ran, dropped) = s.runDue(maxActions = 10)

            assertEquals(3, ran)
            assertEquals(0, dropped)
        }

    @Test
    fun `runDue does not count future actions as dropped`() =
        runTest {
            val clock = MutableClock(0)
            val s = Scheduler(clock)

            repeat(5) { s.scheduleAt(0) { } }
            repeat(3) { s.scheduleAt(1000) { } } // future — not due

            val (ran, dropped) = s.runDue(maxActions = 3)

            assertEquals(3, ran)
            assertEquals(2, dropped) // only the 2 remaining due-now actions
        }
}
