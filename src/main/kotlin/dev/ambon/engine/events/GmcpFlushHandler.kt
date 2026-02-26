package dev.ambon.engine.events

import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.engine.GmcpEmitter
import dev.ambon.engine.GroupSystem
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.status.StatusEffectSystem
import dev.ambon.metrics.GameMetrics

class GmcpFlushHandler(
    private val gmcpDirtyVitals: MutableSet<SessionId>,
    private val gmcpDirtyStatusEffects: MutableSet<SessionId>,
    private val gmcpDirtyMobs: MutableSet<MobId>,
    private val gmcpDirtyGroup: MutableSet<SessionId>,
    private val players: PlayerRegistry,
    private val mobs: MobRegistry,
    private val statusEffectSystem: StatusEffectSystem,
    private val groupSystem: GroupSystem,
    private val gmcpEmitter: GmcpEmitter,
    private val metrics: GameMetrics = GameMetrics.noop(),
) {
    suspend fun flushDirtyVitals() {
        metrics.onGmcpFlushHandlerEvent()
        drainDirty(gmcpDirtyVitals) { sid ->
            val player = players.get(sid) ?: return@drainDirty
            gmcpEmitter.sendCharVitals(sid, player)
        }
    }

    suspend fun flushDirtyStatusEffects() {
        drainDirty(gmcpDirtyStatusEffects) { sid ->
            val effects = statusEffectSystem.activePlayerEffects(sid)
            gmcpEmitter.sendCharStatusEffects(sid, effects)
        }
    }

    suspend fun flushDirtyMobs() {
        if (gmcpDirtyMobs.isEmpty()) return
        val mobsByRoom = mutableMapOf<RoomId, MutableList<MobState>>()
        drainDirty(gmcpDirtyMobs) { mobId ->
            val mob = mobs.get(mobId) ?: return@drainDirty
            mobsByRoom.getOrPut(mob.roomId) { mutableListOf() }.add(mob)
        }
        for ((roomId, roomMobs) in mobsByRoom) {
            for (p in players.playersInRoom(roomId)) {
                for (mob in roomMobs) {
                    gmcpEmitter.sendRoomUpdateMob(p.sessionId, mob)
                }
            }
        }
    }

    suspend fun flushDirtyGroup() {
        drainDirty(gmcpDirtyGroup) { sid ->
            val group = groupSystem.getGroup(sid)
            if (group != null) {
                val leader = players.get(group.leader)?.name
                val members = group.members.mapNotNull { players.get(it) }
                gmcpEmitter.sendGroupInfo(sid, leader, members)
            } else {
                gmcpEmitter.sendGroupInfo(sid, null, emptyList())
            }
        }
    }

    private inline fun <T> drainDirty(set: MutableSet<T>, action: (T) -> Unit) {
        if (set.isEmpty()) return
        for (item in set) {
            action(item)
        }
        set.clear()
    }
}
