package dev.ambon.engine

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.ambon.bus.OutboundBus
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.items.ItemSlot
import dev.ambon.domain.mob.MobState
import dev.ambon.domain.world.Room
import dev.ambon.engine.abilities.AbilityDefinition
import dev.ambon.engine.abilities.AbilityId
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.status.ActiveEffectSnapshot

class GmcpEmitter(
    private val outbound: OutboundBus,
    private val supportsPackage: (SessionId, String) -> Boolean,
    private val progression: PlayerProgression? = null,
    private val isInCombat: (SessionId) -> Boolean = { false },
) {
    private val json = jacksonObjectMapper()

    suspend fun sendCharVitals(
        sessionId: SessionId,
        player: PlayerState,
    ) {
        if (!supportsPackage(sessionId, "Char.Vitals")) return
        val payload =
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
            )
        outbound.send(OutboundEvent.GmcpData(sessionId, "Char.Vitals", json.writeValueAsString(payload)))
    }

    suspend fun sendRoomInfo(
        sessionId: SessionId,
        room: Room,
    ) {
        if (!supportsPackage(sessionId, "Room.Info")) return
        val payload =
            RoomInfoPayload(
                id = room.id.value,
                title = room.title,
                description = room.description,
                zone = room.id.zone,
                exits = room.exits.entries.associate { (dir, roomId) -> dir.name.lowercase() to roomId.value },
            )
        outbound.send(OutboundEvent.GmcpData(sessionId, "Room.Info", json.writeValueAsString(payload)))
    }

    suspend fun sendCharStatusVars(sessionId: SessionId) {
        if (!supportsPackage(sessionId, "Char.StatusVars")) return
        outbound.send(OutboundEvent.GmcpData(sessionId, "Char.StatusVars", CHAR_STATUS_VARS_JSON))
    }

    suspend fun sendCharItemsList(
        sessionId: SessionId,
        inventory: List<ItemInstance>,
        equipment: Map<ItemSlot, ItemInstance>,
    ) {
        if (!supportsPackage(sessionId, "Char.Items.List")) return
        val payload =
            CharItemsListPayload(
                inventory = inventory.map { toItemPayload(it) },
                equipment = ItemSlot.entries.associate { slot -> slot.label() to equipment[slot]?.let { toItemPayload(it) } },
            )
        outbound.send(OutboundEvent.GmcpData(sessionId, "Char.Items.List", json.writeValueAsString(payload)))
    }

    suspend fun sendCharItemsAdd(
        sessionId: SessionId,
        item: ItemInstance,
    ) {
        if (!supportsPackage(sessionId, "Char.Items.Add")) return
        outbound.send(OutboundEvent.GmcpData(sessionId, "Char.Items.Add", json.writeValueAsString(toItemPayload(item))))
    }

    suspend fun sendCharItemsRemove(
        sessionId: SessionId,
        item: ItemInstance,
    ) {
        if (!supportsPackage(sessionId, "Char.Items.Remove")) return
        val payload = CharItemsRemovePayload(id = item.id.value, name = item.item.displayName)
        outbound.send(OutboundEvent.GmcpData(sessionId, "Char.Items.Remove", json.writeValueAsString(payload)))
    }

    suspend fun sendRoomPlayers(
        sessionId: SessionId,
        players: List<PlayerState>,
    ) {
        if (!supportsPackage(sessionId, "Room.Players")) return
        val payload = players.filter { it.sessionId != sessionId }.map { RoomPlayerPayload(name = it.name, level = it.level) }
        outbound.send(OutboundEvent.GmcpData(sessionId, "Room.Players", json.writeValueAsString(payload)))
    }

    suspend fun sendRoomAddPlayer(
        sessionId: SessionId,
        player: PlayerState,
    ) {
        if (!supportsPackage(sessionId, "Room.Players")) return
        outbound.send(
            OutboundEvent.GmcpData(
                sessionId,
                "Room.AddPlayer",
                json.writeValueAsString(RoomPlayerPayload(name = player.name, level = player.level)),
            ),
        )
    }

    suspend fun sendRoomRemovePlayer(
        sessionId: SessionId,
        name: String,
    ) {
        if (!supportsPackage(sessionId, "Room.Players")) return
        outbound.send(OutboundEvent.GmcpData(sessionId, "Room.RemovePlayer", json.writeValueAsString(RoomRemovePlayerPayload(name = name))))
    }

    suspend fun sendRoomMobs(
        sessionId: SessionId,
        mobs: List<MobState>,
    ) {
        if (!supportsPackage(sessionId, "Room.Mobs")) return
        outbound.send(OutboundEvent.GmcpData(sessionId, "Room.Mobs", json.writeValueAsString(mobs.map { toRoomMobPayload(it) })))
    }

    suspend fun sendRoomItems(
        sessionId: SessionId,
        items: List<ItemInstance>,
    ) {
        if (!supportsPackage(sessionId, "Room.Items")) return
        val payload = items.map { RoomItemPayload(id = it.id.value, name = it.item.displayName) }
        outbound.send(OutboundEvent.GmcpData(sessionId, "Room.Items", json.writeValueAsString(payload)))
    }

    suspend fun sendRoomAddMob(
        sessionId: SessionId,
        mob: MobState,
    ) {
        if (!supportsPackage(sessionId, "Room.Mobs")) return
        outbound.send(OutboundEvent.GmcpData(sessionId, "Room.AddMob", json.writeValueAsString(toRoomMobPayload(mob))))
    }

    suspend fun sendRoomUpdateMob(
        sessionId: SessionId,
        mob: MobState,
    ) {
        if (!supportsPackage(sessionId, "Room.Mobs")) return
        outbound.send(OutboundEvent.GmcpData(sessionId, "Room.UpdateMob", json.writeValueAsString(toRoomMobPayload(mob))))
    }

    suspend fun sendRoomRemoveMob(
        sessionId: SessionId,
        mobId: String,
    ) {
        if (!supportsPackage(sessionId, "Room.Mobs")) return
        outbound.send(OutboundEvent.GmcpData(sessionId, "Room.RemoveMob", json.writeValueAsString(RoomRemoveMobPayload(id = mobId))))
    }

    suspend fun sendCharSkills(
        sessionId: SessionId,
        abilities: List<AbilityDefinition>,
        cooldownRemainingMs: (AbilityId) -> Long = { 0L },
    ) {
        if (!supportsPackage(sessionId, "Char.Skills")) return
        val payload =
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
            }
        outbound.send(OutboundEvent.GmcpData(sessionId, "Char.Skills", json.writeValueAsString(payload)))
    }

    suspend fun sendCharName(
        sessionId: SessionId,
        player: PlayerState,
    ) {
        if (!supportsPackage(sessionId, "Char.Name")) return
        val payload =
            CharNamePayload(
                name = player.name,
                race = player.race,
                playerClass = player.playerClass,
                level = player.level,
            )
        outbound.send(OutboundEvent.GmcpData(sessionId, "Char.Name", json.writeValueAsString(payload)))
    }

    suspend fun sendCommChannel(
        sessionId: SessionId,
        channel: String,
        sender: String,
        message: String,
    ) {
        if (!supportsPackage(sessionId, "Comm.Channel")) return
        val payload = CommChannelPayload(channel = channel, sender = sender, message = message)
        outbound.send(OutboundEvent.GmcpData(sessionId, "Comm.Channel", json.writeValueAsString(payload)))
    }

    suspend fun sendCharStatusEffects(
        sessionId: SessionId,
        effects: List<ActiveEffectSnapshot>,
    ) {
        if (!supportsPackage(sessionId, "Char.StatusEffects")) return
        val payload =
            effects.map { e ->
                CharStatusEffectPayload(
                    id = e.id,
                    name = e.name,
                    type = e.type,
                    remainingMs = e.remainingMs,
                    stacks = e.stacks,
                )
            }
        outbound.send(OutboundEvent.GmcpData(sessionId, "Char.StatusEffects", json.writeValueAsString(payload)))
    }

    suspend fun sendGroupInfo(
        sessionId: SessionId,
        leader: String?,
        members: List<PlayerState>,
    ) {
        if (!supportsPackage(sessionId, "Group.Info")) return
        val payload =
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
            )
        outbound.send(OutboundEvent.GmcpData(sessionId, "Group.Info", json.writeValueAsString(payload)))
    }

    suspend fun sendCorePing(sessionId: SessionId) {
        if (!supportsPackage(sessionId, "Core.Ping")) return
        outbound.send(OutboundEvent.GmcpData(sessionId, "Core.Ping", CORE_PING_JSON))
    }

    suspend fun sendCharAchievements(
        sessionId: SessionId,
        player: PlayerState,
        registry: AchievementRegistry,
    ) {
        if (!supportsPackage(sessionId, "Char.Achievements")) return
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
        val payload = CharAchievementsPayload(completed = completed, inProgress = inProgress)
        outbound.send(OutboundEvent.GmcpData(sessionId, "Char.Achievements", json.writeValueAsString(payload)))
    }

    // ---------- private helpers ----------

    private fun toItemPayload(item: ItemInstance) =
        ItemPayload(
            id = item.id.value,
            name = item.item.displayName,
            slot = item.item.slot?.label(),
            damage = item.item.damage,
            armor = item.item.armor,
        )

    private fun toRoomMobPayload(mob: MobState) =
        RoomMobPayload(
            id = mob.id.value,
            name = mob.name,
            hp = mob.hp,
            maxHp = mob.maxHp,
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

    private data class RoomInfoPayload(
        val id: String,
        val title: String,
        val description: String,
        val zone: String,
        val exits: Map<String, String>,
    )

    private data class ItemPayload(
        val id: String,
        val name: String,
        val slot: String?,
        val damage: Int,
        val armor: Int,
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
    )

    private data class RoomRemoveMobPayload(
        val id: String,
    )

    private data class RoomItemPayload(
        val id: String,
        val name: String,
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

    private companion object {
        const val CHAR_STATUS_VARS_JSON =
            """{"hp":"HP","maxHp":"Max HP","mana":"Mana","maxMana":"Max Mana","level":"Level","xp":"XP"}"""
        const val CORE_PING_JSON = "{}"
    }
}
