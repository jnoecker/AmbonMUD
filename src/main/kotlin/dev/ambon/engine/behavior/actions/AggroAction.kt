package dev.ambon.engine.behavior.actions

import dev.ambon.engine.behavior.BtContext
import dev.ambon.engine.behavior.BtNode
import dev.ambon.engine.behavior.BtResult

data object AggroAction : BtNode {
    override suspend fun tick(ctx: BtContext): BtResult {
        val playersInRoom = ctx.players.playersInRoom(ctx.mob.roomId)
        for (p in playersInRoom) {
            val started = ctx.startMobCombat(ctx.mob.id, p.sessionId)
            if (started) return BtResult.SUCCESS
        }
        return BtResult.FAILURE
    }
}
