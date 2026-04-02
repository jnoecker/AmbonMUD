package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.LeaderboardSystem
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent

class LeaderboardHandler(
    ctx: EngineContext,
) : CommandHandler {
    private val outbound = ctx.outbound
    private val gmcpEmitter = ctx.gmcpEmitter
    private val leaderboardSystem = ctx.leaderboardSystem

    override fun register(router: CommandRouter) {
        router.on<Command.Leaderboard> { sid, cmd -> handleLeaderboard(sid, cmd) }
    }

    private suspend fun handleLeaderboard(sessionId: SessionId, cmd: Command.Leaderboard) {
        val sys = leaderboardSystem
        if (sys == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "Leaderboards are not available."))
            return
        }

        val category = resolveCategory(cmd.category)
        if (category == null) {
            val categories = LeaderboardSystem.Category.entries.joinToString(", ") { it.key }
            outbound.send(
                OutboundEvent.SendError(
                    sessionId,
                    "Unknown category '${ cmd.category }'. Available: $categories",
                ),
            )
            return
        }

        val entries = sys.getEntries(category)

        // Send via GMCP for web clients
        gmcpEmitter?.sendLeaderboard(sessionId, category, entries)

        val sb = StringBuilder()
        sb.appendLine("=== ${category.displayName} ===")
        if (entries.isEmpty()) {
            sb.append("The leaderboard is empty. Be the first to make your mark!")
        } else {
            for (e in entries) {
                val rankPad = e.rank.toString().padStart(2)
                sb.appendLine("  $rankPad. ${e.playerName.padEnd(16)} ${e.score} ${category.scoreLabel}")
            }
            val allCategories = LeaderboardSystem.Category.entries.joinToString(", ") { it.key }
            sb.append("Categories: $allCategories")
        }
        outbound.send(OutboundEvent.SendInfo(sessionId, sb.toString().trimEnd()))
    }

    private fun resolveCategory(input: String?): LeaderboardSystem.Category? =
        if (input == null) {
            LeaderboardSystem.Category.LEVEL
        } else {
            LeaderboardSystem.Category.fromKey(input.trim().lowercase())
        }
}
