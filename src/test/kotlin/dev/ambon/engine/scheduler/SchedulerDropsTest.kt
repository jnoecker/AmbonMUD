package dev.ambon.engine.scheduler

import dev.ambon.test.MutableClock
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SchedulerDropsTest {
    @Test
    fun `runDue returns correct ran and deferred counts`() =
        runTest {
            val clock = MutableClock(0)
            val s = Scheduler(clock)

            repeat(5) { s.scheduleAt(0) { } }

            val (ran, deferred) = s.runDue(maxActions = 3)

            assertEquals(3, ran)
            assertEquals(2, deferred)
        }

    @Test
    fun `runDue returns zero deferred when all actions fit within cap`() =
        runTest {
            val clock = MutableClock(0)
            val s = Scheduler(clock)

            repeat(3) { s.scheduleAt(0) { } }

            val (ran, deferred) = s.runDue(maxActions = 10)

            assertEquals(3, ran)
            assertEquals(0, deferred)
        }

    @Test
    fun `runDue does not count future actions as deferred`() =
        runTest {
            val clock = MutableClock(0)
            val s = Scheduler(clock)

            repeat(5) { s.scheduleAt(0) { } }
            repeat(3) { s.scheduleAt(1000) { } } // future — not due

            val (ran, deferred) = s.runDue(maxActions = 3)

            assertEquals(3, ran)
            assertEquals(2, deferred) // only the 2 remaining due-now actions
        }

    @Test
    fun `deferred actions remain in queue and run next call`() =
        runTest {
            val clock = MutableClock(0)
            val s = Scheduler(clock)

            var count = 0
            repeat(5) { s.scheduleAt(0) { count++ } }

            val (ran1, deferred1) = s.runDue(maxActions = 3)
            assertEquals(3, ran1)
            assertEquals(2, deferred1)
            assertEquals(2, s.size())

            val (ran2, deferred2) = s.runDue(maxActions = 10)
            assertEquals(2, ran2)
            assertEquals(0, deferred2)
            assertEquals(5, count)
            assertEquals(0, s.size())
        }
}
