package dev.ambon.engine.behavior.nodes

import dev.ambon.engine.behavior.BtContext
import dev.ambon.engine.behavior.BtNode
import dev.ambon.engine.behavior.BtResult

data class SequenceNode(
    val children: List<BtNode>,
) : BtNode {
    override suspend fun tick(ctx: BtContext): BtResult {
        for (child in children) {
            val result = child.tick(ctx)
            if (result != BtResult.SUCCESS) return result
        }
        return BtResult.SUCCESS
    }
}
