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

/**
 * Movement-broadcast phrase for a departure in this direction.
 * Used to build lines like `"Bob exits to the north."` or `"a goblin flees upward."`.
 */
fun Direction.exitPhrase(): String =
    when (this) {
        Direction.NORTH -> "to the north"
        Direction.SOUTH -> "to the south"
        Direction.EAST -> "to the east"
        Direction.WEST -> "to the west"
        Direction.UP -> "upward"
        Direction.DOWN -> "downward"
    }

/**
 * Movement-broadcast phrase for an arrival from this direction (i.e. where the arriver came from).
 * Used to build lines like `"Bob arrives from the south."` or `"a goblin arrives from above."`.
 */
fun Direction.arrivePhrase(): String =
    when (this) {
        Direction.NORTH -> "from the north"
        Direction.SOUTH -> "from the south"
        Direction.EAST -> "from the east"
        Direction.WEST -> "from the west"
        Direction.UP -> "from above"
        Direction.DOWN -> "from below"
    }
