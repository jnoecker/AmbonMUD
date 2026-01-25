package dev.ambon.domain.world

import dev.ambon.domain.ids.RoomId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DomainTest {
    @Test
    fun `Room data class properties`() {
        val id = RoomId(1)
        val room = Room(id, "Title", "Desc", mapOf(Direction.NORTH to RoomId(2)))
        assertEquals(id, room.id)
        assertEquals("Title", room.title)
        assertEquals("Desc", room.description)
        assertEquals(RoomId(2), room.exits[Direction.NORTH])
    }

    @Test
    fun `World data class properties`() {
        val r1 = Room(RoomId(1), "R1", "D1", emptyMap())
        val world = World(mapOf(r1.id to r1), r1.id)
        assertEquals(1, world.rooms.size)
        assertEquals(r1.id, world.startRoom)
    }

    @Test
    fun `WorldFactory creates demo world with expected rooms`() {
        val world = WorldFactory.demoWorld()
        assertEquals(RoomId(1), world.startRoom)
        assertEquals(3, world.rooms.size)

        val foyer = world.rooms[RoomId(1)]
        assertEquals("The Foyer", foyer?.title)
        assertEquals(2, foyer?.exits?.size)
    }
}
