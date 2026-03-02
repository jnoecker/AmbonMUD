package dev.ambon.domain.world.load

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
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
import dev.ambon.domain.items.ItemUseEffect
import dev.ambon.domain.quest.CompletionType
import dev.ambon.domain.quest.ObjectiveType
import dev.ambon.domain.quest.QuestDef
import dev.ambon.domain.quest.QuestObjectiveDef
import dev.ambon.domain.quest.QuestRewards
import dev.ambon.domain.world.ContainerState
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.DoorState
import dev.ambon.domain.world.ItemSpawn
import dev.ambon.domain.world.LeverState
import dev.ambon.domain.world.MobDrop
import dev.ambon.domain.world.MobSpawn
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.RoomFeature
import dev.ambon.domain.world.ShopDefinition
import dev.ambon.domain.world.World
import dev.ambon.domain.world.data.ExitValue
import dev.ambon.domain.world.data.ExitValueDeserializer
import dev.ambon.domain.world.data.FeatureFile
import dev.ambon.domain.world.data.WorldFile
import dev.ambon.engine.behavior.BehaviorTemplates
import dev.ambon.engine.behavior.BtNode
import dev.ambon.engine.dialogue.DialogueChoice
import dev.ambon.engine.dialogue.DialogueNode
import dev.ambon.engine.dialogue.DialogueTree

class WorldLoadException(
    message: String,
) : RuntimeException(message)

object WorldLoader {
    private val mapper =
        ObjectMapper(YAMLFactory())
            .registerModule(KotlinModule.Builder().build())
            .registerModule(
                SimpleModule().addDeserializer(ExitValue::class.java, ExitValueDeserializer()),
            )

    fun loadFromResource(
        path: String,
        tiers: MobTiersConfig = MobTiersConfig(),
    ): World = loadFromResources(listOf(path), tiers)

