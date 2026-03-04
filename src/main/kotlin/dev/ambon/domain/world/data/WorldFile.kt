package dev.ambon.domain.world.data

data class WorldFile(
    val zone: String,
    val lifespan: Long? = null,
    val startRoom: String,
    val image: ZoneImageDefaults? = null,
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
