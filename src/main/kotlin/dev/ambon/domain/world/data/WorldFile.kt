package dev.ambon.domain.world.data

data class WorldFile(
    val zone: String,
    val lifespan: Long? = null,
    val startRoom: String,
    val image: ZoneImageDefaults? = null,
    val video: ZoneVideoDefaults? = null,
    val audio: ZoneAudioDefaults? = null,
    val rooms: Map<String, RoomFile>,
    val mobs: Map<String, MobFile> = emptyMap(),
    val items: Map<String, ItemFile> = emptyMap(),
    val shops: Map<String, ShopFile> = emptyMap(),
    val quests: Map<String, QuestFile> = emptyMap(),
    val gatheringNodes: Map<String, GatheringNodeFile> = emptyMap(),
    val recipes: Map<String, RecipeFile> = emptyMap(),
)

data class ZoneImageDefaults(
    val room: String? = null,
    val mob: String? = null,
    val item: String? = null,
)

data class ZoneVideoDefaults(
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
