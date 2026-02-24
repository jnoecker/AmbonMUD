package dev.ambon.engine

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.Room
import dev.ambon.engine.events.OutboundEvent

class GmcpEmitter(
    private val outbound: OutboundBus,
    private val supportsPackage: (SessionId, String) -> Boolean,
) {
    suspend fun sendCharVitals(
        sessionId: SessionId,
        player: PlayerState,
    ) {
        if (!supportsPackage(sessionId, "Char.Vitals")) return
        val json =
            """{"hp":${player.hp},"maxHp":${player.maxHp},"mana":${player.mana},"maxMana":${player.maxMana},"level":${player.level},"xp":${player.xpTotal}}"""
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

    private fun String.jsonEscape(): String =
        this
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
}
