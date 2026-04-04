package dev.ambon.domain.world.load

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import dev.ambon.config.MobTiersConfig
import dev.ambon.domain.DamageRange
import dev.ambon.domain.StatMap
import dev.ambon.domain.crafting.GatheringNodeDef
import dev.ambon.domain.crafting.GatheringYield
import dev.ambon.domain.crafting.MaterialRequirement
import dev.ambon.domain.crafting.RareGatheringYield
import dev.ambon.domain.crafting.RecipeDef
import dev.ambon.domain.dungeon.DungeonDifficulty
import dev.ambon.domain.dungeon.DungeonLootTableDef
import dev.ambon.domain.dungeon.DungeonMobPoolDef
import dev.ambon.domain.dungeon.DungeonRoomTemplateDef
import dev.ambon.domain.dungeon.DungeonRoomType
import dev.ambon.domain.dungeon.DungeonTemplateDef
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.qualifyId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.items.ItemSlot
import dev.ambon.domain.puzzle.PuzzleDef
import dev.ambon.domain.puzzle.PuzzleReward
import dev.ambon.domain.puzzle.PuzzleStep
import dev.ambon.domain.puzzle.PuzzleType
import dev.ambon.domain.quest.QuestDef
import dev.ambon.domain.quest.QuestObjectiveDef
import dev.ambon.domain.quest.QuestRewards
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.ItemSpawn
import dev.ambon.domain.world.LeverState
import dev.ambon.domain.world.LockableState
import dev.ambon.domain.world.MobDrop
import dev.ambon.domain.world.MobSpawn
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.RoomFeature
import dev.ambon.domain.world.ShopDefinition
import dev.ambon.domain.world.TrainerDefinition
import dev.ambon.domain.world.World
import dev.ambon.domain.world.data.ExitValue
import dev.ambon.domain.world.data.ExitValueDeserializer
import dev.ambon.domain.world.data.FeatureFile
import dev.ambon.domain.world.data.WorldFile
import dev.ambon.engine.behavior.BehaviorTemplates
import dev.ambon.engine.behavior.BehaviorTreeLoader
import dev.ambon.engine.behavior.BtNode
import dev.ambon.engine.dialogue.DialogueChoice
import dev.ambon.engine.dialogue.DialogueNode
import dev.ambon.engine.dialogue.DialogueTree
import org.slf4j.LoggerFactory

class WorldLoadException(
    message: String,
) : RuntimeException(message)

object WorldLoader {
    private val logger = LoggerFactory.getLogger(WorldLoader::class.java)

    private val mapper =
        ObjectMapper(YAMLFactory())
            .registerModule(KotlinModule.Builder().build())
            .registerModule(
                SimpleModule().addDeserializer(ExitValue::class.java, ExitValueDeserializer()),
            )

    fun loadFromResource(
        path: String,
        tiers: MobTiersConfig = MobTiersConfig(),
        imagesBaseUrl: String = "/images/",
        videosBaseUrl: String = "/videos/",
        audioBaseUrl: String = "/audio/",
    ): World =
        loadFromResources(
            listOf(path),
            tiers,
            imagesBaseUrl = imagesBaseUrl,
            videosBaseUrl = videosBaseUrl,
            audioBaseUrl = audioBaseUrl,
        )

