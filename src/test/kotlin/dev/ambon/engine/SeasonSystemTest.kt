package dev.ambon.engine

import dev.ambon.config.SeasonConfig
import dev.ambon.test.MutableClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SeasonSystemTest {
    // 40 seconds = 1 game year, so each season lasts 10 seconds.
    private val config = SeasonConfig(cycleLengthMs = 40_000L)

    @Test
    fun `starts in spring`() {
        val clock = MutableClock(0L)
        val system = SeasonSystem(config, clock)
        assertEquals(Season.SPRING, system.season())
    }

    @Test
    fun `advances through all four seasons in order`() {
        val clock = MutableClock(0L)
        val system = SeasonSystem(config, clock)
        clock.advance(10_000L)
        assertEquals(Season.SUMMER, system.season())
        clock.advance(10_000L)
        assertEquals(Season.AUTUMN, system.season())
        clock.advance(10_000L)
        assertEquals(Season.WINTER, system.season())
    }

    @Test
    fun `wraps back to spring after a full year`() {
        val clock = MutableClock(0L)
        val system = SeasonSystem(config, clock)
        clock.advance(40_000L)
        assertEquals(Season.SPRING, system.season())
    }

    @Test
    fun `tick returns new season only on change`() {
        val clock = MutableClock(0L)
        val system = SeasonSystem(config, clock)
        assertNull(system.tick(Season.SPRING))
        clock.advance(10_000L)
        assertEquals(Season.SUMMER, system.tick(Season.SPRING))
        assertNotNull(system.tick(Season.SPRING))
        assertNull(system.tick(Season.SUMMER))
    }
}
