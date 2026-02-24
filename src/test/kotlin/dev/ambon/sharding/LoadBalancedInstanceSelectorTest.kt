package dev.ambon.sharding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LoadBalancedInstanceSelectorTest {
    private val engine1 = EngineAddress("engine-1", "host1", 9090)
    private val engine2 = EngineAddress("engine-2", "host2", 9091)
    private val engine3 = EngineAddress("engine-3", "host3", 9092)

    @Test
    fun `returns null when no instances exist`() {
        val registry =
            StaticZoneRegistry(
                assignments = mapOf("engine-1" to Pair(engine1, setOf("other_zone"))),
                instancing = true,
            )
        val selector = LoadBalancedInstanceSelector(registry)

        assertNull(selector.select("nonexistent", "player1"))
    }

    @Test
    fun `returns the single instance when only one exists`() {
        val registry =
            StaticZoneRegistry(
                assignments = mapOf("engine-1" to Pair(engine1, setOf("zone_a"))),
                instancing = true,
            )
        val selector = LoadBalancedInstanceSelector(registry)

        val result = selector.select("zone_a", "player1")
        assertNotNull(result)
        assertEquals("engine-1", result!!.engineId)
    }

    @Test
    fun `selects least-loaded instance under capacity`() {
        val registry =
            StaticZoneRegistry(
                assignments =
                    mapOf(
                        "engine-1" to Pair(engine1, setOf("zone_a")),
                        "engine-2" to Pair(engine2, setOf("zone_a")),
                        "engine-3" to Pair(engine3, setOf("zone_a")),
                    ),
                instancing = true,
            )
        registry.reportLoad("engine-1", mapOf("zone_a" to 100))
        registry.reportLoad("engine-2", mapOf("zone_a" to 30))
        registry.reportLoad("engine-3", mapOf("zone_a" to 70))

        val selector = LoadBalancedInstanceSelector(registry)
        val result = selector.select("zone_a", "player1")

        assertEquals("engine-2", result!!.engineId)
    }

    @Test
    fun `prefers group hint over least-loaded`() {
        val registry =
            StaticZoneRegistry(
                assignments =
                    mapOf(
                        "engine-1" to Pair(engine1, setOf("zone_a")),
                        "engine-2" to Pair(engine2, setOf("zone_a")),
                    ),
                instancing = true,
            )
        registry.reportLoad("engine-1", mapOf("zone_a" to 150))
        registry.reportLoad("engine-2", mapOf("zone_a" to 10))

        val selector = LoadBalancedInstanceSelector(registry)
        // Group hint says engine-1, even though engine-2 has fewer players
        val result = selector.select("zone_a", "player1", groupHint = "engine-1")

        assertEquals("engine-1", result!!.engineId)
    }

    @Test
    fun `ignores group hint when that instance is at capacity`() {
        val registry =
            StaticZoneRegistry(
                assignments =
                    mapOf(
                        "engine-1" to Pair(engine1, setOf("zone_a")),
                        "engine-2" to Pair(engine2, setOf("zone_a")),
                    ),
                instancing = true,
            )
        // engine-1 is at capacity (200 = DEFAULT_CAPACITY)
        registry.reportLoad("engine-1", mapOf("zone_a" to 200))
        registry.reportLoad("engine-2", mapOf("zone_a" to 10))

        val selector = LoadBalancedInstanceSelector(registry)
        val result = selector.select("zone_a", "player1", groupHint = "engine-1")

        assertEquals("engine-2", result!!.engineId)
    }

    @Test
    fun `prefers sticky hint over least-loaded when under capacity`() {
        val registry =
            StaticZoneRegistry(
                assignments =
                    mapOf(
                        "engine-1" to Pair(engine1, setOf("zone_a")),
                        "engine-2" to Pair(engine2, setOf("zone_a")),
                    ),
                instancing = true,
            )
        registry.reportLoad("engine-1", mapOf("zone_a" to 100))
        registry.reportLoad("engine-2", mapOf("zone_a" to 10))

        val selector = LoadBalancedInstanceSelector(registry)
        val result = selector.select("zone_a", "player1", stickyHint = "engine-1")

        assertEquals("engine-1", result!!.engineId)
    }

    @Test
    fun `group hint takes priority over sticky hint`() {
        val registry =
            StaticZoneRegistry(
                assignments =
                    mapOf(
                        "engine-1" to Pair(engine1, setOf("zone_a")),
                        "engine-2" to Pair(engine2, setOf("zone_a")),
                        "engine-3" to Pair(engine3, setOf("zone_a")),
                    ),
                instancing = true,
            )
        registry.reportLoad("engine-1", mapOf("zone_a" to 50))
        registry.reportLoad("engine-2", mapOf("zone_a" to 50))
        registry.reportLoad("engine-3", mapOf("zone_a" to 10))

        val selector = LoadBalancedInstanceSelector(registry)
        val result =
            selector.select(
                "zone_a",
                "player1",
                groupHint = "engine-1",
                stickyHint = "engine-2",
            )

        assertEquals("engine-1", result!!.engineId)
    }

    @Test
    fun `falls back to least-loaded when all instances are at capacity`() {
        val registry =
            StaticZoneRegistry(
                assignments =
                    mapOf(
                        "engine-1" to Pair(engine1, setOf("zone_a")),
                        "engine-2" to Pair(engine2, setOf("zone_a")),
                    ),
                instancing = true,
            )
        registry.reportLoad("engine-1", mapOf("zone_a" to 250))
        registry.reportLoad("engine-2", mapOf("zone_a" to 210))

        val selector = LoadBalancedInstanceSelector(registry)
        val result = selector.select("zone_a", "player1")

        assertEquals("engine-2", result!!.engineId)
    }

    @Test
    fun `handles zero-player instances`() {
        val registry =
            StaticZoneRegistry(
                assignments =
                    mapOf(
                        "engine-1" to Pair(engine1, setOf("zone_a")),
                        "engine-2" to Pair(engine2, setOf("zone_a")),
                    ),
                instancing = true,
            )
        // No load reports — both at 0

        val selector = LoadBalancedInstanceSelector(registry)
        val result = selector.select("zone_a", "player1")

        // Either engine is fine (both have 0 players)
        assertNotNull(result)
    }
}