    fun loadFromResources(
        paths: List<String>,
        tiers: MobTiersConfig = MobTiersConfig(),
        zoneFilter: Set<String> = emptySet(),
        startRoomOverride: RoomId? = null,
        imagesBaseUrl: String = "/images/",
        videosBaseUrl: String = "/videos/",
        audioBaseUrl: String = "/audio/",
    ): World {
        val imagesBase = normalizeBaseUrl(imagesBaseUrl)
        val videosBase = normalizeBaseUrl(videosBaseUrl)
        val audioBase = normalizeBaseUrl(audioBaseUrl)
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
        val mergedTrainers = mutableListOf<TrainerDefinition>()
        val mergedQuests = mutableListOf<QuestDef>()
        val mergedGatheringNodes = mutableListOf<GatheringNodeDef>()
        val mergedRecipes = mutableListOf<RecipeDef>()
        val mergedDungeonTemplates = mutableListOf<DungeonTemplateDef>()
        val mergedPuzzles = mutableListOf<PuzzleDef>()
        val zoneLifespansMinutes = LinkedHashMap<String, Long?>()
        val pvpZones = mutableSetOf<String>()
        val zoneStartRooms = LinkedHashMap<String, RoomId>()

        // startRoomOverride wins; otherwise fall back to first file’s declared startRoom.
        val worldStart = startRoomOverride ?: normalizeId(files.first().zone, files.first().startRoom)

        for (file in files) {
            val zone = requireNonBlank(file.zone) { "World zone cannot be blank" }
            if (!zoneStartRooms.containsKey(zone)) {
                zoneStartRooms[zone] = normalizeId(zone, file.startRoom)
            }
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

            val imageDefaults = file.image
            val audioDefaults = file.audio
            val zoneGraphical = file.graphical
            if (file.pvpEnabled) pvpZones.add(zone)

            // First pass per file: create room shells, detect collisions
            for ((rawId, rf) in file.rooms) {
                val id = normalizeId(zone, rawId)
                if (mergedRooms.containsKey(id)) {
                    throw WorldLoadException("Duplicate room id '${id.value}' across zone files")
                }
                val station = rf.station?.let { raw ->
                    parseCraftingStationType(raw, "Room '${id.value}'")
                }
                mergedRooms[id] =
                    Room(
                        id = id,
                        title = rf.title,
                        description = rf.description,
                        exits = emptyMap(),
                        station = station,
                        bank = rf.bank,
                        tavern = rf.tavern,
                        image = (rf.image ?: imageDefaults?.room)?.let { "$imagesBase$it" },
                        video = rf.video?.let { "$videosBase$it" },
                        music = (rf.music ?: audioDefaults?.music)?.let { "$audioBase$it" },
                        ambient = (rf.ambient ?: audioDefaults?.ambient)?.let { "$audioBase$it" },
                        graphical = zoneGraphical,
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
                                    parseLockableState(
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
                requireAtLeast(level, 1, "Mob '${mobId.value}'", "level")
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
                        val rawItemId = requireNonBlank(drop.itemId) {
                            "Mob '${mobId.value}' drop #${index + 1} itemId cannot be blank"
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

                val mobCtx = "Mob '${mobId.value}'"
                requireAtLeast(resolvedHp, 1, mobCtx, "resolved hp")
                requireAtLeast(resolvedMinDamage, 1, mobCtx, "resolved minDamage")
                if (resolvedMaxDamage < resolvedMinDamage) {
                    throw WorldLoadException(
                        "Mob '${mobId.value}' resolved maxDamage ($resolvedMaxDamage) must be >= " +
                            "minDamage ($resolvedMinDamage)",
                    )
                }
                requireAtLeast(resolvedArmor, 0, mobCtx, "resolved armor")
                requireAtLeast(resolvedXpReward, 0L, mobCtx, "resolved xpReward")
                requireAtLeast(resolvedGoldMin, 0L, mobCtx, "resolved goldMin")
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
                        qualifyId(zone, s)
                    }

                mergedMobs[mobId] =
                    MobSpawn(
                        id = mobId,
                        name = mf.name,
                        description = mf.description,
                        roomId = roomId,
                        maxHp = resolvedHp,
                        damage = DamageRange(resolvedMinDamage, resolvedMaxDamage),
                        armor = resolvedArmor,
                        xpReward = resolvedXpReward,
                        drops = drops,
                        respawnSeconds = respawnSeconds,
                        goldMin = resolvedGoldMin,
                        goldMax = resolvedGoldMax,
                        dialogue = dialogue,
                        behaviorTree = behaviorTree,
                        questIds = questIds,
                        faction = mf.faction,
                        image = (mf.image ?: imageDefaults?.mob)?.let { "$imagesBase$it" },
                        video = mf.video?.let { "$videosBase$it" },
                        aggressive = mf.behavior?.template?.contains("aggro") == true,
                    )
            }

            // Stage items (normalized), validate uniqueness
            for ((rawId, itemFile) in file.items) {
                val itemId = normalizeItemId(zone, rawId)
                if (mergedItems.containsKey(itemId)) {
                    throw WorldLoadException("Duplicate item id '${itemId.value}' across zone files")
                }

                val displayName = requireNonBlank(itemFile.displayName) {
                    "Item '${itemId.value}' displayName cannot be blank"
                }

                val keyword = normalizeKeyword(rawId, itemFile.keyword)

                val slotRaw = itemFile.slot?.trim()
                if (slotRaw != null && slotRaw.isEmpty()) {
                    throw WorldLoadException("Item '${itemId.value}' slot cannot be blank")
                }
                val slot = slotRaw?.let { parseItemSlot(itemId, it) }

                val itemCtx = "Item '${itemId.value}'"
                val damage = itemFile.damage
                requireAtLeast(damage, 0, itemCtx, "damage")

                val armor = itemFile.armor
                requireAtLeast(armor, 0, itemCtx, "armor")

                for ((statKey, statVal) in itemFile.stats) {
                    if (statVal < 0) {
                        throw WorldLoadException("Item '${itemId.value}' stat '$statKey' cannot be negative")
                    }
                }

                val charges = itemFile.charges
                if (charges != null && charges <= 0) {
                    throw WorldLoadException("Item '${itemId.value}' charges must be > 0")
                }

                val onUse =
                    itemFile.onUse?.also { effect ->
                        requireAtLeast(effect.healHp, 0, itemCtx, "onUse.healHp")
                        requireAtLeast(effect.grantXp, 0L, itemCtx, "onUse.grantXp")
                        if (!effect.hasEffect()) {
                            throw WorldLoadException(
                                "Item '${itemId.value}' onUse must define at least one positive effect",
                            )
                        }
                    }

                val basePrice = itemFile.basePrice
                requireAtLeast(basePrice, 0, itemCtx, "basePrice")

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
                                        stats = StatMap(itemFile.stats.mapKeys { (k, _) -> k.uppercase() }),
                                        consumable = itemFile.consumable,
                                        charges = charges,
                                        onUse = onUse,
                                        matchByKey = itemFile.matchByKey,
                                        basePrice = basePrice,
                                        image = (itemFile.image ?: imageDefaults?.item)?.let { "$imagesBase$it" },
                                        video = itemFile.video?.let { "$videosBase$it" },
                                    ),
                            ),
                        roomId = roomId,
                    )
            }

            // Stage shops (normalized)
            for ((rawId, shopFile) in file.shops) {
                val shopName = requireNonBlank(shopFile.name) {
                    "Shop '$rawId' in zone '$zone' name cannot be blank"
                }
                val shopRoomId = normalizeTarget(zone, shopFile.room)
                val shopItemIds =
                    shopFile.items.mapIndexed { index, rawItemId ->
                        val trimmed = requireNonBlank(rawItemId) {
                            "Shop '$rawId' item #${index + 1} cannot be blank"
                        }
                        normalizeItemId(zone, trimmed)
                    }
                mergedShops.add(
                    ShopDefinition(
                        id = qualifyId(zone, rawId),
                        name = shopName,
                        roomId = shopRoomId,
                        itemIds = shopItemIds,
                    ),
                )
            }

            // Stage trainers (normalized)
            for ((rawId, trainerFile) in file.trainers) {
                val trainerName = requireNonBlank(trainerFile.name) {
                    "Trainer '$rawId' in zone '$zone' name cannot be blank"
                }
                val trainerClass = requireNonBlank(trainerFile.className) {
                    "Trainer '$rawId' in zone '$zone' class cannot be blank"
                }
                val trainerRoomId = normalizeTarget(zone, trainerFile.room)
                mergedTrainers.add(
                    TrainerDefinition(
                        id = qualifyId(zone, rawId),
                        name = trainerName,
                        className = trainerClass.uppercase(),
                        roomId = trainerRoomId,
                        image = trainerFile.image,
                    ),
                )
            }

            // Stage quests (normalized)
            for ((rawId, questFile) in file.quests) {
                val questId = qualifyId(zone, rawId)
                val questName = requireNonBlank(questFile.name) {
                    "Quest '$questId' name cannot be blank"
                }
                val giver = requireNonBlank(questFile.giver) {
                    "Quest '$questId' giver cannot be blank"
                }
                val completionType = questFile.completionType.trim().lowercase().ifEmpty { "auto" }
                requireNotEmpty(questFile.objectives, "Quest '$questId'", "objective")
                val objectives =
                    questFile.objectives.mapIndexed { index, obj ->
                        val objectiveType = obj.type.trim().lowercase()
                        if (objectiveType.isEmpty()) {
                            throw WorldLoadException(
                                "Quest '$questId' objective #${index + 1} type cannot be blank",
                            )
                        }
                        val targetKeyRaw = requireNonBlank(obj.targetKey) {
                            "Quest '$questId' objective #${index + 1} targetKey cannot be blank"
                        }
                        val targetId = qualifyId(zone, targetKeyRaw)
                        requireAtLeast(obj.count, 1, "Quest '$questId' objective #${index + 1}", "count")
                        QuestObjectiveDef(
                            type = objectiveType,
                            targetId = targetId,
                            count = obj.count,
                            description = obj.description.ifBlank { "$objectiveType $targetKeyRaw x${obj.count}" },
                        )
                    }
                mergedQuests.add(
                    QuestDef(
                        id = questId,
                        name = questName,
                        description = questFile.description,
                        giverMobId = qualifyId(zone, giver),
                        objectives = objectives,
                        rewards = QuestRewards(
                            xp = questFile.rewards.xp,
                            gold = questFile.rewards.gold,
                            currencies = questFile.rewards.currencies,
                        ),
                        completionType = completionType,
                    ),
                )
            }

            // Stage gathering nodes (normalized)
            for ((rawId, nodeFile) in file.gatheringNodes) {
                val nodeId = qualifyId(zone, rawId)
                val displayName = requireNonBlank(nodeFile.displayName) {
                    "Gathering node '$nodeId' displayName cannot be blank"
                }
                val skill = parseCraftingSkill(nodeFile.skill, "Gathering node '$nodeId'")
                val nodeCtx = "Gathering node '$nodeId'"
                requireAtLeast(nodeFile.skillRequired, 1, nodeCtx, "skillRequired")
                requireNotEmpty(nodeFile.yields, nodeCtx, "yield")
                val yields = nodeFile.yields.mapIndexed { index, yieldFile ->
                    val itemId = normalizeItemId(zone, yieldFile.itemId)
                    requireAtLeast(yieldFile.minQuantity, 1, "$nodeCtx yield #${index + 1}", "minQuantity")
                    if (yieldFile.maxQuantity < yieldFile.minQuantity) {
                        throw WorldLoadException(
                            "Gathering node '$nodeId' yield #${index + 1} maxQuantity must be >= minQuantity",
                        )
                    }
                    GatheringYield(
                        itemId = itemId,
                        minQuantity = yieldFile.minQuantity,
                        maxQuantity = yieldFile.maxQuantity,
                    )
                }
                val rareYields = nodeFile.rareYields.mapIndexed { index, rareFile ->
                    val itemId = normalizeItemId(zone, rareFile.itemId)
                    requireAtLeast(rareFile.quantity, 1, "$nodeCtx rareYield #${index + 1}", "quantity")
                    if (rareFile.dropChance <= 0.0 || rareFile.dropChance > 1.0) {
                        throw WorldLoadException(
                            "Gathering node '$nodeId' rareYield #${index + 1} dropChance must be in (0.0, 1.0]",
                        )
                    }
                    RareGatheringYield(
                        itemId = itemId,
                        quantity = rareFile.quantity,
                        dropChance = rareFile.dropChance,
                    )
                }
                val nodeRoomId = normalizeTarget(zone, nodeFile.room)
                val keyword = normalizeKeyword(rawId, nodeFile.keyword)
                mergedGatheringNodes.add(
                    GatheringNodeDef(
                        id = nodeId,
                        displayName = displayName,
                        keyword = keyword,
                        image = nodeFile.image,
                        skill = skill,
                        skillRequired = nodeFile.skillRequired,
                        yields = yields,
                        rareYields = rareYields,
                        respawnSeconds = nodeFile.respawnSeconds,
                        xpReward = nodeFile.xpReward,
                        roomId = nodeRoomId,
                    ),
                )
            }

            // Stage recipes (normalized)
            for ((rawId, recipeFile) in file.recipes) {
                val recipeId = qualifyId(zone, rawId)
                val displayName = requireNonBlank(recipeFile.displayName) {
                    "Recipe '$recipeId' displayName cannot be blank"
                }
                val skill = parseCraftingSkill(recipeFile.skill, "Recipe '$recipeId'")
                val recipeCtx = "Recipe '$recipeId'"
                requireAtLeast(recipeFile.skillRequired, 1, recipeCtx, "skillRequired")
                requireNotEmpty(recipeFile.materials, recipeCtx, "material")
                val materials = recipeFile.materials.mapIndexed { index, matFile ->
                    val itemId = normalizeItemId(zone, matFile.itemId)
                    requireAtLeast(matFile.quantity, 1, "$recipeCtx material #${index + 1}", "quantity")
                    MaterialRequirement(itemId = itemId, quantity = matFile.quantity)
                }
                val outputItemId = normalizeItemId(zone, recipeFile.outputItemId)
                requireAtLeast(recipeFile.outputQuantity, 1, recipeCtx, "outputQuantity")
                val stationType = recipeFile.station?.let { raw ->
                    parseCraftingStationType(raw, "Recipe '$recipeId'")
                }
                mergedRecipes.add(
                    RecipeDef(
                        id = recipeId,
                        displayName = displayName,
                        skill = skill,
                        skillRequired = recipeFile.skillRequired,
                        levelRequired = recipeFile.levelRequired,
                        materials = materials,
                        outputItemId = outputItemId,
                        outputQuantity = recipeFile.outputQuantity,
                        stationType = stationType,
                        stationBonus = recipeFile.stationBonus,
                        xpReward = recipeFile.xpReward,
                    ),
                )
            }

            // Stage dungeon template (if present)
            val df = file.dungeon
            if (df != null) {
                val dungeonId = qualifyId(zone, "dungeon")
                val roomTemplates = df.roomTemplates.map { (typeKey, templates) ->
                    val type = DungeonRoomType.entries.firstOrNull { it.name.equals(typeKey, ignoreCase = true) }
                        ?: throw WorldLoadException("Dungeon '$dungeonId' unknown room type '$typeKey'")
                    type to templates.map { rt ->
                        DungeonRoomTemplateDef(
                            title = requireNonBlank(rt.title) { "Dungeon '$dungeonId' room template title cannot be blank" },
                            description = rt.description,
                            image = rt.image,
                        )
                    }
                }.toMap()
                val dungeonCtx = "Dungeon '$dungeonId'"
                requireNotEmpty(roomTemplates.entries, dungeonCtx, "roomTemplates entry")
                if (df.roomCountMin > df.roomCountMax) {
                    throw WorldLoadException(
                        "Dungeon '$dungeonId' roomCountMin (${df.roomCountMin}) must be <= roomCountMax (${df.roomCountMax})",
                    )
                }
                requireAtLeast(df.roomCountMin, 3, dungeonCtx, "roomCountMin")
                val mobPools = DungeonMobPoolDef(
                    common = df.mobPools.common,
                    elite = df.mobPools.elite,
                    boss = df.mobPools.boss,
                )
                requireNotEmpty(mobPools.boss, dungeonCtx, "boss mob in mobPools")
                val lootTables = df.lootTables.map { (diffKey, lt) ->
                    val diff = DungeonDifficulty.fromName(diffKey)
                        ?: throw WorldLoadException("Dungeon '$dungeonId' unknown difficulty '$diffKey'")
                    diff to DungeonLootTableDef(
                        mobDrops = lt.mobDrops.map { normalizeItemId(zone, it) },
                        completionRewards = lt.completionRewards.map { normalizeItemId(zone, it) },
                    )
                }.toMap()
                mergedDungeonTemplates.add(
                    DungeonTemplateDef(
                        id = dungeonId,
                        name = df.name,
                        description = df.description,
                        image = df.image,
                        minLevel = df.minLevel,
                        roomCountMin = df.roomCountMin,
                        roomCountMax = df.roomCountMax,
                        roomTemplates = roomTemplates,
                        mobPools = mobPools,
                        lootTables = lootTables,
                        portalRoom = df.portalRoom,
                    ),
                )
            }

            // Stage puzzles (normalized)
            for ((rawId, puzzleFile) in file.puzzles) {
                val puzzleId = qualifyId(zone, rawId)
                val puzzleType = when (puzzleFile.type.trim().lowercase()) {
                    "riddle" -> PuzzleType.RIDDLE
                    "sequence" -> PuzzleType.SEQUENCE
                    else -> throw WorldLoadException(
                        "Puzzle '$puzzleId' has unknown type '${puzzleFile.type}' (expected 'riddle' or 'sequence')",
                    )
                }
                val puzzleRoomId = normalizeTarget(zone, puzzleFile.roomId)

                val qualifiedMobId = puzzleFile.mobId?.let { qualifyId(zone, it) }

                // Build accepted answers for riddle type
                val acceptableAnswers = if (puzzleType == PuzzleType.RIDDLE) {
                    val answers = mutableListOf<String>()
                    puzzleFile.answer?.lowercase()?.trim()?.let { answers.add(it) }
                    puzzleFile.acceptableAnswers.forEach { a ->
                        val normalized = a.lowercase().trim()
                        if (normalized.isNotEmpty() && normalized !in answers) answers.add(normalized)
                    }
                    if (answers.isEmpty()) {
                        throw WorldLoadException("Riddle puzzle '$puzzleId' must have at least one answer")
                    }
                    answers
                } else {
                    emptyList()
                }

                // Build steps for sequence type
                val steps = if (puzzleType == PuzzleType.SEQUENCE) {
                    if (puzzleFile.steps.isEmpty()) {
                        throw WorldLoadException("Sequence puzzle '$puzzleId' must have at least one step")
                    }
                    puzzleFile.steps.mapIndexed { index, stepFile ->
                        if (stepFile.feature.isBlank()) {
                            throw WorldLoadException(
                                "Puzzle '$puzzleId' step #${index + 1} feature cannot be blank",
                            )
                        }
                        if (stepFile.action.isBlank()) {
                            throw WorldLoadException(
                                "Puzzle '$puzzleId' step #${index + 1} action cannot be blank",
                            )
                        }
                        PuzzleStep(
                            featureKeyword = stepFile.feature.trim().lowercase(),
                            action = stepFile.action.trim().lowercase(),
                        )
                    }
                } else {
                    emptyList()
                }

                // Parse reward
                val reward = parsePuzzleReward(puzzleId, zone, puzzleFile.reward)

                mergedPuzzles.add(
                    PuzzleDef(
                        id = puzzleId,
                        type = puzzleType,
                        mobId = qualifiedMobId,
                        roomId = puzzleRoomId,
                        question = puzzleFile.question,
                        acceptableAnswers = acceptableAnswers,
                        steps = steps,
                        reward = reward,
                        failMessage = puzzleFile.failMessage,
                        successMessage = puzzleFile.successMessage,
                        cooldownMs = puzzleFile.cooldownMs,
                        resetOnFail = puzzleFile.resetOnFail,
                    ),
                )
            }
        }

