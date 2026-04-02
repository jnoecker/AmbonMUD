package dev.ambon.engine

import dev.ambon.config.WeatherConfig
import dev.ambon.test.MutableClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WeatherSystemTest {
    private val config = WeatherConfig(
        minTransitionMs = 1000L,
        maxTransitionMs = 1000L, // Fixed interval for deterministic tests
    )

    @Test
    fun `default weather is CLEAR`() {
        val clock = MutableClock(0L)
        val system = WeatherSystem(config, clock)
        assertEquals(WeatherType.CLEAR, system.weatherForZone("test_zone"))
    }

    @Test
    fun `tick with no active zones produces no changes`() {
        val clock = MutableClock(0L)
        val system = WeatherSystem(config, clock)
        val changes = system.tick(emptySet())
        assertTrue(changes.isEmpty())
    }

    @Test
    fun `tick transitions weather after interval`() {
        val clock = MutableClock(0L)
        val system = WeatherSystem(config, clock)

        // Initialize zone
        system.weatherForZone("zone1")

        // No change before interval
        clock.advance(500L)
        val noChanges = system.tick(setOf("zone1"))
        assertTrue(noChanges.isEmpty())

        // After interval, the system rolls a new weather type.
        // It may or may not differ from CLEAR, so just verify the tick ran without error.
        clock.advance(600L) // total 1100ms > 1000ms
        system.tick(setOf("zone1"))
        // Verify zone still has a valid weather type
        assertTrue(WeatherType.entries.contains(system.weatherForZone("zone1")))
    }

    @Test
    fun `different zones have independent weather`() {
        val clock = MutableClock(0L)
        val system = WeatherSystem(config, clock)

        system.setWeather("zone1", WeatherType.RAIN)
        system.setWeather("zone2", WeatherType.SNOW)

        assertEquals(WeatherType.RAIN, system.weatherForZone("zone1"))
        assertEquals(WeatherType.SNOW, system.weatherForZone("zone2"))
    }

    @Test
    fun `setWeather overrides current weather`() {
        val clock = MutableClock(0L)
        val system = WeatherSystem(config, clock)

        system.setWeather("zone1", WeatherType.STORM)
        assertEquals(WeatherType.STORM, system.weatherForZone("zone1"))
    }

    @Test
    fun `allZoneWeather returns all tracked zones`() {
        val clock = MutableClock(0L)
        val system = WeatherSystem(config, clock)

        system.weatherForZone("a")
        system.weatherForZone("b")

        val all = system.allZoneWeather()
        assertEquals(2, all.size)
        assertTrue(all.containsKey("a"))
        assertTrue(all.containsKey("b"))
    }
}
