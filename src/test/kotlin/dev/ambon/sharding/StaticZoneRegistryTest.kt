package dev.ambon.sharding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class StaticZoneRegistryTest {
    private val engine1 = EngineAddress("engine-1", "host1", 9090)
    private val engine2 = EngineAddress("engine-2", "host2", 9091)

    @Test
    fun `ownerOf returns the engine that owns a zone`() {
        val registry = StaticZoneRegistry(
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
        val registry = StaticZoneRegistry(
            mapOf("engine-1" to Pair(engine1, setOf("zone_a"))),
        )

        assertNull(registry.ownerOf("nonexistent"))
    }

    @Test
    fun `allAssignments returns all zone to engine mappings`() {
        val registry = StaticZoneRegistry(
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
        val registry = StaticZoneRegistry(
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
}
