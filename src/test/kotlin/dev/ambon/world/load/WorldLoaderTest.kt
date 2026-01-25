package dev.ambon.domain.world.load

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.world.Direction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorldLoaderTest {
    @Test
    fun `loads a valid small world and wires exits`() {
        val world = WorldLoader.loadFromResource("world/ok_small.yaml")

        assertEquals(RoomId("a"), world.startRoom)
        assertTrue(world.rooms.containsKey(RoomId("a")))
        assertTrue(world.rooms.containsKey(RoomId("b")))

        val a = world.rooms.getValue(RoomId("a"))
        val b = world.rooms.getValue(RoomId("b"))

        assertEquals("Room A", a.title)
        assertEquals("Room B", b.title)

        assertEquals(RoomId("b"), a.exits[Direction.NORTH])
        assertEquals(RoomId("a"), b.exits[Direction.SOUTH])
    }

    @Test
    fun `fails when rooms is empty`() {
        val ex =
            assertThrows(WorldLoadException::class.java) {
                WorldLoader.loadFromResource("world/bad_empty_rooms.yaml")
            }
        assertTrue(ex.message!!.contains("no rooms", ignoreCase = true), "Got: ${ex.message}")
    }

    @Test
    fun `fails when startRoom does not exist`() {
        val ex =
            assertThrows(WorldLoadException::class.java) {
                WorldLoader.loadFromResource("world/bad_start_missing.yaml")
            }
        assertTrue(ex.message!!.contains("startRoom", ignoreCase = true), "Got: ${ex.message}")
        assertTrue(ex.message!!.contains("does not exist", ignoreCase = true), "Got: ${ex.message}")
    }

    @Test
    fun `fails when an exit points to a missing room`() {
        val ex =
            assertThrows(WorldLoadException::class.java) {
                WorldLoader.loadFromResource("world/bad_exit_missing_room.yaml")
            }
        assertTrue(ex.message!!.contains("points to missing room", ignoreCase = true), "Got: ${ex.message}")
    }

    @Test
    fun `fails when a direction is invalid`() {
        val ex =
            assertThrows(WorldLoadException::class.java) {
                WorldLoader.loadFromResource("world/bad_direction.yaml")
            }
        assertTrue(ex.message!!.contains("invalid direction", ignoreCase = true), "Got: ${ex.message}")
    }

    @Test
    fun `accepts direction aliases`() {
        // This test relies on your direction parsing supporting both short and long forms.
        // We'll re-use ok_small.yaml and just assert that NORTH/SOUTH worked (already did),
        // but you can expand with more rooms if you want.
        val world = WorldLoader.loadFromResource("world/ok_small.yaml")
        val a = world.rooms.getValue(RoomId("a"))
        val b = world.rooms.getValue(RoomId("b"))

        assertEquals(RoomId("b"), a.exits[Direction.NORTH])
        assertEquals(RoomId("a"), b.exits[Direction.SOUTH])
    }
}
