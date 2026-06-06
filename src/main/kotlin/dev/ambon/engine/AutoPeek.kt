package dev.ambon.engine

import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.LockableState
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.RoomFeature
import dev.ambon.domain.world.World

/**
 * One entry in the auto-peek line: an open exit plus the title of the room it
 * leads to. See [buildPeekExits] for how entries are selected.
 */
data class PeekExit(
    val direction: Direction,
    val title: String,
)

/**
 * Placeholder title for exits whose destination room isn't loaded on this
 * engine (cross-zone stubs in multi-instance mode).
 */
private const val REMOTE_EXIT_TITLE = "a path leading onward"

/**
 * Builds the auto-peek entries for [room]: each open exit paired with the
 * destination room's title, in fixed [Direction] order.
 *
 * Exits behind a closed or locked door are omitted — the exits line already
 * shows `north [closed]`, and hiding the destination keeps doors meaningful.
 * When [worldState] is null (callers without door-state access), the door's
 * initial state is used instead.
 */
fun buildPeekExits(
    room: Room,
    world: World,
    worldState: WorldStateRegistry?,
): List<PeekExit> = Direction.entries.mapNotNull { dir ->
    val target = room.exits[dir] ?: return@mapNotNull null
    val door =
        worldState?.doorOnExit(room.id, dir)
            ?: room.features.filterIsInstance<RoomFeature.Door>().firstOrNull { it.direction == dir }
    val doorState = door?.let { worldState?.getDoorState(it.id) ?: it.initialState }
    if (doorState != null && doorState != LockableState.OPEN) return@mapNotNull null
    val title = world.rooms[target]?.title ?: REMOTE_EXIT_TITLE
    PeekExit(direction = dir, title = title)
}

/**
 * Formats peek entries as a single sentence, e.g.
 * "You see The Old Mill to the north, The Riverbank to the south, and The Attic above you."
 *
 * Room names are wrapped in `{c:room}…{/c}` color tags — dusty rose on
 * ANSI-enabled sessions, stripped everywhere else.
 */
fun formatPeekLine(peeks: List<PeekExit>): String {
    val phrases = peeks.map { peek ->
        val where = when (peek.direction) {
            Direction.UP -> "above you"
            Direction.DOWN -> "below you"
            else -> "to the ${peek.direction.name.lowercase()}"
        }
        "{c:room}${peek.title}{/c} $where"
    }
    val joined = when (phrases.size) {
        1 -> phrases.single()
        2 -> "${phrases[0]} and ${phrases[1]}"
        else -> phrases.dropLast(1).joinToString(", ") + ", and " + phrases.last()
    }
    return "You see $joined."
}

/** Converts peek entries to the GMCP payload shape used by `Room.Info`. */
fun List<PeekExit>.toGmcpPeek(): List<GmcpEmitter.RoomPeekEntry> =
    map { GmcpEmitter.RoomPeekEntry(direction = it.direction.name.lowercase(), title = it.title) }
