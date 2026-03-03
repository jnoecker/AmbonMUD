package dev.ambon.engine

import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.mob.MobState

class MobRegistry {
    private val mobs = mutableMapOf<MobId, MobState>()
    private val roomMembers = mutableMapOf<RoomId, MutableSet<MobId>>()

    fun upsert(mob: MobState) {
        val existing = mobs[mob.id]
        if (existing == null) {
            mobs[mob.id] = mob
            roomMembers.getOrPut(mob.roomId) { mutableSetOf() }.add(mob.id)
        } else {
            // update fields but preserve membership maps properly if room changes
            if (existing.roomId != mob.roomId) {
                roomMembers.removeFromSet(existing.roomId, mob.id)
                roomMembers.getOrPut(mob.roomId) { mutableSetOf() }.add(mob.id)
            }
            existing.name = mob.name
            existing.roomId = mob.roomId
            existing.hp = mob.hp
            existing.maxHp = mob.maxHp
        }
    }

    fun all(): List<MobState> = mobs.values.toList()

    fun get(mobId: MobId): MobState? = mobs[mobId]

    fun mobsInRoom(roomId: RoomId): List<MobState> = roomMembers[roomId]?.mapNotNull { mobs[it] } ?: emptyList()

    /**
     * Returns mobs in [roomId] whose name contains [keyword] (case-insensitive),
     * sorted alphabetically by name.
     */
    fun findInRoomByKeyword(
        roomId: RoomId,
        keyword: String,
    ): List<MobState> {
        val lower = keyword.lowercase()
        return mobsInRoom(roomId)
            .filter { it.name.lowercase().contains(lower) }
            .sortedBy { it.name }
    }

    fun moveTo(
        mobId: MobId,
        newRoom: RoomId,
    ) {
        val m = mobs[mobId] ?: return
        if (m.roomId == newRoom) return
        roomMembers.removeFromSet(m.roomId, mobId)
        m.roomId = newRoom
        roomMembers.getOrPut(newRoom) { mutableSetOf() }.add(mobId)
    }

    fun remove(mobId: MobId): MobState? {
        val m = mobs.remove(mobId) ?: return null
        roomMembers.removeFromSet(m.roomId, mobId)
        return m
    }
}
