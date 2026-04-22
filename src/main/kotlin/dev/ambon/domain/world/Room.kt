package dev.ambon.domain.world

import dev.ambon.domain.ids.RoomId

/** Per-character achievement gate on an exit. */
data class AchievementGate(
    val achievementId: String,
    val lockedMessage: String?,
)

data class Room(
    val id: RoomId,
    val title: String,
    val description: String,
    val exits: Map<Direction, RoomId>,
    /** Directions whose targets are in zones not loaded on this engine (cross-zone stubs). */
    val remoteExits: Set<Direction> = emptySet(),
    /** Directions whose exits require the player to have unlocked a given achievement. */
    val achievementGates: Map<Direction, AchievementGate> = emptyMap(),
    /** Stateful features in this room: doors, containers, levers, signs. */
    val features: List<RoomFeature> = emptyList(),
    /** Crafting station available in this room, if any. */
    val station: String? = null,
    /** True if this room has a bank NPC (enables deposit/withdraw commands). */
    val bank: Boolean = false,
    /** True if this room is a tavern (enables gambling commands). */
    val tavern: Boolean = false,
    /** True if this room has a stylist NPC (enables race-change commands). */
    val stylist: Boolean = false,
    /** True if this room has a dungeon portal (enables dungeon kiosk badge). */
    val dungeon: Boolean = false,
    /** True if this room has an auction house (enables auction hall badge). */
    val auction: Boolean = false,
    /** True if this room has a housing broker (enables housing kiosk badge). */
    val housingBroker: Boolean = false,
    /** URL to an image representing this room. */
    val image: String? = null,
    /** URL to a video cinematic for this room. */
    val video: String? = null,
    /** Background music track URL for this room. */
    val music: String? = null,
    /** Ambient sound loop URL for this room. */
    val ambient: String? = null,
    /** Precomputed minimap X coordinate (assigned by WorldLoader BFS). */
    val mapX: Int = 0,
    /** Precomputed minimap Y coordinate (assigned by WorldLoader BFS). */
    val mapY: Int = 0,
    /** Whether this room's zone has custom graphical assets (non-placeholder images). */
    val graphical: Boolean = false,
    /** Terrain type — drives default background and weather suppression. */
    val terrain: String = "outside",
)
