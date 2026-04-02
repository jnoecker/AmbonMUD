package dev.ambon.engine

import dev.ambon.config.WorldTimeConfig
import dev.ambon.test.MutableClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WorldTimeSystemTest {
    private val config = WorldTimeConfig(
        cycleLengthMs = 24_000L, // 24 seconds = 1 game day (1 second per game hour)
        dawnHour = 5,
        dayHour = 8,
        duskHour = 18,
        nightHour = 21,
    )

    @Test
    fun `starts at hour 0 (night)`() {
        val clock = MutableClock(0L)
        val system = WorldTimeSystem(config, clock)
        assertEquals(0, system.gameHour())
        assertEquals(TimePeriod.NIGHT, system.period())
    }

    @Test
    fun `dawn at hour 5`() {
        val clock = MutableClock(0L)
        val system = WorldTimeSystem(config, clock)
        // 5 hours = 5000ms into a 24000ms cycle
        clock.advance(5000L)
        assertEquals(5, system.gameHour())
        assertEquals(TimePeriod.DAWN, system.period())
    }

    @Test
    fun `day at hour 8`() {
        val clock = MutableClock(0L)
        val system = WorldTimeSystem(config, clock)
        clock.advance(8000L)
        assertEquals(8, system.gameHour())
        assertEquals(TimePeriod.DAY, system.period())
    }

    @Test
    fun `dusk at hour 18`() {
        val clock = MutableClock(0L)
        val system = WorldTimeSystem(config, clock)
        clock.advance(18000L)
        assertEquals(18, system.gameHour())
        assertEquals(TimePeriod.DUSK, system.period())
    }

    @Test
    fun `night at hour 21`() {
        val clock = MutableClock(0L)
        val system = WorldTimeSystem(config, clock)
        clock.advance(21000L)
        assertEquals(21, system.gameHour())
        assertEquals(TimePeriod.NIGHT, system.period())
    }

    @Test
    fun `cycle wraps around after full day`() {
        val clock = MutableClock(0L)
        val system = WorldTimeSystem(config, clock)
        clock.advance(24000L) // full cycle
        assertEquals(0, system.gameHour())
        assertEquals(TimePeriod.NIGHT, system.period())
    }

    @Test
    fun `tick returns new period on change`() {
        val clock = MutableClock(0L)
        val system = WorldTimeSystem(config, clock)
        // Start at night (hour 0)
        assertNull(system.tick(TimePeriod.NIGHT))
        // Advance to dawn
        clock.advance(5000L)
        val result = system.tick(TimePeriod.NIGHT)
        assertNotNull(result)
        assertEquals(TimePeriod.DAWN, result)
    }

    @Test
    fun `tick returns null when period unchanged`() {
        val clock = MutableClock(0L)
        val system = WorldTimeSystem(config, clock)
        clock.advance(6000L) // hour 6, still dawn
        assertNull(system.tick(TimePeriod.DAWN))
    }

    @Test
    fun `minute calculation`() {
        val clock = MutableClock(0L)
        val system = WorldTimeSystem(config, clock)
        // 1 game hour = 1000ms, so 500ms = 30 game minutes
        clock.advance(500L)
        assertEquals(0, system.gameHour())
        assertEquals(30, system.gameMinute())
    }
}
