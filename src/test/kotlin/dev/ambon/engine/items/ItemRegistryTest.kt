package dev.ambon.engine.items

import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.items.ItemSlot
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
