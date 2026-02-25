package dev.ambon.engine.behavior.actions

import dev.ambon.engine.behavior.BtContext
import dev.ambon.engine.behavior.BtNode
import dev.ambon.engine.behavior.BtResult

data object StationaryAction : BtNode {
    override suspend fun tick(ctx: BtContext): BtResult = BtResult.SUCCESS
}
