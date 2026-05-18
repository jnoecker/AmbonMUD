package dev.ambon.engine.items

import dev.ambon.domain.StatMap
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.ids.idZone
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.items.ItemSlot
import dev.ambon.domain.world.ItemSpawn

class ItemRegistry {
    private val roomItems = mutableMapOf<RoomId, MutableList<ItemInstance>>()
    private val inventoryItems = mutableMapOf<SessionId, MutableList<ItemInstance>>()
    private val mobItems = mutableMapOf<MobId, MutableList<ItemInstance>>()
    private val unplacedItems = mutableMapOf<ItemId, ItemInstance>()
    private val itemTemplates = mutableMapOf<ItemId, ItemInstance>()
    private val equippedItems = mutableMapOf<SessionId, MutableMap<ItemSlot, ItemInstance>>()

    sealed interface EquipResult {
        data class Equipped(
            val item: ItemInstance,
            val slot: ItemSlot,
        ) : EquipResult

        /**
         * Returned when the target slot was already filled and the registry
         * automatically unequipped [previousItem] to make room for [item].
         * The previous item is moved back into the inventory as part of the
         * same atomic operation.
         */
        data class Swapped(
            val item: ItemInstance,
            val previousItem: ItemInstance,
            val slot: ItemSlot,
        ) : EquipResult

        data object NotFound : EquipResult

