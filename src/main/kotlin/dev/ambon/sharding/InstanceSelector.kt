package dev.ambon.sharding

/**
 * Selects which instance of a zone a player should be routed to.
 *
 * Used by [HandoffManager] and gateway [SessionRouter][dev.ambon.gateway.SessionRouter]
 * to distribute players across multiple instances of the same zone when
 * zone instancing is enabled.
 */
interface InstanceSelector {
    /**
     * Select an instance of [zone] for the given player.
     *
     * @param zone The zone name
     * @param playerName The player requesting entry (used for logging/stickiness)
     * @param groupHint Optional engineId of a group member already in this zone
     * @param stickyHint Optional engineId this player was last on for this zone
     * @return The selected [ZoneInstance], or null if no instance is available
     */
    fun select(
        zone: String,
        playerName: String = "",
        groupHint: String? = null,
        stickyHint: String? = null,
    ): ZoneInstance?
}
