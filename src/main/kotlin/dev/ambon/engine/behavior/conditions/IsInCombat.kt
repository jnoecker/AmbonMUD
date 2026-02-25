package dev.ambon.engine.behavior.conditions

import dev.ambon.engine.behavior.BtContext
import dev.ambon.engine.behavior.BtNode
import dev.ambon.engine.behavior.BtResult

data object IsInCombat : BtNode {
    override suspend fun tick(ctx: BtContext): BtResult =
        if (ctx.isMobInCombat(ctx.mob.id)) {
            BtResult.SUCCESS
        } else {
            BtResult.FAILURE
        }
}
