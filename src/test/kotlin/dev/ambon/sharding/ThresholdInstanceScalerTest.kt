package dev.ambon.sharding

import dev.ambon.test.MutableClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThresholdInstanceScalerTest {
    private fun makeRegistry(assignments: Map<String, Pair<EngineAddress, Set<String>>>): StaticZoneRegistry {
        val registry =
            StaticZoneRegistry(
                assignments = assignments,
                instancing = true,
            )
        return registry
    }

    @Test
    fun `scale up triggered when utilization exceeds threshold`() {
        val e1 = EngineAddress("e1", "h1", 9090)
        val e2 = EngineAddress("e2", "h2", 9091)
        val registry =
            makeRegistry(
                mapOf(
                    "e1" to Pair(e1, setOf("hub")),
                    "e2" to Pair(e2, setOf("hub")),
                ),
            )
        // Set high load: 180/200 per instance = 360/400 = 90%
        registry.reportLoad("e1", mapOf("hub" to 180))
        registry.reportLoad("e2", mapOf("hub" to 180))

        val scaler =
            ThresholdInstanceScaler(
                registry = registry,
                scaleUpThreshold = 0.8,
                scaleDownThreshold = 0.2,
            )

        val decisions = scaler.evaluate()
        assertEquals(1, decisions.size)
        assertTrue(decisions[0] is ScaleDecision.ScaleUp)
        assertEquals("hub", decisions[0].zone)
    }

    @Test
    fun `scale down triggered when utilization below threshold`() {
        val e1 = EngineAddress("e1", "h1", 9090)
        val e2 = EngineAddress("e2", "h2", 9091)
        val registry =
            makeRegistry(
                mapOf(
                    "e1" to Pair(e1, setOf("hub")),
                    "e2" to Pair(e2, setOf("hub")),
                ),
            )
        // Set low load: 10/200 per instance = 20/400 = 5%
        registry.reportLoad("e1", mapOf("hub" to 10))
        registry.reportLoad("e2", mapOf("hub" to 10))

        val scaler =
            ThresholdInstanceScaler(
                registry = registry,
                scaleUpThreshold = 0.8,
                scaleDownThreshold = 0.2,
                defaultMinInstances = 1,
            )

        val decisions = scaler.evaluate()
        assertEquals(1, decisions.size)
        assertTrue(decisions[0] is ScaleDecision.ScaleDown)
        assertEquals("hub", decisions[0].zone)
    }

    @Test
    fun `scale down respects min instances`() {
        val e1 = EngineAddress("e1", "h1", 9090)
        val e2 = EngineAddress("e2", "h2", 9091)
        val registry =
            makeRegistry(
                mapOf(
                    "e1" to Pair(e1, setOf("hub")),
                    "e2" to Pair(e2, setOf("hub")),
                ),
            )
        registry.reportLoad("e1", mapOf("hub" to 10))
        registry.reportLoad("e2", mapOf("hub" to 10))

        // Min instances = 2 means we can't scale below 2
        val scaler =
            ThresholdInstanceScaler(
                registry = registry,
                scaleUpThreshold = 0.8,
                scaleDownThreshold = 0.2,
                minInstances = mapOf("hub" to 2),
            )

        val decisions = scaler.evaluate()
        assertTrue(decisions.isEmpty(), "Should not scale down below min instances")
    }

    @Test
    fun `cooldown prevents rapid decisions`() {
        val clock = MutableClock(0L)
        val e1 = EngineAddress("e1", "h1", 9090)
        val e2 = EngineAddress("e2", "h2", 9091)
        val registry =
            makeRegistry(
                mapOf(
                    "e1" to Pair(e1, setOf("hub")),
                    "e2" to Pair(e2, setOf("hub")),
                ),
            )
        registry.reportLoad("e1", mapOf("hub" to 180))
        registry.reportLoad("e2", mapOf("hub" to 180))

        val scaler =
            ThresholdInstanceScaler(
                registry = registry,
                scaleUpThreshold = 0.8,
                cooldownMs = 60_000L,
                clock = clock,
            )

        // First evaluation triggers scale-up
        val first = scaler.evaluate()
        assertEquals(1, first.size)

        // Advance only 30s — within cooldown
        clock.advance(30_000L)
        val second = scaler.evaluate()
        assertTrue(second.isEmpty(), "Should be in cooldown")

        // Advance past cooldown
        clock.advance(31_000L)
        val third = scaler.evaluate()
        assertEquals(1, third.size, "Should trigger again after cooldown")
    }

    @Test
    fun `no decision when utilization is in normal range`() {
        val e1 = EngineAddress("e1", "h1", 9090)
        val e2 = EngineAddress("e2", "h2", 9091)
        val registry =
            makeRegistry(
                mapOf(
                    "e1" to Pair(e1, setOf("hub")),
                    "e2" to Pair(e2, setOf("hub")),
                ),
            )
        // 50% utilization — neither up nor down
        registry.reportLoad("e1", mapOf("hub" to 100))
        registry.reportLoad("e2", mapOf("hub" to 100))

        val scaler =
            ThresholdInstanceScaler(
                registry = registry,
                scaleUpThreshold = 0.8,
                scaleDownThreshold = 0.2,
            )

        val decisions = scaler.evaluate()
        assertTrue(decisions.isEmpty(), "No decisions expected at 50% utilization")
    }

    @Test
    fun `scale down picks least loaded instance`() {
        val e1 = EngineAddress("e1", "h1", 9090)
        val e2 = EngineAddress("e2", "h2", 9091)
        val e3 = EngineAddress("e3", "h3", 9092)
        val registry =
            makeRegistry(
                mapOf(
                    "e1" to Pair(e1, setOf("hub")),
                    "e2" to Pair(e2, setOf("hub")),
                    "e3" to Pair(e3, setOf("hub")),
                ),
            )
        // Low load overall, but e3 has fewest players
        registry.reportLoad("e1", mapOf("hub" to 20))
        registry.reportLoad("e2", mapOf("hub" to 15))
        registry.reportLoad("e3", mapOf("hub" to 5))

        val scaler =
            ThresholdInstanceScaler(
                registry = registry,
                scaleUpThreshold = 0.8,
                scaleDownThreshold = 0.2,
                defaultMinInstances = 1,
            )

        val decisions = scaler.evaluate()
        assertEquals(1, decisions.size)
        val down = decisions[0] as ScaleDecision.ScaleDown
        assertEquals("e3", down.engineId, "Should pick least loaded instance for scale-down")
    }
}
