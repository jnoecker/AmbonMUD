package dev.ambon.engine.behavior

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.domain.world.World
import dev.ambon.engine.GmcpEmitter
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.events.OutboundEvent
import java.time.Clock
import java.util.Random

class BtContext(
    val mob: MobState,
    val world: World,
    val mobs: MobRegistry,
    val players: PlayerRegistry,
    val outbound: OutboundBus,
    val clock: Clock,
    val rng: Random,
    val isMobInCombat: (MobId) -> Boolean,
    val startMobCombat: suspend (MobId, SessionId) -> Boolean,
    val fleeMob: suspend (MobId) -> Boolean,
    val gmcpEmitter: GmcpEmitter?,
    val mobMemory: MobBehaviorMemory,
)

/**
 * Notifies old-room players of departure, moves the mob, then notifies new-room players of arrival.
 * Emits GMCP room-mob add/remove events.
 */
suspend fun BtContext.moveMobWithNotify(
    destination: RoomId,
    departMsg: String = "${mob.name} leaves.",
    arriveMsg: String = "${mob.name} enters.",
) {
    val from = mob.roomId
    for (p in players.playersInRoom(from)) {
        outbound.send(OutboundEvent.SendText(p.sessionId, departMsg))
    }
    gmcpEmitter?.broadcastRoomRemoveMob(from, mob.id.value, players)
    mobs.moveTo(mob.id, destination)
    for (p in players.playersInRoom(destination)) {
        outbound.send(OutboundEvent.SendText(p.sessionId, arriveMsg))
    }
    gmcpEmitter?.broadcastRoomAddMob(destination, mob, players)
}
