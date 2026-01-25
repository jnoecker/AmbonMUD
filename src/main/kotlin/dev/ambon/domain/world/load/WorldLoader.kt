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
        val rawRooms = file.rooms
        if (rawRooms.isEmpty()) throw WorldLoadException("World has no rooms")

        val zone = file.zone.trim()
        if (zone.isEmpty()) throw WorldLoadException("World zone cannot be blank")

        // Normalize room keys once
        val roomIds: Set<RoomId> = rawRooms.keys.map { normalizeId(zone, it) }.toSet()

        val startId = normalizeId(zone, file.startRoom)
        if (!roomIds.contains(startId)) {
            throw WorldLoadException("startRoom '${file.startRoom}' does not exist (normalized as '${startId.value}')")
        }

        // Validate exits
        for ((roomKey, room) in rawRooms) {
            val fromId = normalizeId(zone, roomKey)

            for ((dirStr, targetRaw) in room.exits) {
                val dir =
                    parseDirectionOrNull(dirStr)
                        ?: throw WorldLoadException("Room '${fromId.value}' has invalid direction '$dirStr'")

                val targetId = normalizeId(zone, targetRaw)

                // For now: disallow cross-zone references until you support multi-zone loading
                if (isCrossZone(zone, targetId)) {
                    throw WorldLoadException(
                        "Room '${fromId.value}' exit '$dir' targets cross-zone room '${targetId.value}', " +
                            "but multi-zone loading is not supported yet",
                    )
                }

                if (!roomIds.contains(targetId)) {
                    throw WorldLoadException(
                        "Room '${fromId.value}' exit '$dir' points to missing room '${targetId.value}'",
                    )
                }
            }
        }
    }

    private fun toDomain(file: WorldFile): World {
        val zone = file.zone.trim()

        // First pass: create Room shells with normalized ids
        val rooms: MutableMap<RoomId, Room> =
            file.rooms
                .map { (idStr, rf) ->
                    val id = normalizeId(zone, idStr)
                    id to
                        Room(
                            id = id,
                            title = rf.title,
                            description = rf.description,
                            exits = emptyMap(),
                        )
                }.toMap()
                .toMutableMap()

        // Second pass: wire exits as Direction -> RoomId (normalized)
        for ((idStr, rf) in file.rooms) {
            val id = normalizeId(zone, idStr)
            val room = rooms.getValue(id)

            val exits: Map<Direction, RoomId> =
                rf.exits
                    .map { (dirStr, targetIdStr) ->
                        val dir =
                            parseDirectionOrNull(dirStr)
                                ?: throw WorldLoadException("Room '${id.value}' has invalid direction '$dirStr'")
                        dir to normalizeId(zone, targetIdStr)
                    }.toMap()

            rooms[id] = room.copy(exits = exits)
        }

        return World(
            rooms = rooms,
            startRoom = normalizeId(zone, file.startRoom),
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

    private fun normalizeId(
        zone: String,
        raw: String,
    ): RoomId {
        val s = raw.trim()
        require(s.isNotEmpty()) { "Room id cannot be blank" }
        return if (':' in s) RoomId(s) else RoomId("$zone:$s")
    }

    private fun isCrossZone(
        zone: String,
        id: RoomId,
    ): Boolean = id.zone != zone
}
