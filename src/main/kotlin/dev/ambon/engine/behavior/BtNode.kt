package dev.ambon.engine.behavior

interface BtNode {
    suspend fun tick(ctx: BtContext): BtResult
}
