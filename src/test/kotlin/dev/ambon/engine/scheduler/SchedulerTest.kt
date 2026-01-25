package dev.ambon.engine.scheduler

import dev.ambon.test.MutableClock
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SchedulerTest {
    @Test
    fun `does not run actions before due`() =
        runTest {
            val clock = MutableClock(1000)
            val s = Scheduler(clock)

            var ran = false
            s.scheduleIn(500) { ran = true }

            s.runDue()
            assertFalse(ran)

            clock.advance(499)
            s.runDue()
            assertFalse(ran)

            clock.advance(1)
            s.runDue()
            assertTrue(ran)
        }

    @Test
    fun `caps work per runDue`() =
        runTest {
            val clock = MutableClock(1000)
            val s = Scheduler(clock)

            var count = 0
            repeat(10) { s.scheduleAt(1000) { count++ } }

            s.runDue(maxActions = 3)
            assertEquals(3, count)

            s.runDue(maxActions = 10)
            assertEquals(10, count)
            assertEquals(0, s.size())
        }
}
