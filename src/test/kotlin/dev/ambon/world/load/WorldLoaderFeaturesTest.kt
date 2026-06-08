package dev.ambon.domain.world.load

import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.LeverState
import dev.ambon.domain.world.LockableState
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
        assertEquals(LockableState.LOCKED, door.initialState)
        assertEquals(ItemId("ok_features:brass_key"), door.keyItemId)
        assertFalse(door.keyConsumed)
        assertTrue(door.resetWithZone)
        assertEquals(RoomId("ok_features:entrance"), door.roomId)
        // Custom door art: frame + leaf image refs resolve against the image base.
        assertTrue(door.frameImage!!.endsWith("doorframe.webp"), "got=${door.frameImage}")
        assertTrue(door.leafImage!!.endsWith("doorleaf.webp"), "got=${door.leafImage}")
        assertEquals("right", door.hinge)
        assertEquals(95.0, door.openAngle)
    }

    @Test
    fun `container feature is parsed correctly`() {
        val world = dev.ambon.test.TestWorlds.okFeatures
        val storeroom = world.rooms.getValue(RoomId("ok_features:storeroom"))

        val container = storeroom.features.filterIsInstance<RoomFeature.Container>().single()
        assertEquals("a supply chest", container.displayName)
        assertEquals("chest", container.keyword)
        assertEquals(LockableState.CLOSED, container.initialState)
        assertNull(container.keyItemId)
        assertFalse(container.keyConsumed)
        assertTrue(container.resetWithZone)
        assertEquals(listOf(ItemId("ok_features:silver_coin")), container.initialItems)
        assertEquals(RoomId("ok_features:storeroom"), container.roomId)
        // Backdrop art is a content-addressed filename resolved against the image base.
        assertTrue(container.backgroundImage!!.endsWith("chestbg111.webp"), "got=${container.backgroundImage}")
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
        // No custom art authored — fields stay null so the client renders its
        // built-in vector lever (backward compatibility).
        assertNull(lever.plateImage)
        assertNull(lever.handleImage)
        assertNull(lever.leverPivot)
        assertNull(lever.upAngle)
        assertNull(lever.downAngle)
        assertNull(lever.backgroundImage)
    }

    @Test
    fun `lever with custom art is parsed and image refs are resolved`() {
        val world = dev.ambon.test.TestWorlds.okFeatures
        val entrance = world.rooms.getValue(RoomId("ok_features:entrance"))

        val lever = entrance.features.filterIsInstance<RoomFeature.Lever>().single { it.keyword == "brass" }
        assertEquals("an ostentatious brass lever", lever.displayName)
        assertEquals(LeverState.DOWN, lever.initialState)
        // Image refs are content-addressed filenames resolved against the image base.
        assertTrue(lever.plateImage!!.endsWith("plate123.webp"), "got=${lever.plateImage}")
        assertTrue(lever.handleImage!!.endsWith("handle456.webp"), "got=${lever.handleImage}")
        assertEquals(RoomFeature.LeverPivot(0.4, 0.9), lever.leverPivot)
        assertEquals(-30.0, lever.upAngle)
        assertEquals(35.0, lever.downAngle)
        assertTrue(lever.backgroundImage!!.endsWith("leverbox222.webp"), "got=${lever.backgroundImage}")
    }

    @Test
    fun `sign feature is parsed correctly`() {
        val world = dev.ambon.test.TestWorlds.okFeatures
        val entrance = world.rooms.getValue(RoomId("ok_features:entrance"))

        val sign = entrance.features.filterIsInstance<RoomFeature.Sign>().single()
        assertEquals("a notice board", sign.displayName)
        assertEquals("board", sign.keyword)
        assertEquals("Welcome to the test zone.", sign.text)
        assertTrue(sign.backgroundImage!!.endsWith("signbg789.webp"), "got=${sign.backgroundImage}")
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
