package dev.ambon.domain.dungeon

import dev.ambon.domain.ids.ItemId

/** The type of a room in a dungeon layout. */
enum class DungeonRoomType {
    ENTRANCE,
    CORRIDOR,
    CHAMBER,
    TREASURE,
    BOSS,
}

/** A room description template drawn from a pool during generation. */
data class DungeonRoomTemplateDef(
    val title: String,
    val description: String,
    val image: String? = null,
)

/** Pool of mob IDs per role (referenced from the zone's mobs section). */
data class DungeonMobPoolDef(
    val common: List<String> = emptyList(),
    val elite: List<String> = emptyList(),
    val boss: List<String> = emptyList(),
)

/** Loot configuration for a single difficulty tier. */
data class DungeonLootTableDef(
    val mobDrops: List<ItemId> = emptyList(),
    val completionRewards: List<ItemId> = emptyList(),
)

/** A complete dungeon template loaded from YAML. */
data class DungeonTemplateDef(
    val id: String,
    val name: String,
    val description: String = "",
    val image: String? = null,
    val minLevel: Int = 1,
    val roomCountMin: Int = 20,
    val roomCountMax: Int = 25,
    val roomTemplates: Map<DungeonRoomType, List<DungeonRoomTemplateDef>>,
    val mobPools: DungeonMobPoolDef,
    val lootTables: Map<DungeonDifficulty, DungeonLootTableDef> = emptyMap(),
    val portalRoom: String? = null,
)
