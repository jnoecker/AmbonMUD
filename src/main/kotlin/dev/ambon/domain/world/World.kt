package dev.ambon.domain.world

import dev.ambon.domain.crafting.GatheringNodeDef
import dev.ambon.domain.crafting.RecipeDef
import dev.ambon.domain.dungeon.DungeonTemplateDef
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.idZone
import dev.ambon.domain.quest.QuestDef

class World(
    rooms: Map<RoomId, Room>,
    startRoom: RoomId,
    mobSpawns: List<MobSpawn> = emptyList(),
    itemSpawns: List<ItemSpawn> = emptyList(),
    zoneLifespansMinutes: Map<String, Long> = emptyMap(),
    shopDefinitions: List<ShopDefinition> = emptyList(),
    trainerDefinitions: List<TrainerDefinition> = emptyList(),
    questDefinitions: List<QuestDef> = emptyList(),
    gatheringNodes: List<GatheringNodeDef> = emptyList(),
    recipes: List<RecipeDef> = emptyList(),
    dungeonTemplates: List<DungeonTemplateDef> = emptyList(),
) {
    private val _rooms = LinkedHashMap(rooms)
    val rooms: Map<RoomId, Room> get() = _rooms

    var startRoom: RoomId = startRoom
        private set

    private val _mobSpawns = mobSpawns.toMutableList()
    val mobSpawns: List<MobSpawn> get() = _mobSpawns

    private val _itemSpawns = itemSpawns.toMutableList()
    val itemSpawns: List<ItemSpawn> get() = _itemSpawns

    private val _zoneLifespansMinutes = zoneLifespansMinutes.toMutableMap()
    val zoneLifespansMinutes: Map<String, Long> get() = _zoneLifespansMinutes

    private val _shopDefinitions = shopDefinitions.toMutableList()
    val shopDefinitions: List<ShopDefinition> get() = _shopDefinitions

    private val _trainerDefinitions = trainerDefinitions.toMutableList()
    val trainerDefinitions: List<TrainerDefinition> get() = _trainerDefinitions

    private val _questDefinitions = questDefinitions.toMutableList()
    val questDefinitions: List<QuestDef> get() = _questDefinitions

    private val _gatheringNodes = gatheringNodes.toMutableList()
    val gatheringNodes: List<GatheringNodeDef> get() = _gatheringNodes

    private val _recipes = recipes.toMutableList()
    val recipes: List<RecipeDef> get() = _recipes

    val dungeonTemplates: List<DungeonTemplateDef> = dungeonTemplates.toList()

    /** Returns the set of zone names present in this world. */
    fun zones(): Set<String> = _rooms.keys.mapTo(mutableSetOf()) { it.zone }

    /** Adds or replaces individual rooms (used by the housing system to inject player house rooms). */
    fun putRooms(rooms: Map<RoomId, Room>) {
        _rooms.putAll(rooms)
    }

    /** Removes all rooms whose [RoomId.zone] equals [zone]. */
    fun removeZone(zone: String) {
        _rooms.keys.removeAll { it.zone == zone }
    }

    /**
     * Replaces all data for [zone] with data from [source].
     * Only data whose IDs belong to [zone] is affected; other zones are untouched.
     * Returns room IDs that existed before but are absent from [source].
     */
    fun mergeZone(zone: String, source: World): Set<RoomId> {
        val oldRoomIds = _rooms.keys.filter { it.zone == zone }.toSet()
        _rooms.keys.removeAll { it.zone == zone }
        val newRooms = source.rooms.filter { it.key.zone == zone }
        _rooms.putAll(newRooms)

        _mobSpawns.removeAll { idZone(it.id.value) == zone }
        _mobSpawns.addAll(source.mobSpawns.filter { idZone(it.id.value) == zone })

        _itemSpawns.removeAll { idZone(it.instance.id.value) == zone }
        _itemSpawns.addAll(source.itemSpawns.filter { idZone(it.instance.id.value) == zone })

        _shopDefinitions.removeAll { it.roomId.zone == zone }
        _shopDefinitions.addAll(source.shopDefinitions.filter { it.roomId.zone == zone })

        _trainerDefinitions.removeAll { it.roomId.zone == zone }
        _trainerDefinitions.addAll(source.trainerDefinitions.filter { it.roomId.zone == zone })

        _questDefinitions.removeAll { it.id.startsWith("$zone:") }
        _questDefinitions.addAll(source.questDefinitions.filter { it.id.startsWith("$zone:") })

        _gatheringNodes.removeAll { it.roomId.zone == zone }
        _gatheringNodes.addAll(source.gatheringNodes.filter { it.roomId.zone == zone })

        _recipes.removeAll { it.id.startsWith("$zone:") }
        _recipes.addAll(source.recipes.filter { it.id.startsWith("$zone:") })

        source.zoneLifespansMinutes[zone]?.let { _zoneLifespansMinutes[zone] = it }
            ?: _zoneLifespansMinutes.remove(zone)

        return oldRoomIds - newRooms.keys
    }

    /**
     * Replaces all world data with data from [source].
     * Returns room IDs that existed before but are absent from [source].
     */
    fun replaceAll(source: World): Set<RoomId> {
        val oldRoomIds = _rooms.keys.toSet()

        _rooms.clear()
        _rooms.putAll(source.rooms)
        startRoom = source.startRoom

        _mobSpawns.clear()
        _mobSpawns.addAll(source.mobSpawns)

        _itemSpawns.clear()
        _itemSpawns.addAll(source.itemSpawns)

        _zoneLifespansMinutes.clear()
        _zoneLifespansMinutes.putAll(source.zoneLifespansMinutes)

        _shopDefinitions.clear()
        _shopDefinitions.addAll(source.shopDefinitions)

        _trainerDefinitions.clear()
        _trainerDefinitions.addAll(source.trainerDefinitions)

        _questDefinitions.clear()
        _questDefinitions.addAll(source.questDefinitions)

        _gatheringNodes.clear()
        _gatheringNodes.addAll(source.gatheringNodes)

        _recipes.clear()
        _recipes.addAll(source.recipes)

        return oldRoomIds - source.rooms.keys
    }
}
