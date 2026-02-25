package dev.ambon.engine.behavior.nodes

import dev.ambon.engine.behavior.BtContext
import dev.ambon.engine.behavior.BtNode
import dev.ambon.engine.behavior.BtResult

data class InverterNode(
    val child: BtNode,
) : BtNode {
    override suspend fun tick(ctx: BtContext): BtResult =
        when (child.tick(ctx)) {
            BtResult.SUCCESS -> BtResult.FAILURE
            BtResult.FAILURE -> BtResult.SUCCESS
            BtResult.RUNNING -> BtResult.RUNNING
        }
}
