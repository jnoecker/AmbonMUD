package dev.ambon.sharding

import java.time.Clock

/**
 * Threshold-based [InstanceScaler] that produces scale-up and scale-down
 * signals based on zone instance utilization.
 *
 * - **Scale up** when aggregate utilization across instances of a zone
 *   exceeds [scaleUpThreshold].
 * - **Scale down** when aggregate utilization drops below [scaleDownThreshold]
 *   AND instance count exceeds [minInstances] for the zone.
 *
 * A per-zone cooldown prevents oscillation.
 */
class ThresholdInstanceScaler(
    private val registry: ZoneRegistry,
    private val scaleUpThreshold: Double = 0.8,
    private val scaleDownThreshold: Double = 0.2,
    private val cooldownMs: Long = 60_000L,
    private val minInstances: Map<String, Int> = emptyMap(),
    private val defaultMinInstances: Int = 1,
    private val clock: Clock = Clock.systemUTC(),
) : InstanceScaler {
    private val lastDecisionAt = mutableMapOf<String, Long>()

    override fun evaluate(): List<ScaleDecision> {
        val decisions = mutableListOf<ScaleDecision>()
        val now = clock.millis()

        val allZones = registry.allAssignments().keys

        for (zone in allZones) {
            if (isInCooldown(zone, now)) continue

            val instances = registry.instancesOf(zone)
            if (instances.isEmpty()) continue

            val totalPlayers = instances.sumOf { it.playerCount }
            val totalCapacity = instances.sumOf { it.capacity }
            if (totalCapacity <= 0) continue

            val utilization = totalPlayers.toDouble() / totalCapacity
            val minForZone = minInstances.getOrDefault(zone, defaultMinInstances)

            when {
                utilization >= scaleUpThreshold -> {
                    decisions +=
                        ScaleDecision.ScaleUp(
                            zone = zone,
                            reason =
                                "utilization %.0f%% >= %.0f%% threshold (%d players across %d instances)".format(
                                    utilization * 100,
                                    scaleUpThreshold * 100,
                                    totalPlayers,
                                    instances.size,
                                ),
                        )
                    lastDecisionAt[zone] = now
                }

                utilization <= scaleDownThreshold && instances.size > minForZone -> {
                    // Pick the instance with fewest players to drain
                    val candidate = instances.minByOrNull { it.playerCount }
                    if (candidate != null) {
                        decisions +=
                            ScaleDecision.ScaleDown(
                                zone = zone,
                                engineId = candidate.engineId,
                                reason =
                                    "utilization %.0f%% <= %.0f%% threshold, %d instances > min %d".format(
                                        utilization * 100,
                                        scaleDownThreshold * 100,
                                        instances.size,
                                        minForZone,
                                    ),
                            )
                        lastDecisionAt[zone] = now
                    }
                }
            }
        }

        return decisions
    }

    private fun isInCooldown(
        zone: String,
        now: Long,
    ): Boolean {
        val last = lastDecisionAt[zone] ?: return false
        return (now - last) < cooldownMs
    }
}