    fun loadFromResources(
        paths: List<String>,
        tiers: MobTiersConfig = MobTiersConfig(),
        zoneFilter: Set<String> = emptySet(),
        startRoomOverride: RoomId? = null,
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
        val allRoomFeatures = LinkedHashMap<RoomId, MutableList<RoomFeature>>() // staged features per room
        val mergedMobs = LinkedHashMap<MobId, MobSpawn>()
        val mergedItems = LinkedHashMap<ItemId, ItemSpawn>()
        val mergedShops = mutableListOf<ShopDefinition>()
        val mergedQuests = mutableListOf<QuestDef>()
        val zoneLifespansMinutes = LinkedHashMap<String, Long?>()

        // startRoomOverride wins; otherwise fall back to first file’s declared startRoom.
        val worldStart = startRoomOverride ?: normalizeId(files.first().zone, files.first().startRoom)

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

            // Stage exits + door features (normalized), but don’t validate targets until after merge
            for ((rawId, rf) in file.rooms) {
                val fromId = normalizeId(zone, rawId)
                val featList = allRoomFeatures.getOrPut(fromId) { mutableListOf() }
                val exits: Map<Direction, RoomId> =
                    rf.exits
                        .map { (dirStr, exitValue) ->
                            val dir =
                                parseDirectionOrNull(dirStr)
                                    ?: throw WorldLoadException("Room ‘${fromId.value}’ has invalid direction ‘$dirStr’")
                            val doorFile = exitValue.door
                            if (doorFile != null) {
                                val doorKeyItemId =
                                    doorFile.keyItemId?.trim()?.takeUnless { it.isEmpty() }?.let {
                                        normalizeItemId(zone, it)
                                    }
                                val doorState =
                                    parseDoorState(
                                        doorFile.initialState,
                                        "Room ‘${fromId.value}’ door at ‘$dirStr’",
                                    )
                                val dirAbbrev = dirAbbrev(dir)
                                featList.add(
                                    RoomFeature.Door(
                                        id = "${fromId.value}/$dirAbbrev",
                                        roomId = fromId,
                                        displayName = "door to the ${dir.name.lowercase()}",
                                        keyword = dir.name.lowercase(),
                                        direction = dir,
                                        initialState = doorState,
                                        keyItemId = doorKeyItemId,
                                        keyConsumed = doorFile.keyConsumed,
                                        resetWithZone = doorFile.resetWithZone,
                                    ),
                                )
                            }
                            dir to normalizeTarget(zone, exitValue.to)
                        }.toMap()

                allExits[fromId] = exits
            }

            // Stage non-exit features (containers, levers, signs)
            for ((rawId, rf) in file.rooms) {
                val fromId = normalizeId(zone, rawId)
                val featList = allRoomFeatures.getOrPut(fromId) { mutableListOf() }
                for ((featLocalId, ff) in rf.features) {
                    val featId = "${fromId.value}/$featLocalId"
                    featList.add(parseFeatureFile(featId, fromId, zone, ff))
                }
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
                val resolvedGoldMin = mf.goldMin ?: (tier.baseGoldMin + steps.toLong() * tier.goldPerLevel)
                val resolvedGoldMax = mf.goldMax ?: (tier.baseGoldMax + steps.toLong() * tier.goldPerLevel)
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
                if (resolvedGoldMin < 0L) {
                    throw WorldLoadException("Mob '${mobId.value}' resolved goldMin must be >= 0")
                }
                if (resolvedGoldMax < resolvedGoldMin) {
                    throw WorldLoadException(
                        "Mob '${mobId.value}' resolved goldMax ($resolvedGoldMax) must be >= " +
                            "goldMin ($resolvedGoldMin)",
                    )
                }

                val respawnSeconds = mf.respawnSeconds
                if (respawnSeconds != null && respawnSeconds <= 0L) {
                    throw WorldLoadException("Mob '${mobId.value}' respawnSeconds must be > 0 (got $respawnSeconds)")
                }

                val dialogue = parseDialogue(mobId, mf.dialogue)
                val behaviorTree = parseBehavior(mobId, zone, mf.behavior)
                val questIds =
                    mf.quests.map { rawQuestId ->
                        val s = rawQuestId.trim()
                        if (':' in s) s else "$zone:$s"
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
                        respawnSeconds = respawnSeconds,
                        goldMin = resolvedGoldMin,
                        goldMax = resolvedGoldMax,
                        dialogue = dialogue,
                        behaviorTree = behaviorTree,
                        questIds = questIds,
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

                val charges = itemFile.charges
                if (charges != null && charges <= 0) {
                    throw WorldLoadException("Item '${itemId.value}' charges must be > 0")
                }

                val onUse =
                    itemFile.onUse?.let { effectFile ->
                        if (effectFile.healHp < 0) {
                            throw WorldLoadException("Item '${itemId.value}' onUse.healHp cannot be negative")
                        }
                        if (effectFile.grantXp < 0L) {
                            throw WorldLoadException("Item '${itemId.value}' onUse.grantXp cannot be negative")
                        }
                        ItemUseEffect(
                            healHp = effectFile.healHp,
                            grantXp = effectFile.grantXp,
                        ).also { effect ->
                            if (!effect.hasEffect()) {
                                throw WorldLoadException(
                                    "Item '${itemId.value}' onUse must define at least one positive effect",
                                )
                            }
                        }
                    }

                val basePrice = itemFile.basePrice
                if (basePrice < 0) {
                    throw WorldLoadException("Item '${itemId.value}' basePrice cannot be negative")
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
                                        strength = itemFile.strength,
                                        dexterity = itemFile.dexterity,
                                        intelligence = itemFile.intelligence,
                                        wisdom = itemFile.wisdom,
                                        charisma = itemFile.charisma,
                                        consumable = itemFile.consumable,
                                        charges = charges,
                                        onUse = onUse,
                                        matchByKey = itemFile.matchByKey,
                                        basePrice = basePrice,
                                    ),
                            ),
                        roomId = roomId,
                    )
            }

            // Stage shops (normalized)
            for ((rawId, shopFile) in file.shops) {
                val shopName = shopFile.name.trim()
                if (shopName.isEmpty()) {
                    throw WorldLoadException("Shop '$rawId' in zone '$zone' name cannot be blank")
                }
                val shopRoomId = normalizeTarget(zone, shopFile.room)
                val shopItemIds =
                    shopFile.items.mapIndexed { index, rawItemId ->
                        val trimmed = rawItemId.trim()
                        if (trimmed.isEmpty()) {
                            throw WorldLoadException("Shop '$rawId' item #${index + 1} cannot be blank")
                        }
                        normalizeItemId(zone, trimmed)
                    }
                mergedShops.add(
                    ShopDefinition(
                        id = "$zone:$rawId",
                        name = shopName,
                        roomId = shopRoomId,
                        itemIds = shopItemIds,
                    ),
                )
            }

