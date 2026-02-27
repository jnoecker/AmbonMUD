package dev.ambon.domain.world.load

import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.world.ContainerState
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.DoorState
import dev.ambon.domain.world.LeverState
import dev.ambon.domain.world.RoomFeature
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorldLoaderFeaturesTest {
    @Test
    fun `backward-compatible string exits still work`() {
        val world = dev.ambon.test.TestWorlds.okSmall
        val a = world.rooms.getValue(RoomId("ok_small:a"))
        assertEquals(RoomId("ok_small:b"), a.exits[Direction.NORTH])
    }

    @Test
    fun `door attached to exit is parsed correctly`() {
        val world = dev.ambon.test.TestWorlds.okFeatures
        val entrance = world.rooms.getValue(RoomId("ok_features:entrance"))

        // Exit should still wire up to vault
        assertEquals(RoomId("ok_features:vault"), entrance.exits[Direction.NORTH])

        // Door feature should be attached to entrance room
        val door = entrance.features.filterIsInstance<RoomFeature.Door>().single()
        assertEquals(Direction.NORTH, door.direction)
        assertEquals(DoorState.LOCKED, door.initialState)
        assertEquals(ItemId("ok_features:brass_key"), door.keyItemId)
        assertFalse(door.keyConsumed)
        assertTrue(door.resetWithZone)
        assertEquals(RoomId("ok_features:entrance"), door.roomId)
    }

    @Test
    fun `container feature is parsed correctly`() {
        val world = dev.ambon.test.TestWorlds.okFeatures
        val storeroom = world.rooms.getValue(RoomId("ok_features:storeroom"))

        val container = storeroom.features.filterIsInstance<RoomFeature.Container>().single()
        assertEquals("a supply chest", container.displayName)
        assertEquals("chest", container.keyword)
        assertEquals(ContainerState.CLOSED, container.initialState)
        assertNull(container.keyItemId)
        assertFalse(container.keyConsumed)
        assertTrue(container.resetWithZone)
        assertEquals(listOf(ItemId("ok_features:silver_coin")), container.initialItems)
        assertEquals(RoomId("ok_features:storeroom"), container.roomId)
    }

    @Test
    fun `lever feature is parsed correctly`() {
        val world = dev.ambon.test.TestWorlds.okFeatures
        val vault = world.rooms.getValue(RoomId("ok_features:vault"))

        val lever = vault.features.filterIsInstance<RoomFeature.Lever>().single()
        assertEquals("an iron lever", lever.displayName)
        assertEquals("lever", lever.keyword)
        assertEquals(LeverState.UP, lever.initialState)
        assertTrue(lever.resetWithZone)
    }

    @Test
    fun `sign feature is parsed correctly`() {
        val world = dev.ambon.test.TestWorlds.okFeatures
        val entrance = world.rooms.getValue(RoomId("ok_features:entrance"))

        val sign = entrance.features.filterIsInstance<RoomFeature.Sign>().single()
        assertEquals("a notice board", sign.displayName)
        assertEquals("board", sign.keyword)
        assertEquals("Welcome to the test zone.", sign.text)
    }

    @Test
    fun `door feature ID uses zone-room-dir format`() {
        val world = dev.ambon.test.TestWorlds.okFeatures
        val entrance = world.rooms.getValue(RoomId("ok_features:entrance"))
        val door = entrance.features.filterIsInstance<RoomFeature.Door>().single()

        assertEquals("ok_features:entrance/n", door.id)
    }

    @Test
    fun `container feature ID uses zone-room-localId format`() {
        val world = dev.ambon.test.TestWorlds.okFeatures
        val storeroom = world.rooms.getValue(RoomId("ok_features:storeroom"))
        val container = storeroom.features.filterIsInstance<RoomFeature.Container>().single()

        assertEquals("ok_features:storeroom/supply_chest", container.id)
    }

    @Test
    fun `feature validation rejects unknown keyItemId`() {
        val ex =
            org.junit.jupiter.api.Assertions.assertThrows(WorldLoadException::class.java) {
                WorldLoader.loadFromResource("world/bad_feature_key_missing.yaml")
            }
        assertTrue(ex.message!!.contains("unknown", ignoreCase = true), "Got: ${ex.message}")
    }
}
