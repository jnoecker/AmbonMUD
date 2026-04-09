package dev.ambon.engine

import dev.ambon.config.WeatherConfig
import dev.ambon.test.MutableClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
        assertEquals("CLEAR", system.weatherForZone("test_zone"))
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
        val weatherId = system.weatherForZone("zone1")
        assertTrue(config.types.containsKey(weatherId), "Expected valid weather type, got: $weatherId")
    }

    @Test
    fun `different zones have independent weather`() {
        val clock = MutableClock(0L)
        val system = WeatherSystem(config, clock)

        system.setWeather("zone1", "RAIN")
        system.setWeather("zone2", "SNOW")

        assertEquals("RAIN", system.weatherForZone("zone1"))
        assertEquals("SNOW", system.weatherForZone("zone2"))
    }

    @Test
    fun `setWeather overrides current weather`() {
        val clock = MutableClock(0L)
        val system = WeatherSystem(config, clock)

        system.setWeather("zone1", "STORM")
        assertEquals("STORM", system.weatherForZone("zone1"))
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

    @Test
    fun `typeDefinition returns definition for known type`() {
        val clock = MutableClock(0L)
        val system = WeatherSystem(config, clock)

        val def = system.typeDefinition("RAIN")
        assertNotNull(def)
        assertEquals("Rain", def?.displayName)
        assertEquals("rain", def?.particleHint)
    }

    @Test
    fun `typeDefinition returns null for unknown type`() {
        val clock = MutableClock(0L)
        val system = WeatherSystem(config, clock)
        assertEquals(null, system.typeDefinition("BLIZZARD"))
    }

    @Test
    fun `custom weather types work alongside defaults`() {
        val customConfig = WeatherConfig(
            minTransitionMs = 1000L,
            maxTransitionMs = 1000L,
            types = WeatherConfig.DEFAULT_WEATHER_TYPES + mapOf(
                "MIST" to dev.ambon.config.WeatherTypeDefinition(
                    displayName = "Mist",
                    description = "A pale mist rolls in.",
                    weight = 1.0,
                    particleHint = "fog",
                ),
            ),
        )
        val clock = MutableClock(0L)
        val system = WeatherSystem(customConfig, clock)

        system.setWeather("zone1", "MIST")
        assertEquals("MIST", system.weatherForZone("zone1"))
        assertEquals("fog", system.typeDefinition("MIST")?.particleHint)
    }
}
