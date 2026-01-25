package dev.ambon.domain.world

import dev.ambon.domain.ids.RoomId

object WorldFactory {
    fun demoWorld(): World {
        val r1 =
            Room(
                id = RoomId(1),
                title = "The Foyer",
                description = "A small foyer with a dusty rug. Exits lead east and north.",
                exits = mapOf(Direction.NORTH to RoomId(2), Direction.EAST to RoomId(3)),
            )
        val r2 =
            Room(
                id = RoomId(2),
                title = "A Quiet Hallway",
                description = "The hallway is silent. You feel watched.",
                exits = mapOf(Direction.SOUTH to RoomId(1)),
            )
        val r3 =
            Room(
                id = RoomId(3),
                title = "A Sunlit Courtyard",
                description = "Warm light spills over stone tiles.",
                exits = mapOf(Direction.WEST to RoomId(1)),
            )
        return World(
            rooms = listOf(r1, r2, r3).associateBy { it.id },
            startRoom = r1.id,
        )
    }
}
