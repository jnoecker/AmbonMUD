package dev.ambon.engine.behavior.actions

import dev.ambon.engine.behavior.BtContext
import dev.ambon.engine.behavior.BtNode
import dev.ambon.engine.behavior.BtResult
import dev.ambon.engine.behavior.moveMobWithNotify

data object FleeAction : BtNode {
    override suspend fun tick(ctx: BtContext): BtResult {
        val room = ctx.world.rooms[ctx.mob.roomId] ?: return BtResult.FAILURE
        val exitsList = room.exits.entries.toList()
        if (exitsList.isEmpty()) return BtResult.FAILURE

        // Disengage from combat first
        ctx.fleeMob(ctx.mob.id)

        // Pick a random exit and move
        val pick = exitsList[ctx.rng.nextInt(exitsList.size)]

        ctx.moveMobWithNotify(
            pick.value,
            direction = pick.key,
            departVerb = "flees",
        )

        return BtResult.SUCCESS
    }
}
