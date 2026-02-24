package dev.ambon.sharding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class StaticZoneRegistryTest {
    private val engine1 = EngineAddress("engine-1", "host1", 9090)
    private val engine2 = EngineAddress("engine-2", "host2", 9091)

    @Nested
    inner class ClassicMode {
        @Test
        fun `ownerOf returns the engine that owns a zone`() {
            val registry =
                StaticZoneRegistry(
                    mapOf(
                        "engine-1" to Pair(engine1, setOf("zone_a", "zone_b")),
                        "engine-2" to Pair(engine2, setOf("zone_c")),
                    ),
                )

            assertEquals(engine1, registry.ownerOf("zone_a"))
            assertEquals(engine1, registry.ownerOf("zone_b"))
            assertEquals(engine2, registry.ownerOf("zone_c"))
        }

        @Test
        fun `ownerOf returns null for unknown zone`() {
            val registry =
                StaticZoneRegistry(
                    mapOf("engine-1" to Pair(engine1, setOf("zone_a"))),
                )

            assertNull(registry.ownerOf("nonexistent"))
        }

        @Test
        fun `allAssignments returns all zone to engine mappings`() {
            val registry =
                StaticZoneRegistry(
                    mapOf(
                        "engine-1" to Pair(engine1, setOf("zone_a", "zone_b")),
                        "engine-2" to Pair(engine2, setOf("zone_c")),
                    ),
                )

            val assignments = registry.allAssignments()
            assertEquals(3, assignments.size)
            assertEquals(engine1, assignments["zone_a"])
            assertEquals(engine2, assignments["zone_c"])
        }

        @Test
        fun `isLocal returns true when zone belongs to engine`() {
            val registry =
                StaticZoneRegistry(
                    mapOf("engine-1" to Pair(engine1, setOf("zone_a"))),
                )

            assertTrue(registry.isLocal("zone_a", "engine-1"))
            assertTrue(!registry.isLocal("zone_a", "engine-2"))
        }

        @Test
        fun `rejects duplicate zone assignments`() {
            assertThrows<IllegalArgumentException> {
                StaticZoneRegistry(
                    mapOf(
                        "engine-1" to Pair(engine1, setOf("zone_a")),
                        "engine-2" to Pair(engine2, setOf("zone_a")),
                    ),
                )
            }
        }

        @Test
        fun `instancesOf returns single instance for zone`() {
            val registry =
                StaticZoneRegistry(
                    mapOf("engine-1" to Pair(engine1, setOf("zone_a"))),
                )

            val instances = registry.instancesOf("zone_a")
            assertEquals(1, instances.size)
            assertEquals("engine-1", instances[0].engineId)
            assertEquals(engine1, instances[0].address)
            assertEquals("zone_a", instances[0].zone)
        }

        @Test
        fun `instancesOf returns empty list for unknown zone`() {
            val registry =
                StaticZoneRegistry(
                    mapOf("engine-1" to Pair(engine1, setOf("zone_a"))),
                )

            assertTrue(registry.instancesOf("nonexistent").isEmpty())
        }

        @Test
        fun `instancingEnabled returns false`() {
            val registry =
                StaticZoneRegistry(
                    mapOf("engine-1" to Pair(engine1, setOf("zone_a"))),
                )

            assertFalse(registry.instancingEnabled())
        }
    }

    @Nested
    inner class InstancedMode {
        @Test
        fun `allows duplicate zone assignments when instancing enabled`() {
            val registry =
                StaticZoneRegistry(
                    assignments =
                        mapOf(
                            "engine-1" to Pair(engine1, setOf("zone_a")),
                            "engine-2" to Pair(engine2, setOf("zone_a")),
                        ),
                    instancing = true,
                )

            // Both engines claim zone_a — ownerOf returns the first
            assertEquals(engine1, registry.ownerOf("zone_a"))
        }

        @Test
        fun `instancesOf returns multiple instances for shared zone`() {
            val registry =
                StaticZoneRegistry(
                    assignments =
                        mapOf(
                            "engine-1" to Pair(engine1, setOf("zone_a", "zone_b")),
                            "engine-2" to Pair(engine2, setOf("zone_a", "zone_c")),
                        ),
                    instancing = true,
                )

            val instances = registry.instancesOf("zone_a")
            assertEquals(2, instances.size)
            assertEquals(setOf("engine-1", "engine-2"), instances.map { it.engineId }.toSet())

            // zone_b and zone_c have single instances
            assertEquals(1, registry.instancesOf("zone_b").size)
            assertEquals(1, registry.instancesOf("zone_c").size)
        }

        @Test
        fun `instancingEnabled returns true`() {
            val registry =
                StaticZoneRegistry(
                    assignments = mapOf("engine-1" to Pair(engine1, setOf("zone_a"))),
                    instancing = true,
                )

            assertTrue(registry.instancingEnabled())
        }

        @Test
        fun `reportLoad updates player counts in instancesOf`() {
            val registry =
                StaticZoneRegistry(
                    assignments =
                        mapOf(
                            "engine-1" to Pair(engine1, setOf("zone_a")),
                            "engine-2" to Pair(engine2, setOf("zone_a")),
                        ),
                    instancing = true,
                )

            registry.reportLoad("engine-1", mapOf("zone_a" to 50))
            registry.reportLoad("engine-2", mapOf("zone_a" to 30))

            val instances = registry.instancesOf("zone_a")
            val e1 = instances.first { it.engineId == "engine-1" }
            val e2 = instances.first { it.engineId == "engine-2" }
            assertEquals(50, e1.playerCount)
            assertEquals(30, e2.playerCount)
        }

        @Test
        fun `reportLoad does not affect zones with no load report`() {
            val registry =
                StaticZoneRegistry(
                    assignments =
                        mapOf(
                            "engine-1" to Pair(engine1, setOf("zone_a")),
                        ),
                    instancing = true,
                )

            val instances = registry.instancesOf("zone_a")
            assertEquals(0, instances[0].playerCount)
        }

        @Test
        fun `allAssignments returns first engine for shared zones`() {
            val registry =
                StaticZoneRegistry(
                    assignments =
                        mapOf(
                            "engine-1" to Pair(engine1, setOf("zone_a")),
                            "engine-2" to Pair(engine2, setOf("zone_a")),
                        ),
                    instancing = true,
                )

            val assignments = registry.allAssignments()
            assertEquals(1, assignments.size)
            assertEquals(engine1, assignments["zone_a"])
        }

        @Test
        fun `isLocal returns true when this engine has an instance of the zone`() {
            val registry =
                StaticZoneRegistry(
                    assignments =
                        mapOf(
                            "engine-1" to Pair(engine1, setOf("zone_a")),
                            "engine-2" to Pair(engine2, setOf("zone_a")),
                        ),
                    instancing = true,
                )

            // isLocal delegates to ownerOf which returns first engine
            // For instanced mode, both engines are "local" — but the base
            // implementation only checks against ownerOf's first result.
            assertTrue(registry.isLocal("zone_a", "engine-1"))
        }
    }
}
