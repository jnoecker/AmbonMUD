package dev.ambon.world.load

import dev.ambon.domain.ids.MobId
import dev.ambon.domain.world.load.WorldLoader
import dev.ambon.engine.Season
import dev.ambon.engine.TimePeriod
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorldLoaderConditionTest {
    private val world = WorldLoader.loadFromResource("world/ok_conditional_mob.yaml")

    @Test
    fun `parses spawn condition facets`() {
        val template = world.mobTemplate(MobId("ok_conditional:stormwraith"))!!
        val cond = template.spawnCondition!!
        assertEquals(setOf(TimePeriod.NIGHT), cond.timePeriods)
        assertEquals(setOf("STORM"), cond.weather)
        assertEquals(setOf(Season.WINTER), cond.seasons)
        assertEquals(0.25, cond.chance)
        assertFalse(cond.isUnconditional)
        // Author opt-out flows through.
        assertFalse(template.variantEligible)
    }

    @Test
    fun `unconditioned mob has no spawn condition and stays variant-eligible`() {
        val template = world.mobTemplate(MobId("ok_conditional:rat"))!!
        assertNull(template.spawnCondition)
        assertTrue(template.variantEligible)
    }
}
