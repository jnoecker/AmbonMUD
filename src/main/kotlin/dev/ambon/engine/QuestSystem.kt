package dev.ambon.engine

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.quest.CompletionType
import dev.ambon.domain.quest.ObjectiveProgress
import dev.ambon.domain.quest.ObjectiveType
import dev.ambon.domain.quest.QuestDef
import dev.ambon.domain.quest.QuestObjectiveDef
import dev.ambon.domain.quest.QuestRewards
import dev.ambon.domain.quest.QuestState
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import java.time.Clock

class QuestSystem(
    private val registry: QuestRegistry,
    private val players: PlayerRegistry,
    private val items: ItemRegistry,
    private val outbound: OutboundBus,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Invoked after a quest is successfully completed; used by AchievementSystem. */
    var onQuestCompleted: (suspend (SessionId, String) -> Unit)? = null

    /**
     * Returns quests offered by this mob that the player can accept
     * (not already active, not already completed).
     */
    fun availableQuests(
        sessionId: SessionId,
        mobId: String,
    ): List<QuestDef> {
        val ps = players.get(sessionId) ?: return emptyList()
        return registry.questsForMob(mobId).filter { quest ->
            !ps.activeQuests.containsKey(quest.id) && !ps.completedQuestIds.contains(quest.id)
        }
    }

    /**
     * Accept a quest by id. Returns an error string, or null on success.
     */
    suspend fun acceptQuest(
        sessionId: SessionId,
        questId: String,
    ): String? {
        val ps = players.get(sessionId) ?: return ERR_NOT_CONNECTED
        val quest = registry.get(questId) ?: return "Unknown quest '$questId'."
        if (ps.activeQuests.containsKey(questId)) return "You are already on that quest."
        if (ps.completedQuestIds.contains(questId)) return "You have already completed that quest."

        val state =
            QuestState(
                questId = questId,
                acceptedAtEpochMs = clock.millis(),
                objectives = quest.objectives.map { ObjectiveProgress(current = 0, required = it.count) },
            )
        ps.activeQuests = ps.activeQuests + (questId to state)
        persistPlayer(ps)

        outbound.send(OutboundEvent.SendInfo(sessionId, "Quest accepted: ${quest.name}"))
        for (obj in quest.objectives) {
            outbound.send(OutboundEvent.SendText(sessionId, "  - ${obj.description}"))
        }
        return null
    }

    /**
     * Abandon a quest by name hint. Returns an error string, or null on success.
     */
    suspend fun abandonQuest(
        sessionId: SessionId,
        nameHint: String,
    ): String? {
        val ps = players.get(sessionId) ?: return ERR_NOT_CONNECTED
        val questId = findActiveQuestId(ps.activeQuests, nameHint) ?: return "No active quest matching '$nameHint'."
        val quest = registry.get(questId)
        ps.activeQuests = ps.activeQuests - questId
        persistPlayer(ps)
        outbound.send(OutboundEvent.SendInfo(sessionId, "Quest abandoned: ${quest?.name ?: questId}"))
        return null
    }

    /**
     * Called when a player kills a mob. Updates KILL objectives and checks completion.
     */
    suspend fun onMobKilled(
        sessionId: SessionId,
        templateKey: String,
    ) {
        advanceObjectives(sessionId) { objDef, prog ->
            if (objDef.type != ObjectiveType.KILL) return@advanceObjectives null
            if (objDef.targetId != templateKey) return@advanceObjectives null
            prog.copy(current = prog.current + 1)
        }
    }

    /**
     * Called when a player picks up an item. Updates COLLECT objectives and checks completion.
     */
    suspend fun onItemCollected(
        sessionId: SessionId,
        item: ItemInstance,
    ) {
        advanceObjectives(sessionId) { objDef, prog ->
            if (objDef.type != ObjectiveType.COLLECT) return@advanceObjectives null
            val targetIdRaw = objDef.targetId
            val itemId = item.id.value
            if (itemId != targetIdRaw && !itemId.endsWith(":${targetIdRaw.substringAfterLast(':')}")) return@advanceObjectives null
            val currentCount = items.inventory(sessionId).count { inv -> inv.id.value == itemId }
            val newCurrent = currentCount.coerceAtMost(prog.required)
            if (newCurrent <= prog.current) return@advanceObjectives null
            prog.copy(current = newCurrent)
        }
    }

    /**
     * Iterates all active quest objectives for [sessionId], calling [advance] for each
     * non-complete objective. When [advance] returns a non-null [ObjectiveProgress], the
     * objective is updated and the player is persisted with an auto-complete check.
     */
    private suspend fun advanceObjectives(
        sessionId: SessionId,
        advance: (QuestObjectiveDef, ObjectiveProgress) -> ObjectiveProgress?,
    ) {
        val ps = players.get(sessionId) ?: return
        val updatedQuests = ps.activeQuests.toMutableMap()
        var changed = false

        for ((questId, state) in ps.activeQuests) {
            val quest = registry.get(questId) ?: continue
            val newObjectives = state.objectives.toMutableList()
            var questChanged = false

            for ((index, objDef) in quest.objectives.withIndex()) {
                val prog = newObjectives[index]
                if (prog.isComplete) continue
                val updated = advance(objDef, prog) ?: continue
                newObjectives[index] = updated
                questChanged = true
                sendObjectiveProgress(sessionId, objDef.description, updated)
            }

            if (questChanged) {
                updatedQuests[questId] = state.copy(objectives = newObjectives)
                changed = true
            }
        }

        if (changed) {
            ps.activeQuests = updatedQuests
            persistPlayer(ps)
            checkAutoComplete(sessionId, ps.activeQuests)
        }
    }

    /**
     * Format the quest log for a player.
     */
    fun formatQuestLog(sessionId: SessionId): String {
        val ps = players.get(sessionId) ?: return "No quests."
        if (ps.activeQuests.isEmpty()) return "You have no active quests."
        return buildString {
            appendLine("Active Quests:")
            for ((questId, state) in ps.activeQuests) {
                val quest = registry.get(questId)
                val name = quest?.name ?: questId
                appendLine("  $name")
                for ((index, objDef) in (quest?.objectives ?: emptyList()).withIndex()) {
                    val prog = state.objectives.getOrNull(index) ?: continue
                    val status = if (prog.isComplete) "DONE" else "${prog.current}/${prog.required}"
                    appendLine("    - ${objDef.description}: $status")
                }
            }
        }.trimEnd()
    }

    /**
     * Format detailed quest info for one quest matching nameHint.
     */
    fun formatQuestInfo(
        sessionId: SessionId,
        nameHint: String,
    ): String {
        val ps = players.get(sessionId) ?: return "No quests."
        val questId =
            findActiveQuestId(ps.activeQuests, nameHint)
                ?: return "No active quest matching '$nameHint'."
        val quest = registry.get(questId) ?: return "Quest data not found."
        val state = ps.activeQuests[questId] ?: return "Quest not active."
        return buildString {
            appendLine("Quest: ${quest.name}")
            appendLine(quest.description)
            appendLine("Objectives:")
            for ((index, objDef) in quest.objectives.withIndex()) {
                val prog = state.objectives.getOrNull(index)
                val status = if (prog?.isComplete == true) "DONE" else "${prog?.current ?: 0}/${prog?.required ?: objDef.count}"
                appendLine("  - ${objDef.description}: $status")
            }
            appendLine("Rewards:")
            if (quest.rewards.xp > 0) appendLine("  XP: ${quest.rewards.xp}")
            if (quest.rewards.gold > 0) append("  Gold: ${quest.rewards.gold}")
        }.trimEnd()
    }

    private suspend fun checkAutoComplete(
        sessionId: SessionId,
        activeQuests: Map<String, QuestState>,
    ) {
        for ((questId, state) in activeQuests) {
            val quest = registry.get(questId) ?: continue
            if (quest.completionType != CompletionType.AUTO) continue
            if (state.objectives.all { it.isComplete }) {
                completeQuest(sessionId, questId, quest.rewards)
            }
        }
    }

    private suspend fun completeQuest(
        sessionId: SessionId,
        questId: String,
        rewards: QuestRewards,
    ) {
        val ps = players.get(sessionId) ?: return
        val quest = registry.get(questId) ?: return

        ps.activeQuests = ps.activeQuests - questId
        ps.completedQuestIds = ps.completedQuestIds + questId

        outbound.send(OutboundEvent.SendInfo(sessionId, "Quest complete: ${quest.name}!"))
        onQuestCompleted?.invoke(sessionId, questId)

        grantRewards(sessionId, rewards, ps, players, outbound)
        if (rewards.xp == 0L) persistPlayer(ps)
    }

    private suspend fun sendObjectiveProgress(
        sessionId: SessionId,
        description: String,
        updated: ObjectiveProgress,
    ) {
        if (updated.isComplete) {
            outbound.send(OutboundEvent.SendText(sessionId, "[Quest] $description: complete!"))
        } else {
            outbound.send(OutboundEvent.SendText(sessionId, "[Quest] $description: ${updated.current}/${updated.required}"))
        }
    }

    private suspend fun persistPlayer(ps: dev.ambon.engine.PlayerState) {
        players.persistPlayer(ps.sessionId)
    }

    private fun findActiveQuestId(
        activeQuests: Map<String, QuestState>,
        nameHint: String,
    ): String? {
        val lower = nameHint.trim().lowercase()
        for ((questId, _) in activeQuests) {
            val quest = registry.get(questId) ?: continue
            if (quest.name.lowercase().contains(lower) || questId.lowercase().contains(lower)) {
                return questId
            }
        }
        return null
    }
}
