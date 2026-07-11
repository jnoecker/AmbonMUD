package dev.ambon.domain.world.data

data class WorldFile(
    val zone: String,
    val lifespan: Long? = null,
    val startRoom: String,
    /** Whether this zone has custom graphical assets (rooms, mobs, items with real images). */
    val graphical: Boolean = false,
    /** Whether PvP combat is enabled in this zone. */
    val pvpEnabled: Boolean = false,
    /** Default terrain type for rooms in this zone (overridable per-room). */
    val terrain: String? = null,
    /** Controlling faction for this region. Inherited by mobs that don't set their own. */
    val faction: String? = null,
    /** Dynamic level-scaling configuration. Null/missing = STATIC (use authored levels). */
    val scaling: ZoneScalingFile? = null,
    /**
     * The zone's rectangle on the painted world map, in percent (0–100) of the
     * map image from its top-left corner. Published from Arcanum's Map overlay;
     * drives the web client's World Map atlas tab.
     */
    val worldMap: ZoneWorldMapFile? = null,
    val image: ZoneImageDefaults? = null,
    val audio: ZoneAudioDefaults? = null,
    /** Zone cinematic video (relative path under /videos/). Auto-plays on a player's first entry; replayable from the map. */
    val video: String? = null,
    val rooms: Map<String, RoomFile>,
    val mobs: Map<String, MobFile> = emptyMap(),
    val items: Map<String, ItemFile> = emptyMap(),
    val shops: Map<String, ShopFile> = emptyMap(),
    val trainers: Map<String, TrainerFile> = emptyMap(),
    val quests: Map<String, QuestFile> = emptyMap(),
    val gatheringNodes: Map<String, GatheringNodeFile> = emptyMap(),
    val recipes: Map<String, RecipeFile> = emptyMap(),
    val dungeon: DungeonFile? = null,
    val puzzles: Map<String, PuzzleFile> = emptyMap(),
)

data class ZoneScalingFile(
    val mode: String = "static",
    /** Inclusive [min, max] bounds. Required for BOUNDED mode. */
    val levelRange: List<Int>? = null,
)

/**
 * Zone-level `worldMap` block: the zone's footprint on the painted world map.
 * All four fields are required together; percentages of the map image (0–100)
 * measured from the top-left corner.
 */
data class ZoneWorldMapFile(
    val x: Double? = null,
    val y: Double? = null,
    val w: Double? = null,
    val h: Double? = null,
)

data class TrainerFile(
    val name: String = "",
    /**
     * Legacy single-class field. Prefer [classes] for new content.
     * Still supported for backwards compatibility with existing zones.
     */
    @com.fasterxml.jackson.annotation.JsonProperty("class")
    val className: String = "",
    /**
     * List of class IDs this trainer teaches. When set, takes precedence over [className].
     * Use this for multi-class trainers (e.g. an "academy master" teaching warrior + rogue + ranger).
     */
    val classes: List<String>? = null,
    val room: String = "",
    val image: String? = null,
)

data class ZoneImageDefaults(
    val room: String? = null,
    val mob: String? = null,
    val item: String? = null,
)

data class ZoneAudioDefaults(
    /** Default background music for all rooms in this zone. */
    val music: String? = null,
    /** Default ambient sound for all rooms in this zone. */
    val ambient: String? = null,
)
