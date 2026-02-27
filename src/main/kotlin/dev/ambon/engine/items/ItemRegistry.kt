package dev.ambon.engine.items

import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
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
        for (spawn in spawns) {
            val instance = spawn.instance
            itemTemplates[instance.id] = instance
            when {
                spawn.roomId != null -> addRoomItem(spawn.roomId, instance)
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
        val templateIdsToRemove =
            itemTemplates.keys
                .filter { itemId -> idZone(itemId.value) == zone }
        for (itemId in templateIdsToRemove) {
            itemTemplates.remove(itemId)
        }

        for (spawn in spawns) {
            val instance = spawn.instance
            itemTemplates[instance.id] = instance
            when {
                spawn.roomId != null -> addRoomItem(spawn.roomId, instance)
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

    data class EquipmentBonuses(
        val attack: Int = 0,
        val armor: Int = 0,
        val strength: Int = 0,
        val dexterity: Int = 0,
        val constitution: Int = 0,
        val intelligence: Int = 0,
        val wisdom: Int = 0,
        val charisma: Int = 0,
    )

    fun equipmentBonuses(sessionId: SessionId): EquipmentBonuses {
        val equipped = equippedItems[sessionId]?.values ?: return EquipmentBonuses()
        var attack = 0
        var armor = 0
        var strength = 0
        var dexterity = 0
        var constitution = 0
        var intelligence = 0
        var wisdom = 0
        var charisma = 0
        for (inst in equipped) {
            attack += inst.item.damage
            armor += inst.item.armor
            strength += inst.item.strength
            dexterity += inst.item.dexterity
            constitution += inst.item.constitution
            intelligence += inst.item.intelligence
            wisdom += inst.item.wisdom
            charisma += inst.item.charisma
        }
        return EquipmentBonuses(attack, armor, strength, dexterity, constitution, intelligence, wisdom, charisma)
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
        var firstOccupied: EquipResult.SlotOccupied? = null

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
     * Use an item from inventory or equipment by keyword.
     */
    fun useItem(
        sessionId: SessionId,
        keyword: String,
    ): UseResult {
        val match = findOwnedItemMatch(sessionId, keyword) ?: return UseResult.NotFound
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
                val slot =
                    ItemSlot.entries.firstOrNull { candidateSlot ->
                        val equippedItem = equipped[candidateSlot] ?: return@firstOrNull false
                        mode.matches(equippedItem.item, keyword)
                    }
                if (slot != null) {
                    return MatchedOwnedItem(
                        item = equipped.getValue(slot),
                        location = HeldItemLocation.EQUIPPED,
                        slot = slot,
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

    private fun idZone(rawId: String): String = rawId.substringBefore(':', rawId)
}
