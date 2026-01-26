package dev.ambon.engine.items

import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.world.ItemSpawn

class ItemRegistry {
    private val roomItems = mutableMapOf<RoomId, MutableList<ItemInstance>>()
    private val inventoryItems = mutableMapOf<SessionId, MutableList<ItemInstance>>()
    private val mobItems = mutableMapOf<MobId, MutableList<ItemInstance>>()
    private val unplacedItems = mutableMapOf<ItemId, ItemInstance>()

    fun loadSpawns(spawns: List<ItemSpawn>) {
        roomItems.clear()
        mobItems.clear()
        unplacedItems.clear()
        for (spawn in spawns) {
            val instance = spawn.instance
            when {
                spawn.roomId != null -> addRoomItem(spawn.roomId, instance)
                spawn.mobId != null -> addMobItem(spawn.mobId, instance)
                else -> addUnplacedItem(instance.id, instance)
            }
        }
    }

    fun clearRoom(roomId: RoomId) {
        roomItems.remove(roomId)
    }

    fun addRoomItem(
        roomId: RoomId,
        item: ItemInstance,
    ) {
        roomItems.getOrPut(roomId) { mutableListOf() }.add(item)
    }

    fun setRoomItems(
        roomId: RoomId,
        items: List<ItemInstance>,
    ) {
        roomItems[roomId] = items.toMutableList()
    }

    fun itemsInRoom(roomId: RoomId): List<ItemInstance> = roomItems[roomId]?.toList() ?: emptyList()

    fun inventory(sessionId: SessionId): List<ItemInstance> = inventoryItems[sessionId]?.toList() ?: emptyList()

    fun addMobItem(
        mobId: MobId,
        item: ItemInstance,
    ) {
        mobItems.getOrPut(mobId) { mutableListOf() }.add(item)
    }

    fun itemsInMob(mobId: MobId): List<ItemInstance> = mobItems[mobId]?.toList() ?: emptyList()

    fun addUnplacedItem(
        itemId: ItemId,
        item: ItemInstance,
    ) {
        unplacedItems[itemId] = item
    }

    fun unplacedItems(): Map<ItemId, ItemInstance> = unplacedItems.toMap()

    fun ensurePlayer(sessionId: SessionId) {
        inventoryItems.getOrPut(sessionId) { mutableListOf() }
    }

    fun removePlayer(sessionId: SessionId) {
        inventoryItems.remove(sessionId)
    }

    /**
     * Move an item by keyword (case-insensitive) from a room to a player's inventory.
     * Returns the moved item, or null if not found.
     */
    fun takeFromRoom(
        sessionId: SessionId,
        roomId: RoomId,
        keyword: String,
    ): ItemInstance? {
        val items = roomItems[roomId] ?: return null
        val idx = items.indexOfFirst { it.item.keyword.equals(keyword, ignoreCase = true) }
        if (idx < 0) return null

        val instance = items.removeAt(idx)
        inventoryItems.getOrPut(sessionId) { mutableListOf() }.add(instance)

        if (items.isEmpty()) roomItems.remove(roomId)
        return instance
    }

    /**
     * Move an item by keyword (case-insensitive) from a player's inventory to a room.
     * Returns the moved item, or null if not found.
     */
    fun dropToRoom(
        sessionId: SessionId,
        roomId: RoomId,
        keyword: String,
    ): ItemInstance? {
        val inv = inventoryItems[sessionId] ?: return null
        val idx = inv.indexOfFirst { it.item.keyword.equals(keyword, ignoreCase = true) }
        if (idx < 0) return null

        val instance = inv.removeAt(idx)
        roomItems.getOrPut(roomId) { mutableListOf() }.add(instance)

        if (inv.isEmpty()) inventoryItems.remove(sessionId)
        return instance
    }
}
