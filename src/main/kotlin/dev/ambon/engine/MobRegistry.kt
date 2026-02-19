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
                roomMembers[existing.roomId]?.remove(mob.id)
                if (roomMembers[existing.roomId]?.isEmpty() == true) roomMembers.remove(existing.roomId)
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

    fun moveTo(
        mobId: MobId,
        newRoom: RoomId,
    ) {
        val m = mobs[mobId] ?: return
        if (m.roomId == newRoom) return
        roomMembers[m.roomId]?.remove(mobId)
        if (roomMembers[m.roomId]?.isEmpty() == true) roomMembers.remove(m.roomId)
        m.roomId = newRoom
        roomMembers.getOrPut(newRoom) { mutableSetOf() }.add(mobId)
    }

    fun remove(mobId: MobId): MobState? {
        val m = mobs.remove(mobId) ?: return null
        roomMembers[m.roomId]?.remove(mobId)
        if (roomMembers[m.roomId]?.isEmpty() == true) roomMembers.remove(m.roomId)
        return m
    }
}
