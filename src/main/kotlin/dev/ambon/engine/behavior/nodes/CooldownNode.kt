package dev.ambon.engine.behavior.nodes

import dev.ambon.engine.behavior.BtContext
import dev.ambon.engine.behavior.BtNode
import dev.ambon.engine.behavior.BtResult

data class CooldownNode(
    val cooldownMs: Long,
    val key: String,
    val child: BtNode,
) : BtNode {
    override suspend fun tick(ctx: BtContext): BtResult {
        val now = ctx.clock.millis()
        val lastFired = ctx.mobMemory.cooldownTimestamps[key]
        if (lastFired != null && now - lastFired < cooldownMs) return BtResult.FAILURE
        val result = child.tick(ctx)
        if (result == BtResult.SUCCESS) {
            ctx.mobMemory.cooldownTimestamps[key] = now
        }
        return result
    }
}
