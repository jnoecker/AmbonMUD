package dev.ambon.engine

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.ambon.bus.OutboundBus
import dev.ambon.domain.Gender
import dev.ambon.domain.StatMap
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.items.ItemSlot
import dev.ambon.domain.mob.MobState
import dev.ambon.domain.world.Room
import dev.ambon.engine.abilities.AbilityDefinition
import dev.ambon.engine.abilities.AbilityId
import dev.ambon.engine.abilities.AbilitySystem
import dev.ambon.engine.events.CombatEvent
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.status.ActiveEffectSnapshot
import dev.ambon.engine.status.StatusEffectSystem
import kotlin.math.roundToInt

data class CombatTargetInfo(
    val id: String,
    val name: String,
    val hp: Int,
    val maxHp: Int,
    val image: String? = null,
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
) {
    private val json = jacksonObjectMapper()
    private val imagesBase = if (imagesBaseUrl.endsWith("/")) imagesBaseUrl else "$imagesBaseUrl/"

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
            ),
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
            players.filter { it.sessionId != sessionId }.map { RoomPlayerPayload(name = it.name, level = it.level) },
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
                    targetType = a.targetType.name,
                    classRestriction = a.requiredClass,
                    image = a.image,
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

    suspend fun sendCorePing(sessionId: SessionId) {
        emitRaw(sessionId, "Core.Ping", CORE_PING_JSON)
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
        sendCharStatusVars(sessionId)
        sendCharVitals(sessionId, player)
        sendCharName(sessionId, player)
        sendCharItemsList(sessionId, items.inventory(sessionId), items.equipment(sessionId))
        sendCharSkills(sessionId, abilitySystem.knownAbilities(sessionId)) { abilityId ->
            abilitySystem.cooldownRemainingMs(sessionId, abilityId)
        }
        sendCharStatusEffects(sessionId, statusEffectSystem.activePlayerEffects(sessionId))
        sendCharAchievements(sessionId, player, achievementRegistry)
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
                amount = event.amount,
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
                amount = event.amount,
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
                remaining = event.remaining,
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
                    QuestObjectivePayload(description = o.description, current = o.current, required = o.required)
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
                    shopKeeper = m.shopKeeper,
                    dialogue = m.dialogue,
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

    // ---------- emit helpers ----------

    private suspend fun <T : Any> emit(
        sessionId: SessionId,
        packageName: String,
        payload: T,
        supportCheck: String = packageName,
    ) {
        if (!supportsPackage(sessionId, supportCheck)) return
        outbound.send(OutboundEvent.GmcpData(sessionId, packageName, json.writeValueAsString(payload)))
    }

    private suspend fun emitRaw(
        sessionId: SessionId,
        packageName: String,
        rawJson: String,
        supportCheck: String = packageName,
    ) {
        if (!supportsPackage(sessionId, supportCheck)) return
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
        )

    private fun toRoomMobPayload(mob: MobState) =
        RoomMobPayload(
            id = mob.id.value,
            name = mob.name,
            description = mob.description,
            hp = mob.hp,
            maxHp = mob.maxHp,
            image = mob.image,
            video = mob.video,
        )

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
    )

    private data class CharCombatPayload(
        val targetId: String?,
        val targetName: String?,
        val targetHp: Int?,
        val targetMaxHp: Int?,
        val targetImage: String?,
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
        val classRestriction: String?,
        val image: String? = null,
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
        val amount: Int? = null,
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
        val remaining: Int? = null,
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

    // ---------- cooldown payload ----------

    private data class CharCooldownPayload(
        val abilityId: String,
        val cooldownMs: Long,
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

    // ---------- room mob info payload ----------

    private data class RoomMobInfoPayload(
        val id: String,
        val level: Int,
        val tier: String,
        val questGiver: Boolean,
        val shopKeeper: Boolean,
        val dialogue: Boolean,
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

    private companion object {
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

        private val SPRITE_LEVEL_TIERS = intArrayOf(50, 40, 30, 20, 10, 1)
        private const val STAFF_SPRITE_TIER = 60
    }

    private fun resolveSprite(player: PlayerState): String {
        val gender = Gender.fromString(player.gender) ?: Gender.ENBY
        val race = player.race.lowercase()
        val cls = player.playerClass.lowercase()
        val tier = if (player.isStaff) STAFF_SPRITE_TIER else SPRITE_LEVEL_TIERS.firstOrNull { player.level >= it } ?: 1
        return "${imagesBase}player_sprites/${race}_${gender.spriteCode}_${cls}_l$tier.png"
    }
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
