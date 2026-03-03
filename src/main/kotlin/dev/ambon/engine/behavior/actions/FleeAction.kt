package dev.ambon.engine.behavior.actions

import dev.ambon.engine.behavior.BtContext
import dev.ambon.engine.behavior.BtNode
import dev.ambon.engine.behavior.BtResult
import dev.ambon.engine.behavior.moveMobWithNotify

data object FleeAction : BtNode {
    override suspend fun tick(ctx: BtContext): BtResult {
        val room = ctx.world.rooms[ctx.mob.roomId] ?: return BtResult.FAILURE
        val exits = room.exits.values.toList()
        if (exits.isEmpty()) return BtResult.FAILURE

        // Disengage from combat first
        ctx.fleeMob(ctx.mob.id)

        // Pick a random exit and move
        val destination = exits[ctx.rng.nextInt(exits.size)]

        ctx.moveMobWithNotify(
            destination,
            departMsg = "${ctx.mob.name} flees!",
            arriveMsg = "${ctx.mob.name} arrives in a panic.",
        )

        return BtResult.SUCCESS
    }
}
