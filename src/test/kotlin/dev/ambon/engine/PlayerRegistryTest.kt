package dev.ambon.engine

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlayerRegistryTest {
    private val startRoom = RoomId(1)
    private val otherRoom = RoomId(2)

    @Test
    fun `connect assigns a default name and places player in start room`() {
        val registry = PlayerRegistry(startRoom)
        val sid = SessionId(101L)
        registry.connect(sid)

        val state = registry.get(sid)
        assertNotNull(state)
        assertEquals("Player1", state?.name)
        assertEquals(startRoom, state?.roomId)
        assertTrue(registry.membersInRoom(startRoom).contains(sid))
    }

    @Test
    fun `disconnect removes player from registry and room`() {
        val registry = PlayerRegistry(startRoom)
        val sid = SessionId(101L)
        registry.connect(sid)

        registry.disconnect(sid)
        assertNull(registry.get(sid))
        assertFalse(registry.membersInRoom(startRoom).contains(sid))
    }

    @Test
    fun `moveTo updates player room and room members`() {
        val registry = PlayerRegistry(startRoom)
        val sid = SessionId(101L)
        registry.connect(sid)

        registry.moveTo(sid, otherRoom)

        val state = registry.get(sid)
        assertEquals(otherRoom, state?.roomId)
        assertFalse(registry.membersInRoom(startRoom).contains(sid))
        assertTrue(registry.membersInRoom(otherRoom).contains(sid))
    }

    @Test
    fun `rename updates name and index`() {
        val registry = PlayerRegistry(startRoom)
        val sid = SessionId(101L)
        registry.connect(sid)

        val result = registry.rename(sid, "NewName")
        assertEquals(RenameResult.Ok, result)
        assertEquals("NewName", registry.get(sid)?.name)
        assertEquals(sid, registry.findSessionByName("newname"))
        assertEquals(sid, registry.findSessionByName("NEWNAME "))
    }

    @Test
    fun `rename rejects invalid names`() {
        val registry = PlayerRegistry(startRoom)
        val sid = SessionId(101L)
        registry.connect(sid)

        assertEquals(RenameResult.Invalid, registry.rename(sid, "a")) // too short
        assertEquals(RenameResult.Invalid, registry.rename(sid, "1startwithdigit"))
        assertEquals(RenameResult.Invalid, registry.rename(sid, "name with space"))
        assertEquals(RenameResult.Invalid, registry.rename(sid, "verylongnameexceedinglimit"))
    }

    @Test
    fun `rename rejects taken names`() {
        val registry = PlayerRegistry(startRoom)
        val sid1 = SessionId(101L)
        val sid2 = SessionId(102L)
        registry.connect(sid1)
        registry.connect(sid2)

        val name1 = registry.get(sid1)!!.name
        assertEquals(RenameResult.Taken, registry.rename(sid2, name1))

        registry.rename(sid1, "UniqueOne")
        assertEquals(RenameResult.Ok, registry.rename(sid2, name1)) // now it should be free
    }

    @Test
    fun `allPlayers returns all connected players`() {
        val registry = PlayerRegistry(startRoom)
        registry.connect(SessionId(1))
        registry.connect(SessionId(2))

        assertEquals(2, registry.allPlayers().size)
    }
}
