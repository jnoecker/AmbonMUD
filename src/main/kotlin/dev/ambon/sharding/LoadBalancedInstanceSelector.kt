package dev.ambon.sharding

/**
 * Selects a zone instance using a priority-based strategy:
 *
 * 1. **Group hint** — prefer the instance a group member is on (if under capacity).
 * 2. **Sticky hint** — prefer the instance the player was recently on (if under capacity).
 * 3. **Load-based** — pick the instance with the fewest players that is under capacity.
 * 4. **Fallback** — if all instances are at or over capacity, pick the least loaded one.
 */
class LoadBalancedInstanceSelector(
    private val registry: ZoneRegistry,
) : InstanceSelector {
    override fun select(
        zone: String,
        playerName: String,
        groupHint: String?,
        stickyHint: String?,
    ): ZoneInstance? {
        val instances = registry.instancesOf(zone)
        if (instances.isEmpty()) return null
        if (instances.size == 1) return instances.first()

        // 1. Group hint — keep group members together
        if (groupHint != null) {
            val match = instances.find { it.engineId == groupHint && it.playerCount < it.capacity }
            if (match != null) return match
        }

        // 2. Sticky hint — prefer last-used instance for continuity
        if (stickyHint != null) {
            val match = instances.find { it.engineId == stickyHint && it.playerCount < it.capacity }
            if (match != null) return match
        }

        // 3. Load-based — least-loaded instance under capacity
        val underCapacity = instances.filter { it.playerCount < it.capacity }
        if (underCapacity.isNotEmpty()) {
            return underCapacity.minByOrNull { it.playerCount }
        }

        // 4. Fallback — all full; pick least loaded anyway
        return instances.minByOrNull { it.playerCount }
    }
}
