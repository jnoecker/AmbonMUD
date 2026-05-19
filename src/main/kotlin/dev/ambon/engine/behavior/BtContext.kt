package dev.ambon.engine.behavior

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.World
import dev.ambon.domain.world.arrivePhrase
import dev.ambon.domain.world.exitPhrase
import dev.ambon.domain.world.opposite
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
 *
 * When [direction] is non-null the depart/arrive broadcasts become
 *   `"${mob.name} ${departVerb} to the north."` / `"${mob.name} arrives from the south."`
 * and [departMsg]/[arriveMsg] are ignored.
 */
suspend fun BtContext.moveMobWithNotify(
    destination: RoomId,
    departMsg: String = "${mob.name} leaves.",
    arriveMsg: String = "${mob.name} enters.",
    direction: Direction? = null,
    departVerb: String = "exits",
) {
    val from = mob.roomId
    val departLine = if (direction != null) {
        "${mob.name} $departVerb ${direction.exitPhrase()}."
    } else {
        departMsg
    }
    val arriveLine = if (direction != null) {
        "${mob.name} arrives ${direction.opposite().arrivePhrase()}."
    } else {
        arriveMsg
    }
    for (p in players.playersInRoom(from)) {
        outbound.send(OutboundEvent.SendText(p.sessionId, departLine))
    }
    gmcpEmitter?.broadcastRoomRemoveMob(from, mob.id.value, players)
    mobs.moveTo(mob.id, destination)
    for (p in players.playersInRoom(destination)) {
        outbound.send(OutboundEvent.SendText(p.sessionId, arriveLine))
    }
    gmcpEmitter?.broadcastRoomAddMob(destination, mob, players)
}
