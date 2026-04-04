package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.DailyQuestSystem
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent

class DailyQuestHandler(
    private val ctx: EngineContext,
    private val dailyQuestSystem: DailyQuestSystem? = null,
) : CommandHandler {
    private val outbound = ctx.outbound
    private val gmcpEmitter = ctx.gmcpEmitter

    override fun register(router: CommandRouter) {
        router.on<Command.DailyQuests> { sid, _ -> handleDailyQuests(sid) }
        router.on<Command.WeeklyQuests> { sid, _ -> handleWeeklyQuests(sid) }
    }

    private suspend fun handleDailyQuests(sessionId: SessionId) {
        val dqs = requireSystemOrNull(sessionId, dailyQuestSystem, "Daily quests", outbound) ?: return
        dqs.checkReset(sessionId)
        outbound.send(OutboundEvent.SendInfo(sessionId, dqs.formatDailyBoard(sessionId)))
        gmcpEmitter?.sendDailyQuests(sessionId, dqs)
    }

    private suspend fun handleWeeklyQuests(sessionId: SessionId) {
        val dqs = requireSystemOrNull(sessionId, dailyQuestSystem, "Weekly quests", outbound) ?: return
        dqs.checkReset(sessionId)
        outbound.send(OutboundEvent.SendInfo(sessionId, dqs.formatWeeklyBoard(sessionId)))
        gmcpEmitter?.sendWeeklyQuests(sessionId, dqs)
    }
}