        data class NotWearable(
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

    enum class HeldItemLocation {
        INVENTORY,
        EQUIPPED,
    }

    sealed interface UseResult {
        data class Used(
            val item: ItemInstance,
            val location: HeldItemLocation,
            val consumed: Boolean,
            val remainingCharges: Int?,
        ) : UseResult

        data object NotFound : UseResult

        data class NotUsable(
            val item: ItemInstance,
        ) : UseResult

        data class NoCharges(
            val item: ItemInstance,
        ) : UseResult
    }

    sealed interface GiveResult {
        data class Given(
            val item: ItemInstance,
            val location: HeldItemLocation,
        ) : GiveResult

        data object NotFound : GiveResult
    }

    private data class MatchedOwnedItem(
        val item: ItemInstance,
        val location: HeldItemLocation,
        val index: Int? = null,
        val slot: ItemSlot? = null,
    )

    fun loadSpawns(spawns: List<ItemSpawn>) {
        roomItems.clear()
        mobItems.clear()
        unplacedItems.clear()
        itemTemplates.clear()
        placeSpawns(spawns)
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
        val templateIdsToRemove =
            itemTemplates.keys
                .filter { itemId -> idZone(itemId.value) == zone }
        for (itemId in templateIdsToRemove) {
            itemTemplates.remove(itemId)
        }

        placeSpawns(spawns)
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

    data class EquipmentBonuses(
        val attack: Int = 0,
        val armor: Int = 0,
        val stats: StatMap = StatMap.EMPTY,
    )

    fun equipmentBonuses(sessionId: SessionId): EquipmentBonuses {
        val equipped = equippedItems[sessionId]?.values ?: return EquipmentBonuses()
        var attack = 0
        var armor = 0
        var stats = StatMap.EMPTY
        for (inst in equipped) {
            attack += inst.item.damage
            armor += inst.item.armor
            stats = stats + inst.item.stats
        }
        return EquipmentBonuses(attack, armor, stats)
    }

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

    /**
     * Instantiate a template item and place it in the room as a mob loot drop.
     */
    fun placeMobDrop(
        itemId: ItemId,
        roomId: RoomId,
    ): ItemInstance? {
        val template = itemTemplates[itemId] ?: return null
        val dropped =
            ItemInstance(
                id = template.id,
                item = template.item.copy(),
            )
        addRoomItem(roomId, dropped)
        return dropped
    }

    fun addUnplacedItem(
        itemId: ItemId,
        item: ItemInstance,
    ) {
        unplacedItems[itemId] = item
    }

    fun getTemplate(itemId: ItemId): dev.ambon.domain.items.Item? = itemTemplates[itemId]?.item

    fun createFromTemplate(itemId: ItemId): ItemInstance? {
        val template = itemTemplates[itemId] ?: return null
        return ItemInstance(id = template.id, item = template.item.copy())
    }

    fun removeFromInventory(
        sessionId: SessionId,
        keyword: String,
    ): ItemInstance? {
        val inv = inventoryItems[sessionId] ?: return null
        val idx = findMatchingItemIndex(inv, keyword)
        if (idx < 0) return null
        val removed = inv.removeAt(idx)
        if (inv.isEmpty()) inventoryItems.remove(sessionId)
        return removed
    }

    /** Removes one item matching [itemId] from inventory. Returns the removed instance, or null. */
    fun removeFromInventoryById(
        sessionId: SessionId,
        itemId: ItemId,
    ): ItemInstance? {
        val inv = inventoryItems[sessionId] ?: return null
        val idx = inv.indexOfFirst { it.id == itemId }
        if (idx < 0) return null
        val removed = inv.removeAt(idx)
        if (inv.isEmpty()) inventoryItems.remove(sessionId)
        return removed
    }

    /** Replaces an inventory item in-place (same ID, modified item data). Returns true if found. */
    fun replaceInInventory(
        sessionId: SessionId,
        updated: ItemInstance,
    ): Boolean {
        val inv = inventoryItems[sessionId] ?: return false
        val idx = inv.indexOfFirst { it.id == updated.id }
        if (idx < 0) return false
        inv[idx] = updated
        return true
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
     * Add an item directly to a player's inventory (used by handoff).
     */
    fun addToInventory(
        sessionId: SessionId,
        item: ItemInstance,
    ) {
        inventoryItems.getOrPut(sessionId) { mutableListOf() }.add(item)
    }

    /**
     * Set an equipped item in a specific slot (used by handoff).
     */
    fun setEquippedItem(
        sessionId: SessionId,
        slot: ItemSlot,
        item: ItemInstance,
    ) {
        equippedItems.getOrPut(sessionId) { mutableMapOf() }[slot] = item
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
        for (mode in itemMatchModes(keyword)) {
            val result = equipFromInventoryWithMatcher(sessionId, keyword, mode)
            if (result !is EquipResult.NotFound) return result
        }
        return EquipResult.NotFound
    }

    private fun equipFromInventoryWithMatcher(
        sessionId: SessionId,
        keyword: String,
        mode: ItemMatchMode,
    ): EquipResult {
        val inv = inventoryItems[sessionId] ?: return EquipResult.NotFound
        var firstNonWearable: ItemInstance? = null
        var firstOccupiedIdx: Int = -1

        // Pass 1: prefer a matching item whose target slot is empty. Only if no
        // such item exists do we fall through to auto-swap.
        for ((idx, instance) in inv.withIndex()) {
            if (!mode.matches(instance.item, keyword)) continue

            val slot = instance.item.slot
            if (slot == null) {
                if (firstNonWearable == null) firstNonWearable = instance
                continue
            }

            val equipped = equippedItems.getOrPut(sessionId) { mutableMapOf() }
            val existing = equipped[slot]
            if (existing != null) {
                if (firstOccupiedIdx < 0) firstOccupiedIdx = idx
                continue
            }

            inv.removeAt(idx)
            if (inv.isEmpty()) inventoryItems.remove(sessionId)

            equipped[slot] = instance
            return EquipResult.Equipped(instance, slot)
        }

        // Pass 2: no free slot among matches — auto-swap with the first occupied.
        if (firstOccupiedIdx >= 0) {
            val instance = inv[firstOccupiedIdx]
            val slot = instance.item.slot!!
            val equipped = equippedItems.getOrPut(sessionId) { mutableMapOf() }
            val previous = equipped.getValue(slot)

            inv.removeAt(firstOccupiedIdx)
            equipped[slot] = instance
            inventoryItems.getOrPut(sessionId) { mutableListOf() }.add(previous)

            return EquipResult.Swapped(item = instance, previousItem = previous, slot = slot)
        }

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
     * Use a specific owned item by [itemId]. Same semantics as [useItem] but
     * resolves the item by id rather than keyword, so callers (e.g. quickheal)
     * can target one exact inventory entry.
     */
    fun useItemById(
        sessionId: SessionId,
        itemId: ItemId,
    ): UseResult {
        val match = findOwnedItemMatchById(sessionId, itemId) ?: return UseResult.NotFound
        return applyUse(sessionId, match)
    }

    /**
     * Use an item from inventory or equipment by keyword.
     */
    fun useItem(
        sessionId: SessionId,
        keyword: String,
    ): UseResult {
        val match = findOwnedItemMatch(sessionId, keyword) ?: return UseResult.NotFound
        return applyUse(sessionId, match)
    }

    private fun applyUse(
        sessionId: SessionId,
        match: MatchedOwnedItem,
    ): UseResult {
        if (match.item.item.onUse == null) return UseResult.NotUsable(match.item)

        val currentCharges = match.item.item.charges
        if (currentCharges != null && currentCharges <= 0) {
            return UseResult.NoCharges(match.item)
        }

        val nextCharges = currentCharges?.minus(1)
        val shouldConsume =
            match.item.item.consumable &&
                (
                    nextCharges == null ||
                        nextCharges <= 0
                )

        if (shouldConsume) {
            removeOwnedItem(sessionId, match)
            return UseResult.Used(
                item = match.item,
                location = match.location,
                consumed = true,
                remainingCharges = nextCharges?.coerceAtLeast(0),
            )
        }

        val updatedItem =
            if (currentCharges != null) {
                match.item.copy(item = match.item.item.copy(charges = nextCharges))
            } else {
                match.item
            }
        storeOwnedItem(sessionId, match, updatedItem)
        return UseResult.Used(
            item = updatedItem,
            location = match.location,
            consumed = false,
            remainingCharges = nextCharges,
        )
    }

    /**
     * Move an item from one player to another, searching inventory first and equipment second.
     */
    fun giveToPlayer(
        fromSessionId: SessionId,
        toSessionId: SessionId,
        keyword: String,
    ): GiveResult {
        val match = findOwnedItemMatch(fromSessionId, keyword) ?: return GiveResult.NotFound
        val moved = removeOwnedItem(fromSessionId, match)
        inventoryItems.getOrPut(toSessionId) { mutableListOf() }.add(moved)
        return GiveResult.Given(item = moved, location = match.location)
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
        val idx = findMatchingItemIndex(items, keyword)
        if (idx < 0) return null

        val instance = items.removeAt(idx)
        inventoryItems.getOrPut(sessionId) { mutableListOf() }.add(instance)

        if (items.isEmpty()) roomItems.remove(roomId)
        return instance
    }

    /**
     * Move the specific [instance] from a room to a player's inventory by identity.
     * Returns the instance if it was found and moved, or null if it was no longer in the room.
     * Used by auto-loot so a stray same-keyword pickup can't accidentally grab an unrelated item.
     */
    fun takeFromRoomByInstance(
        sessionId: SessionId,
        roomId: RoomId,
        instance: ItemInstance,
    ): ItemInstance? {
        val items = roomItems[roomId] ?: return null
        val idx = items.indexOfFirst { it === instance }
        if (idx < 0) return null

        val taken = items.removeAt(idx)
        inventoryItems.getOrPut(sessionId) { mutableListOf() }.add(taken)

        if (items.isEmpty()) roomItems.remove(roomId)
        return taken
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
        val idx = findMatchingItemIndex(inv, keyword)
        if (idx < 0) return null

        val instance = inv.removeAt(idx)
        roomItems.getOrPut(roomId) { mutableListOf() }.add(instance)

        if (inv.isEmpty()) inventoryItems.remove(sessionId)
        return instance
    }

    /**
     * Look up (without removing) an item owned by [sessionId] (inventory or equipped)
     * matching [keyword]. Used by handlers to pre-validate actions (e.g. quest-item guards).
     */
    fun peekOwnedItem(
        sessionId: SessionId,
        keyword: String,
    ): ItemInstance? = findOwnedItemMatch(sessionId, keyword)?.item

    /**
     * Look up (without removing) an item in [sessionId]'s inventory only (not equipped)
     * matching [keyword]. Used by handlers that apply only to carried items.
     */
    fun peekInventoryItem(
        sessionId: SessionId,
        keyword: String,
    ): ItemInstance? {
        val inv = inventoryItems[sessionId] ?: return null
        val idx = findMatchingItemIndex(inv, keyword)
        return if (idx >= 0) inv[idx] else null
    }

    private fun findOwnedItemMatchById(
        sessionId: SessionId,
        itemId: ItemId,
    ): MatchedOwnedItem? {
        val inv = inventoryItems[sessionId]
        if (inv != null) {
            val invIdx = inv.indexOfFirst { it.id == itemId }
            if (invIdx >= 0) {
                return MatchedOwnedItem(
                    item = inv[invIdx],
                    location = HeldItemLocation.INVENTORY,
                    index = invIdx,
                )
            }
        }
        val equipped = equippedItems[sessionId]
        if (equipped != null) {
            val entry = equipped.entries.firstOrNull { (_, equippedItem) -> equippedItem.id == itemId }
            if (entry != null) {
                return MatchedOwnedItem(
                    item = entry.value,
                    location = HeldItemLocation.EQUIPPED,
                    slot = entry.key,
                )
            }
        }
        return null
    }

    private fun findOwnedItemMatch(
        sessionId: SessionId,
        keyword: String,
    ): MatchedOwnedItem? {
        val inv = inventoryItems[sessionId]
        val equipped = equippedItems[sessionId]

        for (mode in itemMatchModes(keyword)) {
            if (inv != null) {
                val invIdx = inv.indexOfFirst { mode.matches(it.item, keyword) }
                if (invIdx >= 0) {
                    return MatchedOwnedItem(
                        item = inv[invIdx],
                        location = HeldItemLocation.INVENTORY,
                        index = invIdx,
                    )
                }
            }

            if (equipped != null) {
                val entry = equipped.entries.firstOrNull { (_, equippedItem) ->
                    mode.matches(equippedItem.item, keyword)
                }
                if (entry != null) {
                    return MatchedOwnedItem(
                        item = entry.value,
                        location = HeldItemLocation.EQUIPPED,
                        slot = entry.key,
                    )
                }
            }
        }

        return null
    }

    private fun removeOwnedItem(
        sessionId: SessionId,
        match: MatchedOwnedItem,
    ): ItemInstance =
        when (match.location) {
            HeldItemLocation.INVENTORY -> {
                val inv = inventoryItems[sessionId] ?: return match.item
                val idx = match.index ?: return match.item
                if (idx !in inv.indices) return match.item
                val removed = inv.removeAt(idx)
                if (inv.isEmpty()) inventoryItems.remove(sessionId)
                removed
            }

            HeldItemLocation.EQUIPPED -> {
                val equipped = equippedItems[sessionId] ?: return match.item
                val slot = match.slot ?: return match.item
                val removed = equipped.remove(slot) ?: match.item
                if (equipped.isEmpty()) equippedItems.remove(sessionId)
                removed
            }
        }

    private fun storeOwnedItem(
        sessionId: SessionId,
        match: MatchedOwnedItem,
        item: ItemInstance,
    ) {
        when (match.location) {
            HeldItemLocation.INVENTORY -> {
                val inv = inventoryItems[sessionId] ?: return
                val idx = match.index ?: return
                if (idx !in inv.indices) return
                inv[idx] = item
            }

            HeldItemLocation.EQUIPPED -> {
                val equipped = equippedItems[sessionId] ?: return
                val slot = match.slot ?: return
                if (!equipped.containsKey(slot)) return
                equipped[slot] = item
            }
        }
    }

    /**
     * Updates item templates from [spawns] and places items for genuinely new template IDs only.
     * Existing room/inventory/equipped items are untouched.
     * Returns the list of spawns that were newly added (template ID didn't exist before).
     */
    fun updateTemplates(spawns: List<ItemSpawn>): List<ItemSpawn> {
        val newSpawns = mutableListOf<ItemSpawn>()
        for (spawn in spawns) {
            val instance = spawn.instance
            val existed = itemTemplates.containsKey(instance.id)
            itemTemplates[instance.id] = instance
            if (!existed) {
                newSpawns.add(spawn)
                when {
                    spawn.roomId != null -> addRoomItem(spawn.roomId, instance)
                    else -> addUnplacedItem(instance.id, instance)
                }
            }
        }
        return newSpawns
    }

    private fun placeSpawns(spawns: List<ItemSpawn>) {
        for (spawn in spawns) {
            val instance = spawn.instance
            itemTemplates[instance.id] = instance
            when {
                spawn.roomId != null -> addRoomItem(spawn.roomId, instance)
                else -> addUnplacedItem(instance.id, instance)
            }
        }
    }
}
