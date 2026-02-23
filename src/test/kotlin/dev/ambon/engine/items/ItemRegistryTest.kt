package dev.ambon.engine.items

import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.items.ItemSlot
import dev.ambon.domain.items.ItemUseEffect
import dev.ambon.domain.world.ItemSpawn
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ItemRegistryTest {
    @Test
    fun `resetZone preserves foreign room items`() {
        val registry = ItemRegistry()
        val roomId = RoomId("demo:trailhead")

        registry.setRoomItems(
            roomId,
            listOf(
                instance("demo:lantern", "lantern", "a brass lantern"),
                instance("swamp:totem", "totem", "a swamp totem"),
            ),
        )

        registry.resetZone(
            zone = "demo",
            roomIds = setOf(roomId),
            mobIds = emptySet(),
            spawns =
                listOf(
                    ItemSpawn(
                        instance = instance("demo:coin", "coin", "a silver coin"),
                        roomId = roomId,
                    ),
                ),
        )

        val itemIds = registry.itemsInRoom(roomId).map { it.id.value }.toSet()
        assertEquals(2, itemIds.size)
        assertTrue(itemIds.contains("swamp:totem"))
        assertTrue(itemIds.contains("demo:coin"))
        assertFalse(itemIds.contains("demo:lantern"))
    }

    @Test
    fun `placeMobDrop instantiates template item into room`() {
        val registry = ItemRegistry()
        val roomId = RoomId("demo:trailhead")
        val itemId = ItemId("demo:coin")
        registry.loadSpawns(
            listOf(
                ItemSpawn(
                    instance = instance("demo:coin", "coin", "a silver coin"),
                ),
            ),
        )

        val dropped = registry.placeMobDrop(itemId, roomId)

        assertNotNull(dropped)
        val roomItems = registry.itemsInRoom(roomId)
        assertEquals(1, roomItems.size)
        assertEquals("demo:coin", roomItems.single().id.value)
    }

    @Test
    fun `placeMobDrop returns null when item template is missing`() {
        val registry = ItemRegistry()
        val dropped = registry.placeMobDrop(ItemId("demo:missing"), RoomId("demo:room"))
        assertNull(dropped)
    }

    // --- Substring fallback tests ---

    @Test
    fun `takeFromRoom finds item by displayName substring`() {
        val registry = ItemRegistry()
        val roomId = RoomId("demo:room1")
        val sid = SessionId(1L)
        registry.ensurePlayer(sid)
        registry.setRoomItems(roomId, listOf(instance("demo:lantern", "lantern", "a brass lantern")))

        val result = registry.takeFromRoom(sid, roomId, "lant")

        assertNotNull(result)
        assertEquals("demo:lantern", result!!.id.value)
    }

    @Test
    fun `takeFromRoom exact match takes priority over substring`() {
        val registry = ItemRegistry()
        val roomId = RoomId("demo:room1")
        val sid = SessionId(1L)
        registry.ensurePlayer(sid)
        registry.setRoomItems(
            roomId,
            listOf(
                instance("demo:lan", "lan", "a lantern"),
                instance("demo:lantern", "lantern", "a brass lantern"),
            ),
        )

        val result = registry.takeFromRoom(sid, roomId, "lan")

        assertNotNull(result)
        assertEquals("demo:lan", result!!.id.value)
    }

    @Test
    fun `takeFromRoom rejects short input less than 3 chars`() {
        val registry = ItemRegistry()
        val roomId = RoomId("demo:room1")
        val sid = SessionId(1L)
        registry.ensurePlayer(sid)
        registry.setRoomItems(roomId, listOf(instance("demo:lantern", "lantern", "a brass lantern")))

        val result = registry.takeFromRoom(sid, roomId, "la")

        assertNull(result)
    }

    @Test
    fun `takeFromRoom respects matchByKey flag`() {
        val registry = ItemRegistry()
        val roomId = RoomId("demo:room1")
        val sid = SessionId(1L)
        registry.ensurePlayer(sid)
        registry.setRoomItems(
            roomId,
            listOf(
                ItemInstance(
                    id = ItemId("demo:lantern"),
                    item = Item(keyword = "lantern", displayName = "a brass lantern", matchByKey = true),
                ),
            ),
        )

        val result = registry.takeFromRoom(sid, roomId, "lant")

        assertNull(result)
    }

    @Test
    fun `dropToRoom finds item in inventory by displayName substring`() {
        val registry = ItemRegistry()
        val roomId = RoomId("demo:room1")
        val sid = SessionId(1L)
        registry.ensurePlayer(sid)
        // Seed inventory directly via takeFromRoom round-trip
        val sourceRoom = RoomId("demo:source")
        registry.setRoomItems(sourceRoom, listOf(instance("demo:lantern", "lantern", "a brass lantern")))
        registry.takeFromRoom(sid, sourceRoom, "lantern")

        val result = registry.dropToRoom(sid, roomId, "lant")

        assertNotNull(result)
        assertEquals("demo:lantern", result!!.id.value)
    }

    @Test
    fun `equipFromInventory finds item by displayName substring`() {
        val registry = ItemRegistry()
        val sid = SessionId(1L)
        registry.ensurePlayer(sid)
        val sourceRoom = RoomId("demo:source")
        registry.setRoomItems(
            sourceRoom,
            listOf(
                ItemInstance(
                    id = ItemId("demo:helm"),
                    item = Item(keyword = "helm", displayName = "a dented helm", slot = ItemSlot.HEAD),
                ),
            ),
        )
        registry.takeFromRoom(sid, sourceRoom, "helm")

        val result = registry.equipFromInventory(sid, "dent")

        assertTrue(result is ItemRegistry.EquipResult.Equipped)
    }

    @Test
    fun `equipFromInventory respects matchByKey flag`() {
        val registry = ItemRegistry()
        val sid = SessionId(1L)
        registry.ensurePlayer(sid)
        val sourceRoom = RoomId("demo:source")
        registry.setRoomItems(
            sourceRoom,
            listOf(
                ItemInstance(
                    id = ItemId("demo:helm"),
                    item = Item(keyword = "helm", displayName = "a dented helm", slot = ItemSlot.HEAD, matchByKey = true),
                ),
            ),
        )
        registry.takeFromRoom(sid, sourceRoom, "helm")

        val result = registry.equipFromInventory(sid, "dent")

        assertTrue(result is ItemRegistry.EquipResult.NotFound)
    }

    @Test
    fun `useItem decrements charges and consumes at zero when consumable`() {
        val registry = ItemRegistry()
        val sid = SessionId(1L)
        registry.ensurePlayer(sid)
        val sourceRoom = RoomId("demo:source")
        registry.setRoomItems(
            sourceRoom,
            listOf(
                ItemInstance(
                    id = ItemId("demo:potion"),
                    item =
                        Item(
                            keyword = "potion",
                            displayName = "a potion",
                            consumable = true,
                            charges = 2,
                            onUse = ItemUseEffect(healHp = 5),
                        ),
                ),
            ),
        )
        registry.takeFromRoom(sid, sourceRoom, "potion")

        val first = registry.useItem(sid, "potion")
        assertTrue(first is ItemRegistry.UseResult.Used && !first.consumed)
        val remainingPotion = registry.inventory(sid).single()
        assertEquals(1, remainingPotion.item.charges)

        val second = registry.useItem(sid, "potion")
        assertTrue(second is ItemRegistry.UseResult.Used && second.consumed)
        assertTrue(registry.inventory(sid).isEmpty())
    }

    @Test
    fun `useItem finds equipped items with same matching rules`() {
        val registry = ItemRegistry()
        val sid = SessionId(1L)
        registry.ensurePlayer(sid)
        val sourceRoom = RoomId("demo:source")
        registry.setRoomItems(
            sourceRoom,
            listOf(
                ItemInstance(
                    id = ItemId("demo:circlet"),
                    item =
                        Item(
                            keyword = "circlet",
                            displayName = "a bright circlet",
                            slot = ItemSlot.HEAD,
                            onUse = ItemUseEffect(grantXp = 10),
                        ),
                ),
            ),
        )
        registry.takeFromRoom(sid, sourceRoom, "circlet")
        registry.equipFromInventory(sid, "circlet")

        val result = registry.useItem(sid, "bright")
        assertTrue(result is ItemRegistry.UseResult.Used)
        val used = result as ItemRegistry.UseResult.Used
        assertEquals(ItemRegistry.HeldItemLocation.EQUIPPED, used.location)
    }

    @Test
    fun `giveToPlayer can transfer equipped item`() {
        val registry = ItemRegistry()
        val fromSid = SessionId(1L)
        val toSid = SessionId(2L)
        registry.ensurePlayer(fromSid)
        registry.ensurePlayer(toSid)

        val sourceRoom = RoomId("demo:source")
        registry.setRoomItems(
            sourceRoom,
            listOf(
                ItemInstance(
                    id = ItemId("demo:helm"),
                    item = Item(keyword = "helm", displayName = "a steel helm", slot = ItemSlot.HEAD, armor = 1),
                ),
            ),
        )
        registry.takeFromRoom(fromSid, sourceRoom, "helm")
        registry.equipFromInventory(fromSid, "helm")

        val result = registry.giveToPlayer(fromSid, toSid, "helm")

        assertTrue(result is ItemRegistry.GiveResult.Given)
        val given = result as ItemRegistry.GiveResult.Given
        assertEquals(ItemRegistry.HeldItemLocation.EQUIPPED, given.location)
        assertTrue(registry.equipment(fromSid).isEmpty())
        val received = registry.inventory(toSid).single()
        assertEquals("helm", received.item.keyword)
    }

    private fun instance(
        id: String,
        keyword: String,
        displayName: String,
    ): ItemInstance =
        ItemInstance(
            id = ItemId(id),
            item =
                Item(
                    keyword = keyword,
                    displayName = displayName,
                ),
        )
}
