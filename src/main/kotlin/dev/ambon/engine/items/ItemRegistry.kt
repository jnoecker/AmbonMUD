package dev.ambon.engine.items

import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.items.ItemSlot
import dev.ambon.domain.world.ItemSpawn

class ItemRegistry {
    private val roomItems = mutableMapOf<RoomId, MutableList<ItemInstance>>()
    private val inventoryItems = mutableMapOf<SessionId, MutableList<ItemInstance>>()
    private val mobItems = mutableMapOf<MobId, MutableList<ItemInstance>>()
    private val unplacedItems = mutableMapOf<ItemId, ItemInstance>()
    private val equippedItems = mutableMapOf<SessionId, MutableMap<ItemSlot, ItemInstance>>()

    sealed interface EquipResult {
        data class Equipped(
            val item: ItemInstance,
            val slot: ItemSlot,
        ) : EquipResult

        data object NotFound : EquipResult

        data class NotWearable(
            val item: ItemInstance,
        ) : EquipResult

        data class SlotOccupied(
            val slot: ItemSlot,
            val item: ItemInstance,
        ) : EquipResult
    }

    sealed interface UnequipResult {
        data class Unequipped(
            val item: ItemInstance,
            val slot: ItemSlot,
        ) : UnequipResult

        data class SlotEmpty(
            val slot: ItemSlot,
        ) : UnequipResult
    }

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

    fun resetZone(
        zone: String,
        roomIds: Set<RoomId>,
        mobIds: Set<MobId>,
        spawns: List<ItemSpawn>,
    ) {
        for (roomId in roomIds) {
            val current = roomItems[roomId] ?: continue
            val retained = current.filterTo(mutableListOf()) { instance -> idZone(instance.id.value) != zone }
            if (retained.isEmpty()) {
                roomItems.remove(roomId)
            } else {
                roomItems[roomId] = retained
            }
        }
        for (mobId in mobIds) {
            mobItems.remove(mobId)
        }

        val unplacedIdsToRemove =
            unplacedItems.keys
                .filter { itemId -> idZone(itemId.value) == zone }
        for (itemId in unplacedIdsToRemove) {
            unplacedItems.remove(itemId)
        }

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

    fun equipment(sessionId: SessionId): Map<ItemSlot, ItemInstance> = equippedItems[sessionId]?.toMap() ?: emptyMap()

    fun addMobItem(
        mobId: MobId,
        item: ItemInstance,
    ) {
        mobItems.getOrPut(mobId) { mutableListOf() }.add(item)
    }

    fun itemsInMob(mobId: MobId): List<ItemInstance> = mobItems[mobId]?.toList() ?: emptyList()

    fun removeMobItems(mobId: MobId) {
        mobItems.remove(mobId)
    }

    /**
     * Move all items carried by a mob into a room. Returns moved items.
     */
    fun dropMobItemsToRoom(
        mobId: MobId,
        roomId: RoomId,
    ): List<ItemInstance> {
        val items = mobItems.remove(mobId) ?: return emptyList()
        if (items.isEmpty()) return emptyList()
        roomItems.getOrPut(roomId) { mutableListOf() }.addAll(items)
        return items
    }

    fun addUnplacedItem(
        itemId: ItemId,
        item: ItemInstance,
    ) {
        unplacedItems[itemId] = item
    }

    fun unplacedItems(): Map<ItemId, ItemInstance> = unplacedItems.toMap()

    fun ensurePlayer(sessionId: SessionId) {
        inventoryItems.getOrPut(sessionId) { mutableListOf() }
        equippedItems.getOrPut(sessionId) { mutableMapOf() }
    }

    fun remapPlayer(
        oldSid: SessionId,
        newSid: SessionId,
    ) {
        inventoryItems.remove(oldSid)?.let { inventoryItems[newSid] = it }
        equippedItems.remove(oldSid)?.let { equippedItems[newSid] = it }
    }

    fun removePlayer(sessionId: SessionId) {
        inventoryItems.remove(sessionId)
        equippedItems.remove(sessionId)
    }

    /**
     * Move an item by keyword (case-insensitive) from a player's inventory to an equipment slot.
     * Falls back to substring matching on displayName/description when no exact match is found
     * and the keyword is at least 3 characters.
     */
    fun equipFromInventory(
        sessionId: SessionId,
        keyword: String,
    ): EquipResult {
        val exact = equipFromInventoryWithMatcher(sessionId) { it.keyword.equals(keyword, ignoreCase = true) }
        if (exact !is EquipResult.NotFound) return exact
        if (keyword.length < 3) return EquipResult.NotFound
        return equipFromInventoryWithMatcher(sessionId) { matchesSubstring(it, keyword) }
    }

    private fun equipFromInventoryWithMatcher(
        sessionId: SessionId,
        matcher: (Item) -> Boolean,
    ): EquipResult {
        val inv = inventoryItems[sessionId] ?: return EquipResult.NotFound
        var firstNonWearable: ItemInstance? = null
        var firstOccupied: EquipResult.SlotOccupied? = null

        for ((idx, instance) in inv.withIndex()) {
            if (!matcher(instance.item)) continue

            val slot = instance.item.slot
            if (slot == null) {
                if (firstNonWearable == null) firstNonWearable = instance
                continue
            }

            val equipped = equippedItems.getOrPut(sessionId) { mutableMapOf() }
            val existing = equipped[slot]
            if (existing != null) {
                if (firstOccupied == null) {
                    firstOccupied = EquipResult.SlotOccupied(slot, existing)
                }
                continue
            }

            inv.removeAt(idx)
            if (inv.isEmpty()) inventoryItems.remove(sessionId)

            equipped[slot] = instance
            return EquipResult.Equipped(instance, slot)
        }

        firstOccupied?.let { return it }
        firstNonWearable?.let { return EquipResult.NotWearable(it) }
        return EquipResult.NotFound
    }

    /**
     * Move an equipped item from a slot back into the player's inventory.
     */
    fun unequip(
        sessionId: SessionId,
        slot: ItemSlot,
    ): UnequipResult {
        val equipped = equippedItems[sessionId] ?: return UnequipResult.SlotEmpty(slot)
        val instance = equipped.remove(slot) ?: return UnequipResult.SlotEmpty(slot)
        if (equipped.isEmpty()) equippedItems.remove(sessionId)

        inventoryItems.getOrPut(sessionId) { mutableListOf() }.add(instance)
        return UnequipResult.Unequipped(instance, slot)
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

        var idx = items.indexOfFirst { it.item.keyword.equals(keyword, ignoreCase = true) }
        if (idx < 0 && keyword.length >= 3) {
            idx = items.indexOfFirst { matchesSubstring(it.item, keyword) }
        }
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

        var idx = inv.indexOfFirst { it.item.keyword.equals(keyword, ignoreCase = true) }
        if (idx < 0 && keyword.length >= 3) {
            idx = inv.indexOfFirst { matchesSubstring(it.item, keyword) }
        }
        if (idx < 0) return null

        val instance = inv.removeAt(idx)
        roomItems.getOrPut(roomId) { mutableListOf() }.add(instance)

        if (inv.isEmpty()) inventoryItems.remove(sessionId)
        return instance
    }

    private fun matchesSubstring(
        item: Item,
        input: String,
    ): Boolean =
        !item.matchByKey &&
            (
                item.displayName.contains(input, ignoreCase = true) ||
                    item.description.contains(input, ignoreCase = true)
            )

    private fun idZone(rawId: String): String = rawId.substringBefore(':', rawId)
}
