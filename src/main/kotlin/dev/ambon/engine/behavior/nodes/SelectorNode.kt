package dev.ambon.engine.behavior.nodes

import dev.ambon.engine.behavior.BtContext
import dev.ambon.engine.behavior.BtNode
import dev.ambon.engine.behavior.BtResult

data class SelectorNode(
    val children: List<BtNode>,
) : BtNode {
    override suspend fun tick(ctx: BtContext): BtResult {
        for (child in children) {
            val result = child.tick(ctx)
            if (result != BtResult.FAILURE) return result
        }
        return BtResult.FAILURE
    }
}
