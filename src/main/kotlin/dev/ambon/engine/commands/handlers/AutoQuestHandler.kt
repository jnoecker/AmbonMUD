package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.AutoQuestSystem
import dev.ambon.engine.GmcpEmitter
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent

class AutoQuestHandler(
    private val ctx: EngineContext,
    private val autoQuestSystem: AutoQuestSystem?,
    private val onQuestReward: (suspend (SessionId, Long, Long) -> Unit)? = null,
) : CommandHandler {
    private val outbound = ctx.outbound
    private val gmcpEmitter = ctx.gmcpEmitter

    override fun register(router: CommandRouter) {
        router.on<Command.QuestAuto> { sid, _ -> handleQuestAuto(sid) }
        router.on<Command.QuestAutoInfo> { sid, _ -> handleQuestAutoInfo(sid) }
        router.on<Command.QuestAutoAbandon> { sid, _ -> handleQuestAutoAbandon(sid) }
    }

    private suspend fun handleQuestAuto(sessionId: SessionId) {
        val system = requireSystemOrNull(sessionId, autoQuestSystem, "Bounty quests", outbound) ?: return

        val result = system.requestQuest(sessionId)
        result.fold(
            onSuccess = { quest ->
                val remainingMs = system.timeRemainingMs(sessionId) ?: 0L
                val minutes = (remainingMs + 999) / 1000 / 60
                outbound.send(
                    OutboundEvent.SendInfo(
                        sessionId,
                        "[Bounty] Hunt ${quest.killsRequired} ${quest.targetMobName}!",
                    ),
                )
                outbound.send(
                    OutboundEvent.SendText(
                        sessionId,
                        "Reward: ${quest.rewardGold} gold, ${quest.rewardXp} XP",
                    ),
                )
                outbound.send(
                    OutboundEvent.SendText(
                        sessionId,
                        "Time limit: $minutes minutes. Progress: 0/${quest.killsRequired}",
                    ),
                )
                emitAutoQuestGmcp(sessionId, system)
            },
            onFailure = { error ->
                outbound.send(OutboundEvent.SendError(sessionId, error.message ?: "Failed to generate bounty."))
            },
        )
    }

    private suspend fun handleQuestAutoInfo(sessionId: SessionId) {
        val system = requireSystemOrNull(sessionId, autoQuestSystem, "Bounty quests", outbound) ?: return

        val quest = system.getActiveQuest(sessionId)
        if (quest == null) {
            outbound.send(OutboundEvent.SendInfo(sessionId, "You have no active bounty. Use 'bounty' to request one."))
            return
        }

        val remainingMs = system.timeRemainingMs(sessionId) ?: 0L
        val remainingSec = (remainingMs + 999) / 1000
        val minutes = remainingSec / 60
        val seconds = remainingSec % 60

        outbound.send(
            OutboundEvent.SendInfo(
                sessionId,
                "[Bounty] Hunt ${quest.killsRequired} ${quest.targetMobName}",
            ),
        )
        outbound.send(
            OutboundEvent.SendText(
                sessionId,
                "Progress: ${quest.killsCompleted}/${quest.killsRequired}",
            ),
        )
        outbound.send(
            OutboundEvent.SendText(
                sessionId,
                "Reward: ${quest.rewardGold} gold, ${quest.rewardXp} XP",
            ),
        )
        outbound.send(
            OutboundEvent.SendText(
                sessionId,
                "Time remaining: ${minutes}m ${seconds}s",
            ),
        )
    }

    private suspend fun handleQuestAutoAbandon(sessionId: SessionId) {
        val system = requireSystemOrNull(sessionId, autoQuestSystem, "Bounty quests", outbound) ?: return

        if (system.abandonQuest(sessionId)) {
            outbound.send(OutboundEvent.SendInfo(sessionId, "Bounty abandoned."))
            emitAutoQuestGmcp(sessionId, system)
        } else {
            outbound.send(OutboundEvent.SendError(sessionId, "You have no active bounty to abandon."))
        }
    }

    /**
     * Called by the engine when a mob kill completes an auto-quest.
     * Awards rewards and sends completion messages.
     */
    suspend fun handleAutoQuestComplete(sessionId: SessionId) {
        val system = autoQuestSystem ?: return
        val quest = system.completeQuest(sessionId) ?: return

        outbound.send(
            OutboundEvent.SendInfo(
                sessionId,
                "[Bounty Complete] You have slain ${quest.killsRequired} ${quest.targetMobName}!",
            ),
        )

        // Grant rewards via callback (to use PlayerRegistry.grantXp and gold)
        onQuestReward?.invoke(sessionId, quest.rewardXp, quest.rewardGold)

        emitAutoQuestGmcp(sessionId, system)
    }

    /**
     * Called by the engine on each mob kill to check/update progress.
     * Sends a progress message if the kill counted.
     */
    suspend fun onMobKill(sessionId: SessionId, mobTemplateId: String) {
        val system = autoQuestSystem ?: return
        val quest = system.getActiveQuest(sessionId) ?: return
        val completed = system.onMobKill(sessionId, mobTemplateId)
        if (quest.targetMobTemplateId != mobTemplateId) return

        if (completed) {
            handleAutoQuestComplete(sessionId)
        } else {
            // Progress message
            val updated = system.getActiveQuest(sessionId) ?: return
            outbound.send(
                OutboundEvent.SendInfo(
                    sessionId,
                    "[Bounty] ${updated.killsCompleted}/${updated.killsRequired} ${updated.targetMobName} slain.",
                ),
            )
            emitAutoQuestGmcp(sessionId, system)
        }
    }

    /**
     * Called by the engine tick when a quest expires.
     */
    suspend fun handleExpired(sessionId: SessionId) {
        outbound.send(
            OutboundEvent.SendError(
                sessionId,
                "[Bounty] Time's up! Your bounty has expired.",
            ),
        )
        emitAutoQuestGmcp(sessionId, autoQuestSystem)
    }

    private suspend fun emitAutoQuestGmcp(sessionId: SessionId, system: AutoQuestSystem?) {
        val emitter = gmcpEmitter ?: return
        val quest = system?.getActiveQuest(sessionId)
        if (quest != null) {
            val remainingMs = system.timeRemainingMs(sessionId) ?: 0L
            emitter.sendAutoQuest(
                sessionId,
                GmcpEmitter.AutoQuestPayload(
                    active = true,
                    targetMobName = quest.targetMobName,
                    targetMobTemplateId = quest.targetMobTemplateId,
                    killsRequired = quest.killsRequired,
                    killsCompleted = quest.killsCompleted,
                    rewardGold = quest.rewardGold,
                    rewardXp = quest.rewardXp,
                    timeRemainingMs = remainingMs,
                ),
            )
        } else {
            emitter.sendAutoQuest(
                sessionId,
                GmcpEmitter.AutoQuestPayload(active = false),
            )
        }
    }
}
