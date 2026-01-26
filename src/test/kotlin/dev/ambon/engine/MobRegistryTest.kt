package dev.ambon.engine

import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.mob.MobState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MobRegistryTest {
    @Test
    fun `upsert adds new mob and tracks room membership`() {
        val registry = MobRegistry()
        val roomId = RoomId("zone:room1")
        val mob = MobState(MobId("demo:rat"), "a rat", roomId)

        registry.upsert(mob)

        assertEquals(listOf(mob), registry.all())
        assertEquals(listOf(mob), registry.mobsInRoom(roomId))
    }

    @Test
    fun `upsert updates mob name and moves rooms`() {
        val registry = MobRegistry()
        val roomA = RoomId("zone:roomA")
        val roomB = RoomId("zone:roomB")
        val mobId = MobId("demo:wolf")

        registry.upsert(MobState(mobId, "a wolf", roomA))
        registry.upsert(MobState(mobId, "a hungry wolf", roomB))

        val updated = registry.all().single()
        assertEquals("a hungry wolf", updated.name)
        assertEquals(roomB, updated.roomId)
        assertTrue(registry.mobsInRoom(roomA).isEmpty())
        assertEquals(listOf(updated), registry.mobsInRoom(roomB))
    }

    @Test
    fun `moveTo updates room membership`() {
        val registry = MobRegistry()
        val roomA = RoomId("zone:roomA")
        val roomB = RoomId("zone:roomB")
        val mob = MobState(MobId("demo:owl"), "an owl", roomA)

        registry.upsert(mob)
        registry.moveTo(mob.id, roomB)

        assertEquals(roomB, mob.roomId)
        assertTrue(registry.mobsInRoom(roomA).isEmpty())
        assertEquals(listOf(mob), registry.mobsInRoom(roomB))
    }
}
