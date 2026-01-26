package dev.ambon.engine.items

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item

class ItemRegistry {
    private val roomItems = mutableMapOf<RoomId, MutableList<Item>>()
    private val inventoryItems = mutableMapOf<SessionId, MutableList<Item>>()

    fun clearRoom(roomId: RoomId) {
        roomItems.remove(roomId)
    }

    fun setRoomItems(
        roomId: RoomId,
        items: List<Item>,
    ) {
        roomItems[roomId] = items.toMutableList()
    }

    fun itemsInRoom(roomId: RoomId): List<Item> = roomItems[roomId]?.toList() ?: emptyList()

    fun inventory(sessionId: SessionId): List<Item> = inventoryItems[sessionId]?.toList() ?: emptyList()

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
    ): Item? {
        val items = roomItems[roomId] ?: return null
        val idx = items.indexOfFirst { it.keyword.equals(keyword, ignoreCase = true) }
        if (idx < 0) return null

        val item = items.removeAt(idx)
        inventoryItems.getOrPut(sessionId) { mutableListOf() }.add(item)

        if (items.isEmpty()) roomItems.remove(roomId)
        return item
    }

    /**
     * Move an item by keyword (case-insensitive) from a player's inventory to a room.
     * Returns the moved item, or null if not found.
     */
    fun dropToRoom(
        sessionId: SessionId,
        roomId: RoomId,
        keyword: String,
    ): Item? {
        val inv = inventoryItems[sessionId] ?: return null
        val idx = inv.indexOfFirst { it.keyword.equals(keyword, ignoreCase = true) }
        if (idx < 0) return null

        val item = inv.removeAt(idx)
        roomItems.getOrPut(roomId) { mutableListOf() }.add(item)

        if (inv.isEmpty()) inventoryItems.remove(sessionId)
        return item
    }
}
