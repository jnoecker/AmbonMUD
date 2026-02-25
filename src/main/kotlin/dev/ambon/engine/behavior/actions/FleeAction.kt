package dev.ambon.engine.behavior.actions

import dev.ambon.engine.behavior.BtContext
import dev.ambon.engine.behavior.BtNode
import dev.ambon.engine.behavior.BtResult
import dev.ambon.engine.events.OutboundEvent

data object FleeAction : BtNode {
    override suspend fun tick(ctx: BtContext): BtResult {
        val room = ctx.world.rooms[ctx.mob.roomId] ?: return BtResult.FAILURE
        val exits = room.exits.values.toList()
        if (exits.isEmpty()) return BtResult.FAILURE

        // Disengage from combat first
        ctx.fleeMob(ctx.mob.id)

        // Pick a random exit and move
        val destination = exits[ctx.rng.nextInt(exits.size)]
        val from = ctx.mob.roomId

        // Notify players in old room
        for (p in ctx.players.playersInRoom(from)) {
            ctx.outbound.send(OutboundEvent.SendText(p.sessionId, "${ctx.mob.name} flees!"))
            ctx.gmcpEmitter?.sendRoomRemoveMob(p.sessionId, ctx.mob.id.value)
        }

        ctx.mobs.moveTo(ctx.mob.id, destination)

        // Notify players in new room
        for (p in ctx.players.playersInRoom(destination)) {
            ctx.outbound.send(OutboundEvent.SendText(p.sessionId, "${ctx.mob.name} arrives in a panic."))
            ctx.gmcpEmitter?.sendRoomAddMob(p.sessionId, ctx.mob)
        }

        return BtResult.SUCCESS
    }
}
