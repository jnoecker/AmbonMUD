package dev.ambon.engine

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.ambon.bus.OutboundBus
import dev.ambon.config.CommandMetadata
import dev.ambon.config.EmotePresetConfig
import dev.ambon.domain.RaceDef
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
import dev.ambon.engine.abilities.primaryEffectType
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
    val category: String = "humanoid",
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
    /** Resolved sprite URL — shown in the Who panel's portrait column. */
    val sprite: String = "",
    /** Player-written RP description — shown when another player examines them. */
    val description: String = "",
)

data class PrestigePerkPayload(
    val rank: Int,
    val type: String,
    val description: String,
    val earned: Boolean,
)

/**
 * One entry in the `World.Areas` GMCP package — a single zone with its known
 * level range. `minLevel`/`maxLevel` are null when the zone has no authored
 * BOUNDED scaling and no leveled mob templates (social/staff zones,
 * scaling-only dungeons), in which case the client renders an em-dash.
 */
data class WorldAreaPayload(
    val zone: String,
    val minLevel: Int? = null,
    val maxLevel: Int? = null,
)

/**
 * Advertises which optional gameplay features are enabled server-side so web clients
 * can hide UI tabs/panels for features that are disabled. Emitted on login as
 * `Server.Features`.
 */
data class ServerFeaturesPayload(
    val dailyQuests: Boolean = false,
    val weeklyQuests: Boolean = false,
    val globalQuests: Boolean = false,
    val autoQuests: Boolean = false,
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
    voicesBaseUrl: String = "/voices/",
    private val voicesEnabled: Boolean = false,
    private val globalAssets: Map<String, String> = emptyMap(),
    private val spriteRegistry: SpriteRegistry? = null,
    private val getMobEffects: (dev.ambon.domain.ids.MobId) -> List<ActiveEffectSnapshot> = { emptyList() },
    private val commandEntries: Map<String, CommandMetadata> = emptyMap(),
    private val emotePresets: List<EmotePresetConfig> = emptyList(),
    private val prestigeEnabled: () -> Boolean = { false },
    private val prestigeMaxRank: () -> Int = { 0 },
    private val prestigeAvailableXp: (PlayerState) -> Long? = { null },
    private val prestigeNextCost: (Int) -> Long? = { null },
    private val prestigePerkPayloads: (currentRank: Int, maxRank: Int) -> List<PrestigePerkPayload> = { _, _ -> emptyList() },
    private val environmentConfig: dev.ambon.config.EnvironmentConfig = dev.ambon.config.EnvironmentConfig(),
    private val featureFlags: () -> ServerFeaturesPayload = { ServerFeaturesPayload() },
    private val sanctumRoomId: () -> RoomId? = { null },
    private val worldAreas: List<WorldAreaPayload> = emptyList(),
) {
    private val json = jacksonObjectMapper()
    private val imagesBase = if (imagesBaseUrl.endsWith("/")) imagesBaseUrl else "$imagesBaseUrl/"
    private val voicesBase = if (voicesBaseUrl.endsWith("/")) voicesBaseUrl else "$voicesBaseUrl/"

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

    /**
     * Last Char.Vitals payload sent to each session. Used to suppress redundant
     * emits when nothing has actually changed — at 1k concurrent sessions every
     * tick of unchanged vitals multiplies into tens of thousands of wasted
     * frames/sec on the outbound path.
     */
    private val lastVitalsBySession: MutableMap<SessionId, CharVitalsPayload> =
        object : LinkedHashMap<SessionId, CharVitalsPayload>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SessionId, CharVitalsPayload>): Boolean =
                size > MAX_VITALS_CACHE_ENTRIES
        }

    /** Returns true if the zone changed (or is first seen) for this session. */
    fun trackZoneChange(sessionId: SessionId, zone: String): Boolean {
        val prev = lastZoneBySession.put(sessionId, zone)
        return prev == null || prev != zone
    }

    /** Remove tracked zone state for a disconnected session. */
    fun forgetSession(sessionId: SessionId) {
        lastZoneBySession.remove(sessionId)
        lastVitalsBySession.remove(sessionId)
    }

    /**
     * Resolved asset URLs: each value from [globalAssets] is prefixed with either
     * [imagesBase] (CDN) or `/images/` (local) depending on whether the asset is
     * a bundled default. Bundled assets (defaults/, global_assets/) always resolve
     * locally so they work without any external CDN or asset generation.
     */
    private val resolvedAssets: Map<String, String> =
        globalAssets.mapValues { (_, path) ->
            val bundled = path.startsWith("defaults/") || path.startsWith("global_assets/")
            val base = if (bundled) "/images/" else imagesBase
            "$base$path"
        }

    suspend fun sendCharVitals(
        sessionId: SessionId,
        player: PlayerState,
    ) {
        val payload = CharVitalsPayload(
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
        )
        if (lastVitalsBySession[sessionId] == payload) return
        lastVitalsBySession[sessionId] = payload
        emit(sessionId, "Char.Vitals", payload)
    }

    /**
     * Push a one-shot threat assessment to the player's web client so it can render a
     * `consider` panel — verbal rating, win percentage, hits-to-kill comparison, and
     * the underlying numbers driving the call. Routed under the Char.Combat package
     * family so it ships under the same auto-opt-in subscription as Char.Combat.
     */
    suspend fun sendCombatConsider(
        sessionId: SessionId,
        result: ConsiderResult,
    ) {
        emit(
            sessionId,
            "Char.Combat.Consider",
            CombatConsiderPayload(
                mobId = result.mobId,
                mobName = result.mobName,
                mobLevel = result.mobLevel,
                mobMaxHp = result.mobMaxHp,
                mobCategory = result.mobCategory,
                mobImage = result.mobImage,
                playerLevel = result.playerLevel,
                playerMaxHp = result.playerMaxHp,
                playerAvgDamage = result.playerAvgDamage,
                mobAvgDamage = result.mobAvgDamage,
                hitsToKillMob = result.hitsToKillMob,
                hitsToKillPlayer = result.hitsToKillPlayer,
                dodgeChancePct = result.dodgeChancePct,
                winChancePct = result.winChancePct,
                rating = result.rating.name,
                ratingLabel = result.rating.label,
                ratingFlavor = result.rating.flavor,
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
                targetCategory = target?.category,
            ),
        )
    }

    suspend fun sendRoomInfo(
        sessionId: SessionId,
        room: Room,
        isHousing: Boolean = false,
        housingOwner: String? = null,
        pvpEnabled: Boolean = false,
        trainerName: String? = null,
        lastDeathZone: String? = null,
        peek: List<RoomPeekEntry> = emptyList(),
    ) {
        val canDepart = lastDeathZone != null && sanctumRoomId() == room.id
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
                terrain = room.terrain,
                pvpEnabled = pvpEnabled,
                trainer = trainerName,
                bank = room.bank,
                stylist = room.stylist,
                tavern = room.tavern,
                dungeon = room.dungeon,
                auction = room.auction,
                housingBroker = room.housingBroker,
                inn = room.inn,
                canDepart = canDepart,
                peek = peek,
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
                .map { RoomPlayerPayload(name = it.name, level = it.level, sprite = resolveSprite(it)) },
        )
    }

    suspend fun sendRoomAddPlayer(
        sessionId: SessionId,
        player: PlayerState,
    ) {
        emit(
            sessionId,
            "Room.AddPlayer",
            RoomPlayerPayload(name = player.name, level = player.level, sprite = resolveSprite(player)),
            supportCheck = "Room.Players",
        )
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
                    takeable = it.item.takeable,
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
        broadcastSerialized(players.playersInRoom(roomId), "Room.AddMob", toRoomMobPayload(mob), supportCheck = "Room.Mobs")
    }

    suspend fun broadcastRoomUpdateMob(roomId: RoomId, mob: MobState, players: PlayerRegistry) {
        broadcastSerialized(players.playersInRoom(roomId), "Room.UpdateMob", toRoomMobPayload(mob), supportCheck = "Room.Mobs")
    }

    suspend fun broadcastRoomRemoveMob(roomId: RoomId, mobId: String, players: PlayerRegistry) {
        broadcastSerialized(players.playersInRoom(roomId), "Room.RemoveMob", RoomRemoveMobPayload(id = mobId), supportCheck = "Room.Mobs")
    }

    suspend fun broadcastRoomItems(roomId: RoomId, items: List<ItemInstance>, players: PlayerRegistry) {
        val payload = items.map {
            RoomItemPayload(
                id = it.id.value,
                name = it.item.displayName,
                description = it.item.description,
                image = it.item.image,
                video = it.item.video,
                takeable = it.item.takeable,
            )
        }
        broadcastSerialized(players.playersInRoom(roomId), "Room.Items", payload)
    }

    /**
     * Emits `Char.Skills` for [sessionId]. [manaCostFor] is intentionally required
     * (no default): the GMCP `manaCost` field is the *absolute* per-cast mana
     * cost for this player, resolved by [AbilitySystem.computeManaCost] from the
     * ability's `manaCostPct` against the player's level/class base pool. Passing
     * `it.manaCostPct` directly would silently ship a raw percentage to the
     * client mislabeled as mana — so every caller must supply the resolver
     * explicitly.
     */
    suspend fun sendCharSkills(
        sessionId: SessionId,
        abilities: List<AbilityDefinition>,
        cooldownRemainingMs: (AbilityId) -> Long = { 0L },
        manaCostFor: (AbilityDefinition) -> Int,
    ) {
        emit(
            sessionId,
            "Char.Skills",
            abilities.map { a ->
                CharSkillPayload(
                    id = a.id.value,
                    name = a.displayName,
                    description = a.description,
                    skillPointCost = a.skillPointCost,
                    manaCost = manaCostFor(a).coerceAtLeast(0),
                    cooldownMs = a.cooldownMs,
                    cooldownRemainingMs = cooldownRemainingMs(a.id).coerceAtLeast(0L),
                    levelRequired = a.levelRequired,
                    targetType = a.targetType,
                    effectType = a.effect.primaryEffectType(),
                    classRestriction = a.requiredClass,
                    image = a.image,
                    prerequisites = a.prerequisites.map { it.value },
                    tree = a.tree,
                    tier = a.tier,
                    visual = SkillVisualPayload(
                        archetype = a.visual.archetype.name,
                        projectileImage = a.visual.projectileImage,
                        color = a.visual.color,
                        accentColor = a.visual.accentColor,
                    ),
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
                autolootEnabled = player.autolootEnabled,
                autopeekEnabled = player.autopeekEnabled,
                wimpyThresholdPct = player.wimpyThresholdPct,
                title = player.activeTitle,
                isDemo = player.playerId == null,
            ),
        )
    }

    /**
     * Sends `Char.Recall` with the player's current recall point so clients can
     * display it (e.g. inn popout). Emits null fields when no recall is set.
     */
    suspend fun sendCharRecall(
        sessionId: SessionId,
        roomId: RoomId?,
        roomTitle: String?,
    ) {
        emit(
            sessionId,
            "Char.Recall",
            CharRecallPayload(roomId = roomId?.value, roomTitle = roomTitle),
            supportCheck = "Char.Recall",
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

    /** Sends optional-feature availability flags as `Server.Features`. */
    suspend fun sendServerFeatures(sessionId: SessionId) {
        emit(sessionId, "Server.Features", featureFlags())
    }

    /**
     * Sends the static area catalog as `World.Areas`. The list is computed once
     * at engine init from world zones + bounded scaling / inferred mob levels —
     * the web client filters and highlights the player's current zone locally,
     * so this never needs to be re-emitted.
     */
    suspend fun sendWorldAreas(sessionId: SessionId) {
        if (worldAreas.isEmpty()) return
        emit(sessionId, "World.Areas", WorldAreasPayload(areas = worldAreas))
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
                    sprite = e.sprite,
                    description = e.description,
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
     * Emits `Trainer.List` for a (possibly multi-class) trainer in the current room.
     * Computes the per-class ability list using [abilitySystem] and the player's
     * current unlocked classes / learned abilities.
     */
    suspend fun sendTrainerList(
        sessionId: SessionId,
        trainer: dev.ambon.domain.world.TrainerDefinition,
        player: dev.ambon.engine.PlayerState,
        availableSkillPoints: Int,
        abilitySystem: dev.ambon.engine.abilities.AbilitySystem,
        multiclassConfig: dev.ambon.config.MulticlassConfig,
    ) {
        val knownIds = abilitySystem.knownAbilityIds(sessionId)
        val classPayloads = trainer.classNames.map { className ->
            val unlocked = player.unlockedClasses.any { it.equals(className, ignoreCase = true) }
            val entries = if (unlocked) {
                abilitySystem.trainerPanelAbilities(className, player.level, knownIds)
            } else {
                emptyList()
            }
            TrainerClassPayload(
                className = className,
                classUnlocked = unlocked,
                abilities = entries.map { it.toTrainerAbilityPayload(abilitySystem.computeManaCost(player, it.ability)) },
            )
        }
        emit(
            sessionId,
            "Trainer.List",
            TrainerListPayload(
                trainerId = trainer.id,
                name = trainer.name,
                image = trainer.image,
                availableSkillPoints = availableSkillPoints,
                multiclassMinLevel = multiclassConfig.minLevel,
                multiclassGoldCost = multiclassConfig.costFor(player.unlockedClasses.size),
                multiclassMaxClasses = multiclassConfig.maxClasses,
                multiclassUnlockedCount = player.unlockedClasses.size,
                classes = classPayloads,
            ),
            supportCheck = "Trainer",
        )
    }

    private fun AbilitySystem.TrainerPanelEntry.toTrainerAbilityPayload(manaCost: Int): TrainerAbilityPayload =
        TrainerAbilityPayload(
            id = ability.id.value,
            name = ability.displayName,
            description = ability.description,
            skillPointCost = ability.skillPointCost,
            levelRequired = ability.levelRequired,
            manaCost = manaCost.coerceAtLeast(0),
            cooldownMs = ability.cooldownMs,
            targetType = ability.targetType,
            effectType = ability.effect.primaryEffectType(),
            image = ability.image,
            prerequisites = ability.prerequisites.map { it.value },
            tree = ability.tree,
            tier = ability.tier,
            locked = locked,
            lockReason = lockReason,
        )

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
        world: World? = null,
    ) {
        sendServerAssets(sessionId)
        sendServerCommands(sessionId, player.isStaff)
        sendServerFeatures(sessionId)
        sendServerEmotePresets(sessionId)
        sendWorldAreas(sessionId)
        sendCharStatusVars(sessionId)
        sendCharVitals(sessionId, player)
        sendCharName(sessionId, player)
        sendEquipmentSlots(sessionId)
        sendCharItemsList(sessionId, items.inventory(sessionId), items.equipment(sessionId))
        sendCharSkills(
            sessionId,
            abilitySystem.knownAbilities(sessionId),
            cooldownRemainingMs = { abilityId -> abilitySystem.cooldownRemainingMs(sessionId, abilityId) },
            manaCostFor = { ability -> abilitySystem.computeManaCost(player, ability) },
        )
        sendCharStatusEffects(sessionId, statusEffectSystem.activePlayerEffects(sessionId))
        sendCharAchievements(sessionId, player, achievementRegistry)
        sendCharSprites(sessionId, player)
        sendCharClasses(sessionId, player.unlockedClasses.ifEmpty { setOf(player.playerClass) }, player.playerClass)
        sendPrestigeInfoForPlayer(sessionId, player)
        if (world != null) {
            val recallId = players.recallTarget(sessionId)
            val recallTitle = recallId?.let { world.rooms[it]?.title }
            sendCharRecall(sessionId, recallId, recallTitle)
        }
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
        available: Boolean = true,
    ) {
        emit(
            sessionId,
            "Housing.Info",
            HousingInfoPayload(hasHouse = hasHouse, ownerName = ownerName, rooms = rooms, available = available),
        )
    }

    suspend fun sendHousingTemplates(sessionId: SessionId, templates: List<HousingTemplatePayload>) {
        emit(sessionId, "Housing.Templates", HousingTemplatesPayload(templates = templates))
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
        val available: Boolean,
    )

    data class HousingTemplatePayload(
        val id: String,
        val title: String,
        val description: String,
        val cost: Long,
        val owned: Boolean,
        val isEntry: Boolean,
    )

    private data class HousingTemplatesPayload(
        val templates: List<HousingTemplatePayload>,
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
                text = event.text,
            )
            is CombatEvent.AbilityHit -> CombatEventPayload(
                type = "abilityHit",
                abilityId = event.abilityId,
                abilityName = event.abilityName,
                targetName = event.targetName,
                targetId = event.targetId,
                damage = event.damage,
                sourceIsPlayer = event.sourceIsPlayer,
                text = event.text,
            )
            is CombatEvent.Heal -> CombatEventPayload(
                type = "heal",
                abilityId = event.abilityId,
                abilityName = event.abilityName,
                targetName = event.targetName,
                healing = event.amount,
                sourceIsPlayer = event.sourceIsPlayer,
                text = event.text,
            )
            is CombatEvent.Dodge -> CombatEventPayload(
                type = "dodge",
                targetName = event.targetName,
                targetId = event.targetId,
                sourceIsPlayer = event.sourceIsPlayer,
                text = event.text,
            )
            is CombatEvent.DotTick -> CombatEventPayload(
                type = "dotTick",
                effectName = event.effectName,
                targetName = event.targetName,
                targetId = event.targetId,
                damage = event.damage,
                text = event.text,
            )
            is CombatEvent.HotTick -> CombatEventPayload(
                type = "hotTick",
                effectName = event.effectName,
                targetName = event.targetName,
                healing = event.amount,
                text = event.text,
            )
            is CombatEvent.Kill -> CombatEventPayload(
                type = "kill",
                targetName = event.targetName,
                targetId = event.targetId,
                xpGained = event.xpGained,
                goldGained = event.goldGained,
                lootedItems = event.lootedItems,
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
                text = event.text,
            )
            is CombatEvent.PetHit -> CombatEventPayload(
                type = "petHit",
                petName = event.petName,
                targetName = event.targetName,
                targetId = event.targetId,
                damage = event.damage,
                text = event.text,
            )
            is CombatEvent.PetHurt -> CombatEventPayload(
                type = "petHurt",
                petName = event.petName,
                attackerName = event.attackerName,
                damage = event.damage,
                text = event.text,
            )
            is CombatEvent.AbilityCast -> CombatEventPayload(
                type = "abilityCast",
                abilityId = event.abilityId,
                abilityName = event.abilityName,
                targetName = event.targetName,
                targetId = event.targetId,
                targetIsPlayer = event.targetIsPlayer,
                sourceIsPlayer = event.sourceIsPlayer,
                text = event.text,
            )
            is CombatEvent.Flee -> CombatEventPayload(
                type = "flee",
                targetName = event.targetName,
                forced = event.forced,
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
                readyToTurnIn = q.readyToTurnIn,
                giverMobId = q.giverMobId,
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
        readyToTurnIn: Boolean,
    ) {
        emit(
            sessionId,
            "Quest.Update",
            QuestUpdatePayload(
                questId = questId,
                objectiveIndex = objectiveIndex,
                current = current,
                required = required,
                readyToTurnIn = readyToTurnIn,
            ),
            supportCheck = "Quest",
        )
    }

    suspend fun sendQuestComplete(
        sessionId: SessionId,
        questId: String,
        questName: String,
        questDescription: String,
        rewardXp: Long,
        rewardGold: Long,
        rewardCurrencies: Map<String, Long>,
        rewardItems: List<QuestRewardItemSummary>,
    ) {
        emit(
            sessionId,
            "Quest.Complete",
            QuestCompletePayload(
                questId = questId,
                questName = questName,
                questDescription = questDescription,
                rewards = QuestAvailableRewardsPayload(
                    xp = rewardXp,
                    gold = rewardGold,
                    currencies = rewardCurrencies,
                    items = rewardItems.map { it.toPayload() },
                ),
            ),
            supportCheck = "Quest",
        )
    }

    private fun QuestRewardItemSummary.toPayload(): QuestRewardItemPayload =
        QuestRewardItemPayload(itemId = itemId, displayName = displayName, count = count)

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
                    QuestAvailableObjectivePayload(
                        description = o.description,
                        count = o.count,
                        current = o.current,
                    )
                },
                rewards = QuestAvailableRewardsPayload(
                    xp = q.rewardXp,
                    gold = q.rewardGold,
                    currencies = q.rewardCurrencies,
                    items = q.rewardItems.map { it.toPayload() },
                ),
                levelRequired = q.levelRequired,
                reputationRequired = q.reputationRequired?.let { rep ->
                    QuestReputationPayload(
                        faction = rep.faction,
                        factionName = rep.factionName,
                        min = rep.min,
                        max = rep.max,
                    )
                },
                readyToTurnIn = q.readyToTurnIn,
                lockedReason = q.lockedReason,
            )
        }
        emit(sessionId, "Quest.Available", payload, supportCheck = "Quest")
    }

    // ---------- daily / weekly quests ----------

    suspend fun sendDailyQuests(
        sessionId: SessionId,
        dailyQuestSystem: DailyQuestSystem,
    ) {
        val board = dailyQuestSystem.getDailyQuestBoard(sessionId)
        val streak = dailyQuestSystem.getStreak(sessionId)
        val payload = QuestDailyPayload(
            quests = board.map { info ->
                DailyQuestEntryPayload(
                    index = info.index,
                    type = info.definition.type,
                    description = info.definition.description,
                    current = info.progress,
                    required = info.definition.targetCount,
                    completed = info.completed,
                    goldReward = info.definition.goldReward,
                    xpReward = info.definition.xpReward,
                )
            },
            streakDays = streak,
        )
        emit(sessionId, "Quest.Daily", payload, supportCheck = "Quest")
    }

    suspend fun sendWeeklyQuests(
        sessionId: SessionId,
        dailyQuestSystem: DailyQuestSystem,
    ) {
        val board = dailyQuestSystem.getWeeklyQuestBoard(sessionId)
        val payload = board.map { info ->
            DailyQuestEntryPayload(
                index = info.index,
                type = info.definition.type,
                description = info.definition.description,
                current = info.progress,
                required = info.definition.targetCount,
                completed = info.completed,
                goldReward = info.definition.goldReward,
                xpReward = info.definition.xpReward,
            )
        }
        emit(sessionId, "Quest.Weekly", payload, supportCheck = "Quest")
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

    suspend fun sendCraftingCooldown(
        sessionId: SessionId,
        type: String,
        untilMs: Long,
    ) {
        emit(
            sessionId,
            "Crafting.Cooldown",
            CraftingCooldownPayload(type = type, untilMs = untilMs),
            supportCheck = "Crafting",
        )
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

    // ---------- lottery ----------

    data class LotteryInfoPayload(
        val jackpot: Long,
        val totalTickets: Int,
        val playerTickets: Int,
        val nextDrawingMs: Long,
        val ticketCost: Long,
        val maxTicketsPerPlayer: Int,
        val diceMinBet: Long,
        val diceMaxBet: Long,
        val diceWinMultiplier: Double,
        val diceWinTarget: Int,
        val coinMaxThreshold: Int,
        val coinWinMultiplier: Double,
        val coinJackpotMultiplier: Double,
        val diceCooldownMs: Long,
    )

    suspend fun sendLotteryInfo(
        sessionId: SessionId,
        info: LotteryInfo,
    ) {
        emit(
            sessionId,
            "Lottery.Info",
            LotteryInfoPayload(
                jackpot = info.jackpot,
                totalTickets = info.totalTickets,
                playerTickets = info.playerTickets,
                nextDrawingMs = info.nextDrawingMs,
                ticketCost = info.ticketCost,
                maxTicketsPerPlayer = info.maxTicketsPerPlayer,
                diceMinBet = info.diceMinBet,
                diceMaxBet = info.diceMaxBet,
                diceWinMultiplier = info.diceWinMultiplier,
                diceWinTarget = info.diceWinTarget,
                coinMaxThreshold = info.coinMaxThreshold,
                coinWinMultiplier = info.coinWinMultiplier,
                coinJackpotMultiplier = info.coinJackpotMultiplier,
                diceCooldownMs = info.diceCooldownMs,
            ),
        )
    }

    /** One die within a resolved roll, in tumble order. */
    data class GambleDiePayload(
        val kind: String,
        val sides: Int,
        val value: Int,
        val isMax: Boolean,
    )

    data class GambleResultPayload(
        /** "win" (base), "lose", "coin" (Luneqrae rescue), or "jackpot" (coin + base). */
        val outcome: String,
        val bet: Long,
        val payout: Long,
        val multiplier: Double,
        /** The six children in roll order, big → small. */
        val dice: List<GambleDiePayload>,
        val sum: Int,
        val target: Int,
        val maxCount: Int,
        val coinFired: Boolean,
        val coinWon: Boolean,
        val cooldownMs: Long,
    )

    /** Emits the result of a tavern dice roll so the web client can animate it. */
    suspend fun sendGambleResult(
        sessionId: SessionId,
        payload: GambleResultPayload,
    ) {
        emit(sessionId, "Lottery.Gamble", payload)
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

    data class PetSkillPayload(
        val id: String,
        val name: String,
        val description: String,
        val cooldownMs: Long,
        val cooldownRemainingMs: Long,
        val effectType: String,
        val image: String?,
        val petName: String,
    )

    /**
     * Emits the active pet's signature skills with live cooldowns. The web client uses this to
     * populate the spellbook and quickbar with pet-driven abilities. Send an empty array on
     * dismiss/expire so the client clears its pet-skill panel.
     */
    suspend fun sendPetSkills(sessionId: SessionId, payload: List<PetSkillPayload>) {
        emit(sessionId, "Char.Pet.Skills", payload)
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

    // ---------- stylist ----------

    data class StylistStatePayload(
        val currentRace: String,
        val feeGold: Long,
        val playerGold: Long,
        val races: List<StylistRacePayload>,
    )

    data class StylistRacePayload(
        val id: String,
        val displayName: String,
        val description: String,
        val image: String,
        val statMods: Map<String, Int>,
    )

    suspend fun sendStylistClose(sessionId: SessionId) {
        emitRaw(sessionId, "Char.Stylist.Close", "{}")
    }

    suspend fun sendStylistState(
        sessionId: SessionId,
        currentRace: String,
        feeGold: Long,
        playerGold: Long,
        races: List<RaceDef>,
    ) {
        emit(
            sessionId,
            "Char.Stylist",
            StylistStatePayload(
                currentRace = currentRace,
                feeGold = feeGold,
                playerGold = playerGold,
                races = races.map {
                    StylistRacePayload(
                        id = it.id,
                        displayName = it.displayName,
                        description = it.description,
                        image = if (it.image.isNotEmpty()) "$imagesBase${it.image}" else "",
                        statMods = it.statMods.nonZero(),
                    )
                },
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
        val particleHint: String = "",
        val icon: String = "",
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
        broadcastSerialized(players.allPlayers(), "World.Time", payload)
    }

    suspend fun sendWorldWeather(sessionId: SessionId, payload: WorldWeatherPayload) {
        emit(sessionId, "World.Weather", payload)
    }

    // ---------- zone environment theme ----------

    data class MoteColorPayload(
        val core: String,
        val glow: String,
    )

    data class SkyGradientPayload(
        val top: String,
        val bottom: String,
    )

    data class ZoneEnvironmentPayload(
        val zone: String,
        val moteColors: List<MoteColorPayload>,
        val skyGradients: Map<String, SkyGradientPayload>,
        val transitionColors: List<String>,
        val weatherParticleOverrides: Map<String, String>,
    )

    suspend fun sendZoneEnvironment(sessionId: SessionId, payload: ZoneEnvironmentPayload) {
        emit(sessionId, "Zone.Environment", payload)
    }

    // ---------- zone cinematic ----------

    /**
     * Zone intro cinematic. [autoplay] is true only on the player's first-ever
     * entry to the zone; the client keeps [url] around so the cinematic stays
     * replayable from the expanded map.
     */
    data class ZoneCinematicPayload(
        val zone: String,
        val url: String,
        val autoplay: Boolean,
    )

    suspend fun sendZoneCinematic(sessionId: SessionId, payload: ZoneCinematicPayload) {
        emit(sessionId, "Zone.Cinematic", payload)
    }

    /** Resolves the environment theme for a zone (zone override merged over defaults). */
    fun buildZoneEnvironmentPayload(zone: String): ZoneEnvironmentPayload {
        val defaults = environmentConfig.defaultTheme
        val zoneOverride = environmentConfig.zones[zone]

        val moteColors = (zoneOverride?.moteColors?.takeIf { it.isNotEmpty() } ?: defaults.moteColors)
            .map { MoteColorPayload(it.core, it.glow) }
        val skyGradients = (defaults.skyGradients + (zoneOverride?.skyGradients ?: emptyMap()))
            .mapValues { (_, g) -> SkyGradientPayload(g.top, g.bottom) }
        val transitionColors = zoneOverride?.transitionColors?.takeIf { it.isNotEmpty() }
            ?: defaults.transitionColors
        val weatherOverrides = (defaults.weatherParticleOverrides + (zoneOverride?.weatherParticleOverrides ?: emptyMap()))

        return ZoneEnvironmentPayload(
            zone = zone,
            moteColors = moteColors,
            skyGradients = skyGradients,
            transitionColors = transitionColors,
            weatherParticleOverrides = weatherOverrides,
        )
    }

    suspend fun sendWorldEvents(sessionId: SessionId, events: List<WorldEventPayload>) {
        emit(sessionId, "World.Events", events)
    }

    suspend fun broadcastWorldEvents(events: List<WorldEventPayload>, players: PlayerRegistry) {
        broadcastSerialized(players.allPlayers(), "World.Events", events)
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

    // ---------- currencies ----------

    data class CurrencyBalancePayload(
        val id: String,
        val name: String,
        val abbreviation: String,
        val balance: Long,
    )

    suspend fun sendCharCurrencies(
        sessionId: SessionId,
        currencies: List<CurrencyBalancePayload>,
    ) {
        emit(sessionId, "Char.Currencies", currencies)
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
                    gold = msg.gold,
                    itemName = msg.item?.item?.displayName,
                    itemImage = msg.item?.item?.image,
                    claimed = msg.claimed,
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
                gold = msg.gold,
                itemName = msg.item?.item?.displayName,
                itemImage = msg.item?.item?.image,
                claimed = msg.claimed,
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

    // ---------- level-up event ----------

    /**
     * Emits a dedicated Char.LevelUp event for the client to present a prominent
     * celebratory overlay. This is distinct from Char.Gain so clients can subscribe
     * to it independently and keep the existing floating-text popup for smaller
     * gains. [isMilestone] flags round-number levels (10, 25, 50) for extra flourish.
     */
    suspend fun sendCharLevelUp(
        sessionId: SessionId,
        previousLevel: Int,
        newLevel: Int,
        levelsGained: Int,
        hpGained: Int,
        manaGained: Int,
        newAbilities: List<String> = emptyList(),
        skillPointsAvailable: Int = 0,
        isMilestone: Boolean = false,
    ) {
        emit(
            sessionId,
            "Char.LevelUp",
            CharLevelUpPayload(
                previousLevel = previousLevel,
                newLevel = newLevel,
                levelsGained = levelsGained,
                hpGained = hpGained,
                manaGained = manaGained,
                newAbilities = newAbilities,
                skillPointsAvailable = skillPointsAvailable,
                isMilestone = isMilestone,
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
                    combatant = m.combatant,
                )
            },
        )
    }

    suspend fun broadcastRoomMobInfo(roomId: RoomId, mobInfos: List<MobInfoEntry>, players: PlayerRegistry) {
        val payload = mobInfos.map { m ->
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
                combatant = m.combatant,
            )
        }
        broadcastSerialized(players.playersInRoom(roomId), "Room.MobInfo", payload)
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
            level = if (mob.level > 0) mob.level else estimateMobLevel(mob.xpReward),
            tier = "standard",
            questGiver = mob.questIds.isNotEmpty(),
            questAvailable = mid in questAvailableMobIds,
            questComplete = mid in questCompleteMobIds,
            shopKeeper = mid in shopMobIds,
            dialogue = mob.dialogue != null,
            aggressive = mob.aggressive,
            combatant = mob.role.isCombatant,
        )
    }

    // ---------- dialogue ----------

    suspend fun sendDialogueNode(
        sessionId: SessionId,
        mobName: String,
        text: String,
        choices: List<Pair<Int, String>>,
        zone: String = "",
        templateKey: String = "",
        nodeId: String = "",
    ) {
        emit(
            sessionId,
            "Dialogue.Node",
            DialogueNodePayload(
                mobName = mobName,
                text = text,
                voiceUrl = dialogueVoiceUrl(zone, templateKey, nodeId, text),
                choices = choices.map { (index, choiceText) -> DialogueChoicePayload(index = index, text = choiceText) },
            ),
        )
    }

    /**
     * Resolves the voice-over clip URL for a dialogue node, or null when voice-over is disabled
     * or the line lacks a stable identity. Path shape mirrors `docs/VOICE_OVER_CONTRACT.md`:
     * `<voicesBase><zone>/<templateKey>/<nodeId>.<sha8>.mp3`, where `<sha8>` is the first 8 hex
     * chars of SHA-256 over the raw node text. The hash makes the path edit-safe: changing a
     * line yields a new filename, so stale audio is never served. Arcanum must hash identically.
     *
     * [templateKey] arrives zone-qualified for zone-loaded mobs (`WorldLoader` qualifies template
     * ids as `<zone>:<rawId>`), so we strip the prefix to the local key — the contract puts the
     * unqualified key in its own segment (`headmaster_aldric`, not `academy:headmaster_aldric`),
     * and `zone` is already a separate segment. Unqualified keys pass through unchanged.
     */
    private fun dialogueVoiceUrl(
        zone: String,
        templateKey: String,
        nodeId: String,
        text: String,
    ): String? =
        if (!voicesEnabled || zone.isBlank() || templateKey.isBlank() || nodeId.isBlank()) {
            null
        } else {
            "$voicesBase$zone/${templateKey.substringAfter(':')}/$nodeId.${sha8(text)}.mp3"
        }

    /** First 8 lowercase-hex chars of SHA-256 over the UTF-8 bytes of [text]. */
    private fun sha8(text: String): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(8)

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

    // ---------- guild hall ----------

    data class GuildHallRoomPayload(
        val id: String,
        val template: String,
        val title: String,
    )

    suspend fun sendGuildHall(
        sessionId: SessionId,
        guildName: String,
        rooms: List<GuildHallRoomPayload>,
        membersInHall: Int,
        maxRooms: Int,
    ) {
        emit(
            sessionId,
            "Guild.Hall",
            GuildHallGmcpPayload(
                guildName = guildName,
                rooms = rooms.map { GuildHallRoomGmcpEntry(it.id, it.template, it.title) },
                membersInHall = membersInHall,
                maxRooms = maxRooms,
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

    // ---------- puzzle ----------

    suspend fun sendPuzzleList(
        sessionId: SessionId,
        puzzles: List<PuzzlePayload>,
    ) {
        emit(
            sessionId,
            "Puzzle.List",
            PuzzleListPayload(puzzles = puzzles),
            supportCheck = "Puzzle",
        )
    }

    suspend fun sendPuzzleClose(sessionId: SessionId) {
        emitRaw(sessionId, "Puzzle.Close", "{}", supportCheck = "Puzzle")
    }

    /**
     * Result of a riddle answer attempt. The web client uses this to play the
     * inscribe → puff-of-flame beat (gold flare on [correct], ash scatter
     * otherwise) and surface [message] on the page.
     */
    suspend fun sendPuzzleResult(
        sessionId: SessionId,
        id: String,
        correct: Boolean,
        message: String,
    ) {
        emit(
            sessionId,
            "Puzzle.Result",
            PuzzleResultPayload(id = id, correct = correct, message = message),
            supportCheck = "Puzzle",
        )
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
        playerDescription: String? = null,
        slot: String? = null,
        damage: Int? = null,
        armor: Int? = null,
        basePrice: Int? = null,
        stats: Map<String, Int>? = null,
        enchantments: List<String>? = null,
        consumable: Boolean? = null,
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
                playerDescription = playerDescription,
                slot = slot,
                damage = damage,
                armor = armor,
                basePrice = basePrice,
                stats = stats,
                enchantments = enchantments,
                consumable = consumable,
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
        val grouped = world.mobTemplates.values.groupBy { it.id.value.substringBefore(':') }
        val zones = grouped.entries.sortedBy { it.key }.map { (zone, templates) ->
            StaffMobZonePayload(
                zone = zone,
                mobs = templates.sortedBy { it.id.value }.map { tpl ->
                    StaffMobTemplatePayload(
                        id = tpl.id.value,
                        name = tpl.name,
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

    /**
     * Broadcasts the same [payload] to every recipient in [recipients], serializing
     * the JSON once regardless of the recipient count. All [broadcast*] helpers in
     * this class route through here. Per-session [emit] calls would otherwise build
     * the payload + `writeValueAsString` it once per recipient, which at 1k+ online
     * sessions in a shared room / world-wide broadcast becomes the dominant
     * allocation-and-CPU cost of the GMCP flush pass.
     */
    private suspend fun <T : Any> broadcastSerialized(
        recipients: Collection<PlayerState>,
        packageName: String,
        payload: T,
        supportCheck: String = packageName,
    ) {
        if (recipients.isEmpty()) return
        val serialized = json.writeValueAsString(payload)
        if (serialized.length > MAX_GMCP_PAYLOAD_BYTES) {
            log.warn { "GMCP payload exceeds ${MAX_GMCP_PAYLOAD_BYTES}B limit for $packageName (${serialized.length}B), skipping" }
            return
        }
        for (p in recipients) {
            if (!supportsPackage(p.sessionId, supportCheck)) continue
            outbound.send(OutboundEvent.GmcpData(p.sessionId, packageName, serialized))
        }
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
            consumable = if (item.item.consumable) true else null,
            useEffect = item.item.onUse?.describe(),
            onUse = item.item.onUse?.let { effect ->
                if (effect.healHp > 0 || effect.healMana > 0) {
                    ItemUseEffectPayload(healHp = effect.healHp, healMana = effect.healMana)
                } else {
                    null
                }
            },
            itemType = item.item.resolvedType().label(),
            questItem = if (item.item.questItem) true else null,
            stackKey = computeStackKey(item),
        )

    /**
     * Grouping key used by the web client to visually collapse identical instances
     * into a single row with a count. Two instances share a stackKey only when their
     * item definition, enchantments, and remaining charges all match.
     */
    private fun computeStackKey(item: ItemInstance): String {
        val enchants = if (item.enchantments.isEmpty()) {
            ""
        } else {
            item.enchantments.sorted().joinToString(",")
        }
        val charges = item.item.charges?.toString() ?: ""
        return "${item.item.keyword}|$enchants|$charges"
    }

    private fun toRoomMobPayload(mob: MobState): RoomMobPayload {
        val effects = getMobEffects(mob.id)
        return RoomMobPayload(
            id = mob.id.value,
            templateKey = mob.templateKey,
            name = mob.name,
            description = mob.description,
            hp = mob.hp,
            maxHp = mob.maxHp,
            image = mob.image,
            video = mob.video,
            category = mob.category,
            effects = if (effects.isEmpty()) {
                null
            } else {
                effects.map { e ->
                    MobEffectPayload(name = e.name, type = e.type, remainingMs = e.remainingMs, stacks = e.stacks)
                }
            },
            ownerName = mob.ownerName,
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
        val targetCategory: String?,
    )

    @Suppress("unused") // Jackson serializes all fields
    private data class CombatConsiderPayload(
        val mobId: String,
        val mobName: String,
        val mobLevel: Int,
        val mobMaxHp: Int,
        val mobCategory: String,
        val mobImage: String?,
        val playerLevel: Int,
        val playerMaxHp: Int,
        val playerAvgDamage: Int,
        val mobAvgDamage: Int,
        val hitsToKillMob: Int,
        val hitsToKillPlayer: Int,
        val dodgeChancePct: Int,
        val winChancePct: Int,
        val rating: String,
        val ratingLabel: String,
        val ratingFlavor: String,
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
        val terrain: String = "outside",
        val pvpEnabled: Boolean = false,
        val trainer: String? = null,
        val bank: Boolean = false,
        val stylist: Boolean = false,
        val tavern: Boolean = false,
        val dungeon: Boolean = false,
        val auction: Boolean = false,
        val housingBroker: Boolean = false,
        val inn: Boolean = false,
        val canDepart: Boolean = false,
        /** Auto-peek entries: open exits paired with destination room titles. Empty when the player has autopeek off. */
        val peek: List<RoomPeekEntry> = emptyList(),
    )

    /** One auto-peek entry for `Room.Info`: an open exit and the title of the room it leads to. */
    data class RoomPeekEntry(
        val direction: String,
        val title: String,
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
        val consumable: Boolean? = null,
        val useEffect: String? = null,
        val onUse: ItemUseEffectPayload? = null,
        val itemType: String,
        val questItem: Boolean? = null,
        val stackKey: String,
    )

    private data class ItemUseEffectPayload(
        val healHp: Int,
        val healMana: Int,
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
        val sprite: String? = null,
    )

    private data class RoomRemovePlayerPayload(
        val name: String,
    )

    private data class RoomMobPayload(
        val id: String,
        val templateKey: String,
        val name: String,
        val description: String = "",
        val hp: Int,
        val maxHp: Int,
        val image: String? = null,
        val video: String? = null,
        val category: String = "humanoid",
        val effects: List<MobEffectPayload>? = null,
        val ownerName: String? = null,
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
        val takeable: Boolean = true,
    )

    private data class CharSkillPayload(
        val id: String,
        val name: String,
        val description: String,
        val skillPointCost: Int,
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
        val visual: SkillVisualPayload? = null,
    )

    private data class SkillVisualPayload(
        val archetype: String,
        val projectileImage: String? = null,
        val color: String? = null,
        val accentColor: String? = null,
    )

    private data class TrainerAbilityPayload(
        val id: String,
        val name: String,
        val description: String,
        val skillPointCost: Int,
        val levelRequired: Int,
        val manaCost: Int,
        val cooldownMs: Long,
        val targetType: String,
        val effectType: String,
        val image: String? = null,
        val prerequisites: List<String> = emptyList(),
        val tree: String = "",
        val tier: Int = 0,
        val locked: Boolean = false,
        val lockReason: String? = null,
    )

    private data class TrainerClassPayload(
        @get:JsonProperty("class") val className: String,
        val classUnlocked: Boolean,
        val abilities: List<TrainerAbilityPayload>,
    )

    private data class TrainerListPayload(
        val trainerId: String,
        val name: String,
        val image: String?,
        val availableSkillPoints: Int,
        val multiclassMinLevel: Int,
        /** Gold cost for *this player's next* unlock; already includes the exponential scaling. */
        val multiclassGoldCost: Long,
        /** Hard cap on the player's total unlocked classes (including starter). */
        val multiclassMaxClasses: Long,
        /** Player's current unlocked-class count (including starter). */
        val multiclassUnlockedCount: Int,
        /**
         * One entry per class this trainer teaches. Always non-empty. Single-class
         * trainers emit a one-element list — clients can still render the old
         * "one class" UI by reading `classes[0]`.
         */
        val classes: List<TrainerClassPayload>,
    )

    private data class CharClassesPayload(
        val originalClass: String,
        val unlockedClasses: List<String>,
    )

    private data class CharRecallPayload(
        val roomId: String?,
        val roomTitle: String?,
    )

    private data class CharNamePayload(
        val name: String,
        val gender: String,
        val race: String,
        @get:JsonProperty("class") val playerClass: String,
        val level: Int,
        val sprite: String,
        val isStaff: Boolean,
        val autolootEnabled: Boolean,
        val autopeekEnabled: Boolean,
        val wimpyThresholdPct: Int,
        /** The player's currently-equipped achievement title, or null when none is set. */
        val title: String?,
        /**
         * True for unclaimed demo characters (no persistent account). Web client
         * surfaces a "Save your progress" prompt when set. Flips to false after
         * a successful `/claim`.
         */
        val isDemo: Boolean,
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

    private data class GuildHallGmcpPayload(
        val guildName: String,
        val rooms: List<GuildHallRoomGmcpEntry>,
        val membersInHall: Int,
        val maxRooms: Int,
    )

    private data class GuildHallRoomGmcpEntry(
        val id: String,
        val template: String,
        val title: String,
    )

    private data class DialogueChoicePayload(
        val index: Int,
        val text: String,
    )

    private data class DialogueNodePayload(
        val mobName: String,
        val text: String,
        val voiceUrl: String?,
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
        val petName: String? = null,
        val targetIsPlayer: Boolean? = null,
        val lootedItems: List<String> = emptyList(),
        val forced: Boolean? = null,
        /**
         * Server-rendered narrative line (custom config templates, stat-formula suffixes).
         * When present, the web client combat log uses it instead of the hardcoded fallback.
         */
        val text: String? = null,
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
        val readyToTurnIn: Boolean,
        val giverMobId: String,
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
        val readyToTurnIn: Boolean,
    )

    private data class QuestCompletePayload(
        val questId: String,
        val questName: String,
        val questDescription: String,
        val rewards: QuestAvailableRewardsPayload,
    )

    private data class QuestAvailablePayload(
        val id: String,
        val name: String,
        val description: String,
        val giverMobId: String,
        val objectives: List<QuestAvailableObjectivePayload>,
        val rewards: QuestAvailableRewardsPayload,
        val levelRequired: Int?,
        val reputationRequired: QuestReputationPayload?,
        val readyToTurnIn: Boolean,
        val lockedReason: String?,
    )

    private data class QuestAvailableObjectivePayload(
        val description: String,
        val count: Int,
        val current: Int,
    )

    private data class QuestAvailableRewardsPayload(
        val xp: Long,
        val gold: Long,
        val currencies: Map<String, Long>,
        val items: List<QuestRewardItemPayload>,
    )

    private data class QuestRewardItemPayload(
        val itemId: String,
        val displayName: String,
        val count: Int,
    )

    private data class QuestReputationPayload(
        val faction: String,
        val factionName: String,
        val min: Int?,
        val max: Int?,
    )

    // ---------- daily / weekly quest payloads ----------

    private data class QuestDailyPayload(
        val quests: List<DailyQuestEntryPayload>,
        val streakDays: Int,
    )

    private data class DailyQuestEntryPayload(
        val index: Int,
        val type: String,
        val description: String,
        val current: Int,
        val required: Int,
        val completed: Boolean,
        val goldReward: Long,
        val xpReward: Long,
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

    private data class CraftingCooldownPayload(
        val type: String,
        val untilMs: Long,
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
        // Optional per-feature backdrop art for the World Features modal.
        // CONTAINER → chest, SIGN → board, LEVER → the box the lever sits inside.
        val backgroundImage: String? = null,
        // Optional custom lever art (static per-lever; rides along with the feature).
        val plateImage: String? = null,
        val handleImage: String? = null,
        val leverPivot: LeverPivotPayload? = null,
        val upAngle: Double? = null,
        val downAngle: Double? = null,
        // Optional custom door art (DOOR only): a static frame + a swinging leaf
        // that pivots on `hinge` between 0° (closed) and `openAngle` (open).
        val frameImage: String? = null,
        val leafImage: String? = null,
        val hinge: String? = null,
        val openAngle: Double? = null,
        val leafScale: Double? = null,
        val leafOffsetY: Double? = null,
        // The key that locks/unlocks this door (DOOR only), for the lock UI.
        val keyImage: String? = null,
        val keyName: String? = null,
    )

    data class LeverPivotPayload(
        val x: Double,
        val y: Double,
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
        val gold: Long,
        val itemName: String?,
        val itemImage: String?,
        val claimed: Boolean,
    )

    private data class MailMessagePayload(
        val index: Int,
        val id: String,
        val from: String,
        val body: String,
        val date: Long,
        val read: Boolean,
        val gold: Long,
        val itemName: String?,
        val itemImage: String?,
        val claimed: Boolean,
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

    // ---------- level-up payload ----------

    private data class CharLevelUpPayload(
        val previousLevel: Int,
        val newLevel: Int,
        val levelsGained: Int,
        val hpGained: Int,
        val manaGained: Int,
        val newAbilities: List<String>,
        val skillPointsAvailable: Int,
        val isMilestone: Boolean,
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
        val sprite: String,
        val description: String,
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

    private data class WorldAreasPayload(
        val areas: List<WorldAreaPayload>,
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
        val combatant: Boolean,
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
        val playerDescription: String? = null,
        val slot: String? = null,
        val damage: Int? = null,
        val armor: Int? = null,
        val basePrice: Int? = null,
        val stats: Map<String, Int>? = null,
        val enchantments: List<String>? = null,
        val consumable: Boolean? = null,
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

    // ---------- puzzle payloads ----------

    private data class PuzzleListPayload(
        val puzzles: List<PuzzlePayload>,
    )

    /** Shape sent to the web client for a single in-room puzzle. */
    data class PuzzlePayload(
        val id: String,
        /** "riddle" or "sequence". */
        val type: String,
        /** Riddle question text, or null for sequence puzzles. */
        val question: String?,
        /** Total number of steps in a sequence puzzle, or null for riddles. */
        val totalSteps: Int?,
        /** Current progress (0-based next step index) for sequence puzzles, or null. */
        val currentStep: Int?,
        /** True if the player has already solved this puzzle. */
        val solved: Boolean,
        /** Optional parchment/grimoire backdrop art (resolved URL), or null. */
        val backgroundImage: String? = null,
    )

    /** Result of a riddle answer attempt — drives the client's inscribe/flame beat. */
    private data class PuzzleResultPayload(
        val id: String,
        val correct: Boolean,
        val message: String,
    )

    internal companion object {
        /** Maximum serialized GMCP JSON payload size in bytes (64 KB). */
        const val MAX_GMCP_PAYLOAD_BYTES = 65_536

        /** Upper bound for the zone-tracking LRU cache (well above any realistic session count). */
        const val MAX_ZONE_CACHE_ENTRIES = 10_000

        /** Upper bound for the vitals dirty-diff LRU cache. */
        const val MAX_VITALS_CACHE_ENTRIES = 10_000

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

    // ---------- global quest ----------

    /** Sends the `Quest.Global` GMCP package with current global quest state. */
    suspend fun sendGlobalQuest(
        sessionId: SessionId,
        status: GlobalQuestStatus,
        playerProgress: Int,
    ) {
        emit(
            sessionId,
            "Quest.Global",
            GlobalQuestPayload(
                active = true,
                objective = status.objective.description,
                objectiveType = status.objective.type.name.lowercase(),
                targetCount = status.objective.targetCount,
                playerProgress = playerProgress,
                endsAtMs = status.endsAtMs,
                completed = status.completed,
                leaderboard = status.leaderboard.mapIndexed { index, entry ->
                    GlobalQuestLeaderboardPayload(
                        rank = index + 1,
                        name = entry.playerName,
                        progress = entry.progress,
                    )
                },
            ),
            supportCheck = "Quest",
        )
    }

    /** Sends a `Quest.Global` GMCP with active=false when no quest is running. */
    suspend fun sendGlobalQuestInactive(sessionId: SessionId) {
        emit(
            sessionId,
            "Quest.Global",
            GlobalQuestInactivePayload(active = false),
            supportCheck = "Quest",
        )
    }

    /** Broadcasts `Quest.Global` to all connected players. */
    suspend fun broadcastGlobalQuest(
        status: GlobalQuestStatus,
        players: PlayerRegistry,
        progressBySession: Map<SessionId, Int>,
    ) {
        for (p in players.allPlayers()) {
            sendGlobalQuest(p.sessionId, status, progressBySession[p.sessionId] ?: 0)
        }
    }

    /** Broadcasts `Quest.Global` inactive to all connected players. */
    suspend fun broadcastGlobalQuestInactive(players: PlayerRegistry) {
        broadcastSerialized(
            players.allPlayers(),
            "Quest.Global",
            GlobalQuestInactivePayload(active = false),
            supportCheck = "Quest",
        )
    }

    private data class GlobalQuestPayload(
        val active: Boolean,
        val objective: String,
        val objectiveType: String,
        val targetCount: Int,
        val playerProgress: Int,
        val endsAtMs: Long,
        val completed: Boolean,
        val leaderboard: List<GlobalQuestLeaderboardPayload>,
    )

    private data class GlobalQuestInactivePayload(
        val active: Boolean,
    )

    private data class GlobalQuestLeaderboardPayload(
        val rank: Int,
        val name: String,
        val progress: Int,
    )

    // ---------- duel ----------

    private data class DuelStatePayload(
        val active: Boolean,
        val opponentName: String? = null,
        val startedAtMs: Long? = null,
    )

    private data class DuelChallengePayload(
        val challengerName: String,
        val targetName: String,
        val direction: String,
    )

    suspend fun sendDuelState(
        sessionId: SessionId,
        active: Boolean,
        opponentName: String? = null,
        startedAtMs: Long? = null,
    ) {
        emit(
            sessionId,
            "Duel.State",
            DuelStatePayload(
                active = active,
                opponentName = opponentName,
                startedAtMs = startedAtMs,
            ),
            supportCheck = "Duel",
        )
    }

    suspend fun sendDuelChallenge(
        sessionId: SessionId,
        challengerName: String,
        targetName: String,
        direction: String,
    ) {
        emit(
            sessionId,
            "Duel.Challenge",
            DuelChallengePayload(
                challengerName = challengerName,
                targetName = targetName,
                direction = direction,
            ),
            supportCheck = "Duel",
        )
    }

    // ---------- dungeon ----------

    private data class DungeonInfoPayload(
        val active: Boolean,
        val instanceId: String? = null,
        val name: String? = null,
        val difficulty: String? = null,
        val totalRooms: Int? = null,
        val completed: Boolean? = null,
        val memberCount: Int? = null,
    )

    data class DungeonCatalogDifficultyPayload(
        val id: String,
        val label: String,
        val summary: String,
    )

    data class DungeonCatalogEntryPayload(
        val id: String,
        val name: String,
        val description: String,
        val minLevel: Int,
        val portalHint: String? = null,
        val difficulties: List<DungeonCatalogDifficultyPayload> = emptyList(),
    )

    suspend fun sendDungeonInfo(
        sessionId: SessionId,
        active: Boolean,
        instanceId: String? = null,
        name: String? = null,
        difficulty: String? = null,
        totalRooms: Int? = null,
        completed: Boolean? = null,
        memberCount: Int? = null,
    ) {
        emit(
            sessionId,
            "Dungeon.Info",
            DungeonInfoPayload(
                active = active,
                instanceId = instanceId,
                name = name,
                difficulty = difficulty,
                totalRooms = totalRooms,
                completed = completed,
                memberCount = memberCount,
            ),
            supportCheck = "Dungeon",
        )
    }

    suspend fun sendDungeonCatalog(
        sessionId: SessionId,
        catalog: List<DungeonCatalogEntryPayload>,
    ) {
        emit(
            sessionId,
            "Dungeon.Catalog",
            catalog,
            supportCheck = "Dungeon",
        )
    }

    // ---------- prestige ----------

    private data class PrestigeInfoPayload(
        val enabled: Boolean,
        val currentRank: Int,
        val maxRank: Int,
        val availableXp: Long,
        val nextRankCost: Long?,
        val requiredLevel: Int,
        val perks: List<PrestigePerkPayload>,
    )

    suspend fun sendPrestigeInfo(
        sessionId: SessionId,
        enabled: Boolean,
        currentRank: Int,
        maxRank: Int,
        availableXp: Long,
        nextRankCost: Long?,
        requiredLevel: Int,
        perks: List<PrestigePerkPayload>,
    ) {
        emit(
            sessionId,
            "Prestige.Info",
            PrestigeInfoPayload(
                enabled = enabled,
                currentRank = currentRank,
                maxRank = maxRank,
                availableXp = availableXp,
                nextRankCost = nextRankCost,
                requiredLevel = requiredLevel,
                perks = perks,
            ),
            supportCheck = "Prestige",
        )
    }

    suspend fun sendPrestigeInfoForPlayer(
        sessionId: SessionId,
        player: PlayerState,
    ) {
        val enabled = prestigeEnabled()
        val currentRank = player.prestigeLevel
        val maxRank = if (enabled) prestigeMaxRank() else 0
        val requiredLevel = progression?.maxLevel ?: 0
        val availableXp =
            if (enabled && (progression == null || player.level >= progression.maxLevel)) {
                prestigeAvailableXp(player) ?: 0L
            } else {
                0L
            }
        val nextRankCost =
            if (enabled && currentRank < maxRank) {
                prestigeNextCost(currentRank)
            } else {
                null
            }
        val perks =
            if (enabled && maxRank > 0) {
                prestigePerkPayloads(currentRank, maxRank)
            } else {
                emptyList()
            }
        sendPrestigeInfo(
            sessionId = sessionId,
            enabled = enabled,
            currentRank = currentRank,
            maxRank = maxRank,
            availableXp = availableXp,
            nextRankCost = nextRankCost,
            requiredLevel = requiredLevel,
            perks = perks,
        )
    }

    /** Helper to build perk payloads from the prestige system. */
    fun buildPrestigePerkPayloads(
        currentRank: Int,
        maxRank: Int,
        perkForRank: (Int) -> dev.ambon.config.PrestigePerkConfig?,
    ): List<PrestigePerkPayload> =
        (1..maxRank).map { rank ->
            val perk = perkForRank(rank)
            PrestigePerkPayload(
                rank = rank,
                type = perk?.type?.uppercase() ?: "",
                description = perk?.description ?: "—",
                earned = rank <= currentRank,
            )
        }
}

// ---------- public data entry types for new GMCP methods ----------

data class QuestListEntry(
    val id: String,
    val name: String,
    val description: String,
    val objectives: List<QuestObjectiveEntry>,
    val readyToTurnIn: Boolean = false,
    val giverMobId: String = "",
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
    val rewardCurrencies: Map<String, Long> = emptyMap(),
    val rewardItems: List<QuestRewardItemSummary> = emptyList(),
    val levelRequired: Int? = null,
    val reputationRequired: ReputationRequirementSummary? = null,
    val readyToTurnIn: Boolean = false,
    val lockedReason: String? = null,
)

data class QuestRewardItemSummary(
    val itemId: String,
    val displayName: String,
    val count: Int,
)

data class QuestAvailableObjectiveSummary(
    val description: String,
    val count: Int,
    val current: Int = 0,
)

data class ReputationRequirementSummary(
    val faction: String,
    val factionName: String,
    val min: Int? = null,
    val max: Int? = null,
)

data class QuestCompletionSummary(
    val questId: String,
    val questName: String,
    val questDescription: String,
    val rewardXp: Long,
    val rewardGold: Long,
    val rewardCurrencies: Map<String, Long>,
    val rewardItems: List<QuestRewardItemSummary> = emptyList(),
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
    val combatant: Boolean,
)

/** Input DTO for building a Zone.Instances GMCP payload. */
data class ZoneInstanceEntry(
    val engineId: String,
    val playerCount: Int,
    val capacity: Int,
)