        // Validate exit targets. Missing targets are logged as warnings and treated
        // as remote/unreachable exits (players see "the way shimmers" instead of crashing).
        val loadedZones = mergedRooms.keys.mapTo(mutableSetOf()) { it.zone }
        val filteredLoad = zoneFilter.isNotEmpty()
        val brokenExits = mutableSetOf<Pair<RoomId, Direction>>()
        for ((fromId, exits) in allExits) {
            for ((dir, targetId) in exits) {
                if (filteredLoad && targetId.zone !in loadedZones) continue
                if (!mergedRooms.containsKey(targetId)) {
                    logger.warn(
                        "Room '${fromId.value}' exit '$dir' points to missing room '${targetId.value}' — " +
                            "exit will shimmer as unreachable",
                    )
                    brokenExits.add(fromId to dir)
                }
            }
        }

        // Apply exits + features by copying rooms (immutable style).
        // Exits that point to unloaded zones or broken targets are marked as remote
        // so navigation shows the shimmer message instead of crashing.
        for ((fromId, exits) in allExits) {
            val room = mergedRooms.getValue(fromId)
            val remoteExits = buildSet {
                if (filteredLoad) {
                    addAll(exits.filterValues { it.zone !in loadedZones }.keys)
                }
                for ((dir, _) in exits) {
                    if ((fromId to dir) in brokenExits) add(dir)
                }
            }
            mergedRooms[fromId] = room.copy(
                exits = exits,
                remoteExits = remoteExits,
                features = allRoomFeatures[fromId] ?: emptyList(),
                station = room.station,
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

        // Validate trainer room references after merge
        for (trainer in mergedTrainers) {
            if (!mergedRooms.containsKey(trainer.roomId)) {
                throw WorldLoadException(
                    "Trainer '${trainer.id}' references missing room '${trainer.roomId.value}'",
                )
            }
        }

        // Validate gathering node references after merge
        for (node in mergedGatheringNodes) {
            if (!mergedRooms.containsKey(node.roomId)) {
                throw WorldLoadException(
                    "Gathering node '${node.id}' references missing room '${node.roomId.value}'",
                )
            }
            for ((index, yield) in node.yields.withIndex()) {
                if (!mergedItems.containsKey(yield.itemId)) {
                    throw WorldLoadException(
                        "Gathering node '${node.id}' yield #${index + 1} references missing item '${yield.itemId.value}'",
                    )
                }
            }
        }

        // Validate recipe references after merge
        for (recipe in mergedRecipes) {
            for ((index, mat) in recipe.materials.withIndex()) {
                if (!mergedItems.containsKey(mat.itemId)) {
                    throw WorldLoadException(
                        "Recipe '${recipe.id}' material #${index + 1} references missing item '${mat.itemId.value}'",
                    )
                }
            }
            if (!mergedItems.containsKey(recipe.outputItemId)) {
                throw WorldLoadException(
                    "Recipe '${recipe.id}' output references missing item '${recipe.outputItemId.value}'",
                )
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

        // Assign minimap coordinates via per-zone BFS
        val coordRooms = assignMapCoordinates(mergedRooms, zoneStartRooms)

        return World(
            rooms = coordRooms.toMutableMap(),
            startRoom = worldStart,
            mobSpawns = mergedMobs.values.sortedBy { it.id.value },
            itemSpawns = mergedItems.values.sortedBy { it.instance.id.value },
            zoneLifespansMinutes =
                zoneLifespansMinutes.entries
                    .mapNotNull { (zone, lifespanMinutes) -> lifespanMinutes?.let { zone to it } }
                    .toMap(),
            pvpZones = pvpZones.toSet(),
            zoneStartRooms = zoneStartRooms.toMap(),
            shopDefinitions = mergedShops.toList(),
            trainerDefinitions = mergedTrainers.toList(),
            questDefinitions = mergedQuests.toList(),
            gatheringNodes = mergedGatheringNodes.toList(),
            recipes = mergedRecipes.toList(),
            dungeonTemplates = mergedDungeonTemplates.toList(),
            puzzleDefinitions = mergedPuzzles.toList(),
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
        val zone = requireNonBlank(file.zone) { "World zone cannot be blank" }
        if (file.lifespan != null) {
            requireAtLeast(file.lifespan, 0L, "Zone '$zone'", "lifespan")
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

    private fun normalizeBaseUrl(url: String): String = if (url.endsWith("/")) url else "$url/"

    /**
     * Normalize a room id that is expected to be "local to zone" unless qualified.
     */
    private fun normalizeId(
        zone: String,
        raw: String,
    ): RoomId {
        val s = requireNonBlank(raw) { "Room id cannot be blank" }
        return RoomId(qualifyId(zone, s))
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
        val s = requireNonBlank(raw) { "Mob id cannot be blank" }
        return MobId(qualifyId(zone, s))
    }

    private fun normalizeItemId(
        zone: String,
        raw: String,
    ): ItemId {
        val s = requireNonBlank(raw) { "Item id cannot be blank" }
        return ItemId(qualifyId(zone, s))
    }

    private fun normalizeKeyword(
        rawId: String,
        rawKeyword: String?,
    ): String {
        if (rawKeyword != null) {
            return requireNonBlank(rawKeyword) { "Item keyword cannot be blank" }
        }
        return keywordFromId(rawId)
    }

    private fun keywordFromId(rawId: String): String {
        val trimmed = requireNonBlank(rawId) { "Item keyword cannot be blank" }
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
                "Item '${itemId.value}' has invalid slot '$raw' (slot must be non-empty)",
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

        // Inline tree definition takes precedence over template
        if (behaviorFile.tree != null) {
            return try {
                BehaviorTreeLoader.load(behaviorFile.tree, zone)
            } catch (e: IllegalArgumentException) {
                throw WorldLoadException(
                    "Mob '${mobId.value}' has invalid inline behavior tree: ${e.message}",
                )
            }
        }

        val templateName = behaviorFile.template
            ?: throw WorldLoadException(
                "Mob '${mobId.value}' behavior must specify either 'template' or 'tree'.",
            )

        val tree =
            BehaviorTemplates.resolve(
                templateName,
                behaviorFile.params,
                zone,
            ) ?: throw WorldLoadException(
                "Mob '${mobId.value}' references unknown behavior template '$templateName'. " +
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

    private fun parseLockableState(
        raw: String?,
        context: String,
    ): LockableState =
        when (raw?.trim()?.lowercase() ?: "closed") {
            "open" -> LockableState.OPEN
            "closed" -> LockableState.CLOSED
            "locked" -> LockableState.LOCKED
            else -> throw WorldLoadException("$context has invalid initialState '$raw' (expected: open, closed, locked)")
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
        val displayName = requireNonBlank(ff.displayName) {
            "Feature '$featId' displayName cannot be blank"
        }
        val keyword = requireNonBlank(ff.keyword) {
            "Feature '$featId' keyword cannot be blank"
        }
        return when (type) {
            "CONTAINER" -> {
                val keyItemId =
                    ff.keyItemId?.trim()?.takeUnless { it.isEmpty() }?.let {
                        normalizeItemId(zone, it)
                    }
                val initialState = parseLockableState(ff.initialState, "Container '$featId'")
                val initialItems =
                    ff.items.mapIndexed { index, rawItemId ->
                        val s = requireNonBlank(rawItemId) {
                            "Container '$featId' item #${index + 1} cannot be blank"
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

    private fun parseCraftingSkill(
        raw: String,
        context: String,
    ): String {
        val id = raw.trim().lowercase()
        if (id.isEmpty()) throw WorldLoadException("$context crafting skill cannot be blank")
        return id
    }

    private fun parseCraftingStationType(
        raw: String,
        context: String,
    ): String {
        val id = raw.trim().lowercase()
        if (id.isEmpty()) throw WorldLoadException("$context station type cannot be blank")
        return id
    }

    private inline fun requireNonBlank(value: String, lazyMessage: () -> String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) throw WorldLoadException(lazyMessage())
        return trimmed
    }

    private fun requireAtLeast(value: Int, min: Int, context: String, field: String): Int {
        if (value < min) throw WorldLoadException("$context $field must be >= $min (got $value)")
        return value
    }

    private fun requireAtLeast(value: Long, min: Long, context: String, field: String): Long {
        if (value < min) throw WorldLoadException("$context $field must be >= $min (got $value)")
        return value
    }

    private fun parsePuzzleReward(
        puzzleId: String,
        zone: String,
        reward: dev.ambon.domain.world.data.PuzzleRewardFile,
    ): PuzzleReward = when (reward.type.trim().lowercase()) {
        "unlock_exit" -> {
            val dirStr = reward.exitDirection
                ?: throw WorldLoadException("Puzzle '$puzzleId' unlock_exit reward requires exitDirection")
            val dir = parseDirectionOrNull(dirStr)
                ?: throw WorldLoadException("Puzzle '$puzzleId' unknown exit direction '${reward.exitDirection}'")
            val targetStr = reward.targetRoom
                ?: throw WorldLoadException("Puzzle '$puzzleId' unlock_exit reward requires targetRoom")
            PuzzleReward.UnlockExit(direction = dir, targetRoom = normalizeTarget(zone, targetStr))
        }
        "give_item" -> {
            val itemId = reward.itemId
                ?: throw WorldLoadException("Puzzle '$puzzleId' give_item reward requires itemId")
            PuzzleReward.GiveItem(itemId = qualifyId(zone, itemId))
        }
        "give_gold" -> {
            if (reward.gold <= 0) throw WorldLoadException("Puzzle '$puzzleId' give_gold reward requires gold > 0")
            PuzzleReward.GiveGold(amount = reward.gold)
        }
        "give_xp" -> {
            if (reward.xp <= 0) throw WorldLoadException("Puzzle '$puzzleId' give_xp reward requires xp > 0")
            PuzzleReward.GiveXp(amount = reward.xp)
        }
        else -> throw WorldLoadException(
            "Puzzle '$puzzleId' has unknown reward type '${reward.type}' " +
                "(expected unlock_exit, give_item, give_gold, or give_xp)",
        )
    }

    private fun requireNotEmpty(collection: Collection<*>, context: String, what: String) {
        if (collection.isEmpty()) throw WorldLoadException("$context must have at least one $what")
    }

    /**
     * Direction → grid offset for minimap coordinate assignment.
     * Must match the client-side MAP_OFFSETS in constants.ts.
     */
    private val DIRECTION_OFFSETS: Map<Direction, Pair<Int, Int>> = mapOf(
        Direction.NORTH to (0 to -1),
        Direction.SOUTH to (0 to 1),
        Direction.EAST to (1 to 0),
        Direction.WEST to (-1 to 0),
        Direction.UP to (1 to -1),
        Direction.DOWN to (-1 to 1),
    )

    /**
     * Assigns 2D minimap coordinates to every room via per-zone BFS.
     *
     * Each zone is laid out independently, starting from the zone's declared start room at (0,0).
     * Only horizontal exits (N/S/E/W) are traversed during BFS — up/down exits are treated as
     * portals rather than spatial moves, so they don't scatter rooms diagonally or cause grid
     * collisions with unrelated branches. Rooms reachable only via up/down are placed in a
     * second pass relative to their horizontal neighbors.
     *
     * When two rooms would occupy the same grid cell (non-euclidean exit topology), the later
     * arrival is placed at the nearest unoccupied cell via a spiral search.
     */
    private fun assignMapCoordinates(
        rooms: Map<RoomId, Room>,
        zoneStartRooms: Map<String, RoomId>,
    ): Map<RoomId, Room> {
        // Group rooms by zone
        val roomsByZone = rooms.keys.groupBy { it.zone }
        val coords = HashMap<RoomId, Pair<Int, Int>>(rooms.size)

        data class Pending(
            val roomId: RoomId,
            val x: Int,
            val y: Int,
        )

        val horizontalDirs = setOf(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)

        for ((zone, roomIds) in roomsByZone) {
            val zoneRoomSet = roomIds.toHashSet()
            val occupied = HashMap<Pair<Int, Int>, RoomId>()
            val startId = zoneStartRooms[zone] ?: roomIds.first()

            // Phase 1: BFS using only horizontal exits (N/S/E/W).
            val queue = ArrayDeque<Pending>()
            queue.addLast(Pending(startId, 0, 0))

            while (queue.isNotEmpty()) {
                val (roomId, desiredX, desiredY) = queue.removeFirst()
                if (coords.containsKey(roomId)) continue

                val pos = findFreePosition(desiredX, desiredY, occupied)
                coords[roomId] = pos
                occupied[pos] = roomId

                val room = rooms[roomId] ?: continue
                for ((dir, targetId) in room.exits) {
                    if (dir !in horizontalDirs) continue
                    if (coords.containsKey(targetId)) continue
                    if (targetId !in zoneRoomSet) continue
                    val (dx, dy) = DIRECTION_OFFSETS[dir] ?: continue
                    queue.addLast(Pending(targetId, pos.first + dx, pos.second + dy))
                }
            }

            // Phase 2: Place rooms not reached by horizontal BFS (reachable only via up/down,
            // or completely unreachable dead-ends). Try to position relative to an already-placed
            // horizontal neighbor; fall back to placing near any connected neighbor.
            for (unreachedId in roomIds) {
                if (coords.containsKey(unreachedId)) continue
                val room = rooms[unreachedId] ?: continue
                var placed = false
                // Prefer horizontal neighbors for placement (they have reliable offsets)
                for ((dir, neighborId) in room.exits) {
                    val neighborPos = coords[neighborId] ?: continue
                    if (dir in horizontalDirs) {
                        val (dx, dy) = DIRECTION_OFFSETS[dir] ?: continue
                        val desiredPos = findFreePosition(neighborPos.first - dx, neighborPos.second - dy, occupied)
                        coords[unreachedId] = desiredPos
                        occupied[desiredPos] = unreachedId
                        placed = true
                        break
                    }
                }
                // Fall back to placing near any connected neighbor
                if (!placed) {
                    for ((_, neighborId) in room.exits) {
                        val neighborPos = coords[neighborId] ?: continue
                        val desiredPos = findFreePosition(neighborPos.first, neighborPos.second, occupied)
                        coords[unreachedId] = desiredPos
                        occupied[desiredPos] = unreachedId
                        placed = true
                        break
                    }
                }
                if (!placed) {
                    val pos = findFreePosition(0, 0, occupied)
                    coords[unreachedId] = pos
                    occupied[pos] = unreachedId
                }
            }
        }

        return rooms.mapValues { (id, room) ->
            val (x, y) = coords[id] ?: (0 to 0)
            room.copy(mapX = x, mapY = y)
        }
    }

    /**
     * If the desired (x, y) is free, returns it.
     * Otherwise spirals outward to find the nearest unoccupied cell.
     */
    private fun findFreePosition(
        x: Int,
        y: Int,
        occupied: Map<Pair<Int, Int>, RoomId>,
    ): Pair<Int, Int> {
        val pos = x to y
        if (!occupied.containsKey(pos)) return pos

        // Walk the perimeter of expanding squares
        for (radius in 1..500) {
            // Top and bottom edges
            for (dx in -radius..radius) {
                val top = (x + dx) to (y - radius)
                if (!occupied.containsKey(top)) return top
                val bottom = (x + dx) to (y + radius)
                if (!occupied.containsKey(bottom)) return bottom
            }
            // Left and right edges (excluding corners already checked)
            for (dy in -radius + 1..<radius) {
                val left = (x - radius) to (y + dy)
                if (!occupied.containsKey(left)) return left
                val right = (x + radius) to (y + dy)
                if (!occupied.containsKey(right)) return right
            }
        }
        return pos
    }
}
