package dev.ambon.engine

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.items.ItemSlot
import dev.ambon.domain.mob.MobState
import dev.ambon.domain.world.Room
import dev.ambon.engine.abilities.AbilityDefinition
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.status.ActiveEffectSnapshot

class GmcpEmitter(
    private val outbound: OutboundBus,
    private val supportsPackage: (SessionId, String) -> Boolean,
    private val progression: PlayerProgression? = null,
) {
    suspend fun sendCharVitals(
        sessionId: SessionId,
        player: PlayerState,
    ) {
        if (!supportsPackage(sessionId, "Char.Vitals")) return
        val xpInto = progression?.xpIntoLevel(player.xpTotal) ?: 0L
        val xpNeeded = progression?.xpToNextLevel(player.xpTotal)
        val xpNeededJson = if (xpNeeded != null) "$xpNeeded" else "null"
        val json =
            """{"hp":${player.hp},"maxHp":${player.maxHp},"mana":${player.mana},"maxMana":${player.maxMana},"level":${player.level},"xp":${player.xpTotal},"xpIntoLevel":$xpInto,"xpToNextLevel":$xpNeededJson,"gold":${player.gold}}"""
        outbound.send(OutboundEvent.GmcpData(sessionId, "Char.Vitals", json))
    }

    suspend fun sendRoomInfo(
        sessionId: SessionId,
        room: Room,
    ) {
        if (!supportsPackage(sessionId, "Room.Info")) return
        val exitsJson =
            room.exits.entries.joinToString(",") { (dir, roomId) ->
                """"${dir.name.lowercase()}":"${roomId.value.jsonEscape()}""""
            }
        val json =
            """{"id":"${room.id.value.jsonEscape()}","title":"${room.title.jsonEscape()}","description":"${room.description.jsonEscape()}","zone":"${room.id.zone.jsonEscape()}","exits":{$exitsJson}}"""
        outbound.send(OutboundEvent.GmcpData(sessionId, "Room.Info", json))
    }

    suspend fun sendCharStatusVars(sessionId: SessionId) {
        if (!supportsPackage(sessionId, "Char.StatusVars")) return
        val json = """{"hp":"HP","maxHp":"Max HP","mana":"Mana","maxMana":"Max Mana","level":"Level","xp":"XP"}"""
        outbound.send(OutboundEvent.GmcpData(sessionId, "Char.StatusVars", json))
    }

    suspend fun sendCharItemsList(
        sessionId: SessionId,
        inventory: List<ItemInstance>,
        equipment: Map<ItemSlot, ItemInstance>,
    ) {
        if (!supportsPackage(sessionId, "Char.Items.List")) return
        val invJson = inventory.joinToString(",") { itemToJson(it) }
        val eqJson =
            ItemSlot.entries.joinToString(",") { slot ->
                val item = equipment[slot]
                """"${slot.label()}":${if (item != null) itemToJson(item) else "null"}"""
            }
        val json = """{"inventory":[$invJson],"equipment":{$eqJson}}"""
        outbound.send(OutboundEvent.GmcpData(sessionId, "Char.Items.List", json))
    }

    suspend fun sendCharItemsAdd(
        sessionId: SessionId,
        item: ItemInstance,
    ) {
        if (!supportsPackage(sessionId, "Char.Items.Add")) return
        outbound.send(OutboundEvent.GmcpData(sessionId, "Char.Items.Add", itemToJson(item)))
    }

    suspend fun sendCharItemsRemove(
        sessionId: SessionId,
        item: ItemInstance,
    ) {
        if (!supportsPackage(sessionId, "Char.Items.Remove")) return
        val json = """{"id":"${item.id.value.jsonEscape()}","name":"${item.item.displayName.jsonEscape()}"}"""
        outbound.send(OutboundEvent.GmcpData(sessionId, "Char.Items.Remove", json))
    }

    suspend fun sendRoomPlayers(
        sessionId: SessionId,
        players: List<PlayerState>,
    ) {
        if (!supportsPackage(sessionId, "Room.Players")) return
        val json =
            players
                .filter { it.sessionId != sessionId }
                .joinToString(",", prefix = "[", postfix = "]") { p ->
                    """{"name":"${p.name.jsonEscape()}","level":${p.level}}"""
                }
        outbound.send(OutboundEvent.GmcpData(sessionId, "Room.Players", json))
    }

    suspend fun sendRoomAddPlayer(
        sessionId: SessionId,
        player: PlayerState,
    ) {
        if (!supportsPackage(sessionId, "Room.Players")) return
        val json = """{"name":"${player.name.jsonEscape()}","level":${player.level}}"""
        outbound.send(OutboundEvent.GmcpData(sessionId, "Room.AddPlayer", json))
    }

    suspend fun sendRoomRemovePlayer(
        sessionId: SessionId,
        name: String,
    ) {
        if (!supportsPackage(sessionId, "Room.Players")) return
        val json = """{"name":"${name.jsonEscape()}"}"""
        outbound.send(OutboundEvent.GmcpData(sessionId, "Room.RemovePlayer", json))
    }

    suspend fun sendRoomMobs(
        sessionId: SessionId,
        mobs: List<MobState>,
    ) {
        if (!supportsPackage(sessionId, "Room.Mobs")) return
        val json =
            mobs.joinToString(",", prefix = "[", postfix = "]") { m ->
                """{"id":"${m.id.value.jsonEscape()}","name":"${m.name.jsonEscape()}","hp":${m.hp},"maxHp":${m.maxHp}}"""
            }
        outbound.send(OutboundEvent.GmcpData(sessionId, "Room.Mobs", json))
    }

    suspend fun sendRoomAddMob(
        sessionId: SessionId,
        mob: MobState,
    ) {
        if (!supportsPackage(sessionId, "Room.Mobs")) return
        val json =
            """{"id":"${mob.id.value.jsonEscape()}","name":"${mob.name.jsonEscape()}","hp":${mob.hp},"maxHp":${mob.maxHp}}"""
        outbound.send(OutboundEvent.GmcpData(sessionId, "Room.AddMob", json))
    }

    suspend fun sendRoomUpdateMob(
        sessionId: SessionId,
        mob: MobState,
    ) {
        if (!supportsPackage(sessionId, "Room.Mobs")) return
        val json =
            """{"id":"${mob.id.value.jsonEscape()}","name":"${mob.name.jsonEscape()}","hp":${mob.hp},"maxHp":${mob.maxHp}}"""
        outbound.send(OutboundEvent.GmcpData(sessionId, "Room.UpdateMob", json))
    }

    suspend fun sendRoomRemoveMob(
        sessionId: SessionId,
        mobId: String,
    ) {
        if (!supportsPackage(sessionId, "Room.Mobs")) return
        val json = """{"id":"${mobId.jsonEscape()}"}"""
        outbound.send(OutboundEvent.GmcpData(sessionId, "Room.RemoveMob", json))
    }

    suspend fun sendCharSkills(
        sessionId: SessionId,
        abilities: List<AbilityDefinition>,
    ) {
        if (!supportsPackage(sessionId, "Char.Skills")) return
        val json =
            abilities.joinToString(",", prefix = "[", postfix = "]") { a ->
                """{"id":"${a.id.value.jsonEscape()}","name":"${a.displayName.jsonEscape()}","manaCost":${a.manaCost},"cooldownMs":${a.cooldownMs}}"""
            }
        outbound.send(OutboundEvent.GmcpData(sessionId, "Char.Skills", json))
    }

    suspend fun sendCharName(
        sessionId: SessionId,
        player: PlayerState,
    ) {
        if (!supportsPackage(sessionId, "Char.Name")) return
        val json =
            """{"name":"${player.name.jsonEscape()}","race":"${player.race.jsonEscape()}","class":"${player.playerClass.jsonEscape()}","level":${player.level}}"""
        outbound.send(OutboundEvent.GmcpData(sessionId, "Char.Name", json))
    }

    suspend fun sendCommChannel(
        sessionId: SessionId,
        channel: String,
        sender: String,
        message: String,
    ) {
        if (!supportsPackage(sessionId, "Comm.Channel")) return
        val json =
            """{"channel":"${channel.jsonEscape()}","sender":"${sender.jsonEscape()}","message":"${message.jsonEscape()}"}"""
        outbound.send(OutboundEvent.GmcpData(sessionId, "Comm.Channel", json))
    }

    suspend fun sendCharStatusEffects(
        sessionId: SessionId,
        effects: List<ActiveEffectSnapshot>,
    ) {
        if (!supportsPackage(sessionId, "Char.StatusEffects")) return
        val json =
            effects.joinToString(",", prefix = "[", postfix = "]") { e ->
                """{"id":"${e.id.jsonEscape()}","name":"${e.name.jsonEscape()}","type":"${e.type.jsonEscape()}","remainingMs":${e.remainingMs},"stacks":${e.stacks}}"""
            }
        outbound.send(OutboundEvent.GmcpData(sessionId, "Char.StatusEffects", json))
    }

    suspend fun sendCorePing(sessionId: SessionId) {
        if (!supportsPackage(sessionId, "Core.Ping")) return
        outbound.send(OutboundEvent.GmcpData(sessionId, "Core.Ping", "{}"))
    }

    suspend fun sendCharAchievements(
        sessionId: SessionId,
        player: PlayerState,
        registry: AchievementRegistry,
    ) {
        if (!supportsPackage(sessionId, "Char.Achievements")) return
        val completedJson =
            player.unlockedAchievementIds
                .joinToString(",", prefix = "[", postfix = "]") { id ->
                    val def = registry.get(id)
                    val name = def?.displayName?.jsonEscape() ?: id.jsonEscape()
                    val titleJson =
                        def?.rewards?.title?.let { "\"${it.jsonEscape()}\"" } ?: "null"
                    """{"id":"${id.jsonEscape()}","name":"$name","title":$titleJson}"""
                }
        val inProgressJson =
            player.achievementProgress.entries
                .filter { (id, _) -> registry.get(id)?.hidden != true }
                .joinToString(",", prefix = "[", postfix = "]") { (id, state) ->
                    val def = registry.get(id)
                    val name = def?.displayName?.jsonEscape() ?: id.jsonEscape()
                    val totalCurrent = state.progress.sumOf { it.current }
                    val totalRequired = state.progress.sumOf { it.required }
                    """{"id":"${id.jsonEscape()}","name":"$name","current":$totalCurrent,"required":$totalRequired}"""
                }
        val json = """{"completed":$completedJson,"inProgress":$inProgressJson}"""
        outbound.send(OutboundEvent.GmcpData(sessionId, "Char.Achievements", json))
    }

    private fun itemToJson(item: ItemInstance): String {
        val slot =
            item.item.slot
                ?.label()
                ?.let { "\"${it.jsonEscape()}\"" }
                ?: "null"
        val id = item.id.value.jsonEscape()
        val name = item.item.displayName.jsonEscape()
        return """{"id":"$id","name":"$name","slot":$slot,"damage":${item.item.damage},"armor":${item.item.armor}}"""
    }

    private fun String.jsonEscape(): String =
        this
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
}
