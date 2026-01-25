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

    fun loadFromResource(path: String): World = loadFromResources(listOf(path))

    fun loadFromResources(paths: List<String>): World {
        if (paths.isEmpty()) throw WorldLoadException("No zone files provided")

        val files = paths.map { path -> readWorldFile(path) }

        // Validate per-file basics (no cross-zone resolution yet)
        files.forEach { validateFileBasics(it) }

        // Normalize + merge all rooms
        val mergedRooms = LinkedHashMap<RoomId, Room>()
        val allExits = LinkedHashMap<RoomId, Map<Direction, RoomId>>() // staged exits per room

        // If multiple files provide startRoom, pick first file’s startRoom as world start.
        val worldStart = normalizeId(files.first().zone, files.first().startRoom)

        for (file in files) {
            val zone = file.zone.trim()
            if (zone.isEmpty()) throw WorldLoadException("World zone cannot be blank")

            // First pass per file: create room shells, detect collisions
            for ((rawId, rf) in file.rooms) {
                val id = normalizeId(zone, rawId)
                if (mergedRooms.containsKey(id)) {
                    throw WorldLoadException("Duplicate room id '${id.value}' across zone files")
                }
                mergedRooms[id] =
                    Room(
                        id = id,
                        title = rf.title,
                        description = rf.description,
                        exits = emptyMap(),
                    )
            }

            // Stage exits (normalized), but don’t validate targets until after merge
            for ((rawId, rf) in file.rooms) {
                val fromId = normalizeId(zone, rawId)
                val exits: Map<Direction, RoomId> =
                    rf.exits
                        .map { (dirStr, targetRaw) ->
                            val dir =
                                parseDirectionOrNull(dirStr)
                                    ?: throw WorldLoadException("Room '${fromId.value}' has invalid direction '$dirStr'")
                            dir to normalizeTarget(zone, targetRaw)
                        }.toMap()

                allExits[fromId] = exits
            }
        }

        // Now validate that all exit targets exist in the merged room set
        for ((fromId, exits) in allExits) {
            for ((dir, targetId) in exits) {
                if (!mergedRooms.containsKey(targetId)) {
                    throw WorldLoadException(
                        "Room '${fromId.value}' exit '$dir' points to missing room '${targetId.value}'",
                    )
                }
            }
        }

        // Apply exits by copying rooms (immutable style)
        for ((fromId, exits) in allExits) {
            val room = mergedRooms.getValue(fromId)
            mergedRooms[fromId] = room.copy(exits = exits)
        }

        // Validate worldStart exists
        if (!mergedRooms.containsKey(worldStart)) {
            throw WorldLoadException("World startRoom '${worldStart.value}' does not exist in merged world")
        }

        return World(
            rooms = mergedRooms.toMutableMap(),
            startRoom = worldStart,
        )
    }

    private fun readWorldFile(path: String): WorldFile {
        val text =
            WorldLoader::class.java.classLoader
                .getResource(path)
                ?.readText()
                ?: throw WorldLoadException("World resource not found: $path")
        try {
            return mapper.readValue(text)
        } catch (e: Exception) {
            throw WorldLoadException("Failed to parse '$path': ${e.message}")
        }
    }

    private fun validateFileBasics(file: WorldFile) {
        val zone = file.zone.trim()
        if (zone.isEmpty()) throw WorldLoadException("World zone cannot be blank")

        if (file.rooms.isEmpty()) throw WorldLoadException("Zone '$zone' has no rooms")

        // startRoom can be local (preferred) or fully qualified; normalize and check within this zone file.
        val start = normalizeId(zone, file.startRoom)
        val roomIds =
            file.rooms.keys
                .map { normalizeId(zone, it) }
                .toSet()

        if (!roomIds.contains(start)) {
            throw WorldLoadException("Zone '$zone' startRoom '${file.startRoom}' does not exist (normalized as '${start.value}')")
        }
    }

    /**
     * Normalize a room id that is expected to be "local to zone" unless qualified.
     */
    private fun normalizeId(
        zone: String,
        raw: String,
    ): RoomId {
        val s = raw.trim()
        if (s.isEmpty()) throw WorldLoadException("Room id cannot be blank")
        return if (':' in s) RoomId(s) else RoomId("$zone:$s")
    }

    /**
     * Normalize exit targets:
     * - If "other:room" => keep as-is
     * - If "room" => treat as local to zone
     */
    private fun normalizeTarget(
        zone: String,
        raw: String,
    ): RoomId = normalizeId(zone, raw)

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
