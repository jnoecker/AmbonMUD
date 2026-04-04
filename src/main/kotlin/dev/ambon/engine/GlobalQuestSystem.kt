package dev.ambon.engine

import dev.ambon.config.GlobalQuestObjectiveConfig
import dev.ambon.config.GlobalQuestsConfig
import dev.ambon.domain.ids.SessionId
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock

private val log = KotlinLogging.logger {}

/**
 * The type of objective a global competitive quest tracks.
 */
enum class GlobalQuestObjectiveType {
    KILL,
    GATHER,
    CRAFT,
}

/**
 * A resolved objective for a running global quest.
 */
data class GlobalQuestObjective(
    val type: GlobalQuestObjectiveType,
    val targetCount: Int,
    val description: String,
)

/**
 * Tracks a single active global competitive quest, including participant progress.
 */
data class GlobalQuest(
    val objective: GlobalQuestObjective,
    val startedAtMs: Long,
    val endsAtMs: Long,
    val progress: MutableMap<SessionId, Int> = mutableMapOf(),
    var lastAnnounceMs: Long = 0L,
    var completed: Boolean = false,
)

/**
 * Snapshot of the current global quest status for display.
 */
data class GlobalQuestStatus(
    val objective: GlobalQuestObjective,
    val startedAtMs: Long,
    val endsAtMs: Long,
    val completed: Boolean,
    val leaderboard: List<GlobalQuestLeaderboardEntry>,
)

/**
 * A single entry on the global quest leaderboard.
 */
data class GlobalQuestLeaderboardEntry(
    val playerName: String,
    val progress: Int,
)

/**
 * Reward tier placement for a finished global quest.
 */
data class GlobalQuestWinner(
    val sessionId: SessionId,
    val playerName: String,
    val progress: Int,
    val goldReward: Long,
    val xpReward: Long,
    val place: Int,
)

/**
 * Result of a tick: what happened this tick (announcements to broadcast, winners to reward).
 */
sealed interface GlobalQuestTickResult {
    data object Nothing : GlobalQuestTickResult

    data class QuestStarted(
        val announcement: String,
    ) : GlobalQuestTickResult

    data class ProgressUpdate(
        val announcement: String,
    ) : GlobalQuestTickResult

    data class QuestEnded(
        val announcement: String,
        val winners: List<GlobalQuestWinner>,
    ) : GlobalQuestTickResult
}

/**
 * Server-wide competitive quest system.
 *
 * Periodically starts a global quest where all online players race to complete an
 * objective (kill count, gathering, crafting). Winners are announced and rewarded.
 *
 * This system is ephemeral — no persistence across server restarts.
 */
