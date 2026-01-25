package dev.ambon.domain.world.load

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.World
import dev.ambon.domain.world.data.WorldFile

class WorldLoadException(
    message: String,
) : RuntimeException(message)

object WorldLoader {
    private val mapper =
        ObjectMapper(YAMLFactory())
            .registerModule(KotlinModule.Builder().build())

    fun loadFromResource(path: String): World {
        val text =
            WorldLoader::class.java.classLoader
                .getResource(path)
                ?.readText()
                ?: throw WorldLoadException("World resource not found: $path")

        val file: WorldFile = mapper.readValue(text)
        validate(file)
        return toDomain(file)
    }

    private fun validate(file: WorldFile) {
        val rooms = file.rooms
        if (rooms.isEmpty()) throw WorldLoadException("World has no rooms")

        if (!rooms.containsKey(file.startRoom)) {
            throw WorldLoadException("startRoom '${file.startRoom}' does not exist")
        }

        for ((roomIdStr, room) in rooms) {
            for ((dirStr, targetIdStr) in room.exits) {
                val dir =
                    parseDirectionOrNull(dirStr)
                        ?: throw WorldLoadException("Room '$roomIdStr' has invalid direction '$dirStr'")

                if (!rooms.containsKey(targetIdStr)) {
                    throw WorldLoadException(
                        "Room '$roomIdStr' exit '$dir' points to missing room '$targetIdStr'",
                    )
                }
            }
        }
    }

    private fun toDomain(file: WorldFile): World {
        // First pass: create Room shells
        val rooms: MutableMap<RoomId, Room> =
            file.rooms
                .map { (idStr, rf) ->
                    val id = RoomId(idStr)
                    id to
                        Room(
                            id = id,
                            title = rf.title,
                            description = rf.description,
                            // wire in second pass
                            exits = emptyMap(),
                        )
                }.toMap()
                .toMutableMap()

        // Second pass: wire exits as Direction -> RoomId
        for ((idStr, rf) in file.rooms) {
            val id = RoomId(idStr)
            val room = rooms.getValue(id)

            val exits: Map<Direction, RoomId> =
                rf.exits
                    .map { (dirStr, targetIdStr) ->
                        val dir =
                            parseDirectionOrNull(dirStr)
                                ?: throw WorldLoadException("Room '$idStr' has invalid direction '$dirStr'")
                        dir to RoomId(targetIdStr)
                    }.toMap()

            rooms[id] = room.copy(exits = exits)
        }

        return World(
            rooms = rooms,
            startRoom = RoomId(file.startRoom),
        )
    }

    private fun parseDirectionOrNull(s: String): Direction? =
        when (s.lowercase()) {
            "n", "north" -> Direction.NORTH
            "s", "south" -> Direction.SOUTH
            "e", "east" -> Direction.EAST
            "w", "west" -> Direction.WEST
            "u", "up" -> Direction.UP
            "d", "down" -> Direction.DOWN
            else -> null
        }
}
