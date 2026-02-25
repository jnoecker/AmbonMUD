package dev.ambon.domain.world.data

data class WorldFile(
    val zone: String,
    val lifespan: Long? = null,
    val startRoom: String,
    val rooms: Map<String, RoomFile>,
    val mobs: Map<String, MobFile> = emptyMap(),
    val items: Map<String, ItemFile> = emptyMap(),
    val shops: Map<String, ShopFile> = emptyMap(),
    val quests: Map<String, QuestFile> = emptyMap(),
)
