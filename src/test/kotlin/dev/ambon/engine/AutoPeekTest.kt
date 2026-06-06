package dev.ambon.engine

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.LockableState
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.RoomFeature
import dev.ambon.domain.world.World
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AutoPeekTest {
    private fun room(
        id: String,
        title: String,
        exits: Map<Direction, RoomId> = emptyMap(),
        remoteExits: Set<Direction> = emptySet(),
        features: List<RoomFeature> = emptyList(),
    ) = Room(
        id = RoomId(id),
        title = title,
        description = "desc",
        exits = exits,
        remoteExits = remoteExits,
        features = features,
    )

    private fun door(
        roomId: String,
        direction: Direction,
        state: LockableState,
    ) = RoomFeature.Door(
        id = "$roomId/${direction.name.lowercase()}",
        roomId = RoomId(roomId),
        displayName = "a door",
        keyword = "door",
        direction = direction,
        initialState = state,
        keyItemId = null,
        keyConsumed = false,
        resetWithZone = false,
    )

    @Test
    fun `resolves destination titles in fixed direction order`() {
        val mill = room("z:mill", "The Old Mill")
        val river = room("z:river", "The Riverbank")
        val attic = room("z:attic", "The Attic")
        val center = room(
            "z:center",
            "Center",
            exits = mapOf(
                // Deliberately out of order — output must follow Direction order.
                Direction.UP to attic.id,
                Direction.SOUTH to river.id,
                Direction.NORTH to mill.id,
            ),
        )
        val world = World(
            rooms = listOf(center, mill, river, attic).associateBy { it.id },
            startRoom = center.id,
        )

        val peeks = buildPeekExits(center, world, worldState = null)

        assertEquals(
            listOf(
                PeekExit(Direction.NORTH, "The Old Mill"),
                PeekExit(Direction.SOUTH, "The Riverbank"),
                PeekExit(Direction.UP, "The Attic"),
            ),
            peeks,
        )
    }

    @Test
    fun `exits behind closed or locked doors are omitted`() {
        val mill = room("z:mill", "The Old Mill")
        val vault = room("z:vault", "The Vault")
        val cellar = room("z:cellar", "The Cellar")
        val center = room(
            "z:center",
            "Center",
            exits = mapOf(
                Direction.NORTH to mill.id,
                Direction.EAST to vault.id,
                Direction.DOWN to cellar.id,
            ),
            features = listOf(
                door("z:center", Direction.EAST, LockableState.LOCKED),
                door("z:center", Direction.DOWN, LockableState.CLOSED),
                door("z:center", Direction.NORTH, LockableState.OPEN),
            ),
        )
        val world = World(
            rooms = listOf(center, mill, vault, cellar).associateBy { it.id },
            startRoom = center.id,
        )

        val peeks = buildPeekExits(center, world, worldState = null)

        assertEquals(listOf(PeekExit(Direction.NORTH, "The Old Mill")), peeks)
    }

    @Test
    fun `unloaded cross-zone targets fall back to generic phrasing`() {
        val center = room(
            "z:center",
            "Center",
            exits = mapOf(Direction.EAST to RoomId("otherzone:gate")),
            remoteExits = setOf(Direction.EAST),
        )
        val world = World(rooms = mapOf(center.id to center), startRoom = center.id)

        val peeks = buildPeekExits(center, world, worldState = null)

        assertEquals(listOf(PeekExit(Direction.EAST, "a path leading onward")), peeks)
    }

    @Test
    fun `formats a single exit`() {
        assertEquals(
            "You see The Old Mill to the north.",
            formatPeekLine(listOf(PeekExit(Direction.NORTH, "The Old Mill"))),
        )
    }

    @Test
    fun `formats two exits with and`() {
        assertEquals(
            "You see The Old Mill to the north and The Riverbank to the south.",
            formatPeekLine(
                listOf(
                    PeekExit(Direction.NORTH, "The Old Mill"),
                    PeekExit(Direction.SOUTH, "The Riverbank"),
                ),
            ),
        )
    }

    @Test
    fun `formats three or more exits with oxford comma and vertical phrasing`() {
        assertEquals(
            "You see The Old Mill to the north, The Attic above you, and The Cellar below you.",
            formatPeekLine(
                listOf(
                    PeekExit(Direction.NORTH, "The Old Mill"),
                    PeekExit(Direction.UP, "The Attic"),
                    PeekExit(Direction.DOWN, "The Cellar"),
                ),
            ),
        )
    }
}
