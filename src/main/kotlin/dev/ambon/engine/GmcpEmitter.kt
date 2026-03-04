package dev.ambon.engine

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.ambon.bus.OutboundBus
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.items.ItemSlot
import dev.ambon.domain.mob.MobState
import dev.ambon.domain.world.Room
import dev.ambon.engine.abilities.AbilityDefinition
import dev.ambon.engine.abilities.AbilityId
import dev.ambon.engine.abilities.AbilitySystem
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
) {
    private val json = jacksonObjectMapper()

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
            supportCheck = "Char",
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
                equipment = ItemSlot.entries.associate { slot -> slot.label() to equipment[slot]?.let { toItemPayload(it) } },
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
        emit(sessionId, "Room.Items", items.map { RoomItemPayload(id = it.id.value, name = it.item.displayName, image = it.item.image) })
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
                    classRestriction = a.requiredClass?.name,
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
                race = player.race,
                playerClass = player.playerClass,
                level = player.level,
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

    private fun toItemPayload(item: ItemInstance) =
        ItemPayload(
            id = item.id.value,
            name = item.item.displayName,
            slot = item.item.slot?.label(),
            damage = item.item.damage,
            armor = item.item.armor,
            basePrice = item.item.basePrice,
            image = item.item.image,
        )

    private fun toRoomMobPayload(mob: MobState) =
        RoomMobPayload(
            id = mob.id.value,
            name = mob.name,
            hp = mob.hp,
            maxHp = mob.maxHp,
            image = mob.image,
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
    )

    private data class ItemPayload(
        val id: String,
        val name: String,
        val slot: String?,
        val damage: Int,
        val armor: Int,
        val basePrice: Int = 0,
        val image: String? = null,
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
        val hp: Int,
        val maxHp: Int,
        val image: String? = null,
    )

    private data class RoomRemoveMobPayload(
        val id: String,
    )

    private data class RoomItemPayload(
        val id: String,
        val name: String,
        val image: String? = null,
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
    )

    private data class CharNamePayload(
        val name: String,
        val race: String,
        @get:JsonProperty("class") val playerClass: String,
        val level: Int,
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
    )

    private companion object {
        const val CHAR_STATUS_VARS_JSON =
            """{"hp":"HP","maxHp":"Max HP","mana":"Mana","maxMana":"Max Mana","level":"Level","xp":"XP"}"""
        const val CORE_PING_JSON = "{}"
    }
}
