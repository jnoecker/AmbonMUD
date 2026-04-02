package dev.ambon.engine

import dev.ambon.config.WorldEventDefinition
import dev.ambon.config.WorldEventsConfig
import dev.ambon.test.MutableClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneOffset

class WorldEventSystemTest {
    private fun clockAtDate(date: String): MutableClock {
        val instant = LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant()
        return MutableClock(instant.toEpochMilli())
    }

    private val festivalDef = WorldEventDefinition(
        displayName = "Spring Festival",
        description = "Flowers bloom.",
        startDate = "2026-03-20",
        endDate = "2026-04-20",
        flags = listOf("spring_festival", "bonus_herbalism"),
        startMessage = "Festival begins!",
        endMessage = "Festival ends.",
    )

    private val alwaysActiveDef = WorldEventDefinition(
        displayName = "Permanent Event",
        description = "Always on.",
    )

    @Test
    fun `event activates within date range`() {
        val clock = clockAtDate("2026-03-25")
        val config = WorldEventsConfig(definitions = mapOf("spring" to festivalDef))
        val system = WorldEventSystem(config, clock)

        val result = system.tick()
        assertTrue(result.activated.contains("spring"))
        assertTrue(system.activeEventIds().contains("spring"))
    }

    @Test
    fun `event not active before start date`() {
        val clock = clockAtDate("2026-03-01")
        val config = WorldEventsConfig(definitions = mapOf("spring" to festivalDef))
        val system = WorldEventSystem(config, clock)

        val result = system.tick()
        assertTrue(result.activated.isEmpty())
        assertFalse(system.activeEventIds().contains("spring"))
    }

    @Test
    fun `event not active after end date`() {
        val clock = clockAtDate("2026-05-01")
        val config = WorldEventsConfig(definitions = mapOf("spring" to festivalDef))
        val system = WorldEventSystem(config, clock)

        val result = system.tick()
        assertTrue(result.activated.isEmpty())
    }

    @Test
    fun `event deactivates when date passes`() {
        val clock = clockAtDate("2026-04-15")
        val config = WorldEventsConfig(definitions = mapOf("spring" to festivalDef))
        val system = WorldEventSystem(config, clock)

        // Activate
        system.tick()
        assertTrue(system.activeEventIds().contains("spring"))

        // Advance past end date (6 days = 518400000ms)
        clock.advance(518_400_000L)

        val result = system.tick()
        assertTrue(result.deactivated.contains("spring"))
        assertFalse(system.activeEventIds().contains("spring"))
    }

    @Test
    fun `always-active event is always on`() {
        val clock = clockAtDate("2026-01-01")
        val config = WorldEventsConfig(definitions = mapOf("perm" to alwaysActiveDef))
        val system = WorldEventSystem(config, clock)

        val result = system.tick()
        assertTrue(result.activated.contains("perm"))
    }

    @Test
    fun `flags are queryable`() {
        val clock = clockAtDate("2026-04-01")
        val config = WorldEventsConfig(definitions = mapOf("spring" to festivalDef))
        val system = WorldEventSystem(config, clock)
        system.tick()

        assertTrue(system.hasFlag("spring_festival"))
        assertTrue(system.hasFlag("bonus_herbalism"))
        assertFalse(system.hasFlag("nonexistent"))
    }

    @Test
    fun `no duplicate activations on repeated ticks`() {
        val clock = clockAtDate("2026-04-01")
        val config = WorldEventsConfig(definitions = mapOf("spring" to festivalDef))
        val system = WorldEventSystem(config, clock)

        system.tick()
        val result2 = system.tick()
        assertTrue(result2.activated.isEmpty())
        assertTrue(result2.deactivated.isEmpty())
    }

    @Test
    fun `activeEvents returns definitions for active events`() {
        val clock = clockAtDate("2026-04-01")
        val config = WorldEventsConfig(definitions = mapOf("spring" to festivalDef))
        val system = WorldEventSystem(config, clock)
        system.tick()

        val active = system.activeEvents()
        assertEquals(1, active.size)
        assertEquals("Spring Festival", active["spring"]?.displayName)
    }
}
