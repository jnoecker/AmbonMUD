package dev.ambon.engine

import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.world.ContainerState
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.DoorState
import dev.ambon.domain.world.LeverState
import dev.ambon.domain.world.load.WorldLoader
import dev.ambon.persistence.WorldStateSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorldStateRegistryTest {
    private val world = WorldLoader.loadFromResource("world/ok_features.yaml")
    private val registry = WorldStateRegistry(world)

    @Test
    fun `initial door state comes from feature definition`() {
        val doorId = "ok_features:entrance/n"
        assertEquals(DoorState.LOCKED, registry.getDoorState(doorId))
    }

    @Test
    fun `setDoorState updates state and marks dirty`() {
        val doorId = "ok_features:entrance/n"
        assertFalse(registry.isDirty)

        registry.setDoorState(doorId, DoorState.OPEN)

        assertEquals(DoorState.OPEN, registry.getDoorState(doorId))
        assertTrue(registry.isDirty)
    }

    @Test
    fun `initial container state comes from feature definition`() {
        val containerId = "ok_features:storeroom/supply_chest"
        assertEquals(ContainerState.CLOSED, registry.getContainerState(containerId))
    }

    @Test
    fun `setContainerState updates state and marks dirty`() {
        val containerId = "ok_features:storeroom/supply_chest"

        registry.setContainerState(containerId, ContainerState.OPEN)

        assertEquals(ContainerState.OPEN, registry.getContainerState(containerId))
        assertTrue(registry.isDirty)
    }

    @Test
    fun `initial lever state comes from feature definition`() {
        val leverId = "ok_features:vault/iron_lever"
        assertEquals(LeverState.UP, registry.getLeverState(leverId))
    }

    @Test
    fun `setLeverState updates state and marks dirty`() {
        val leverId = "ok_features:vault/iron_lever"

        registry.setLeverState(leverId, LeverState.DOWN)

        assertEquals(LeverState.DOWN, registry.getLeverState(leverId))
        assertTrue(registry.isDirty)
    }

    @Test
    fun `doorOnExit finds door by room and direction`() {
        val door = registry.doorOnExit(RoomId("ok_features:entrance"), Direction.NORTH)
        assertNotNull(door)
        assertEquals("ok_features:entrance/n", door!!.id)
    }

    @Test
    fun `doorOnExit returns null when no door`() {
        val door = registry.doorOnExit(RoomId("ok_features:entrance"), Direction.EAST)
        assertNull(door)
    }

    @Test
    fun `clearDirty resets isDirty flag`() {
        registry.setDoorState("ok_features:entrance/n", DoorState.OPEN)
        assertTrue(registry.isDirty)

        registry.clearDirty()

        assertFalse(registry.isDirty)
    }

    @Test
    fun `addToContainer and getContainerContents`() {
        val containerId = "ok_features:storeroom/supply_chest"
        val item = makeItem("ok_features:silver_coin", "coin")

        registry.addToContainer(containerId, item)
        registry.clearDirty()

        val contents = registry.getContainerContents(containerId)
        assertEquals(1, contents.size)
        assertEquals("coin", contents.single().item.keyword)
    }

    @Test
    fun `removeFromContainer removes by keyword and marks dirty`() {
        val containerId = "ok_features:storeroom/supply_chest"
        val item = makeItem("ok_features:silver_coin", "coin")
        registry.addToContainer(containerId, item)
        registry.clearDirty()

        val removed = registry.removeFromContainer(containerId, "coin")

        assertNotNull(removed)
        assertTrue(registry.isDirty)
        assertTrue(registry.getContainerContents(containerId).isEmpty())
    }

    @Test
    fun `resetZone resets features with resetWithZone=true`() {
        val doorId = "ok_features:entrance/n"
        registry.setDoorState(doorId, DoorState.OPEN)
        val leverId = "ok_features:vault/iron_lever"
        registry.setLeverState(leverId, LeverState.DOWN)
        registry.clearDirty()

        registry.resetZone("ok_features")

        assertEquals(DoorState.LOCKED, registry.getDoorState(doorId))
        assertEquals(LeverState.UP, registry.getLeverState(leverId))
        assertTrue(registry.isDirty)
    }

    @Test
    fun `buildSnapshot captures current state`() {
        val doorId = "ok_features:entrance/n"
        registry.setDoorState(doorId, DoorState.OPEN)

        val snap = registry.buildSnapshot()

        assertEquals("OPEN", snap.doorStates[doorId])
    }

    @Test
    fun `applySnapshot restores persisted state`() {
        val doorId = "ok_features:entrance/n"
        val containerId = "ok_features:storeroom/supply_chest"
        val snap =
            WorldStateSnapshot(
                doorStates = mapOf(doorId to "OPEN"),
                containerStates = mapOf(containerId to "OPEN"),
                containerItems = mapOf(containerId to listOf("ok_features:silver_coin")),
            )

        registry.applySnapshot(snap) { itemId ->
            if (itemId.value == "ok_features:silver_coin") makeItem(itemId.value, "coin") else null
        }

        assertEquals(DoorState.OPEN, registry.getDoorState(doorId))
        assertEquals(ContainerState.OPEN, registry.getContainerState(containerId))
        assertEquals(1, registry.getContainerContents(containerId).size)
        assertFalse(registry.isDirty)
    }

    @Test
    fun `applySnapshot with empty container items clears contents`() {
        val containerId = "ok_features:storeroom/supply_chest"
        registry.addToContainer(containerId, makeItem("ok_features:silver_coin", "coin"))
        registry.clearDirty()

        val snap = WorldStateSnapshot(containerItems = mapOf(containerId to emptyList()))
        registry.applySnapshot(snap) { null }

        assertTrue(registry.getContainerContents(containerId).isEmpty())
    }

    @Test
    fun `findFeatureByKeyword finds by exact keyword`() {
        val feature = registry.findFeatureByKeyword(RoomId("ok_features:entrance"), "board")
        assertNotNull(feature)
        assertEquals("a notice board", feature!!.displayName)
    }

    @Test
    fun `findFeatureByKeyword returns null for unknown keyword`() {
        val feature = registry.findFeatureByKeyword(RoomId("ok_features:entrance"), "xyzzy")
        assertNull(feature)
    }

    // ---- helpers ----

    private fun makeItem(
        id: String,
        keyword: String,
    ) = ItemInstance(
        id = ItemId(id),
        item =
            Item(
                displayName = keyword,
                keyword = keyword,
                description = "",
            ),
    )
}
