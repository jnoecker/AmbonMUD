package dev.ambon.swarm

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BotEnvironmentTrackerTest {
    @Test
    fun `updates exits from look output`() {
        val env = BotEnvironmentTracker.update(BotEnvironment(), "Exits: north, east, south")
        assertEquals(listOf("north", "east", "south"), env.exits)
        assertTrue(env.hasMovementTarget)
    }

    @Test
    fun `updates visible mobs from look output`() {
        val env = BotEnvironmentTracker.update(BotEnvironment(), "You see: rat, goblin scout")
        assertEquals(listOf("rat", "goblin scout"), env.visibleMobs)
        assertTrue(env.hasCombatTarget)
    }

    @Test
    fun `normalizes none and nothing lists`() {
        var env = BotEnvironment(exits = listOf("north"), visibleMobs = listOf("rat"))
        env = BotEnvironmentTracker.update(env, "Exits: none")
        env = BotEnvironmentTracker.update(env, "You see: nothing")
        assertFalse(env.hasMovementTarget)
        assertFalse(env.hasCombatTarget)
    }

    @Test
    fun `extractMobKeyword picks first token`() {
        assertEquals("goblin", BotEnvironmentTracker.extractMobKeyword("goblin scout (wounded)"))
        assertEquals("rat", BotEnvironmentTracker.extractMobKeyword(""))
    }
}