            // Stage quests (normalized)
            for ((rawId, questFile) in file.quests) {
                val questId = "$zone:$rawId"
                val questName = questFile.name.trim()
                if (questName.isEmpty()) {
                    throw WorldLoadException("Quest '$questId' name cannot be blank")
                }
                val giver = questFile.giver.trim()
                if (giver.isEmpty()) {
                    throw WorldLoadException("Quest '$questId' giver cannot be blank")
                }
                val completionType =
                    when (questFile.completionType.uppercase()) {
                        "AUTO" -> CompletionType.AUTO
                        "NPC_TURN_IN" -> CompletionType.NPC_TURN_IN
                        else -> throw WorldLoadException(
                            "Quest '$questId' has unknown completionType '${questFile.completionType}'",
                        )
                    }
                if (questFile.objectives.isEmpty()) {
                    throw WorldLoadException("Quest '$questId' must have at least one objective")
                }
                val objectives =
                    questFile.objectives.mapIndexed { index, obj ->
                        val objectiveType =
                            when (obj.type.uppercase()) {
                                "KILL" -> ObjectiveType.KILL
                                "COLLECT" -> ObjectiveType.COLLECT
                                else -> throw WorldLoadException(
                                    "Quest '$questId' objective #${index + 1} has unknown type '${obj.type}'",
                                )
                            }
                        val targetKeyRaw = obj.targetKey.trim()
                        if (targetKeyRaw.isEmpty()) {
                            throw WorldLoadException(
                                "Quest '$questId' objective #${index + 1} targetKey cannot be blank",
                            )
                        }
                        val targetId = if (':' in targetKeyRaw) targetKeyRaw else "$zone:$targetKeyRaw"
                        if (obj.count < 1) {
                            throw WorldLoadException(
                                "Quest '$questId' objective #${index + 1} count must be >= 1",
                            )
                        }
                        QuestObjectiveDef(
                            type = objectiveType,
                            targetId = targetId,
                            count = obj.count,
                            description = obj.description.ifBlank { "${objectiveType.name.lowercase()} $targetKeyRaw x${obj.count}" },
                        )
                    }
                mergedQuests.add(
                    QuestDef(
                        id = questId,
                        name = questName,
                        description = questFile.description,
                        giverMobId = if (':' in giver) giver else "$zone:$giver",
                        objectives = objectives,
                        rewards = QuestRewards(xp = questFile.rewards.xp, gold = questFile.rewards.gold),
                        completionType = completionType,
                    ),
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

        // Apply exits + features by copying rooms (immutable style)
        for ((fromId, exits) in allExits) {
            val room = mergedRooms.getValue(fromId)
            val remoteExits =
                if (filteredLoad) {
                    exits.filterValues { it.zone !in loadedZones }.keys.toSet()
                } else {
                    emptySet()
                }
            mergedRooms[fromId] = room.copy(
                exits = exits,
                remoteExits = remoteExits,
                features = allRoomFeatures[fromId] ?: emptyList(),
            )
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

        // Validate shop references after merge
        for (shop in mergedShops) {
            if (!mergedRooms.containsKey(shop.roomId)) {
                throw WorldLoadException(
                    "Shop '${shop.id}' references missing room '${shop.roomId.value}'",
                )
            }
            for ((index, itemId) in shop.itemIds.withIndex()) {
                if (!mergedItems.containsKey(itemId)) {
                    throw WorldLoadException(
                        "Shop '${shop.id}' item #${index + 1} references missing item '${itemId.value}'",
                    )
                }
            }
        }

        // Validate feature item cross-references after merge
        for (features in allRoomFeatures.values) {
            for (feature in features) {
                when (feature) {
                    is RoomFeature.Door -> {
                        feature.keyItemId?.let { keyId ->
                            if (!mergedItems.containsKey(keyId)) {
                                throw WorldLoadException(
                                    "Door '${feature.id}' keyItemId references unknown item '${keyId.value}'",
                                )
                            }
                        }
                    }
                    is RoomFeature.Container -> {
                        feature.keyItemId?.let { keyId ->
                            if (!mergedItems.containsKey(keyId)) {
                                throw WorldLoadException(
                                    "Container '${feature.id}' keyItemId references unknown item '${keyId.value}'",
                                )
                            }
                        }
                        for ((index, itemId) in feature.initialItems.withIndex()) {
                            if (!mergedItems.containsKey(itemId)) {
                                throw WorldLoadException(
                                    "Container '${feature.id}' item #${index + 1} references unknown item '${itemId.value}'",
                                )
                            }
                        }
                    }
                    is RoomFeature.Lever, is RoomFeature.Sign -> Unit
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
            shopDefinitions = mergedShops.toList(),
            questDefinitions = mergedQuests.toList(),
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

    private fun parseBehavior(
        mobId: MobId,
        zone: String,
        behaviorFile: dev.ambon.domain.world.data.BehaviorFile?,
    ): BtNode? {
        if (behaviorFile == null) return null

        val tree =
            BehaviorTemplates.resolve(
                behaviorFile.template,
                behaviorFile.params,
                zone,
            ) ?: throw WorldLoadException(
                "Mob '${mobId.value}' references unknown behavior template '${behaviorFile.template}'. " +
                    "Known templates: ${BehaviorTemplates.templateNames.sorted().joinToString(", ")}",
            )

        return tree
    }

    private fun dirAbbrev(dir: Direction): String =
        when (dir) {
            Direction.NORTH -> "n"
            Direction.SOUTH -> "s"
            Direction.EAST -> "e"
            Direction.WEST -> "w"
            Direction.UP -> "u"
            Direction.DOWN -> "d"
        }

    private fun parseDoorState(
        raw: String,
        context: String,
    ): DoorState =
        when (raw.trim().lowercase()) {
            "open" -> DoorState.OPEN
            "closed" -> DoorState.CLOSED
            "locked" -> DoorState.LOCKED
            else -> throw WorldLoadException("$context has invalid door initialState '$raw' (expected: open, closed, locked)")
        }

    private fun parseContainerState(
        raw: String?,
        context: String,
    ): ContainerState =
        when (raw?.trim()?.lowercase() ?: "closed") {
            "open" -> ContainerState.OPEN
            "closed" -> ContainerState.CLOSED
            "locked" -> ContainerState.LOCKED
            else -> throw WorldLoadException("$context has invalid container initialState '$raw' (expected: open, closed, locked)")
        }

    private fun parseLeverState(
        raw: String?,
        context: String,
    ): LeverState =
        when (raw?.trim()?.lowercase() ?: "up") {
            "up" -> LeverState.UP
            "down" -> LeverState.DOWN
            else -> throw WorldLoadException("$context has invalid lever initialState '$raw' (expected: up, down)")
        }

    private fun parseFeatureFile(
        featId: String,
        roomId: RoomId,
        zone: String,
        ff: FeatureFile,
    ): RoomFeature {
        val type = ff.type.trim().uppercase()
        val displayName = ff.displayName.trim()
        if (displayName.isEmpty()) {
            throw WorldLoadException("Feature '$featId' displayName cannot be blank")
        }
        val keyword = ff.keyword.trim()
        if (keyword.isEmpty()) {
            throw WorldLoadException("Feature '$featId' keyword cannot be blank")
        }
        return when (type) {
            "CONTAINER" -> {
                val keyItemId =
                    ff.keyItemId?.trim()?.takeUnless { it.isEmpty() }?.let {
                        normalizeItemId(zone, it)
                    }
                val initialState = parseContainerState(ff.initialState, "Container '$featId'")
                val initialItems =
                    ff.items.mapIndexed { index, rawItemId ->
                        val s = rawItemId.trim()
                        if (s.isEmpty()) {
                            throw WorldLoadException("Container '$featId' item #${index + 1} cannot be blank")
                        }
                        normalizeItemId(zone, s)
                    }
                RoomFeature.Container(
                    id = featId,
                    roomId = roomId,
                    displayName = displayName,
                    keyword = keyword,
                    initialState = initialState,
                    keyItemId = keyItemId,
                    keyConsumed = ff.keyConsumed,
                    resetWithZone = ff.resetWithZone,
                    initialItems = initialItems,
                )
            }
            "LEVER" -> {
                val initialState = parseLeverState(ff.initialState, "Lever '$featId'")
                RoomFeature.Lever(
                    id = featId,
                    roomId = roomId,
                    displayName = displayName,
                    keyword = keyword,
                    initialState = initialState,
                    resetWithZone = ff.resetWithZone,
                )
            }
            "SIGN" -> {
                val text = ff.text ?: throw WorldLoadException("Sign '$featId' must have a 'text' field")
                RoomFeature.Sign(
                    id = featId,
                    roomId = roomId,
                    displayName = displayName,
                    keyword = keyword,
                    text = text,
                )
            }
            else -> throw WorldLoadException(
                "Feature '$featId' has unknown type '$type' (expected: CONTAINER, LEVER, SIGN)",
            )
        }
    }

    private fun parseDialogue(
        mobId: MobId,
        raw: Map<String, dev.ambon.domain.world.data.DialogueNodeFile>,
    ): DialogueTree? {
        if (raw.isEmpty()) return null

        val rootKey = "root"
        if (!raw.containsKey(rootKey)) {
            throw WorldLoadException(
                "Mob '${mobId.value}' dialogue must contain a '$rootKey' node",
            )
        }

        val nodes = mutableMapOf<String, DialogueNode>()
        for ((key, nodeFile) in raw) {
            val choices =
                nodeFile.choices.map { choiceFile ->
                    val nextNodeId = choiceFile.next
                    if (nextNodeId != null && !raw.containsKey(nextNodeId)) {
                        throw WorldLoadException(
                            "Mob '${mobId.value}' dialogue node '$key' choice references " +
                                "missing node '$nextNodeId'",
                        )
                    }
                    DialogueChoice(
                        text = choiceFile.text,
                        nextNodeId = nextNodeId,
                        minLevel = choiceFile.minLevel,
                        requiredClass = choiceFile.requiredClass,
                        action = choiceFile.action,
                    )
                }
            nodes[key] = DialogueNode(text = nodeFile.text, choices = choices)
        }

        return DialogueTree(rootNodeId = rootKey, nodes = nodes)
    }
}
