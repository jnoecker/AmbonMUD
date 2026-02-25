package dev.ambon.engine

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.achievement.AchievementCategory
import dev.ambon.domain.achievement.AchievementDef
import dev.ambon.domain.achievement.AchievementState
import dev.ambon.domain.achievement.CriterionProgress
import dev.ambon.domain.achievement.CriterionType
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.OutboundEvent

/**
 * Tracks player progress toward achievements and unlocks them when criteria are met.
 * Mirrors the QuestSystem pattern: event hooks update per-criterion counters stored
 * on PlayerState, and completed achievements grant XP/gold/title rewards.
 */
class AchievementSystem(
    private val registry: AchievementRegistry,
    private val players: PlayerRegistry,
    private val outbound: OutboundBus,
    private val gmcpEmitter: GmcpEmitter? = null,
) {
    /**
     * Called when a player kills a mob. Increments KILL criteria matching [templateKey]
     * (or any mob when targetId is blank).
     */
    suspend fun onMobKilled(
        sessionId: SessionId,
        templateKey: String,
    ) {
        val ps = players.get(sessionId) ?: return
        var changed = false
        val updatedProgress = ps.achievementProgress.toMutableMap()

        for (def in registry.all()) {
            if (ps.unlockedAchievementIds.contains(def.id)) continue

            var achievementChanged = false
            val currentState = updatedProgress[def.id]
            val progressList =
                currentState?.progress
                    ?: def.criteria.map { c -> CriterionProgress(current = 0, required = c.count) }
            val newProgressList = progressList.toMutableList()

            for ((index, criterion) in def.criteria.withIndex()) {
                if (criterion.type != CriterionType.KILL) continue
                val matches = criterion.targetId.isBlank() || criterion.targetId == templateKey
                if (!matches) continue
                val prog = newProgressList[index]
                if (prog.isComplete) continue

                newProgressList[index] = prog.copy(current = prog.current + 1)
                achievementChanged = true
            }

            if (achievementChanged) {
                updatedProgress[def.id] = AchievementState(def.id, newProgressList)
                changed = true
            }
        }

        if (changed) {
            ps.achievementProgress = updatedProgress
            checkAndUnlock(sessionId, ps.achievementProgress)
        }
    }

    /**
     * Called when a player gains a level. Checks REACH_LEVEL criteria.
     */
    suspend fun onLevelReached(
        sessionId: SessionId,
        newLevel: Int,
    ) {
        val ps = players.get(sessionId) ?: return
        var changed = false
        val updatedProgress = ps.achievementProgress.toMutableMap()

        for (def in registry.all()) {
            if (ps.unlockedAchievementIds.contains(def.id)) continue

            var achievementChanged = false
            val currentState = updatedProgress[def.id]
            val progressList =
                currentState?.progress
                    ?: def.criteria.map { c -> CriterionProgress(current = 0, required = c.count) }
            val newProgressList = progressList.toMutableList()

            for ((index, criterion) in def.criteria.withIndex()) {
                if (criterion.type != CriterionType.REACH_LEVEL) continue
                val prog = newProgressList[index]
                if (prog.isComplete) continue
                if (newLevel >= criterion.count) {
                    newProgressList[index] = prog.copy(current = newLevel)
                    achievementChanged = true
                } else if (newLevel > prog.current) {
                    // Update current level even if not yet met, for progress display
                    newProgressList[index] = prog.copy(current = newLevel)
                    achievementChanged = true
                }
            }

            if (achievementChanged) {
                updatedProgress[def.id] = AchievementState(def.id, newProgressList)
                changed = true
            }
        }

        if (changed) {
            ps.achievementProgress = updatedProgress
            checkAndUnlock(sessionId, ps.achievementProgress)
        }
    }

    /**
     * Called when a player completes a quest. Checks QUEST_COMPLETE criteria.
     */
    suspend fun onQuestCompleted(
        sessionId: SessionId,
        questId: String,
    ) {
        val ps = players.get(sessionId) ?: return
        var changed = false
        val updatedProgress = ps.achievementProgress.toMutableMap()

        for (def in registry.all()) {
            if (ps.unlockedAchievementIds.contains(def.id)) continue

            var achievementChanged = false
            val currentState = updatedProgress[def.id]
            val progressList =
                currentState?.progress
                    ?: def.criteria.map { c -> CriterionProgress(current = 0, required = c.count) }
            val newProgressList = progressList.toMutableList()

            for ((index, criterion) in def.criteria.withIndex()) {
                if (criterion.type != CriterionType.QUEST_COMPLETE) continue
                val matches = criterion.targetId.isBlank() || criterion.targetId == questId
                if (!matches) continue
                val prog = newProgressList[index]
                if (prog.isComplete) continue

                newProgressList[index] = prog.copy(current = 1)
                achievementChanged = true
            }

            if (achievementChanged) {
                updatedProgress[def.id] = AchievementState(def.id, newProgressList)
                changed = true
            }
        }

        if (changed) {
            ps.achievementProgress = updatedProgress
            checkAndUnlock(sessionId, ps.achievementProgress)
        }
    }

    /**
     * Returns the list of (achievementId, titleString) pairs available to this player.
     * A title is available when the corresponding achievement is unlocked and has a title reward.
     */
    fun availableTitles(sessionId: SessionId): List<Pair<String, String>> {
        val ps = players.get(sessionId) ?: return emptyList()
        return ps.unlockedAchievementIds.mapNotNull { id ->
            val title = registry.get(id)?.rewards?.title ?: return@mapNotNull null
            id to title
        }
    }

    /**
     * Formats the achievement list for display to a player, grouped by category.
     */
    fun formatAchievements(sessionId: SessionId): String {
        val ps = players.get(sessionId) ?: return "No achievements."
        val all = registry.all()
        if (all.isEmpty()) return "No achievements are defined."

        return buildString {
            for (category in AchievementCategory.entries) {
                val inCategory = all.filter { it.category == category }.sortedBy { it.displayName }
                if (inCategory.isEmpty()) continue
                appendLine("[ ${category.name} ]")
                for (def in inCategory) {
                    when {
                        ps.unlockedAchievementIds.contains(def.id) -> {
                            val titleNote =
                                def.rewards.title?.let { " (Title: $it)" } ?: ""
                            appendLine("  [X] ${def.displayName}$titleNote - ${def.description}")
                        }
                        def.hidden -> {
                            appendLine("  [ ] ????")
                        }
                        else -> {
                            val prog = ps.achievementProgress[def.id]
                            val progressNote = formatProgress(def, prog)
                            appendLine("  [ ] ${def.displayName} ($progressNote) - ${def.description}")
                        }
                    }
                }
            }
        }.trimEnd()
    }

    private suspend fun checkAndUnlock(
        sessionId: SessionId,
        progress: Map<String, AchievementState>,
    ) {
        val ps = players.get(sessionId) ?: return
        var persistNeeded = false

        for ((achievementId, state) in progress) {
            if (ps.unlockedAchievementIds.contains(achievementId)) continue
            if (state.progress.all { it.isComplete }) {
                unlockAchievement(sessionId, achievementId)
                persistNeeded = true
            }
        }

        if (persistNeeded) {
            players.saveAchievementState(sessionId)
        }
    }

    private suspend fun unlockAchievement(
        sessionId: SessionId,
        achievementId: String,
    ) {
        val ps = players.get(sessionId) ?: return
        val def = registry.get(achievementId) ?: return

        // Remove from in-progress, mark as unlocked
        ps.achievementProgress = ps.achievementProgress - achievementId
        ps.unlockedAchievementIds = ps.unlockedAchievementIds + achievementId

        // Notify player
        outbound.send(
            OutboundEvent.SendInfo(sessionId, "[Achievement] ${def.displayName}: ${def.description}"),
        )

        // Grant rewards
        if (def.rewards.gold > 0) {
            ps.gold += def.rewards.gold
            outbound.send(OutboundEvent.SendText(sessionId, "You receive ${def.rewards.gold} gold."))
        }
        if (def.rewards.xp > 0) {
            players.grantXp(sessionId, def.rewards.xp)
            outbound.send(OutboundEvent.SendText(sessionId, "You gain ${def.rewards.xp} XP."))
        }
        if (def.rewards.title != null) {
            outbound.send(
                OutboundEvent.SendText(
                    sessionId,
                    "A title is now available: '${def.rewards.title}' — use 'title ${def.rewards.title}' to set it.",
                ),
            )
        }

        // GMCP update
        val updatedPs = players.get(sessionId) ?: return
        gmcpEmitter?.sendCharAchievements(sessionId, updatedPs, registry)
    }

    private fun formatProgress(
        def: AchievementDef,
        state: AchievementState?,
    ): String {
        if (def.criteria.isEmpty()) return ""
        return def.criteria
            .mapIndexed { index, criterion ->
                val prog = state?.progress?.getOrNull(index)
                val current = prog?.current ?: 0
                val required = criterion.count
                when (criterion.type) {
                    CriterionType.KILL, CriterionType.QUEST_COMPLETE -> "$current/$required"
                    CriterionType.REACH_LEVEL -> "level $current/$required"
                }
            }.joinToString(", ")
    }
}
