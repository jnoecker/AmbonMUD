package dev.ambon.domain.world.data

data class DungeonRoomTemplateFile(
    val title: String,
    val description: String = "",
    val image: String? = null,
)

data class DungeonMobPoolFile(
    val common: List<String> = emptyList(),
    val elite: List<String> = emptyList(),
    val boss: List<String> = emptyList(),
)

data class DungeonLootTableFile(
    val mobDrops: List<String> = emptyList(),
    val completionRewards: List<String> = emptyList(),
)

data class DungeonFile(
    val name: String,
    val description: String = "",
    val image: String? = null,
    val minLevel: Int = 1,
    val roomCountMin: Int = 20,
    val roomCountMax: Int = 25,
    val roomTemplates: Map<String, List<DungeonRoomTemplateFile>> = emptyMap(),
    val mobPools: DungeonMobPoolFile = DungeonMobPoolFile(),
    val lootTables: Map<String, DungeonLootTableFile> = emptyMap(),
    val portalRoom: String? = null,
)
