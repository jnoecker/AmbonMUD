package dev.ambon.domain.world.load

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.ItemSpawn
import dev.ambon.domain.world.MobSpawn
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
        val mergedMobs = LinkedHashMap<MobId, MobSpawn>()
        val mergedItems = LinkedHashMap<ItemId, ItemSpawn>()

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

            // Stage mobs (normalized), validate uniqueness
            for ((rawId, mf) in file.mobs) {
                val mobId = normalizeMobId(zone, rawId)
                if (mergedMobs.containsKey(mobId)) {
                    throw WorldLoadException("Duplicate mob id '${mobId.value}' across zone files")
                }
                val roomId = normalizeTarget(zone, mf.room)
                mergedMobs[mobId] = MobSpawn(id = mobId, name = mf.name, roomId = roomId)
            }

            // Stage items (normalized), validate uniqueness
            for ((rawId, itemFile) in file.items) {
                val itemId = normalizeItemId(zone, rawId)
                if (mergedItems.containsKey(itemId)) {
                    throw WorldLoadException("Duplicate item id '${itemId.value}' across zone files")
                }

                val displayName = itemFile.displayName.trim()
                if (displayName.isEmpty()) {
                    throw WorldLoadException("Item '${itemId.value}' displayName cannot be blank")
                }

                val keyword = normalizeKeyword(rawId, itemFile.keyword)

                val roomRaw = itemFile.room?.trim()?.takeUnless { it.isEmpty() }
                val mobRaw = itemFile.mob?.trim()?.takeUnless { it.isEmpty() }
                if (roomRaw != null && mobRaw != null) {
                    throw WorldLoadException("Item '${itemId.value}' cannot be placed in both room and mob")
                }

                val roomId = roomRaw?.let { normalizeTarget(zone, it) }
                val mobId = mobRaw?.let { normalizeMobRef(zone, it) }

                mergedItems[itemId] =
                    ItemSpawn(
                        instance =
                            ItemInstance(
                                id = itemId,
                                item = Item(keyword = keyword, displayName = displayName, description = itemFile.description),
                            ),
                        roomId = roomId,
                        mobId = mobId,
                    )
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

        // Validate mob starting rooms exist after merge
        for ((mobId, mob) in mergedMobs) {
            if (!mergedRooms.containsKey(mob.roomId)) {
                throw WorldLoadException(
                    "Mob '${mobId.value}' starts in missing room '${mob.roomId.value}'",
                )
            }
        }

        // Validate item starting locations exist after merge
        for ((itemId, item) in mergedItems) {
            val roomId = item.roomId
            if (roomId != null && !mergedRooms.containsKey(roomId)) {
                throw WorldLoadException(
                    "Item '${itemId.value}' starts in missing room '${roomId.value}'",
                )
            }
            val mobId = item.mobId
            if (mobId != null && !mergedMobs.containsKey(mobId)) {
                throw WorldLoadException(
                    "Item '${itemId.value}' starts in missing mob '${mobId.value}' " +
                        "(check items.${itemId.value.substringAfter(':')}.mob references a mobs: entry)",
                )
            }
        }

        return World(
            rooms = mergedRooms.toMutableMap(),
            startRoom = worldStart,
            mobSpawns = mergedMobs.values.sortedBy { it.id.value },
            itemSpawns = mergedItems.values.sortedBy { it.instance.id.value },
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

    private fun normalizeMobId(
        zone: String,
        raw: String,
    ): MobId {
        val s = raw.trim()
        if (s.isEmpty()) throw WorldLoadException("Mob id cannot be blank")
        return if (':' in s) MobId(s) else MobId("$zone:$s")
    }

    private fun normalizeMobRef(
        zone: String,
        raw: String,
    ): MobId = normalizeMobId(zone, raw)

    private fun normalizeItemId(
        zone: String,
        raw: String,
    ): ItemId {
        val s = raw.trim()
        if (s.isEmpty()) throw WorldLoadException("Item id cannot be blank")
        return if (':' in s) ItemId(s) else ItemId("$zone:$s")
    }

    private fun normalizeKeyword(
        rawId: String,
        rawKeyword: String?,
    ): String {
        if (rawKeyword != null) {
            val trimmed = rawKeyword.trim()
            if (trimmed.isEmpty()) throw WorldLoadException("Item keyword cannot be blank")
            return trimmed
        }
        return keywordFromId(rawId)
    }

    private fun keywordFromId(rawId: String): String {
        val trimmed = rawId.trim()
        if (trimmed.isEmpty()) throw WorldLoadException("Item keyword cannot be blank")
        val base = trimmed.substringAfterLast(':')
        if (base.isEmpty()) throw WorldLoadException("Item keyword cannot be blank")
        return base
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
