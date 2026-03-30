package dev.ambon.domain.world

enum class Direction { NORTH, SOUTH, EAST, WEST, UP, DOWN }

/** Returns the opposite direction. */
fun Direction.opposite(): Direction =
    when (this) {
        Direction.NORTH -> Direction.SOUTH
        Direction.SOUTH -> Direction.NORTH
        Direction.EAST -> Direction.WEST
        Direction.WEST -> Direction.EAST
        Direction.UP -> Direction.DOWN
        Direction.DOWN -> Direction.UP
    }
