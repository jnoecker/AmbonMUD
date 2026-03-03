package dev.ambon.engine.behavior.actions

import dev.ambon.engine.behavior.BtContext
import dev.ambon.engine.behavior.BtNode
import dev.ambon.engine.behavior.BtResult
import dev.ambon.engine.behavior.moveMobWithNotify

data object WanderAction : BtNode {
    override suspend fun tick(ctx: BtContext): BtResult {
        val room = ctx.world.rooms[ctx.mob.roomId] ?: return BtResult.FAILURE
        val homeZone = ctx.mob.roomId.zone
        val exits = room.exits.values.filter { it.zone == homeZone }
        if (exits.isEmpty()) return BtResult.FAILURE

        val destination = exits[ctx.rng.nextInt(exits.size)]
        if (ctx.mob.roomId == destination) return BtResult.SUCCESS

        ctx.moveMobWithNotify(destination)

        return BtResult.SUCCESS
    }
}
