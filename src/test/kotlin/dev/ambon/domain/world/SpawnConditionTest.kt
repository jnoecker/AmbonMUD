package dev.ambon.domain.world

import dev.ambon.engine.Season
import dev.ambon.engine.TimePeriod
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpawnConditionTest {
    @Test
    fun `empty condition is unconditional`() {
        assertTrue(SpawnCondition().isUnconditional)
        assertFalse(SpawnCondition(timePeriods = setOf(TimePeriod.NIGHT)).isUnconditional)
        assertFalse(SpawnCondition(chance = 0.5).isUnconditional)
    }

    @Test
    fun `single facet gates correctly`() {
        val night = SpawnCondition(timePeriods = setOf(TimePeriod.NIGHT))
        assertTrue(night.gatesSatisfied(TimePeriod.NIGHT, "CLEAR", Season.SUMMER, emptySet()))
        assertFalse(night.gatesSatisfied(TimePeriod.DAY, "CLEAR", Season.SUMMER, emptySet()))
    }

    @Test
    fun `facets are AND-ed`() {
        val cond = SpawnCondition(
            timePeriods = setOf(TimePeriod.NIGHT),
            weather = setOf("STORM"),
            seasons = setOf(Season.WINTER),
        )
        assertTrue(cond.gatesSatisfied(TimePeriod.NIGHT, "STORM", Season.WINTER, emptySet()))
        // Each mismatch alone fails the whole condition.
        assertFalse(cond.gatesSatisfied(TimePeriod.DAY, "STORM", Season.WINTER, emptySet()))
        assertFalse(cond.gatesSatisfied(TimePeriod.NIGHT, "CLEAR", Season.WINTER, emptySet()))
        assertFalse(cond.gatesSatisfied(TimePeriod.NIGHT, "STORM", Season.SUMMER, emptySet()))
    }

    @Test
    fun `event flags require any one active`() {
        val cond = SpawnCondition(eventFlags = setOf("harvest_moon", "blood_moon"))
        assertFalse(cond.gatesSatisfied(TimePeriod.NIGHT, "CLEAR", Season.AUTUMN, emptySet()))
        assertTrue(cond.gatesSatisfied(TimePeriod.NIGHT, "CLEAR", Season.AUTUMN, setOf("blood_moon")))
    }
}
