package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.GlobalQuestObjectiveType
import dev.ambon.engine.GlobalQuestSystem
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent
import java.time.Clock

class GlobalQuestHandler(
    ctx: EngineContext,
    private val globalQuestSystem: GlobalQuestSystem?,
    private val clock: Clock = Clock.systemUTC(),
) : CommandHandler {
    private val outbound = ctx.outbound
    private val players = ctx.players
    private val gmcpEmitter = ctx.gmcpEmitter

    override fun register(router: CommandRouter) {
        router.on<Command.GlobalQuestInfo> { sid, _ -> handleGlobalQuestInfo(sid) }
    }

    private suspend fun handleGlobalQuestInfo(sessionId: SessionId) {
        val sys = globalQuestSystem
        if (sys == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "Global quests are not available."))
            return
        }

        val status = sys.getStatus()
        if (status == null) {
            outbound.send(OutboundEvent.SendInfo(sessionId, "No global quest is currently active."))
            return
        }

        val myProgress = sys.getPlayerProgress(sessionId) ?: 0
        val remainMs = (status.endsAtMs - clock.millis()).coerceAtLeast(0)
        val remainMin = remainMs / 60_000
        val remainSec = (remainMs % 60_000) / 1_000

        val sb = StringBuilder()
        sb.appendLine("=== GLOBAL QUEST ===")
        sb.appendLine("Objective: ${status.objective.description}")
        sb.appendLine("Target: ${status.objective.targetCount} ${objectiveLabel(status.objective.type)}")
        sb.appendLine("Time remaining: ${remainMin}m ${remainSec}s")
        sb.appendLine("Your progress: $myProgress/${status.objective.targetCount}")
        if (status.leaderboard.isNotEmpty()) {
            sb.appendLine("--- Leaderboard ---")
            for ((index, entry) in status.leaderboard.withIndex()) {
                val rank = index + 1
                val marker = if (entry.playerName == players.get(sessionId)?.name) " (you)" else ""
                sb.appendLine("  $rank. ${entry.playerName.padEnd(16)} ${entry.progress}/${status.objective.targetCount}$marker")
            }
        }
        outbound.send(OutboundEvent.SendInfo(sessionId, sb.toString().trimEnd()))

        // Also emit GMCP
        gmcpEmitter?.sendGlobalQuest(sessionId, status, myProgress)
    }

    private fun objectiveLabel(type: GlobalQuestObjectiveType): String = when (type) {
        GlobalQuestObjectiveType.KILL -> "kills"
        GlobalQuestObjectiveType.GATHER -> "gathers"
        GlobalQuestObjectiveType.CRAFT -> "crafts"
    }
}
