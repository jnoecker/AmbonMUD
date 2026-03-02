package dev.ambon.engine.behavior.actions

import dev.ambon.engine.behavior.BtContext
import dev.ambon.engine.behavior.BtNode
import dev.ambon.engine.behavior.BtResult
import dev.ambon.engine.events.OutboundEvent

data object WanderAction : BtNode {
    override suspend fun tick(ctx: BtContext): BtResult {
        val room = ctx.world.rooms[ctx.mob.roomId] ?: return BtResult.FAILURE
        val homeZone = ctx.mob.roomId.zone
        val exits = room.exits.values.filter { it.zone == homeZone }
        if (exits.isEmpty()) return BtResult.FAILURE

        val destination = exits[ctx.rng.nextInt(exits.size)]
        val from = ctx.mob.roomId
        if (from == destination) return BtResult.SUCCESS

        // Notify players in old room
        for (p in ctx.players.playersInRoom(from)) {
            ctx.outbound.send(OutboundEvent.SendText(p.sessionId, "${ctx.mob.name} leaves."))
            ctx.gmcpEmitter?.sendRoomRemoveMob(p.sessionId, ctx.mob.id.value)
        }

        ctx.mobs.moveTo(ctx.mob.id, destination)

        // Notify players in new room
        for (p in ctx.players.playersInRoom(destination)) {
            ctx.outbound.send(OutboundEvent.SendText(p.sessionId, "${ctx.mob.name} enters."))
            ctx.gmcpEmitter?.sendRoomAddMob(p.sessionId, ctx.mob)
        }

        return BtResult.SUCCESS
    }
}
