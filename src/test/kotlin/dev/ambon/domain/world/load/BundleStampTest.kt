package dev.ambon.domain.world.load

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BundleStampTest {
    @Test
    fun `zone files stamped with one bundle load and report it`() {
        val world = WorldLoader.loadFromResources(listOf("world/bundle_a1.yaml", "world/bundle_a2.yaml"))
        assertEquals("rc-1", world.bundleId)
        assertTrue(world.unstampedZones.isEmpty())
    }

    @Test
    fun `the config's bundle id must match the zone files`() {
        val world =
            WorldLoader.loadFromResources(
                listOf("world/bundle_a1.yaml", "world/bundle_a2.yaml"),
                expectedBundleId = "rc-1",
            )
        assertEquals("rc-1", world.bundleId)

        val ex =
            assertThrows<WorldLoadException> {
                WorldLoader.loadFromResources(
                    listOf("world/bundle_a1.yaml", "world/bundle_a2.yaml"),
                    expectedBundleId = "rc-2",
                )
            }
        assertTrue(ex.message!!.contains("config is bundle rc-2"), ex.message)
        assertTrue(ex.message!!.contains("zone files are bundle rc-1"), ex.message)
    }

    @Test
    fun `zone files from two publishes fail the load`() {
        val ex =
            assertThrows<WorldLoadException> {
                WorldLoader.loadFromResources(listOf("world/bundle_a1.yaml", "world/bundle_b.yaml"))
            }
        assertTrue(ex.message!!.contains("2 different bundle ids"), ex.message)
        assertTrue(ex.message!!.contains("bundle_b"), ex.message)
    }

    @Test
    fun `unstamped zone files are tolerated and reported`() {
        val alone = WorldLoader.loadFromResource("world/bundle_none.yaml", expectedBundleId = "rc-1")
        assertNull(alone.bundleId)
        assertEquals(listOf("bundle_none"), alone.unstampedZones)

        val mixed = WorldLoader.loadFromResources(listOf("world/bundle_a1.yaml", "world/bundle_none.yaml"), expectedBundleId = "rc-1")
        assertEquals("rc-1", mixed.bundleId)
        assertEquals(listOf("bundle_none"), mixed.unstampedZones)
    }

    @Test
    fun `an unstamped config skips the comparison`() {
        val world = WorldLoader.loadFromResources(listOf("world/bundle_a1.yaml", "world/bundle_a2.yaml"), expectedBundleId = null)
        assertEquals("rc-1", world.bundleId)
    }
}
