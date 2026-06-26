package dev.ambon.domain.world

import dev.ambon.domain.ids.RoomId

/** Per-character achievement gate on an exit. */
data class AchievementGate(
    val achievementId: String,
    val lockedMessage: String?,
)

/**
 * One authored boat passage from a [Room.boatDock]: a fixed fast-travel route to [to] for a
 * flat, author-set [price] in gold (no distance scaling, paid each trip). The destination's
 * world-map pin is read from the [to] room's own [Room.boatMapX]/[Room.boatMapY].
 */
data class BoatRoute(
    val to: RoomId,
    val price: Long,
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
    /** True if this room is an inn (enables resting + recall-point setting). */
    val inn: Boolean = false,
    /** True if this room holds an Akathavae shrine (enables `pledge`/`renounce`). */
    val akathavaeShrine: Boolean = false,
    /** True if this room has a flight master (enables `flights`/`fly` fast-travel + kiosk badge). */
    val flightMaster: Boolean = false,
    /**
     * Flight-master position on the painted Ambon world map as a percentage (0–100) of the map
     * image — X across, Y down — driving the clickable griffin hotspot in the web kiosk. Null
     * means "unplaced": the destination still lists textually but has no map pin. Only meaningful
     * when [flightMaster] is true. Mirrors the equipment paper-doll percentage convention.
     */
    val flightMapX: Double? = null,
    val flightMapY: Double? = null,
    /** True if this room is a boat dock (enables `voyages`/`sail` + harbor kiosk badge). */
    val boatDock: Boolean = false,
    /**
     * Boat-dock position on the painted Ambon world map as a percentage (0–100) of the map image
     * — X across, Y down — driving the clickable anchor hotspot in the web kiosk. Doubles as the
     * map pin for any [BoatRoute] whose destination is this room. Null means "unplaced": the dock
     * still works and routes here still list textually, just without a map pin.
     */
    val boatMapX: Double? = null,
    val boatMapY: Double? = null,
    /**
     * Authored boat passages leaving this dock — the "fixed paths" players can buy. Each is a flat
     * author-set fare to a destination room. Only meaningful when [boatDock] is true; routes whose
     * destination isn't loaded on this engine are silently skipped at runtime.
     */
    val boatRoutes: List<BoatRoute> = emptyList(),
    /** URL to an image representing this room. */
    val image: String? = null,
    /** URL to a video cinematic for this room. */
    val video: String? = null,
    /** Background music track URL for this room. */
    val music: String? = null,
    /** Ambient sound loop URL for this room. */
    val ambient: String? = null,
    /** Jukebox playlist for this room. Non-empty enables the jukebox command + badge. */
    val jukebox: List<JukeboxSong> = emptyList(),
    /** A single-song music box for this room, or null. Present enables the music-box device + badge. */
    val musicBox: MusicBox? = null,
    /** Precomputed minimap X coordinate (assigned by WorldLoader BFS). */
    val mapX: Int = 0,
    /** Precomputed minimap Y coordinate (assigned by WorldLoader BFS). */
    val mapY: Int = 0,
    /**
     * Precomputed minimap floor (z layer). Rooms horizontally connected to the
     * zone start room are floor 0; areas reachable only through up/down exits
     * sit one floor above/below their stair partner. Clients render one floor
     * at a time, following the player.
     */
    val mapZ: Int = 0,
    /** Whether this room's zone has custom graphical assets (non-placeholder images). */
    val graphical: Boolean = false,
    /** Terrain type — drives default background and weather suppression. */
    val terrain: String = "outside",
)
