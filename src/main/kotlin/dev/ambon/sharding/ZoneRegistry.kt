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
 * Shared registry mapping zone names to the engine instance that owns them.
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
}
