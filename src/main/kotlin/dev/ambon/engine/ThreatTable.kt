package dev.ambon.engine

import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.SessionId

class ThreatTable {
    private val tables = mutableMapOf<MobId, MutableMap<SessionId, Double>>()

    fun addThreat(
        mobId: MobId,
        sessionId: SessionId,
        amount: Double,
    ) {
        val table = tables.getOrPut(mobId) { mutableMapOf() }
        table[sessionId] = (table[sessionId] ?: 0.0) + amount
    }

    fun setThreat(
        mobId: MobId,
        sessionId: SessionId,
        amount: Double,
    ) {
        val table = tables.getOrPut(mobId) { mutableMapOf() }
        table[sessionId] = amount
    }

    fun getThreat(
        mobId: MobId,
        sessionId: SessionId,
    ): Double = tables[mobId]?.get(sessionId) ?: 0.0

    fun topThreat(mobId: MobId): SessionId? =
        tables[mobId]
            ?.maxByOrNull { it.value }
            ?.key

    fun topThreatInRoom(
        mobId: MobId,
        isInRoom: (SessionId) -> Boolean,
    ): SessionId? =
        tables[mobId]
            ?.entries
            ?.maxByOrNull { if (isInRoom(it.key)) it.value else Double.NEGATIVE_INFINITY }
            ?.takeIf { isInRoom(it.key) }
            ?.key

    fun getThreats(mobId: MobId): Map<SessionId, Double> = tables[mobId]?.toMap() ?: emptyMap()

    fun maxThreatValue(mobId: MobId): Double =
        tables[mobId]
            ?.values
            ?.maxOrNull() ?: 0.0

    fun removeMob(mobId: MobId) {
        tables.remove(mobId)
    }

    fun removePlayer(sessionId: SessionId) {
        val emptyMobs = mutableListOf<MobId>()
        for ((mobId, table) in tables) {
            table.remove(sessionId)
            if (table.isEmpty()) emptyMobs.add(mobId)
        }
        for (mobId in emptyMobs) {
            tables.remove(mobId)
        }
    }

    fun remapSession(
        oldSid: SessionId,
        newSid: SessionId,
    ) {
        for ((_, table) in tables) {
            val threat = table.remove(oldSid) ?: continue
            table[newSid] = (table[newSid] ?: 0.0) + threat
        }
    }

    fun hasThreat(
        mobId: MobId,
        sessionId: SessionId,
    ): Boolean = tables[mobId]?.containsKey(sessionId) == true

    fun mobsThreatenedBy(sessionId: SessionId): Set<MobId> =
        tables
            .filter { (_, table) -> table.containsKey(sessionId) }
            .keys
            .toSet()

    fun playersThreateningMob(mobId: MobId): Set<SessionId> =
        tables[mobId]
            ?.keys
            ?.toSet() ?: emptySet()

    fun hasMobEntry(mobId: MobId): Boolean = tables.containsKey(mobId)
}
