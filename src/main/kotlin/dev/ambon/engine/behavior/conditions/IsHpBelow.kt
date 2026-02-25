package dev.ambon.engine.behavior.conditions

import dev.ambon.engine.behavior.BtContext
import dev.ambon.engine.behavior.BtNode
import dev.ambon.engine.behavior.BtResult

data class IsHpBelow(
    val percent: Int,
) : BtNode {
    override suspend fun tick(ctx: BtContext): BtResult {
        val threshold = ctx.mob.maxHp * percent / 100
        return if (ctx.mob.hp <= threshold) BtResult.SUCCESS else BtResult.FAILURE
    }
}
