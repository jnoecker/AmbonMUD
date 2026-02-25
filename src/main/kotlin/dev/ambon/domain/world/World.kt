package dev.ambon.domain.world

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.quest.QuestDef

class World(
    val rooms: Map<RoomId, Room>,
    val startRoom: RoomId,
    val mobSpawns: List<MobSpawn> = emptyList(),
    val itemSpawns: List<ItemSpawn> = emptyList(),
    val zoneLifespansMinutes: Map<String, Long> = emptyMap(),
    val shopDefinitions: List<ShopDefinition> = emptyList(),
    val questDefinitions: List<QuestDef> = emptyList(),
)
