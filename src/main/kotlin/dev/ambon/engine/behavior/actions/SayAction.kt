package dev.ambon.engine.behavior.actions

import dev.ambon.engine.behavior.BtContext
import dev.ambon.engine.behavior.BtNode
import dev.ambon.engine.behavior.BtResult
import dev.ambon.engine.events.OutboundEvent

data class SayAction(
    val message: String,
) : BtNode {
    override suspend fun tick(ctx: BtContext): BtResult {
        for (p in ctx.players.playersInRoom(ctx.mob.roomId)) {
            ctx.outbound.send(
                OutboundEvent.SendText(p.sessionId, "${ctx.mob.name} says, \"$message\""),
            )
        }
        return BtResult.SUCCESS
    }
}
