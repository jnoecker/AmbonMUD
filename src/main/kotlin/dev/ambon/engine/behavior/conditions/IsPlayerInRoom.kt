package dev.ambon.engine.behavior.conditions

import dev.ambon.engine.behavior.BtContext
import dev.ambon.engine.behavior.BtNode
import dev.ambon.engine.behavior.BtResult

data object IsPlayerInRoom : BtNode {
    override suspend fun tick(ctx: BtContext): BtResult =
        if (ctx.players.playersInRoom(ctx.mob.roomId).isNotEmpty()) {
            BtResult.SUCCESS
        } else {
            BtResult.FAILURE
        }
}
