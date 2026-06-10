package dev.ambon.engine

import dev.ambon.config.EngineConfig
import dev.ambon.config.MobVariantsConfig
import dev.ambon.test.GameEngineHarness
import dev.ambon.test.MutableClock
import dev.ambon.test.TestWorlds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Cold-start seeding of rare variants. The world should already hold a few
 * variant sightings the moment it boots — so explorers find them right away —
 * rather than only after the first zone reset or respawn.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GameEngineColdStartVariantTest {
    @Test
    fun `cold start rolls rare variants into the freshly-booted world`() = runTest {
        val harness = GameEngineHarness.start(
            scope = this,
            world = TestWorlds.testWorld,
            clock = MutableClock(0L),
            // chance = 1.0 makes every eligible COMBAT mob a variant deterministically.
            engineConfig = EngineConfig(mobVariants = MobVariantsConfig(chance = 1.0)),
        )
        try {
            val mob = harness.mobs.all().single()
            assertTrue(mob.isVariant, "an eligible cold-start mob should roll a variant when chance = 1.0")
            assertNotNull(mob.variantId)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `cold start leaves mobs ordinary when variants are disabled`() = runTest {
        val harness = GameEngineHarness.start(
            scope = this,
            world = TestWorlds.testWorld,
            clock = MutableClock(0L),
            engineConfig = EngineConfig(mobVariants = MobVariantsConfig(enabled = false)),
        )
        try {
            assertFalse(harness.mobs.all().single().isVariant)
        } finally {
            harness.close()
        }
    }
}
