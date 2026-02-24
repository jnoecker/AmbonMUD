package dev.ambon.sharding

/**
 * Metadata about a single instance of a zone running on a specific engine.
 *
 * When zone instancing is enabled, multiple engines can host copies of the same
 * zone. Each (zone, engineId) pair is a distinct instance with its own players,
 * mobs, and combat state.
 */
data class ZoneInstance(
    val engineId: String,
    val address: EngineAddress,
    val zone: String,
    /** Current player count on this instance, reported by the engine. */
    val playerCount: Int = 0,
    /** Configured capacity for this instance. */
    val capacity: Int = DEFAULT_CAPACITY,
) {
    companion object {
        const val DEFAULT_CAPACITY = 200
    }
}
