package dev.ambon.domain.world.data

data class WorldFile(
    val zone: String,
    val lifespan: Long? = null,
    val startRoom: String,
    /** Whether this zone has custom graphical assets (rooms, mobs, items with real images). */
    val graphical: Boolean = false,
    val image: ZoneImageDefaults? = null,
    val audio: ZoneAudioDefaults? = null,
    val rooms: Map<String, RoomFile>,
    val mobs: Map<String, MobFile> = emptyMap(),
    val items: Map<String, ItemFile> = emptyMap(),
    val shops: Map<String, ShopFile> = emptyMap(),
    val trainers: Map<String, TrainerFile> = emptyMap(),
    val quests: Map<String, QuestFile> = emptyMap(),
    val gatheringNodes: Map<String, GatheringNodeFile> = emptyMap(),
    val recipes: Map<String, RecipeFile> = emptyMap(),
    val dungeon: DungeonFile? = null,
)

data class TrainerFile(
    val name: String = "",
    @com.fasterxml.jackson.annotation.JsonProperty("class")
    val className: String = "",
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
