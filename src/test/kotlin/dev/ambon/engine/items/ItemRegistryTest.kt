package dev.ambon.engine.items

import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.world.ItemSpawn
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
