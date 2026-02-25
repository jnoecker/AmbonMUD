package dev.ambon.engine.behavior

import dev.ambon.domain.world.load.WorldLoadException
import dev.ambon.domain.world.load.WorldLoader
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BehaviorYamlParsingTest {
    @Test
    fun `valid behavior YAML loads successfully`() {
        val world = WorldLoader.loadFromResource("world/ok_behavior.yaml")

        val guard = world.mobSpawns.find { it.id.value == "behavior_test:guard" }
        assertNotNull(guard, "guard mob should exist")
        assertNotNull(guard!!.behaviorTree, "guard should have a behavior tree")

        val sentry = world.mobSpawns.find { it.id.value == "behavior_test:sentry" }
        assertNotNull(sentry, "sentry mob should exist")
        assertNotNull(sentry!!.behaviorTree, "sentry should have a behavior tree")

        val rat = world.mobSpawns.find { it.id.value == "behavior_test:rat" }
        assertNotNull(rat, "rat mob should exist")
        assertNotNull(rat!!.behaviorTree, "rat should have a behavior tree")
    }

    @Test
    fun `mob without behavior has null behaviorTree`() {
        val world = WorldLoader.loadFromResource("world/ok_dialogue.yaml")
        for (mob in world.mobSpawns) {
            assertNull(mob.behaviorTree, "mob ${mob.id.value} should not have a behavior tree")
        }
    }

    @Test
    fun `unknown behavior template causes load error`() {
        val ex =
            assertThrows<WorldLoadException> {
                WorldLoader.loadFromResource("world/bad_behavior_unknown_template.yaml")
            }
        assertTrue(ex.message!!.contains("unknown behavior template"))
        assertTrue(ex.message!!.contains("nonexistent_behavior"))
    }

    @Test
    fun `behavior with stationary true causes load error`() {
        val ex =
            assertThrows<WorldLoadException> {
                WorldLoader.loadFromResource("world/bad_behavior_stationary_conflict.yaml")
            }
        assertTrue(ex.message!!.contains("stationary"))
        assertTrue(ex.message!!.contains("behavior"))
    }
}
