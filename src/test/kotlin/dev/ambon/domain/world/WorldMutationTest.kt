package dev.ambon.domain.world

import dev.ambon.domain.DamageRange
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorldMutationTest {
    private val roomA = RoomId("zone1:roomA")
    private val roomB = RoomId("zone1:roomB")
    private val roomC = RoomId("zone2:roomC")
    private val roomD = RoomId("zone2:roomD")

    @Test
    fun `replaceAll updates rooms and returns removed IDs`() {
        val world = World(
            rooms = mapOf(
                roomA to Room(roomA, "A", "desc", emptyMap()),
                roomB to Room(roomB, "B", "desc", emptyMap()),
            ),
            startRoom = roomA,
        )

        val newWorld = World(
            rooms = mapOf(
                roomA to Room(roomA, "A updated", "new desc", emptyMap()),
                roomC to Room(roomC, "C", "zone2 room", emptyMap()),
            ),
            startRoom = roomA,
        )

        val removed = world.replaceAll(newWorld)

        assertEquals(setOf(roomB), removed)
        assertEquals("A updated", world.rooms[roomA]!!.title)
        assertNull(world.rooms[roomB])
        assertEquals("C", world.rooms[roomC]!!.title)
    }

    @Test
    fun `replaceAll updates mob spawns`() {
        val world = World(
            rooms = mapOf(roomA to Room(roomA, "A", "desc", emptyMap())),
            startRoom = roomA,
            mobSpawns = listOf(
                MobSpawn(MobId("zone1:rat"), "rat", roomA, damage = DamageRange(1, 2)),
            ),
        )

        val newWorld = World(
            rooms = mapOf(roomA to Room(roomA, "A", "desc", emptyMap())),
            startRoom = roomA,
            mobSpawns = listOf(
                MobSpawn(MobId("zone1:wolf"), "wolf", roomA, damage = DamageRange(3, 5)),
            ),
        )

        world.replaceAll(newWorld)

        assertEquals(1, world.mobSpawns.size)
        assertEquals("wolf", world.mobSpawns[0].name)
    }

    @Test
    fun `replaceAll updates item spawns`() {
        val world = World(
            rooms = mapOf(roomA to Room(roomA, "A", "desc", emptyMap())),
            startRoom = roomA,
            itemSpawns = listOf(
                ItemSpawn(ItemInstance(ItemId("zone1:sword"), Item("sword", "a sword")), roomA),
            ),
        )

        val newWorld = World(
            rooms = mapOf(roomA to Room(roomA, "A", "desc", emptyMap())),
            startRoom = roomA,
            itemSpawns = emptyList(),
        )

        world.replaceAll(newWorld)

        assertTrue(world.itemSpawns.isEmpty())
    }

    @Test
    fun `mergeZone replaces only target zone`() {
        val world = World(
            rooms = mapOf(
                roomA to Room(roomA, "A", "desc", emptyMap()),
                roomC to Room(roomC, "C", "desc", emptyMap()),
            ),
            startRoom = roomA,
            mobSpawns = listOf(
                MobSpawn(MobId("zone1:rat"), "rat", roomA, damage = DamageRange(1, 2)),
                MobSpawn(MobId("zone2:wolf"), "wolf", roomC, damage = DamageRange(3, 5)),
            ),
        )

        val sourceWorld = World(
            rooms = mapOf(
                roomA to Room(roomA, "A updated", "new desc", emptyMap()),
                roomB to Room(roomB, "B new", "new room", emptyMap()),
            ),
            startRoom = roomA,
            mobSpawns = listOf(
                MobSpawn(MobId("zone1:rat"), "big rat", roomA, maxHp = 20, damage = DamageRange(2, 4)),
            ),
        )

        val removed = world.mergeZone("zone1", sourceWorld)

        // zone1 rooms should be updated
        assertEquals("A updated", world.rooms[roomA]!!.title)
        assertEquals("B new", world.rooms[roomB]!!.title)
        // zone2 room untouched
        assertEquals("C", world.rooms[roomC]!!.title)
        // No zone1 rooms were removed (A was replaced, B was added)
        assertTrue(removed.isEmpty())
        // zone1 mobs updated, zone2 mobs untouched
        assertEquals(2, world.mobSpawns.size)
        assertTrue(world.mobSpawns.any { it.name == "big rat" })
        assertTrue(world.mobSpawns.any { it.name == "wolf" })
    }

    @Test
    fun `mergeZone returns removed room IDs`() {
        val world = World(
            rooms = mapOf(
                roomA to Room(roomA, "A", "desc", emptyMap()),
                roomB to Room(roomB, "B", "desc", emptyMap()),
            ),
            startRoom = roomA,
        )

        val sourceWorld = World(
            rooms = mapOf(
                roomA to Room(roomA, "A updated", "desc", emptyMap()),
            ),
            startRoom = roomA,
        )

        val removed = world.mergeZone("zone1", sourceWorld)

        assertEquals(setOf(roomB), removed)
        assertFalse(world.rooms.containsKey(roomB))
    }

    @Test
    fun `zones returns all unique zone names`() {
        val world = World(
            rooms = mapOf(
                roomA to Room(roomA, "A", "desc", emptyMap()),
                roomC to Room(roomC, "C", "desc", emptyMap()),
                roomD to Room(roomD, "D", "desc", emptyMap()),
            ),
            startRoom = roomA,
        )

        assertEquals(setOf("zone1", "zone2"), world.zones())
    }

    @Test
    fun `replaceAll updates startRoom`() {
        val world = World(
            rooms = mapOf(roomA to Room(roomA, "A", "desc", emptyMap())),
            startRoom = roomA,
        )
        val newWorld = World(
            rooms = mapOf(roomC to Room(roomC, "C", "desc", emptyMap())),
            startRoom = roomC,
        )

        world.replaceAll(newWorld)

        assertEquals(roomC, world.startRoom)
    }

    @Test
    fun `existing references see updated data after replaceAll`() {
        val world = World(
            rooms = mapOf(roomA to Room(roomA, "A", "desc", emptyMap())),
            startRoom = roomA,
        )
        // Simulate a handler holding a reference to world.rooms
        val roomsRef = world.rooms

        val newWorld = World(
            rooms = mapOf(roomA to Room(roomA, "A updated", "new desc", emptyMap())),
            startRoom = roomA,
        )
        world.replaceAll(newWorld)

        // The reference should see the updated data
        assertEquals("A updated", roomsRef[roomA]!!.title)
    }
}
