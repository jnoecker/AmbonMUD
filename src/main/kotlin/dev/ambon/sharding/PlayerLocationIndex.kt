package dev.ambon.sharding

/**
 * Tracks which engine currently hosts each online player.
 * Used for O(1) cross-engine tell routing.
 *
 * Key: player name (lowercase)
 * Value: engine ID string
 */
interface PlayerLocationIndex {
    /** Register (or refresh) this player on the current engine. */
    fun register(playerName: String)

    /** Remove a player from the index (on logout or disconnect). */
    fun unregister(playerName: String)

    /**
     * Look up which engine hosts the named player.
     * Returns the engine ID, or null if the player is not in the index.
     */
    fun lookupEngineId(playerName: String): String?

    /**
     * Refresh the TTL for all listed players (heartbeat).
     * Called periodically to prevent keys from expiring while players are still online.
     */
    fun refreshAll(playerNames: Collection<String>)
}
