package dev.ambon.domain.world

/**
 * A zone's authored footprint on the painted world map, in percentages of the
 * map image (0–100 measured from the top-left corner). Authored in Arcanum's
 * Map overlay and published into zone YAML as the zone-level `worldMap` block;
 * surfaced to clients through the `World.Areas` GMCP package so the web atlas
 * can seat each zone onto the `world_map` art at any resolution.
 */
data class ZoneWorldMap(
    /** Left edge, percent of the map width. */
    val x: Double,
    /** Top edge, percent of the map height. */
    val y: Double,
    /** Width, percent of the map width. */
    val w: Double,
    /** Height, percent of the map height. */
    val h: Double,
)
