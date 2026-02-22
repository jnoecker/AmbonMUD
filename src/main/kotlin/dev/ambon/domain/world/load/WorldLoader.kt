package dev.ambon.domain.world.load

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import dev.ambon.config.MobTiersConfig
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.items.ItemSlot
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.ItemSpawn
import dev.ambon.domain.world.MobDrop
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

    fun loadFromResource(
        path: String,
        tiers: MobTiersConfig = MobTiersConfig(),
    ): World = loadFromResources(listOf(path), tiers)

    fun loadFromResources(
        paths: List<String>,
        tiers: MobTiersConfig = MobTiersConfig(),
        zoneFilter: Set<String> = emptySet(),
    ): World {
        if (paths.isEmpty()) throw WorldLoadException("No zone files provided")

        val allFiles = paths.map { path -> readWorldFile(path) }
        val files =
            if (zoneFilter.isEmpty()) {
                allFiles
            } else {
                allFiles.filter { it.zone.trim() in zoneFilter }
            }
        if (files.isEmpty()) {
            throw WorldLoadException("No zone files match the zone filter: $zoneFilter")
        }

        // Validate per-file basics (no cross-zone resolution yet)
        files.forEach { validateFileBasics(it) }

        // Normalize + merge all rooms
        val mergedRooms = LinkedHashMap<RoomId, Room>()
        val allExits = LinkedHashMap<RoomId, Map<Direction, RoomId>>() // staged exits per room
        val mergedMobs = LinkedHashMap<MobId, MobSpawn>()
        val mergedItems = LinkedHashMap<ItemId, ItemSpawn>()
        val zoneLifespansMinutes = LinkedHashMap<String, Long?>()

        // If multiple files provide startRoom, pick first file’s startRoom as world start.
        val worldStart = normalizeId(files.first().zone, files.first().startRoom)

        for (file in files) {
            val zone = file.zone.trim()
            if (zone.isEmpty()) throw WorldLoadException("World zone cannot be blank")
            val declaredLifespanMinutes = file.lifespan
            if (!zoneLifespansMinutes.containsKey(zone)) {
                zoneLifespansMinutes[zone] = declaredLifespanMinutes
            } else {
                val existingLifespanMinutes = zoneLifespansMinutes.getValue(zone)
                when {
                    declaredLifespanMinutes == null -> Unit
                    existingLifespanMinutes == null -> zoneLifespansMinutes[zone] = declaredLifespanMinutes
                    existingLifespanMinutes != declaredLifespanMinutes -> {
                        throw WorldLoadException(
                            "Zone '$zone' declares conflicting lifespan values " +
                                "($existingLifespanMinutes and $declaredLifespanMinutes)",
                        )
                    }
                }
            }

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

                val tierName = mf.tier?.trim()?.lowercase()
                val tier =
                    if (tierName == null) {
                        tiers.standard
                    } else {
                        tiers.forName(tierName)
                            ?: throw WorldLoadException(
                                "Mob '${mobId.value}' has unknown tier '$tierName' " +
                                    "(expected: weak, standard, elite, boss)",
                            )
                    }

                val level = mf.level ?: 1
                if (level < 1) {
                    throw WorldLoadException("Mob '${mobId.value}' level must be >= 1 (got $level)")
                }
                val steps = level - 1

                val resolvedHp = mf.hp ?: (tier.baseHp + steps * tier.hpPerLevel)
                val resolvedMinDamage = mf.minDamage ?: (tier.baseMinDamage + steps * tier.damagePerLevel)
                val resolvedMaxDamage = mf.maxDamage ?: (tier.baseMaxDamage + steps * tier.damagePerLevel)
                val resolvedArmor = mf.armor ?: tier.baseArmor
                val resolvedXpReward = mf.xpReward ?: (tier.baseXpReward + steps.toLong() * tier.xpRewardPerLevel)
                val drops =
                    mf.drops.mapIndexed { index, drop ->
                        val rawItemId = drop.itemId.trim()
                        if (rawItemId.isEmpty()) {
                            throw WorldLoadException("Mob '${mobId.value}' drop #${index + 1} itemId cannot be blank")
                        }

                        val chance = drop.chance
                        if (chance.isNaN() || chance < 0.0 || chance > 1.0) {
                            throw WorldLoadException(
                                "Mob '${mobId.value}' drop #${index + 1} chance must be in [0.0, 1.0] (got $chance)",
                            )
                        }

                        MobDrop(
                            itemId = normalizeItemId(zone, rawItemId),
                            chance = chance,
                        )
                    }

                if (resolvedHp < 1) throw WorldLoadException("Mob '${mobId.value}' resolved hp must be >= 1")
                if (resolvedMinDamage < 1) {
                    throw WorldLoadException("Mob '${mobId.value}' resolved minDamage must be >= 1")
                }
                if (resolvedMaxDamage < resolvedMinDamage) {
                    throw WorldLoadException(
                        "Mob '${mobId.value}' resolved maxDamage ($resolvedMaxDamage) must be >= " +
                            "minDamage ($resolvedMinDamage)",
                    )
                }
                if (resolvedArmor < 0) throw WorldLoadException("Mob '${mobId.value}' resolved armor must be >= 0")
                if (resolvedXpReward < 0L) {
                    throw WorldLoadException("Mob '${mobId.value}' resolved xpReward must be >= 0")
                }

                mergedMobs[mobId] =
                    MobSpawn(
                        id = mobId,
                        name = mf.name,
                        roomId = roomId,
                        maxHp = resolvedHp,
                        minDamage = resolvedMinDamage,
                        maxDamage = resolvedMaxDamage,
                        armor = resolvedArmor,
                        xpReward = resolvedXpReward,
                        drops = drops,
                    )
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

                val slotRaw = itemFile.slot?.trim()
                if (slotRaw != null && slotRaw.isEmpty()) {
                    throw WorldLoadException("Item '${itemId.value}' slot cannot be blank")
                }
                val slot = slotRaw?.let { parseItemSlot(itemId, it) }

                val damage = itemFile.damage
                if (damage < 0) {
                    throw WorldLoadException("Item '${itemId.value}' damage cannot be negative")
                }

                val armor = itemFile.armor
                if (armor < 0) {
                    throw WorldLoadException("Item '${itemId.value}' armor cannot be negative")
                }

                val constitution = itemFile.constitution
                if (constitution < 0) {
                    throw WorldLoadException("Item '${itemId.value}' constitution cannot be negative")
                }

                val roomRaw = itemFile.room?.trim()?.takeUnless { it.isEmpty() }
                val mobRaw = itemFile.mob?.trim()?.takeUnless { it.isEmpty() }
                if (roomRaw != null && mobRaw != null) {
                    throw WorldLoadException(
                        "Item '${itemId.value}' cannot be placed in both room and mob. " +
                            "Use mobs.<id>.drops for mob loot.",
                    )
                }
                if (mobRaw != null) {
                    throw WorldLoadException(
                        "Item '${itemId.value}' uses deprecated 'mob' placement. " +
                            "Use mobs.<id>.drops instead.",
                    )
                }

                val roomId = roomRaw?.let { normalizeTarget(zone, it) }

                mergedItems[itemId] =
                    ItemSpawn(
                        instance =
                            ItemInstance(
                                id = itemId,
                                item =
                                    Item(
                                        keyword = keyword,
                                        displayName = displayName,
                                        description = itemFile.description,
                                        slot = slot,
                                        damage = damage,
                                        armor = armor,
                                        constitution = constitution,
                                        matchByKey = itemFile.matchByKey,
                                    ),
                            ),
                        roomId = roomId,
                    )
            }
        }

        // Now validate that all exit targets exist in the merged room set.
        // When a zone filter is active, exits pointing to rooms in non-loaded zones
        // are kept but not validated (they are cross-zone stubs).
        val loadedZones = mergedRooms.keys.mapTo(mutableSetOf()) { it.zone }
        val filteredLoad = zoneFilter.isNotEmpty()
        for ((fromId, exits) in allExits) {
            for ((dir, targetId) in exits) {
                if (filteredLoad && targetId.zone !in loadedZones) continue
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
            val remoteExits =
                if (filteredLoad) {
                    exits.filterValues { it.zone !in loadedZones }.keys.toSet()
                } else {
                    emptySet()
                }
            mergedRooms[fromId] = room.copy(exits = exits, remoteExits = remoteExits)
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
        }

        // Validate mob drop item references exist after merge
        for ((mobId, mob) in mergedMobs) {
            for ((index, drop) in mob.drops.withIndex()) {
                if (!mergedItems.containsKey(drop.itemId)) {
                    throw WorldLoadException(
                        "Mob '${mobId.value}' drop #${index + 1} references missing item '${drop.itemId.value}'",
                    )
                }
            }
        }

        return World(
            rooms = mergedRooms.toMutableMap(),
            startRoom = worldStart,
            mobSpawns = mergedMobs.values.sortedBy { it.id.value },
            itemSpawns = mergedItems.values.sortedBy { it.instance.id.value },
            zoneLifespansMinutes =
                zoneLifespansMinutes.entries
                    .mapNotNull { (zone, lifespanMinutes) -> lifespanMinutes?.let { zone to it } }
                    .toMap(),
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
        if (file.lifespan != null && file.lifespan < 0L) {
            throw WorldLoadException("Zone '$zone' lifespan must be >= 0")
        }

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

    private fun parseItemSlot(
        itemId: ItemId,
        raw: String,
    ): ItemSlot =
        ItemSlot.parse(raw)
            ?: throw WorldLoadException(
                "Item '${itemId.value}' has invalid slot '$raw' (expected: head, body, hand)",
            )

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
