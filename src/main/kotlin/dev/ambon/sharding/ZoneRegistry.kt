package dev.ambon.sharding

/**
 * Address of a remote engine instance.
 */
data class EngineAddress(
    val engineId: String,
    val host: String,
    val port: Int,
)

/**
 * Shared registry mapping zone names to the engine instance(s) that own them.
 *
 * In classic (non-instanced) mode each zone maps to exactly one engine.
 * When instancing is enabled, multiple engines may host copies of the same zone;
 * each (zone, engineId) pair is a distinct [ZoneInstance].
 *
 * Implementations can be backed by static config ([StaticZoneRegistry]) for
 * development or by Redis ([RedisZoneRegistry]) for dynamic deployments.
 */
interface ZoneRegistry {
    /** Which engine owns this zone right now? Null if unclaimed. */
    fun ownerOf(zone: String): EngineAddress?

    /** Register this engine as owner of the given zones. */
    fun claimZones(
        engineId: String,
        address: EngineAddress,
        zones: Set<String>,
    )

    /** Heartbeat to keep leases alive (only meaningful for TTL-backed registries). */
    fun renewLease(engineId: String)

    /** All current zone → engine assignments. */
    fun allAssignments(): Map<String, EngineAddress>

    /** Is the given zone owned by this engine? */
    fun isLocal(
        zone: String,
        engineId: String,
    ): Boolean = ownerOf(zone)?.engineId == engineId

    // ---- Instancing-aware methods (default impls for backward compat) ----

    /** All instances of a given zone. Empty list if nobody claims it. */
    fun instancesOf(zone: String): List<ZoneInstance> =
        listOfNotNull(
            ownerOf(zone)?.let {
                ZoneInstance(engineId = it.engineId, address = it, zone = zone)
            },
        )

    /**
     * Report per-zone player counts from this engine. Called periodically by
     * engines so the registry can provide load data to [InstanceSelector].
     */
    fun reportLoad(
        engineId: String,
        zoneCounts: Map<String, Int>,
    ) { /* no-op by default */ }

    /** Whether instancing is enabled (multiple engines may share a zone). */
    fun instancingEnabled(): Boolean = false
}