class GlobalQuestSystem(
    private val config: GlobalQuestsConfig,
    private val clock: Clock,
    private val playerCount: () -> Int,
    private val resolvePlayerName: (SessionId) -> String?,
) {
    private var activeQuest: GlobalQuest? = null
    private var lastQuestEndMs: Long = 0L

    /**
     * Called every engine tick. Returns what happened (if anything).
     */
    fun tick(nowMs: Long = clock.millis()): GlobalQuestTickResult {
        if (!config.enabled) return GlobalQuestTickResult.Nothing

        val quest = activeQuest
        if (quest != null) {
            return tickActiveQuest(quest, nowMs)
        }

        // Check whether to start a new quest.
        if (nowMs - lastQuestEndMs >= config.intervalMs && playerCount() >= config.minPlayersOnline) {
            return startNewQuest(nowMs)
        }

        return GlobalQuestTickResult.Nothing
    }

    /**
     * Record progress for a participant on an event type.
     * Returns true if the quest is now complete (someone hit the target).
     */
    fun onEvent(sessionId: SessionId, type: GlobalQuestObjectiveType, count: Int = 1): Boolean {
        val quest = activeQuest ?: return false
        if (quest.completed) return false
        if (quest.objective.type != type) return false

        val current = quest.progress.getOrDefault(sessionId, 0)
        val newProgress = current + count
        quest.progress[sessionId] = newProgress

        if (newProgress >= quest.objective.targetCount) {
            quest.completed = true
            return true
        }
        return false
    }

    /**
     * Returns the current quest status, or null if no quest is active.
     */
    fun getStatus(): GlobalQuestStatus? {
        val quest = activeQuest ?: return null
        return GlobalQuestStatus(
            objective = quest.objective,
            startedAtMs = quest.startedAtMs,
            endsAtMs = quest.endsAtMs,
            completed = quest.completed,
            leaderboard = buildLeaderboard(quest),
        )
    }

    /**
     * Returns the sorted leaderboard for the current active quest.
     */
    fun getLeaderboard(): List<GlobalQuestLeaderboardEntry> {
        val quest = activeQuest ?: return emptyList()
        return buildLeaderboard(quest)
    }

    /**
     * Get the player's current progress in the active quest, or null if no quest or not participating.
     */
    fun getPlayerProgress(sessionId: SessionId): Int? {
        val quest = activeQuest ?: return null
        return quest.progress[sessionId]
    }

    internal fun activeQuestForTesting(): GlobalQuest? = activeQuest

    // ── Private ─────────────────────────────────────────────────────────

    private fun startNewQuest(nowMs: Long): GlobalQuestTickResult {
        val objectives = config.objectives
        if (objectives.isEmpty()) {
            log.warn { "GlobalQuestSystem: no objectives configured, cannot start quest" }
            return GlobalQuestTickResult.Nothing
        }

        val objectiveConfig = objectives.random()
        val objective = resolveObjective(objectiveConfig)

        val quest = GlobalQuest(
            objective = objective,
            startedAtMs = nowMs,
            endsAtMs = nowMs + config.durationMs,
            lastAnnounceMs = nowMs,
        )
        activeQuest = quest

        val minutes = config.durationMs / 60_000
        val announcement = "GLOBAL QUEST: ${objective.description}! You have $minutes minutes. Type 'gquest' for details."
        log.info { "Global quest started: ${objective.description} (duration=${minutes}m)" }
        return GlobalQuestTickResult.QuestStarted(announcement)
    }

    private fun tickActiveQuest(quest: GlobalQuest, nowMs: Long): GlobalQuestTickResult {
        // Check if quest completed (someone hit target or was flagged by onEvent).
        if (quest.completed || nowMs >= quest.endsAtMs) {
            return endQuest(quest, nowMs)
        }

        // Check if it's time for a progress announcement.
        if (nowMs - quest.lastAnnounceMs >= config.announceIntervalMs) {
            quest.lastAnnounceMs = nowMs
            return buildProgressUpdate(quest, nowMs)
        }

        return GlobalQuestTickResult.Nothing
    }

    private fun endQuest(quest: GlobalQuest, nowMs: Long): GlobalQuestTickResult {
        activeQuest = null
        lastQuestEndMs = nowMs

        val sorted = quest.progress.entries
            .sortedByDescending { it.value }
            .take(3)

        val winners = sorted.mapIndexed { index, (sid, progress) ->
            val place = index + 1
            val name = resolvePlayerName(sid) ?: "Unknown"
            GlobalQuestWinner(
                sessionId = sid,
                playerName = name,
                progress = progress,
                goldReward = rewardGoldForPlace(place),
                xpReward = rewardXpForPlace(place),
                place = place,
            )
        }

        val announcement = buildEndAnnouncement(quest, winners)
        log.info { "Global quest ended: ${quest.objective.description}, winners=${winners.map { it.playerName }}" }
        return GlobalQuestTickResult.QuestEnded(announcement, winners)
    }

    private fun buildProgressUpdate(quest: GlobalQuest, nowMs: Long): GlobalQuestTickResult {
        val leaderboard = buildLeaderboard(quest)
        if (leaderboard.isEmpty()) {
            val remainMs = quest.endsAtMs - nowMs
            val remainMin = remainMs / 60_000
            val announcement = "GLOBAL QUEST UPDATE: No participants yet! " +
                "${quest.objective.description} — $remainMin minutes remaining."
            return GlobalQuestTickResult.ProgressUpdate(announcement)
        }

        val leader = leaderboard.first()
        val remainMs = quest.endsAtMs - nowMs
        val remainMin = remainMs / 60_000
        val target = quest.objective.targetCount
        val announcement = "GLOBAL QUEST UPDATE: ${leader.playerName} leads with " +
            "${leader.progress}/$target! $remainMin minutes remaining."
        return GlobalQuestTickResult.ProgressUpdate(announcement)
    }

    private fun buildEndAnnouncement(quest: GlobalQuest, winners: List<GlobalQuestWinner>): String {
        if (winners.isEmpty()) {
            return "GLOBAL QUEST ENDED: ${quest.objective.description} — No participants!"
        }
        val sb = StringBuilder("GLOBAL QUEST COMPLETE! ")
        val first = winners[0]
        sb.append("${first.playerName} wins with ${first.progress} ")
        sb.append(objectiveVerb(quest.objective.type))
        sb.append("!")
        if (winners.size > 1) {
            val rest = winners.drop(1).joinToString(", ") { w ->
                "${w.playerName} (${ordinal(w.place)}, ${w.progress} ${objectiveVerb(quest.objective.type)})"
            }
            sb.append(" $rest.")
        }
        return sb.toString()
    }

    private fun buildLeaderboard(quest: GlobalQuest): List<GlobalQuestLeaderboardEntry> =
        quest.progress.entries
            .sortedByDescending { it.value }
            .map { (sid, progress) ->
                GlobalQuestLeaderboardEntry(
                    playerName = resolvePlayerName(sid) ?: "Unknown",
                    progress = progress,
                )
            }

    private fun resolveObjective(cfg: GlobalQuestObjectiveConfig): GlobalQuestObjective {
        val type = when (cfg.type.lowercase()) {
            "kill" -> GlobalQuestObjectiveType.KILL
            "gather" -> GlobalQuestObjectiveType.GATHER
            "craft" -> GlobalQuestObjectiveType.CRAFT
            else -> {
                log.warn { "Unknown global quest objective type '${cfg.type}', defaulting to KILL" }
                GlobalQuestObjectiveType.KILL
            }
        }
        return GlobalQuestObjective(
            type = type,
            targetCount = cfg.targetCount,
            description = cfg.description,
        )
    }

    private fun rewardGoldForPlace(place: Int): Long = when (place) {
        1 -> config.rewardGoldFirst
        2 -> config.rewardGoldSecond
        3 -> config.rewardGoldThird
        else -> 0L
    }

    private fun rewardXpForPlace(place: Int): Long = when (place) {
        1 -> config.rewardXpFirst
        2 -> config.rewardXpSecond
        3 -> config.rewardXpThird
        else -> 0L
    }

    private fun objectiveVerb(type: GlobalQuestObjectiveType): String = when (type) {
        GlobalQuestObjectiveType.KILL -> "kills"
        GlobalQuestObjectiveType.GATHER -> "gathers"
        GlobalQuestObjectiveType.CRAFT -> "crafts"
    }

    private fun ordinal(n: Int): String = when (n) {
        1 -> "1st"
        2 -> "2nd"
        3 -> "3rd"
        else -> "${n}th"
    }
}
