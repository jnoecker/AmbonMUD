package dev.ambon.engine

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.ambon.bus.OutboundBus
import dev.ambon.config.CommandMetadata
import dev.ambon.config.EmotePresetConfig
import dev.ambon.domain.StatMap
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.items.ItemSlot
import dev.ambon.domain.mob.MobState
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.World
import dev.ambon.engine.abilities.AbilityDefinition
import dev.ambon.engine.abilities.AbilityId
import dev.ambon.engine.abilities.AbilitySystem
import dev.ambon.engine.abilities.toEffectType
import dev.ambon.engine.events.CombatEvent
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.status.ActiveEffectSnapshot
import dev.ambon.engine.status.StatusEffectSystem
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.math.roundToInt

private val log = KotlinLogging.logger {}

data class CombatTargetInfo(
    val id: String,
    val name: String,
    val hp: Int,
    val maxHp: Int,
    val image: String? = null,
)

/** Input DTO for building a Server.Who GMCP payload. */
data class WhoEntry(
    val name: String,
    val level: Int,
    val race: String,
    val playerClass: String,
    val title: String?,
    val guild: String?,
    val groupSize: Int,
    val idleSeconds: Long,
)

class GmcpEmitter(
    private val outbound: OutboundBus,
    private val supportsPackage: (SessionId, String) -> Boolean,
    private val progression: PlayerProgression? = null,
    private val isInCombat: (SessionId) -> Boolean = { false },
    private val getCombatTarget: (SessionId) -> CombatTargetInfo? = { null },
    private val statRegistry: StatRegistry? = null,
    private val equipmentSlotRegistry: EquipmentSlotRegistry? = null,
    imagesBaseUrl: String = "/images/",
    private val globalAssets: Map<String, String> = emptyMap(),
    private val spriteRegistry: SpriteRegistry? = null,
    private val getMobEffects: (dev.ambon.domain.ids.MobId) -> List<ActiveEffectSnapshot> = { emptyList() },
    private val commandEntries: Map<String, CommandMetadata> = emptyMap(),
    private val emotePresets: List<EmotePresetConfig> = emptyList(),
    private val prestigeAvailableXp: (PlayerState) -> Long? = { null },
    private val prestigeNextCost: (Int) -> Long? = { null },
) {
    private val json = jacksonObjectMapper()
    private val imagesBase = if (imagesBaseUrl.endsWith("/")) imagesBaseUrl else "$imagesBaseUrl/"

    /**
     * Tracks the last zone seen by each session so we only emit zone-change GMCP
     * when the player actually moves to a new zone.  Bounded with LRU eviction
     * as a safety net against orphaned sessions that somehow bypass [forgetSession].
     */
    private val lastZoneBySession: MutableMap<SessionId, String> =
        object : LinkedHashMap<SessionId, String>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SessionId, String>): Boolean =
                size > MAX_ZONE_CACHE_ENTRIES
        }

    /** Returns true if the zone changed (or is first seen) for this session. */
    fun trackZoneChange(sessionId: SessionId, zone: String): Boolean {
        val prev = lastZoneBySession.put(sessionId, zone)
        return prev == null || prev != zone
    }

    /** Remove tracked zone state for a disconnected session. */
    fun forgetSession(sessionId: SessionId) {
        lastZoneBySession.remove(sessionId)
    }

    /** Resolved asset URLs: each value from [globalAssets] is prefixed with [imagesBase]. */
    private val resolvedAssets: Map<String, String> =
        globalAssets.mapValues { (_, path) -> "$imagesBase$path" }

    suspend fun sendCharVitals(
        sessionId: SessionId,
        player: PlayerState,
    ) {
        emit(
            sessionId,
            "Char.Vitals",
            CharVitalsPayload(
                hp = player.hp,
                maxHp = player.maxHp,
                mana = player.mana,
                maxMana = player.maxMana,
                level = player.level,
                xp = player.xpTotal,
                xpIntoLevel = progression?.xpIntoLevel(player.xpTotal) ?: 0L,
                xpToNextLevel = progression?.xpToNextLevel(player.xpTotal),
                gold = player.gold,
                inCombat = isInCombat(sessionId),
                prestigeLevel = player.prestigeLevel,
                prestigeXpAvailable = prestigeAvailableXp(player),
                prestigeNextCost = prestigeNextCost(player.prestigeLevel),
                pvpKills = player.pvpKills,
                pvpDeaths = player.pvpDeaths,
            ),
        )
    }

    suspend fun sendCharCombat(sessionId: SessionId) {
        val target = getCombatTarget(sessionId)
        emit(
            sessionId,
            "Char.Combat",
            CharCombatPayload(
                targetId = target?.id,
                targetName = target?.name,
                targetHp = target?.hp,
                targetMaxHp = target?.maxHp,
                targetImage = target?.image,
            ),
        )
    }

    suspend fun sendRoomInfo(
        sessionId: SessionId,
        room: Room,
        isHousing: Boolean = false,
        housingOwner: String? = null,
        pvpEnabled: Boolean = false,
    ) {
        emit(
            sessionId,
            "Room.Info",
            RoomInfoPayload(
                id = room.id.value,
                title = room.title,
                description = room.description,
                zone = room.id.zone,
                exits = room.exits.entries.associate { (dir, roomId) -> dir.name.lowercase() to roomId.value },
                image = room.image,
                video = room.video,
                music = room.music,
                ambient = room.ambient,
                station = room.station,
                mapX = room.mapX,
                mapY = room.mapY,
                housing = isHousing,
                housingOwner = housingOwner,
                graphical = room.graphical,
                pvpEnabled = pvpEnabled,
            ),
        )
    }

    /**
     * Send the full room layout for a zone so the client can render a fog-of-war
     * map with cloud-reveal as the player explores. Only horizontal exits (N/S/E/W)
     * are included — vertical transitions are handled by floor buttons.
     */
    suspend fun sendZoneMap(
        sessionId: SessionId,
        zone: String,
        rooms: Collection<Room>,
    ) {
        val horizontalDirs = setOf(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)
        emit(
            sessionId,
            "Zone.Map",
            ZoneMapPayload(
                zone = zone,
                rooms = rooms.map { r ->
                    ZoneMapRoom(
                        id = r.id.value,
                        x = r.mapX,
                        y = r.mapY,
                        exits = r.exits.entries
                            .filter { (dir, target) -> dir in horizontalDirs && target.zone == zone }
                            .associate { (dir, target) -> dir.name.lowercase() to target.value },
                    )
                },
            ),
            supportCheck = "Zone.Map",
        )
    }

    suspend fun sendCharStatusVars(sessionId: SessionId) {
        emitRaw(sessionId, "Char.StatusVars", CHAR_STATUS_VARS_JSON)
    }

    suspend fun sendCharItemsList(
        sessionId: SessionId,
        inventory: List<ItemInstance>,
        equipment: Map<ItemSlot, ItemInstance>,
    ) {
        emit(
            sessionId,
            "Char.Items.List",
            CharItemsListPayload(
                inventory = inventory.map { toItemPayload(it) },
                equipment = equipmentSlotMap(equipment),
            ),
        )
    }

    /**
     * Sends equipment slot definitions (id, displayName, order, x/y positions)
     * so the client can render a paper-doll equipment panel.
     */
    suspend fun sendEquipmentSlots(sessionId: SessionId) {
        val registry = equipmentSlotRegistry ?: return
        val payload = registry.all().map { def ->
            EquipmentSlotPayload(
                id = def.slot.name,
                displayName = def.displayName,
                order = def.order,
                x = def.x,
                y = def.y,
            )
        }
        emit(sessionId, "Char.Equipment.Slots", payload)
    }

    suspend fun sendCharItemsAdd(
        sessionId: SessionId,
        item: ItemInstance,
    ) {
        emit(sessionId, "Char.Items.Add", toItemPayload(item))
    }

    suspend fun sendCharItemsRemove(
        sessionId: SessionId,
        item: ItemInstance,
    ) {
        emit(sessionId, "Char.Items.Remove", CharItemsRemovePayload(id = item.id.value, name = item.item.displayName))
    }

    suspend fun sendRoomPlayers(
        sessionId: SessionId,
        players: List<PlayerState>,
    ) {
        emit(
            sessionId,
            "Room.Players",
            players.filter { it.sessionId != sessionId && !it.invisible }
                .map { RoomPlayerPayload(name = it.name, level = it.level) },
        )
    }

    suspend fun sendRoomAddPlayer(
        sessionId: SessionId,
        player: PlayerState,
    ) {
        emit(sessionId, "Room.AddPlayer", RoomPlayerPayload(name = player.name, level = player.level), supportCheck = "Room.Players")
    }

    suspend fun sendRoomRemovePlayer(
        sessionId: SessionId,
        name: String,
    ) {
        emit(sessionId, "Room.RemovePlayer", RoomRemovePlayerPayload(name = name), supportCheck = "Room.Players")
    }

    suspend fun sendRoomMobs(
        sessionId: SessionId,
        mobs: List<MobState>,
    ) {
        emit(sessionId, "Room.Mobs", mobs.map { toRoomMobPayload(it) })
    }

    suspend fun sendRoomItems(
        sessionId: SessionId,
        items: List<ItemInstance>,
    ) {
        emit(
            sessionId,
            "Room.Items",
            items.map {
                RoomItemPayload(
                    id = it.id.value,
                    name = it.item.displayName,
                    description = it.item.description,
                    image = it.item.image,
                    video = it.item.video,
                )
            },
        )
    }

    suspend fun sendRoomAddMob(
        sessionId: SessionId,
        mob: MobState,
    ) {
        emit(sessionId, "Room.AddMob", toRoomMobPayload(mob), supportCheck = "Room.Mobs")
    }

    suspend fun sendRoomUpdateMob(
        sessionId: SessionId,
        mob: MobState,
    ) {
        emit(sessionId, "Room.UpdateMob", toRoomMobPayload(mob), supportCheck = "Room.Mobs")
    }

    suspend fun sendRoomRemoveMob(
        sessionId: SessionId,
        mobId: String,
    ) {
        emit(sessionId, "Room.RemoveMob", RoomRemoveMobPayload(id = mobId), supportCheck = "Room.Mobs")
    }

    // ── Room-level broadcast helpers ─────────────────────────────────────

    suspend fun broadcastRoomAddMob(roomId: RoomId, mob: MobState, players: PlayerRegistry) {
        for (p in players.playersInRoom(roomId)) sendRoomAddMob(p.sessionId, mob)
    }

    suspend fun broadcastRoomUpdateMob(roomId: RoomId, mob: MobState, players: PlayerRegistry) {
        for (p in players.playersInRoom(roomId)) sendRoomUpdateMob(p.sessionId, mob)
    }

    suspend fun broadcastRoomRemoveMob(roomId: RoomId, mobId: String, players: PlayerRegistry) {
        for (p in players.playersInRoom(roomId)) sendRoomRemoveMob(p.sessionId, mobId)
    }

    suspend fun broadcastRoomItems(roomId: RoomId, items: List<ItemInstance>, players: PlayerRegistry) {
        for (p in players.playersInRoom(roomId)) sendRoomItems(p.sessionId, items)
    }

    suspend fun sendCharSkills(
        sessionId: SessionId,
        abilities: List<AbilityDefinition>,
        cooldownRemainingMs: (AbilityId) -> Long = { 0L },
    ) {
        emit(
            sessionId,
            "Char.Skills",
            abilities.map { a ->
                CharSkillPayload(
                    id = a.id.value,
                    name = a.displayName,
                    description = a.description,
                    manaCost = a.manaCost,
                    cooldownMs = a.cooldownMs,
                    cooldownRemainingMs = cooldownRemainingMs(a.id).coerceAtLeast(0L),
                    levelRequired = a.levelRequired,
                    targetType = a.targetType,
                    effectType = a.effect.toEffectType(),
                    classRestriction = a.requiredClass,
                    image = a.image,
                    prerequisites = a.prerequisites.map { it.value },
                    tree = a.tree,
                    tier = a.tier,
                )
            },
        )
    }

    suspend fun sendCharName(
        sessionId: SessionId,
        player: PlayerState,
    ) {
        emit(
            sessionId,
            "Char.Name",
            CharNamePayload(
                name = player.name,
                gender = player.gender,
                race = player.race,
                playerClass = player.playerClass,
                level = player.level,
                sprite = resolveSprite(player),
                isStaff = player.isStaff,
            ),
        )
    }

    suspend fun sendSessionAuthToken(
        sessionId: SessionId,
        token: String,
        characterName: String,
        expiresInDays: Int,
    ) {
        emit(
            sessionId,
            "Session.AuthToken",
            SessionAuthTokenPayload(token = token, characterName = characterName, expiresInDays = expiresInDays),
        )
    }

    suspend fun sendSessionAuthResult(
        sessionId: SessionId,
        success: Boolean,
        message: String = "",
    ) {
        emit(sessionId, "Session.AuthResult", SessionAuthResultPayload(success = success, message = message))
    }

    suspend fun sendSessionResumeToken(
        sessionId: SessionId,
        token: String,
        expiresInSeconds: Int,
    ) {
        emit(
            sessionId,
            "Session.ResumeToken",
            SessionResumeTokenPayload(token = token, expiresIn = expiresInSeconds),
        )
    }

    suspend fun sendSessionResumeResult(
        sessionId: SessionId,
        success: Boolean,
    ) {
        emit(sessionId, "Session.ResumeResult", SessionResumeResultPayload(success = success))
    }

    suspend fun sendCommChannel(
        sessionId: SessionId,
        channel: String,
        sender: String,
        message: String,
    ) {
        emit(sessionId, "Comm.Channel", CommChannelPayload(channel = channel, sender = sender, message = message))
    }

    suspend fun sendCharStatusEffects(
        sessionId: SessionId,
        effects: List<ActiveEffectSnapshot>,
    ) {
        emit(
            sessionId,
            "Char.StatusEffects",
            effects.map { e ->
                CharStatusEffectPayload(
                    id = e.id,
                    name = e.name,
                    type = e.type,
                    remainingMs = e.remainingMs,
                    stacks = e.stacks,
                )
            },
        )
    }

    suspend fun sendGroupInfo(
        sessionId: SessionId,
        leader: String?,
        members: List<PlayerState>,
    ) {
        emit(
            sessionId,
            "Group.Info",
            GroupInfoPayload(
                leader = leader,
                members =
                    members.map { p ->
                        GroupMemberPayload(
                            name = p.name,
                            level = p.level,
                            hp = p.hp,
                            maxHp = p.maxHp,
                            mana = p.mana,
                            maxMana = p.maxMana,
                            playerClass = p.playerClass,
                        )
                    },
            ),
        )
    }

    suspend fun sendGroupInvite(
        sessionId: SessionId,
        inviterName: String,
    ) {
        emit(
            sessionId,
            "Group.Invite",
            GroupInvitePayload(inviterName = inviterName),
            supportCheck = "Group.Info",
        )
    }

    suspend fun sendCorePing(sessionId: SessionId) {
        emitRaw(sessionId, "Core.Ping", CORE_PING_JSON)
    }

    /** Sends resolved global asset URLs as `Server.Assets`. */
    suspend fun sendServerAssets(sessionId: SessionId) {
        if (resolvedAssets.isEmpty()) return
        emit(sessionId, "Server.Assets", resolvedAssets)
    }

    /**
     * Sends the command manifest as `Server.Commands`.
     * Staff players receive all commands; non-staff players receive only non-staff commands.
     */
    suspend fun sendServerCommands(sessionId: SessionId, isStaff: Boolean) {
        if (commandEntries.isEmpty()) return
        val commands = commandEntries
            .filter { (_, meta) -> isStaff || !meta.staff }
            .map { (name, meta) ->
                ServerCommandPayload(
                    name = name,
                    usage = meta.usage,
                    description = meta.description,
                    category = meta.category,
                    staff = meta.staff,
                    requiresTarget = meta.requiresTarget,
                )
            }
        emit(sessionId, "Server.Commands", ServerCommandsPayload(commands = commands))
    }

    /** Sends emote presets as `Server.EmotePresets`. */
    suspend fun sendServerEmotePresets(sessionId: SessionId) {
        if (emotePresets.isEmpty()) return
        val payload = emotePresets.map { p ->
            EmotePresetPayload(label = p.label, emoji = p.emoji, action = p.action)
        }
        emit(sessionId, "Server.EmotePresets", payload)
    }

    /** Sends the structured who list as `Server.Who`. */
    suspend fun sendServerWho(sessionId: SessionId, entries: List<WhoEntry>) {
        val payload = ServerWhoPayload(
            players = entries.map { e ->
                WhoPlayerPayload(
                    name = e.name,
                    level = e.level,
                    race = e.race,
                    playerClass = e.playerClass,
                    title = e.title,
                    guild = e.guild,
                    groupSize = e.groupSize,
                    idle = e.idleSeconds,
                )
            },
        )
        emit(sessionId, "Server.Who", payload, supportCheck = "Server.Who")
    }

    /** Sends zone instance list as `Zone.Instances`. Pass null to clear. */
    suspend fun sendZoneInstances(
        sessionId: SessionId,
        zone: String,
        currentEngineId: String,
        instances: List<ZoneInstanceEntry>,
    ) {
        emit(
            sessionId,
            "Zone.Instances",
            ZoneInstancesPayload(
                zone = zone,
                currentEngineId = currentEngineId,
                instances = instances.map { i ->
                    ZoneInstanceItemPayload(
                        engineId = i.engineId,
                        playerCount = i.playerCount,
                        capacity = i.capacity,
                        isCurrent = i.engineId == currentEngineId,
                    )
                },
            ),
            supportCheck = "Zone.Instances",
        )
    }

    /** Clears zone instance data (single-instance zone or instancing disabled). */
    suspend fun clearZoneInstances(sessionId: SessionId) {
        emit(
            sessionId,
            "Zone.Instances",
            ZoneInstancesPayload(zone = null, currentEngineId = null, instances = emptyList()),
            supportCheck = "Zone.Instances",
        )
    }

    /**
     * Sends the `Trainer.List` GMCP package for the trainer in the current room.
     */
    suspend fun sendTrainerList(
        sessionId: SessionId,
        trainer: dev.ambon.domain.world.TrainerDefinition,
        abilities: List<AbilityDefinition>,
        availableSkillPoints: Int,
        classUnlocked: Boolean,
        multiclassConfig: dev.ambon.config.MulticlassConfig,
    ) {
        emit(
            sessionId,
            "Trainer.List",
            TrainerListPayload(
                trainerId = trainer.id,
                name = trainer.name,
                className = trainer.className,
                image = trainer.image,
                classUnlocked = classUnlocked,
                availableSkillPoints = availableSkillPoints,
                multiclassMinLevel = multiclassConfig.minLevel,
                multiclassGoldCost = multiclassConfig.goldCost,
                abilities = abilities.map { a ->
                    TrainerAbilityPayload(
                        id = a.id.value,
                        name = a.displayName,
                        description = a.description,
                        levelRequired = a.levelRequired,
                        manaCost = a.manaCost,
                        cooldownMs = a.cooldownMs,
                        targetType = a.targetType,
                        effectType = a.effect.toEffectType(),
                        image = a.image,
                        prerequisites = a.prerequisites.map { it.value },
                        tree = a.tree,
                        tier = a.tier,
                    )
                },
            ),
            supportCheck = "Trainer",
        )
    }

    /** Sends `Char.Classes` with the player's unlocked classes. */
    suspend fun sendCharClasses(
        sessionId: SessionId,
        unlockedClasses: Set<String>,
        originalClass: String,
    ) {
        emit(
            sessionId,
            "Char.Classes",
            CharClassesPayload(
                originalClass = originalClass,
                unlockedClasses = unlockedClasses.toList(),
            ),
            supportCheck = "Char.Classes",
        )
    }

    /**
     * Sends the full character GMCP state: status vars, vitals, name, items,
     * skills, status effects, achievements, and group info. Called on login
     * and when a client negotiates GMCP support.
     */
    suspend fun sendFullCharacterSync(
        sessionId: SessionId,
        player: PlayerState,
        items: ItemRegistry,
        abilitySystem: AbilitySystem,
        statusEffectSystem: StatusEffectSystem,
        achievementRegistry: AchievementRegistry,
        groupSystem: GroupSystem,
        players: PlayerRegistry,
        guildSystem: GuildSystem? = null,
    ) {
        sendServerAssets(sessionId)
        sendServerCommands(sessionId, player.isStaff)
        sendServerEmotePresets(sessionId)
        sendCharStatusVars(sessionId)
        sendCharVitals(sessionId, player)
        sendCharName(sessionId, player)
        sendEquipmentSlots(sessionId)
        sendCharItemsList(sessionId, items.inventory(sessionId), items.equipment(sessionId))
        sendCharSkills(sessionId, abilitySystem.knownAbilities(sessionId)) { abilityId ->
            abilitySystem.cooldownRemainingMs(sessionId, abilityId)
        }
        sendCharStatusEffects(sessionId, statusEffectSystem.activePlayerEffects(sessionId))
        sendCharAchievements(sessionId, player, achievementRegistry)
        sendCharSprites(sessionId, player)
        sendCharClasses(sessionId, player.unlockedClasses.ifEmpty { setOf(player.playerClass) }, player.playerClass)
        sendGroupSync(sessionId, groupSystem, players)
        guildSystem?.sendGuildSync(sessionId)
    }

    /**
     * Resolves and sends the current group state for [sessionId].
     * If the player is not in a group, sends an empty group payload.
     */
    suspend fun sendGroupSync(
        sessionId: SessionId,
        groupSystem: GroupSystem,
        players: PlayerRegistry,
    ) {
        val group = groupSystem.getGroup(sessionId)
        if (group != null) {
            val leader = players.get(group.leader)?.name
            val members = group.members.mapNotNull { players.get(it) }
            sendGroupInfo(sessionId, leader, members)
        } else {
            sendGroupInfo(sessionId, null, emptyList())
        }
    }

    suspend fun sendCharAchievements(
        sessionId: SessionId,
        player: PlayerState,
        registry: AchievementRegistry,
    ) {
        val completed =
            player.unlockedAchievementIds.map { id ->
                val def = registry.get(id)
                CompletedAchievementPayload(
                    id = id,
                    name = def?.displayName ?: id,
                    title = def?.rewards?.title,
                )
            }
        val inProgress =
            player.achievementProgress.entries
                .filter { (id, _) -> registry.get(id)?.hidden != true }
                .map { (id, state) ->
                    val def = registry.get(id)
                    InProgressAchievementPayload(
                        id = id,
                        name = def?.displayName ?: id,
                        current = state.progress.sumOf { it.current },
                        required = state.progress.sumOf { it.required },
                    )
                }
        emit(sessionId, "Char.Achievements", CharAchievementsPayload(completed = completed, inProgress = inProgress))
    }

    // ---------- friends ----------

    suspend fun sendFriendsList(sessionId: SessionId, friends: List<FriendInfo>) {
        emit(
            sessionId,
            "Friends.List",
            friends.map { f ->
                FriendPayload(name = f.name, online = f.online, level = f.level, zone = f.zone)
            },
        )
    }

    suspend fun sendFriendOnline(sessionId: SessionId, friendName: String, level: Int) {
        emit(sessionId, "Friends.Online", FriendEventPayload(name = friendName, level = level))
    }

    suspend fun sendFriendOffline(sessionId: SessionId, friendName: String) {
        emit(sessionId, "Friends.Offline", FriendOfflinePayload(name = friendName))
    }

    // ---------- housing ----------

    suspend fun sendHousingInfo(
        sessionId: SessionId,
        hasHouse: Boolean,
        ownerName: String? = null,
        rooms: List<HousingRoomPayload> = emptyList(),
    ) {
        emit(
            sessionId,
            "Housing.Info",
            HousingInfoPayload(hasHouse = hasHouse, ownerName = ownerName, rooms = rooms),
        )
    }

    data class HousingRoomPayload(
        val templateId: String,
        val title: String,
        val description: String,
    )

    private data class HousingInfoPayload(
        val hasHouse: Boolean,
        val ownerName: String?,
        val rooms: List<HousingRoomPayload>,
    )

    // ---------- combat events ----------

    suspend fun sendCombatEvent(
        sessionId: SessionId,
        event: CombatEvent,
    ) {
        val payload = when (event) {
            is CombatEvent.MeleeHit -> CombatEventPayload(
                type = "meleeHit",
                targetName = event.targetName,
                targetId = event.targetId,
                damage = event.damage,
                sourceIsPlayer = event.sourceIsPlayer,
            )
            is CombatEvent.AbilityHit -> CombatEventPayload(
                type = "abilityHit",
                abilityId = event.abilityId,
                abilityName = event.abilityName,
                targetName = event.targetName,
                targetId = event.targetId,
                damage = event.damage,
                sourceIsPlayer = event.sourceIsPlayer,
            )
            is CombatEvent.Heal -> CombatEventPayload(
                type = "heal",
                abilityName = event.abilityName,
                targetName = event.targetName,
                healing = event.amount,
                sourceIsPlayer = event.sourceIsPlayer,
            )
            is CombatEvent.Dodge -> CombatEventPayload(
                type = "dodge",
                targetName = event.targetName,
                targetId = event.targetId,
                sourceIsPlayer = event.sourceIsPlayer,
            )
            is CombatEvent.DotTick -> CombatEventPayload(
                type = "dotTick",
                effectName = event.effectName,
                targetName = event.targetName,
                targetId = event.targetId,
                damage = event.damage,
            )
            is CombatEvent.HotTick -> CombatEventPayload(
                type = "hotTick",
                effectName = event.effectName,
                targetName = event.targetName,
                healing = event.amount,
            )
            is CombatEvent.Kill -> CombatEventPayload(
                type = "kill",
                targetName = event.targetName,
                targetId = event.targetId,
                xpGained = event.xpGained,
                goldGained = event.goldGained,
            )
            is CombatEvent.Death -> CombatEventPayload(
                type = "death",
                killerName = event.killerName,
                killerIsPlayer = event.killerIsPlayer,
            )
            is CombatEvent.ShieldAbsorb -> CombatEventPayload(
                type = "shieldAbsorb",
                attackerName = event.attackerName,
                absorbed = event.absorbed,
                shieldRemaining = event.remaining,
            )
        }
        emit(sessionId, "Char.Combat.Event", payload, supportCheck = "Char.Combat.Event")
    }

    // ---------- character stats ----------

    suspend fun sendCharStats(
        sessionId: SessionId,
        player: PlayerState,
        effectiveStats: StatMap,
        baseDamageMin: Int,
        baseDamageMax: Int,
        armor: Int,
        dodgePercent: Int,
    ) {
        val baseStats = player.stats
        val statEntries = statRegistry?.all()?.map { def ->
            CharStatEntry(
                id = def.id,
                name = def.displayName,
                abbrev = def.abbreviation,
                base = baseStats[def.id],
                effective = effectiveStats[def.id],
            )
        } ?: effectiveStats.values.map { (id, effective) ->
            CharStatEntry(id = id, name = id, abbrev = id, base = baseStats[id], effective = effective)
        }
        emit(
            sessionId,
            "Char.Stats",
            CharStatsPayload(
                stats = statEntries,
                baseDamageMin = baseDamageMin,
                baseDamageMax = baseDamageMax,
                armor = armor,
                dodgePercent = dodgePercent,
            ),
        )
    }

    // ---------- quests ----------

    suspend fun sendQuestList(
        sessionId: SessionId,
        quests: List<QuestListEntry>,
    ) {
        val payload = quests.map { q ->
            QuestListPayload(
                id = q.id,
                name = q.name,
                description = q.description,
                objectives = q.objectives.map { o ->
                    QuestObjectivePayload(
                        description = o.description,
                        current = o.current,
                        required = o.required,
                        targetRoomIds = o.targetRoomIds,
                    )
                },
            )
        }
        emit(sessionId, "Quest.List", payload)
    }

    suspend fun sendQuestUpdate(
        sessionId: SessionId,
        questId: String,
        objectiveIndex: Int,
        current: Int,
        required: Int,
    ) {
        emit(
            sessionId,
            "Quest.Update",
            QuestUpdatePayload(questId = questId, objectiveIndex = objectiveIndex, current = current, required = required),
            supportCheck = "Quest",
        )
    }

    suspend fun sendQuestComplete(
        sessionId: SessionId,
        questId: String,
        questName: String,
    ) {
        emit(
            sessionId,
            "Quest.Complete",
            QuestCompletePayload(questId = questId, questName = questName),
            supportCheck = "Quest",
        )
    }

    /**
     * Sends available (offerable) quests when the player talks to an NPC.
     * An empty list clears any previous offers on the client.
     */
    suspend fun sendQuestAvailable(
        sessionId: SessionId,
        quests: List<QuestAvailableEntry>,
    ) {
        val payload = quests.map { q ->
            QuestAvailablePayload(
                id = q.id,
                name = q.name,
                description = q.description,
                giverMobId = q.giverMobId,
                objectives = q.objectives.map { o ->
                    QuestAvailableObjectivePayload(description = o.description, count = o.count)
                },
                rewards = QuestAvailableRewardsPayload(xp = q.rewardXp, gold = q.rewardGold),
            )
        }
        emit(sessionId, "Quest.Available", payload, supportCheck = "Quest")
    }

    data class AutoQuestPayload(
        val active: Boolean,
        val targetMobName: String? = null,
        val targetMobTemplateId: String? = null,
        val killsRequired: Int? = null,
        val killsCompleted: Int? = null,
        val rewardGold: Long? = null,
        val rewardXp: Long? = null,
        val timeRemainingMs: Long? = null,
    )

    suspend fun sendAutoQuest(
        sessionId: SessionId,
        payload: AutoQuestPayload,
    ) {
        emit(sessionId, "Quest.Auto", payload, supportCheck = "Quest")
    }

    // ---------- cooldowns ----------

    suspend fun sendCharCooldown(
        sessionId: SessionId,
        abilityId: String,
        cooldownMs: Long,
    ) {
        emit(
            sessionId,
            "Char.Cooldown",
            CharCooldownPayload(abilityId = abilityId, cooldownMs = cooldownMs),
        )
    }

    // ---------- crafting ----------

    suspend fun sendCraftingSkills(
        sessionId: SessionId,
        skills: List<CraftingSkillPayload>,
    ) {
        emit(sessionId, "Crafting.Skills", skills, supportCheck = "Crafting")
    }

    suspend fun sendCraftingRecipes(
        sessionId: SessionId,
        recipes: List<CraftingRecipePayload>,
    ) {
        emit(sessionId, "Crafting.Recipes", recipes, supportCheck = "Crafting")
    }

    suspend fun sendCraftingNodes(
        sessionId: SessionId,
        nodes: List<CraftingNodePayload>,
    ) {
        emit(sessionId, "Crafting.Nodes", nodes, supportCheck = "Crafting")
    }

    suspend fun sendCraftingResult(
        sessionId: SessionId,
        type: String,
        skill: String,
        xpAwarded: Int,
        leveledUp: Boolean,
        newLevel: Int,
        itemName: String? = null,
        quantity: Int? = null,
        rareFind: Boolean = false,
        quality: String? = null,
    ) {
        emit(
            sessionId,
            "Crafting.Result",
            CraftingResultPayload(
                type = type,
                skill = skill,
                xpAwarded = xpAwarded,
                leveledUp = leveledUp,
                newLevel = newLevel,
                itemName = itemName,
                quantity = quantity,
                rareFind = rareFind,
                quality = quality,
            ),
            supportCheck = "Crafting",
        )
    }

    // ---------- trade ----------

    data class TradeItemPayload(
        val id: String,
        val name: String,
    )

    data class TradeStatePayload(
        val active: Boolean,
        val partner: String?,
        val myItems: List<TradeItemPayload>,
        val theirItems: List<TradeItemPayload>,
        val myGold: Long,
        val theirGold: Long,
        val myAccepted: Boolean,
        val theirAccepted: Boolean,
    )

    suspend fun sendTradeState(
        sessionId: SessionId,
        payload: TradeStatePayload,
    ) {
        emit(sessionId, "Trade.State", payload)
    }

    // ---------- auction ----------

    data class AuctionListingPayload(
        val id: Int,
        val itemName: String,
        val itemId: String,
        val price: Long,
        val seller: String,
    )

    suspend fun sendAuctionList(
        sessionId: SessionId,
        listings: List<AuctionListingPayload>,
    ) {
        emit(sessionId, "Auction.List", listings)
    }

    // ---------- pets ----------

    data class PetStatePayload(
        val active: Boolean,
        val name: String?,
        val hp: Int?,
        val maxHp: Int?,
        val minDamage: Int?,
        val maxDamage: Int?,
        val armor: Int?,
        val image: String?,
    )

    suspend fun sendPetState(sessionId: SessionId, payload: PetStatePayload) {
        emit(sessionId, "Char.Pet", payload)
    }

    // ---------- bank ----------

    data class BankStatePayload(
        val gold: Long,
        val items: List<BankItemPayload>,
        val maxItems: Int,
    )

    data class BankItemPayload(
        val id: String,
        val name: String,
        val keyword: String,
        val image: String? = null,
    )

    suspend fun sendBankState(
        sessionId: SessionId,
        gold: Long,
        items: List<dev.ambon.domain.items.ItemInstance>,
        maxItems: Int,
    ) {
        emit(
            sessionId,
            "Char.Bank",
            BankStatePayload(
                gold = gold,
                items = items.map { BankItemPayload(it.id.value, it.item.displayName, it.item.keyword, it.item.image) },
                maxItems = maxItems,
            ),
        )
    }

    // ---------- world atmosphere ----------

    data class WorldTimePayload(
        val period: String,
        val hour: Int,
        val minute: Int,
    )

    data class WorldWeatherPayload(
        val zone: String,
        val weather: String,
        val description: String,
    )

    data class WorldEventPayload(
        val id: String,
        val name: String,
        val description: String,
    )

    suspend fun sendWorldTime(sessionId: SessionId, payload: WorldTimePayload) {
        emit(sessionId, "World.Time", payload)
    }

    suspend fun broadcastWorldTime(payload: WorldTimePayload, players: PlayerRegistry) {
        for (p in players.allPlayers()) {
            emit(p.sessionId, "World.Time", payload)
        }
    }

    suspend fun sendWorldWeather(sessionId: SessionId, payload: WorldWeatherPayload) {
        emit(sessionId, "World.Weather", payload)
    }

    suspend fun sendWorldEvents(sessionId: SessionId, events: List<WorldEventPayload>) {
        emit(sessionId, "World.Events", events)
    }

    suspend fun broadcastWorldEvents(events: List<WorldEventPayload>, players: PlayerRegistry) {
        for (p in players.allPlayers()) {
            emit(p.sessionId, "World.Events", events)
        }
    }

    // ---------- reputation / factions ----------

    data class FactionStandingPayload(
        val id: String,
        val name: String,
        val reputation: Int,
        val tier: String,
    )

    suspend fun sendCharFactions(
        sessionId: SessionId,
        standings: List<FactionStandingPayload>,
    ) {
        emit(sessionId, "Char.Factions", standings)
    }

    // ---------- room features ----------

    suspend fun sendRoomFeatures(
        sessionId: SessionId,
        features: List<RoomFeaturePayload>,
    ) {
        emit(sessionId, "Room.Features", features, supportCheck = "Room.Info")
    }

    suspend fun sendContainerContents(
        sessionId: SessionId,
        featureId: String,
        name: String,
        keyword: String,
        items: List<ContainerItemPayload>,
    ) {
        emit(
            sessionId,
            "Room.ContainerContents",
            ContainerContentsPayload(featureId = featureId, name = name, keyword = keyword, items = items),
            supportCheck = "Room.Info",
        )
    }

    // ---------- mail ----------

    suspend fun sendMailList(
        sessionId: SessionId,
        inbox: List<dev.ambon.domain.mail.MailMessage>,
    ) {
        emit(
            sessionId,
            "Mail.List",
            inbox.mapIndexed { index, msg ->
                MailListEntry(
                    index = index + 1,
                    id = msg.id,
                    from = msg.fromName,
                    date = msg.sentAtEpochMs,
                    read = msg.read,
                    preview = msg.body.lineSequence().firstOrNull()?.take(80) ?: "",
                )
            },
            supportCheck = "Mail",
        )
    }

    suspend fun sendMailMessage(
        sessionId: SessionId,
        index: Int,
        msg: dev.ambon.domain.mail.MailMessage,
    ) {
        emit(
            sessionId,
            "Mail.Message",
            MailMessagePayload(
                index = index,
                id = msg.id,
                from = msg.fromName,
                body = msg.body,
                date = msg.sentAtEpochMs,
                read = msg.read,
            ),
            supportCheck = "Mail",
        )
    }

    suspend fun sendMailNotification(
        sessionId: SessionId,
        from: String,
        unreadCount: Int,
    ) {
        emit(
            sessionId,
            "Mail.Notification",
            MailNotificationPayload(from = from, unreadCount = unreadCount),
            supportCheck = "Mail",
        )
    }

    // ---------- gain events ----------

    suspend fun sendCharGain(
        sessionId: SessionId,
        type: String,
        amount: Long,
        source: String? = null,
        newLevel: Int? = null,
        hpGained: Int? = null,
        manaGained: Int? = null,
    ) {
        emit(
            sessionId,
            "Char.Gain",
            CharGainPayload(
                type = type,
                amount = amount,
                source = source,
                newLevel = newLevel,
                hpGained = hpGained,
                manaGained = manaGained,
            ),
        )
    }

    // ---------- room mob info ----------

    suspend fun sendRoomMobInfo(
        sessionId: SessionId,
        mobs: List<MobInfoEntry>,
    ) {
        emit(
            sessionId,
            "Room.MobInfo",
            mobs.map { m ->
                RoomMobInfoPayload(
                    id = m.id,
                    level = m.level,
                    tier = m.tier,
                    questGiver = m.questGiver,
                    questAvailable = m.questAvailable,
                    questComplete = m.questComplete,
                    shopKeeper = m.shopKeeper,
                    dialogue = m.dialogue,
                    aggressive = m.aggressive,
                )
            },
        )
    }

    suspend fun broadcastRoomMobInfo(roomId: RoomId, mobInfos: List<MobInfoEntry>, players: PlayerRegistry) {
        for (p in players.playersInRoom(roomId)) sendRoomMobInfo(p.sessionId, mobInfos)
    }

    /**
     * Builds [MobInfoEntry] list from raw [MobState] data and an optional set of shop mob IDs.
     * [questAvailableMobIds] — mobs offering quests the player can accept.
     * [questCompleteMobIds] — mobs with a turn-in quest whose objectives the player has completed.
     */
    fun buildMobInfoEntries(
        mobs: List<MobState>,
        shopMobIds: Set<String> = emptySet(),
        questAvailableMobIds: Set<String> = emptySet(),
        questCompleteMobIds: Set<String> = emptySet(),
    ): List<MobInfoEntry> = mobs.map { mob ->
        val mid = mob.id.value
        MobInfoEntry(
            id = mid,
            level = estimateMobLevel(mob.xpReward),
            tier = "standard",
            questGiver = mob.questIds.isNotEmpty(),
            questAvailable = mid in questAvailableMobIds,
            questComplete = mid in questCompleteMobIds,
            shopKeeper = mid in shopMobIds,
            dialogue = mob.dialogue != null,
            aggressive = mob.aggressive,
        )
    }

    // ---------- dialogue ----------

    suspend fun sendDialogueNode(
        sessionId: SessionId,
        mobName: String,
        text: String,
        choices: List<Pair<Int, String>>,
    ) {
        emit(
            sessionId,
            "Dialogue.Node",
            DialogueNodePayload(
                mobName = mobName,
                text = text,
                choices = choices.map { (index, choiceText) -> DialogueChoicePayload(index = index, text = choiceText) },
            ),
        )
    }

    suspend fun sendDialogueEnd(
        sessionId: SessionId,
        mobName: String,
        reason: String,
    ) {
        emit(sessionId, "Dialogue.End", DialogueEndPayload(mobName = mobName, reason = reason), supportCheck = "Dialogue")
    }

    // ---------- guild ----------

    suspend fun sendGuildInfo(
        sessionId: SessionId,
        name: String?,
        tag: String?,
        rank: String?,
        motd: String?,
        memberCount: Int,
        maxSize: Int,
    ) {
        emit(
            sessionId,
            "Guild.Info",
            GuildInfoGmcpPayload(
                name = name,
                tag = tag,
                rank = rank,
                motd = motd,
                memberCount = memberCount,
                maxSize = maxSize,
            ),
        )
    }

    suspend fun sendGuildMembers(
        sessionId: SessionId,
        members: List<GuildMemberInfo>,
    ) {
        emit(
            sessionId,
            "Guild.Members",
            members.map { m ->
                GuildMemberGmcpPayload(name = m.name, rank = m.rank, online = m.online, level = m.level)
            },
            supportCheck = "Guild.Info",
        )
    }

    suspend fun sendGuildChat(
        sessionId: SessionId,
        sender: String,
        message: String,
    ) {
        emit(sessionId, "Guild.Chat", GuildChatGmcpPayload(sender = sender, message = message), supportCheck = "Guild.Info")
    }

    suspend fun sendGuildInvite(
        sessionId: SessionId,
        inviterName: String,
        guildName: String,
        guildTag: String,
    ) {
        emit(
            sessionId,
            "Guild.Invite",
            GuildInvitePayload(
                inviterName = inviterName,
                guildName = guildName,
                guildTag = guildTag,
            ),
            supportCheck = "Guild.Info",
        )
    }

    // ---------- shop ----------

    suspend fun sendShopList(
        sessionId: SessionId,
        shopName: String,
        shopItems: List<Pair<dev.ambon.domain.ids.ItemId, dev.ambon.domain.items.Item>>,
        buyMultiplier: Double,
        sellMultiplier: Double,
    ) {
        emit(
            sessionId,
            "Shop.List",
            ShopListPayload(
                name = shopName,
                sellMultiplier = sellMultiplier,
                items = shopItems.map { (itemId, item) ->
                    ShopItemPayload(
                        id = itemId.value,
                        name = item.displayName,
                        keyword = item.keyword,
                        description = item.description,
                        slot = item.slot?.label(),
                        damage = item.damage,
                        armor = item.armor,
                        buyPrice = (item.basePrice * buyMultiplier).roundToInt(),
                        basePrice = item.basePrice,
                        consumable = item.consumable,
                        image = item.image,
                        video = item.video,
                    )
                },
            ),
            supportCheck = "Shop",
        )
    }

    suspend fun sendShopClose(sessionId: SessionId) {
        emitRaw(sessionId, "Shop.Close", "{}", supportCheck = "Shop")
    }

    // ---------- look target ----------

    suspend fun sendLookTarget(
        sessionId: SessionId,
        type: String,
        name: String,
        description: String,
        image: String? = null,
        level: Int? = null,
        race: String? = null,
        playerClass: String? = null,
    ) {
        emit(
            sessionId,
            "Room.LookTarget",
            LookTargetPayload(
                type = type,
                name = name,
                description = description,
                image = image,
                level = level,
                race = race,
                playerClass = playerClass,
            ),
            supportCheck = "Room.Info",
        )
    }

    // ---------- UI feedback ----------

    suspend fun sendUiFeedback(
        sessionId: SessionId,
        type: String,
        message: String,
        code: String? = null,
        scope: String? = null,
        command: String? = null,
    ) {
        emit(
            sessionId,
            "UI.Feedback",
            UiFeedbackPayload(type = type, message = message, code = code, scope = scope, command = command),
            supportCheck = "UI.Feedback",
        )
    }

    // ---------- staff possession state ----------

    suspend fun sendStaffPossessionState(
        sessionId: SessionId,
        possessing: Boolean,
        mobName: String?,
    ) {
        emit(
            sessionId,
            "Staff.Possession",
            StaffPossessionPayload(active = possessing, mobName = mobName),
            supportCheck = "Staff",
        )
    }

    // ---------- server broadcast ----------

    suspend fun sendServerBroadcast(
        sessionId: SessionId,
        sender: String,
        message: String,
    ) {
        emit(
            sessionId,
            "Server.Broadcast",
            ServerBroadcastPayload(sender = sender, message = message),
            supportCheck = "Server",
        )
    }

    // ---------- staff world info ----------

    /**
     * Sends full world zone/room listing to staff players for the teleport browser.
     */
    suspend fun sendStaffWorldInfo(sessionId: SessionId, world: World) {
        val zones = world.zones().sorted().map { zone ->
            val rooms = world.rooms.values
                .filter { it.id.zone == zone }
                .sortedBy { it.id.value }
                .map { StaffRoomPayload(id = it.id.value, title = it.title) }
            StaffZonePayload(zone = zone, rooms = rooms)
        }
        emit(sessionId, "Staff.WorldInfo", zones, supportCheck = "Staff")
    }

    /**
     * Sends mob template listing to staff players for the spawn browser.
     */
    suspend fun sendStaffMobTemplates(sessionId: SessionId, world: World) {
        val grouped = world.mobSpawns.groupBy { it.id.value.substringBefore(':') }
        val zones = grouped.entries.sortedBy { it.key }.map { (zone, mobs) ->
            StaffMobZonePayload(
                zone = zone,
                mobs = mobs.sortedBy { it.id.value }.map { mob ->
                    StaffMobTemplatePayload(
                        id = mob.id.value,
                        name = mob.name,
                    )
                },
            )
        }
        emit(sessionId, "Staff.MobTemplates", zones, supportCheck = "Staff")
    }

    @Suppress("unused") // Jackson serializes all fields
    private data class UiFeedbackPayload(
        val type: String,
        val message: String,
        val code: String? = null,
        val scope: String? = null,
        val command: String? = null,
    )

    private data class StaffZonePayload(
        val zone: String,
        val rooms: List<StaffRoomPayload>,
    )

    private data class StaffRoomPayload(
        val id: String,
        val title: String,
    )

    private data class StaffMobZonePayload(
        val zone: String,
        val mobs: List<StaffMobTemplatePayload>,
    )

    private data class StaffMobTemplatePayload(
        val id: String,
        val name: String,
    )

    private data class StaffPossessionPayload(
        val active: Boolean,
        val mobName: String?,
    )

    // ---------- emit helpers ----------

    private suspend fun <T : Any> emit(
        sessionId: SessionId,
        packageName: String,
        payload: T,
        supportCheck: String = packageName,
    ) {
        if (!supportsPackage(sessionId, supportCheck)) return
        val serialized = json.writeValueAsString(payload)
        if (serialized.length > MAX_GMCP_PAYLOAD_BYTES) {
            log.warn { "GMCP payload exceeds ${MAX_GMCP_PAYLOAD_BYTES}B limit for $packageName (${serialized.length}B), skipping" }
            return
        }
        outbound.send(OutboundEvent.GmcpData(sessionId, packageName, serialized))
    }

    private suspend fun emitRaw(
        sessionId: SessionId,
        packageName: String,
        rawJson: String,
        supportCheck: String = packageName,
    ) {
        if (!supportsPackage(sessionId, supportCheck)) return
        if (rawJson.length > MAX_GMCP_PAYLOAD_BYTES) {
            log.warn { "GMCP payload exceeds ${MAX_GMCP_PAYLOAD_BYTES}B limit for $packageName (${rawJson.length}B), skipping" }
            return
        }
        outbound.send(OutboundEvent.GmcpData(sessionId, packageName, rawJson))
    }

    // ---------- private helpers ----------

    private fun equipmentSlotMap(equipment: Map<ItemSlot, ItemInstance>): Map<String, ItemPayload?> {
        val slots = equipmentSlotRegistry?.allSlots() ?: equipment.keys.toList()
        return slots.associate { slot -> slot.label() to equipment[slot]?.let { toItemPayload(it) } }
    }

    private fun toItemPayload(item: ItemInstance) =
        ItemPayload(
            id = item.id.value,
            name = item.item.displayName,
            keyword = item.item.keyword,
            slot = item.item.slot?.label(),
            damage = item.item.damage,
            armor = item.item.armor,
            basePrice = item.item.basePrice,
            image = item.item.image,
            video = item.item.video,
            stats = item.item.stats.nonZero().ifEmpty { null },
            enchantments = item.enchantments.ifEmpty { null },
        )

    private fun toRoomMobPayload(mob: MobState): RoomMobPayload {
        val effects = getMobEffects(mob.id)
        return RoomMobPayload(
            id = mob.id.value,
            name = mob.name,
            description = mob.description,
            hp = mob.hp,
            maxHp = mob.maxHp,
            image = mob.image,
            video = mob.video,
            effects = if (effects.isEmpty()) {
                null
            } else {
                effects.map { e ->
                    MobEffectPayload(name = e.name, type = e.type, remainingMs = e.remainingMs, stacks = e.stacks)
                }
            },
        )
    }

    // ---------- payload types ----------

    private data class CharVitalsPayload(
        val hp: Int,
        val maxHp: Int,
        val mana: Int,
        val maxMana: Int,
        val level: Int,
        val xp: Long,
        val xpIntoLevel: Long,
        val xpToNextLevel: Long?,
        val gold: Long,
        val inCombat: Boolean,
        val prestigeLevel: Int = 0,
        val prestigeXpAvailable: Long? = null,
        val prestigeNextCost: Long? = null,
        val pvpKills: Int = 0,
        val pvpDeaths: Int = 0,
    )

    private data class CharCombatPayload(
        val targetId: String?,
        val targetName: String?,
        val targetHp: Int?,
        val targetMaxHp: Int?,
        val targetImage: String?,
    )

    private data class ZoneMapPayload(
        val zone: String,
        val rooms: List<ZoneMapRoom>,
    )

    private data class ZoneMapRoom(
        val id: String,
        val x: Int,
        val y: Int,
        val exits: Map<String, String>,
    )

    private data class RoomInfoPayload(
        val id: String,
        val title: String,
        val description: String,
        val zone: String,
        val exits: Map<String, String>,
        val image: String? = null,
        val video: String? = null,
        val music: String? = null,
        val ambient: String? = null,
        val station: String? = null,
        val mapX: Int = 0,
        val mapY: Int = 0,
        val housing: Boolean = false,
        val housingOwner: String? = null,
        val graphical: Boolean = false,
        val pvpEnabled: Boolean = false,
    )

    private data class ItemPayload(
        val id: String,
        val name: String,
        val keyword: String,
        val slot: String?,
        val damage: Int,
        val armor: Int,
        val basePrice: Int = 0,
        val image: String? = null,
        val video: String? = null,
        val stats: Map<String, Int>? = null,
        val enchantments: List<String>? = null,
    )

    private data class EquipmentSlotPayload(
        val id: String,
        val displayName: String,
        val order: Int,
        val x: Double,
        val y: Double,
    )

    private data class CharItemsListPayload(
        val inventory: List<ItemPayload>,
        val equipment: Map<String, ItemPayload?>,
    )

    private data class CharItemsRemovePayload(
        val id: String,
        val name: String,
    )

    private data class RoomPlayerPayload(
        val name: String,
        val level: Int,
    )

    private data class RoomRemovePlayerPayload(
        val name: String,
    )

    private data class RoomMobPayload(
        val id: String,
        val name: String,
        val description: String = "",
        val hp: Int,
        val maxHp: Int,
        val image: String? = null,
        val video: String? = null,
        val effects: List<MobEffectPayload>? = null,
    )

    private data class MobEffectPayload(
        val name: String,
        val type: String,
        val remainingMs: Long,
        val stacks: Int,
    )

    private data class RoomRemoveMobPayload(
        val id: String,
    )

    private data class RoomItemPayload(
        val id: String,
        val name: String,
        val description: String = "",
        val image: String? = null,
        val video: String? = null,
    )

    private data class CharSkillPayload(
        val id: String,
        val name: String,
        val description: String,
        val manaCost: Int,
        val cooldownMs: Long,
        val cooldownRemainingMs: Long,
        val levelRequired: Int,
        val targetType: String,
        val effectType: String,
        val classRestriction: String?,
        val image: String? = null,
        val prerequisites: List<String> = emptyList(),
        val tree: String = "",
        val tier: Int = 0,
    )

    private data class TrainerAbilityPayload(
        val id: String,
        val name: String,
        val description: String,
        val levelRequired: Int,
        val manaCost: Int,
        val cooldownMs: Long,
        val targetType: String,
        val effectType: String,
        val image: String? = null,
        val prerequisites: List<String> = emptyList(),
        val tree: String = "",
        val tier: Int = 0,
    )

    private data class TrainerListPayload(
        val trainerId: String,
        val name: String,
        @get:JsonProperty("class") val className: String,
        val image: String?,
        val classUnlocked: Boolean,
        val availableSkillPoints: Int,
        val multiclassMinLevel: Int,
        val multiclassGoldCost: Long,
        val abilities: List<TrainerAbilityPayload>,
    )

    private data class CharClassesPayload(
        val originalClass: String,
        val unlockedClasses: List<String>,
    )

    private data class CharNamePayload(
        val name: String,
        val gender: String,
        val race: String,
        @get:JsonProperty("class") val playerClass: String,
        val level: Int,
        val sprite: String,
        val isStaff: Boolean,
    )

    private data class SessionAuthTokenPayload(
        val token: String,
        val characterName: String,
        val expiresInDays: Int,
    )

    private data class SessionAuthResultPayload(
        val success: Boolean,
        val message: String,
    )

    private data class SessionResumeTokenPayload(
        val token: String,
        val expiresIn: Int,
    )

    private data class SessionResumeResultPayload(
        val success: Boolean,
    )

    private data class CommChannelPayload(
        val channel: String,
        val sender: String,
        val message: String,
    )

    private data class CharStatusEffectPayload(
        val id: String,
        val name: String,
        val type: String,
        val remainingMs: Long,
        val stacks: Int,
    )

    private data class GroupMemberPayload(
        val name: String,
        val level: Int,
        val hp: Int,
        val maxHp: Int,
        val mana: Int,
        val maxMana: Int,
        @get:JsonProperty("class") val playerClass: String,
    )

    private data class GroupInfoPayload(
        val leader: String?,
        val members: List<GroupMemberPayload>,
    )

    private data class GroupInvitePayload(
        val inviterName: String,
    )

    private data class GuildInvitePayload(
        val inviterName: String,
        val guildName: String,
        val guildTag: String,
    )

    private data class CompletedAchievementPayload(
        val id: String,
        val name: String,
        val title: String?,
    )

    private data class InProgressAchievementPayload(
        val id: String,
        val name: String,
        val current: Int,
        val required: Int,
    )

    private data class CharAchievementsPayload(
        val completed: List<CompletedAchievementPayload>,
        val inProgress: List<InProgressAchievementPayload>,
    )

    private data class FriendPayload(
        val name: String,
        val online: Boolean,
        val level: Int?,
        val zone: String?,
    )

    private data class FriendEventPayload(
        val name: String,
        val level: Int,
    )

    private data class FriendOfflinePayload(
        val name: String,
    )

    private data class GuildInfoGmcpPayload(
        val name: String?,
        val tag: String?,
        val rank: String?,
        val motd: String?,
        val memberCount: Int,
        val maxSize: Int,
    )

    private data class GuildMemberGmcpPayload(
        val name: String,
        val rank: String,
        val online: Boolean,
        val level: Int?,
    )

    private data class GuildChatGmcpPayload(
        val sender: String,
        val message: String,
    )

    private data class DialogueChoicePayload(
        val index: Int,
        val text: String,
    )

    private data class DialogueNodePayload(
        val mobName: String,
        val text: String,
        val choices: List<DialogueChoicePayload>,
    )

    private data class DialogueEndPayload(
        val mobName: String,
        val reason: String,
    )

    // ---------- combat event payload ----------

    private data class CombatEventPayload(
        val type: String,
        val targetName: String? = null,
        val targetId: String? = null,
        val damage: Int? = null,
        val healing: Int? = null,
        val sourceIsPlayer: Boolean? = null,
        val abilityId: String? = null,
        val abilityName: String? = null,
        val effectName: String? = null,
        val xpGained: Long? = null,
        val goldGained: Long? = null,
        val killerName: String? = null,
        val killerIsPlayer: Boolean? = null,
        val attackerName: String? = null,
        val absorbed: Int? = null,
        val shieldRemaining: Int? = null,
    )

    // ---------- stats payload ----------

    private data class CharStatEntry(
        val id: String,
        val name: String,
        val abbrev: String,
        val base: Int,
        val effective: Int,
    )

    private data class CharStatsPayload(
        val stats: List<CharStatEntry>,
        val baseDamageMin: Int,
        val baseDamageMax: Int,
        val armor: Int,
        val dodgePercent: Int,
    )

    // ---------- quest payloads ----------

    private data class QuestListPayload(
        val id: String,
        val name: String,
        val description: String,
        val objectives: List<QuestObjectivePayload>,
    )

    private data class QuestObjectivePayload(
        val description: String,
        val current: Int,
        val required: Int,
        val targetRoomIds: List<String> = emptyList(),
    )

    private data class QuestUpdatePayload(
        val questId: String,
        val objectiveIndex: Int,
        val current: Int,
        val required: Int,
    )

    private data class QuestCompletePayload(
        val questId: String,
        val questName: String,
    )

    private data class QuestAvailablePayload(
        val id: String,
        val name: String,
        val description: String,
        val giverMobId: String,
        val objectives: List<QuestAvailableObjectivePayload>,
        val rewards: QuestAvailableRewardsPayload,
    )

    private data class QuestAvailableObjectivePayload(
        val description: String,
        val count: Int,
    )

    private data class QuestAvailableRewardsPayload(
        val xp: Long,
        val gold: Long,
    )

    // ---------- cooldown payload ----------

    private data class CharCooldownPayload(
        val abilityId: String,
        val cooldownMs: Long,
    )

    // ---------- crafting payloads ----------

    data class CraftingSkillPayload(
        val id: String,
        val name: String,
        val level: Int,
        val xp: Long,
        val xpToNext: Long,
        val maxLevel: Int,
        val type: String,
    )

    data class CraftingRecipePayload(
        val id: String,
        val name: String,
        val skill: String,
        val skillRequired: Int,
        val levelRequired: Int,
        val materials: List<CraftingMaterialPayload>,
        val outputName: String,
        val outputQuantity: Int,
    )

    data class CraftingMaterialPayload(
        val name: String,
        val quantity: Int,
    )

    data class CraftingNodePayload(
        val id: String,
        val name: String,
        val skill: String,
        val skillRequired: Int,
        val image: String? = null,
    )

    private data class CraftingResultPayload(
        val type: String,
        val skill: String,
        val xpAwarded: Int,
        val leveledUp: Boolean,
        val newLevel: Int,
        val itemName: String?,
        val quantity: Int?,
        val rareFind: Boolean = false,
        val quality: String? = null,
    )

    // ---------- room feature payloads ----------

    data class RoomFeaturePayload(
        val id: String,
        val name: String,
        val keyword: String,
        val type: String,
        val state: String? = null,
        val direction: String? = null,
        val locked: Boolean? = null,
        val keyRequired: Boolean? = null,
        val text: String? = null,
    )

    data class ContainerItemPayload(
        val name: String,
        val keyword: String,
    )

    private data class ContainerContentsPayload(
        val featureId: String,
        val name: String,
        val keyword: String,
        val items: List<ContainerItemPayload>,
    )

    // ---------- mail payloads ----------

    private data class MailListEntry(
        val index: Int,
        val id: String,
        val from: String,
        val date: Long,
        val read: Boolean,
        val preview: String,
    )

    private data class MailMessagePayload(
        val index: Int,
        val id: String,
        val from: String,
        val body: String,
        val date: Long,
        val read: Boolean,
    )

    private data class MailNotificationPayload(
        val from: String,
        val unreadCount: Int,
    )

    // ---------- gain payload ----------

    private data class CharGainPayload(
        val type: String,
        val amount: Long,
        val source: String? = null,
        val newLevel: Int? = null,
        val hpGained: Int? = null,
        val manaGained: Int? = null,
    )

    // ---------- who payload ----------

    private data class ServerWhoPayload(
        val players: List<WhoPlayerPayload>,
    )

    private data class WhoPlayerPayload(
        val name: String,
        val level: Int,
        val race: String,
        @get:JsonProperty("class") val playerClass: String,
        val title: String?,
        val guild: String?,
        val groupSize: Int,
        val idle: Long,
    )

    // ---------- server broadcast payload ----------

    private data class ServerBroadcastPayload(
        val sender: String,
        val message: String,
    )

    // ---------- server commands payload ----------

    private data class ServerCommandsPayload(
        val commands: List<ServerCommandPayload>,
    )

    private data class ServerCommandPayload(
        val name: String,
        val usage: String,
        val description: String,
        val category: String,
        val staff: Boolean,
        val requiresTarget: Boolean,
    )

    // ---------- emote presets payload ----------

    private data class EmotePresetPayload(
        val label: String,
        val emoji: String,
        val action: String,
    )

    // ---------- zone instances payload ----------

    private data class ZoneInstancesPayload(
        val zone: String?,
        val currentEngineId: String?,
        val instances: List<ZoneInstanceItemPayload>,
    )

    private data class ZoneInstanceItemPayload(
        val engineId: String,
        val playerCount: Int,
        val capacity: Int,
        val isCurrent: Boolean,
    )

    // ---------- room mob info payload ----------

    private data class RoomMobInfoPayload(
        val id: String,
        val level: Int,
        val tier: String,
        val questGiver: Boolean,
        val questAvailable: Boolean,
        val questComplete: Boolean,
        val shopKeeper: Boolean,
        val dialogue: Boolean,
        val aggressive: Boolean,
    )

    // ---------- look target payload ----------

    private data class LookTargetPayload(
        val type: String,
        val name: String,
        val description: String,
        val image: String? = null,
        val level: Int? = null,
        val race: String? = null,
        @get:JsonProperty("class") val playerClass: String? = null,
    )

    // ---------- shop payloads ----------

    private data class ShopListPayload(
        val name: String,
        val sellMultiplier: Double,
        val items: List<ShopItemPayload>,
    )

    private data class ShopItemPayload(
        val id: String,
        val name: String,
        val keyword: String,
        val description: String,
        val slot: String?,
        val damage: Int,
        val armor: Int,
        val buyPrice: Int,
        val basePrice: Int,
        val consumable: Boolean,
        val image: String? = null,
        val video: String? = null,
    )

    internal companion object {
        /** Maximum serialized GMCP JSON payload size in bytes (64 KB). */
        const val MAX_GMCP_PAYLOAD_BYTES = 65_536

        /** Upper bound for the zone-tracking LRU cache (well above any realistic session count). */
        const val MAX_ZONE_CACHE_ENTRIES = 10_000

        const val CHAR_STATUS_VARS_JSON =
            """{"hp":"HP","maxHp":"Max HP","mana":"Mana","maxMana":"Max Mana","level":"Level","xp":"XP"}"""
        const val CORE_PING_JSON = "{}"

        /** Rough mob level estimate based on XP reward. */
        fun estimateMobLevel(xpReward: Long): Int = when {
            xpReward <= 0L -> 1
            xpReward < 50L -> 1
            xpReward < 100L -> 2
            xpReward < 200L -> 3
            xpReward < 400L -> 5
            xpReward < 800L -> 7
            else -> ((xpReward / 100) + 5).toInt().coerceIn(1, 50)
        }
    }

    /**
     * Resolves the sprite image URL for a player.
     *
     * If the player has an [activeSprite][PlayerState.activeSprite] set and the
     * sprite registry confirms it is still valid, that variant is used. Otherwise
     * falls back to auto-resolve (highest unlocked tier, best variant match).
     */
    internal fun resolveSprite(player: PlayerState): String {
        val reg = spriteRegistry
        if (reg != null) {
            // Explicit selection
            val chosen = player.activeSprite
            if (chosen != null) {
                val valid = reg.validateSelection(
                    imageId = chosen,
                    level = player.level,
                    unlockedAchievementIds = player.unlockedAchievementIds,
                    isStaff = player.isStaff,
                    playerRace = player.race,
                    playerClass = player.playerClass,
                    playerGender = player.gender,
                )
                if (valid != null) return "$imagesBase${valid.imagePath}"
            }
            // Auto-resolve
            val auto = reg.autoResolve(
                level = player.level,
                isStaff = player.isStaff,
                playerRace = player.race,
                playerClass = player.playerClass,
                playerGender = player.gender,
            )
            if (auto != null) return "$imagesBase${auto.imagePath}"
        }
        // Fallback (no registry) — legacy behaviour
        val race = player.race.lowercase()
        if (player.isStaff) return "${imagesBase}player_sprites/${race}_base_tstaff.png"
        val cls = player.playerClass.lowercase()
        return "${imagesBase}player_sprites/${race}_${cls}_t0.png"
    }

    /** Sends the `Char.Sprites` GMCP package listing available sprites. */
    suspend fun sendCharSprites(
        sessionId: SessionId,
        player: PlayerState,
    ) {
        val reg = spriteRegistry ?: return
        val available = reg.availableVariants(
            level = player.level,
            unlockedAchievementIds = player.unlockedAchievementIds,
            isStaff = player.isStaff,
            playerRace = player.race,
            playerClass = player.playerClass,
            playerGender = player.gender,
        )
        val active = player.activeSprite
            ?: reg.autoResolve(
                level = player.level,
                isStaff = player.isStaff,
                playerRace = player.race,
                playerClass = player.playerClass,
                playerGender = player.gender,
            )?.imageId

        val sprites = available.map { (def, v) ->
            CharSpriteEntry(
                imageId = v.imageId,
                displayName = v.displayName,
                category = def.category.name.lowercase(),
                imagePath = "$imagesBase${v.imagePath}",
            )
        }
        emit(sessionId, "Char.Sprites", CharSpritesPayload(active = active, sprites = sprites))
    }

    private data class CharSpriteEntry(
        val imageId: String,
        val displayName: String,
        val category: String,
        val imagePath: String,
    )

    private data class CharSpritesPayload(
        val active: String?,
        val sprites: List<CharSpriteEntry>,
    )

    // ---------- leaderboard ----------

    /** Sends the `Leaderboard.Data` GMCP package for a specific category. */
    suspend fun sendLeaderboard(
        sessionId: SessionId,
        category: LeaderboardSystem.Category,
        entries: List<LeaderboardSystem.LeaderboardEntry>,
    ) {
        emit(
            sessionId,
            "Leaderboard.Data",
            LeaderboardPayload(
                category = category.key,
                label = category.displayName,
                scoreLabel = category.scoreLabel,
                entries = entries.map { LeaderboardEntryPayload(rank = it.rank, name = it.playerName, score = it.score) },
            ),
            supportCheck = "Leaderboard",
        )
    }

    private data class LeaderboardEntryPayload(
        val rank: Int,
        val name: String,
        val score: Long,
    )

    private data class LeaderboardPayload(
        val category: String,
        val label: String,
        val scoreLabel: String,
        val entries: List<LeaderboardEntryPayload>,
    )
}

// ---------- public data entry types for new GMCP methods ----------

data class QuestListEntry(
    val id: String,
    val name: String,
    val description: String,
    val objectives: List<QuestObjectiveEntry>,
)

data class QuestObjectiveEntry(
    val description: String,
    val current: Int,
    val required: Int,
    val targetRoomIds: List<String> = emptyList(),
)

data class QuestAvailableEntry(
    val id: String,
    val name: String,
    val description: String,
    val giverMobId: String,
    val objectives: List<QuestAvailableObjectiveSummary>,
    val rewardXp: Long,
    val rewardGold: Long,
)

data class QuestAvailableObjectiveSummary(
    val description: String,
    val count: Int,
)

data class MobInfoEntry(
    val id: String,
    val level: Int,
    val tier: String,
    val questGiver: Boolean,
    val questAvailable: Boolean,
    val questComplete: Boolean,
    val shopKeeper: Boolean,
    val dialogue: Boolean,
    val aggressive: Boolean,
)

/** Input DTO for building a Zone.Instances GMCP payload. */
data class ZoneInstanceEntry(
    val engineId: String,
    val playerCount: Int,
    val capacity: Int,
)
