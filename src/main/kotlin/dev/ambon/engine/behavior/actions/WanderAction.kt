package dev.ambon.engine.behavior.actions

import dev.ambon.engine.behavior.BtContext
import dev.ambon.engine.behavior.BtNode
import dev.ambon.engine.behavior.BtResult
import dev.ambon.engine.behavior.moveMobWithNotify

data class WanderAction(
    val maxDistance: Int = 3,
) : BtNode {
    override suspend fun tick(ctx: BtContext): BtResult {
        val room = ctx.world.rooms[ctx.mob.roomId] ?: return BtResult.FAILURE
        val homeZone = ctx.mob.roomId.zone
        val distanceMap = ctx.mob.spawnDistanceMap

        val exits = room.exits.values.filter { dest ->
            dest.zone == homeZone &&
                (distanceMap.isEmpty() || (distanceMap[dest] ?: Int.MAX_VALUE) <= maxDistance)
        }
        if (exits.isEmpty()) return BtResult.FAILURE

        val destination = exits[ctx.rng.nextInt(exits.size)]
        if (ctx.mob.roomId == destination) return BtResult.SUCCESS

        ctx.moveMobWithNotify(destination)

        return BtResult.SUCCESS
    }
}
