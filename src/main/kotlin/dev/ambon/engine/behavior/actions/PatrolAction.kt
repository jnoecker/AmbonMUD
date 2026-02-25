package dev.ambon.engine.behavior.actions

import dev.ambon.domain.ids.RoomId
import dev.ambon.engine.behavior.BtContext
import dev.ambon.engine.behavior.BtNode
import dev.ambon.engine.behavior.BtResult
import dev.ambon.engine.events.OutboundEvent

data class PatrolAction(
    val route: List<RoomId>,
) : BtNode {
    override suspend fun tick(ctx: BtContext): BtResult {
        if (route.isEmpty()) return BtResult.FAILURE

        val memory = ctx.mobMemory
        val nextWaypoint = route[memory.patrolIndex % route.size]
        val from = ctx.mob.roomId

        if (from == nextWaypoint) {
            // Already at waypoint, advance to next
            memory.patrolIndex = (memory.patrolIndex + 1) % route.size
            return BtResult.SUCCESS
        }

        // Move toward the next waypoint (direct move — assumes route is connected)
        for (p in ctx.players.playersInRoom(from)) {
            ctx.outbound.send(OutboundEvent.SendText(p.sessionId, "${ctx.mob.name} leaves."))
            ctx.gmcpEmitter?.sendRoomRemoveMob(p.sessionId, ctx.mob.id.value)
        }

        ctx.mobs.moveTo(ctx.mob.id, nextWaypoint)

        for (p in ctx.players.playersInRoom(nextWaypoint)) {
            ctx.outbound.send(OutboundEvent.SendText(p.sessionId, "${ctx.mob.name} enters."))
            ctx.gmcpEmitter?.sendRoomAddMob(p.sessionId, ctx.mob)
        }

        // Advance to the next waypoint for the following tick
        memory.patrolIndex = (memory.patrolIndex + 1) % route.size

        return BtResult.SUCCESS
    }
}
