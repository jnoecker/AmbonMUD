package dev.ambon.engine

import dev.ambon.config.DailyQuestDefinition
import dev.ambon.config.DailyQuestsConfig
import dev.ambon.domain.ids.SessionId
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Random

/**
 * Manages daily and weekly rotating quests with deterministic selection,
 * progress tracking, completion rewards, and streak bonuses.
 *
 * Quest selection is seeded by the date (daily) or ISO week number (weekly)
 * so that all players see the same quests on any given day/week.
 */
class DailyQuestSystem(
    private val config: DailyQuestsConfig,
    private val players: PlayerRegistry,
    private val clock: Clock = Clock.systemUTC(),
    private val progression: PlayerProgression = PlayerProgression(),
) {
    /** Returns today's date string (ISO format) in UTC. */
    fun todayDate(): String {
        val instant = Instant.ofEpochMilli(clock.millis())
        return LocalDate.ofInstant(instant, ZoneOffset.UTC).toString()
    }

    /** Returns the Monday ISO date of the current week (used as weekly reset key). */
    fun currentWeekDate(): String {
        val instant = Instant.ofEpochMilli(clock.millis())
        val today = LocalDate.ofInstant(instant, ZoneOffset.UTC)
        val monday = today.with(java.time.DayOfWeek.MONDAY)
        return monday.toString()
    }

    /** Selects today's daily quests from the pool using a date-seeded RNG. */
    fun selectDailyQuests(): List<DailyQuestDefinition> =
        selectFromPool(config.dailyPool, config.dailySlots, todayDate())

    /** Selects this week's weekly quests from the pool using a week-seeded RNG. */
    fun selectWeeklyQuests(): List<DailyQuestDefinition> =
        selectFromPool(config.weeklyPool, config.weeklySlots, currentWeekDate())

    private fun selectFromPool(
        pool: List<DailyQuestDefinition>,
        slots: Int,
        seed: String,
    ): List<DailyQuestDefinition> {
        if (pool.isEmpty() || slots <= 0) return emptyList()
        val rng = Random(seed.hashCode().toLong())
        val indices = pool.indices.toMutableList()
        val selected = mutableListOf<DailyQuestDefinition>()
        val count = slots.coerceAtMost(pool.size)
        repeat(count) {
            val pick = rng.nextInt(indices.size)
            selected += pool[indices.removeAt(pick)]
        }
        return selected
    }

    /**
     * Checks whether the player's daily/weekly state needs resetting (new day or week).
     * Resets progress and updates the streak if applicable.
     */
    fun checkReset(sessionId: SessionId) {
        val player = players.get(sessionId) ?: return
        val state = player.dailyQuestState
        val today = todayDate()
        val weekDate = currentWeekDate()

        // Daily reset
        if (state.lastDailyResetDate != today) {
            // Check streak: did player complete ALL dailies yesterday?
            if (state.lastDailyResetDate.isNotEmpty()) {
                val yesterday = LocalDate.parse(today).minusDays(1).toString()
                val todayQuests = selectFromPool(config.dailyPool, config.dailySlots, state.lastDailyResetDate)
                val completedAll = state.dailyCompletions.size >= todayQuests.size && todayQuests.isNotEmpty()
                if (completedAll && state.lastDailyResetDate == yesterday) {
                    state.streakDays = (state.streakDays + 1).coerceAtMost(config.streakMaxDays)
                    state.lastStreakDate = state.lastDailyResetDate
                } else if (state.lastDailyResetDate != yesterday) {
                    // Missed a day — reset streak
                    state.streakDays = 0
                    state.lastStreakDate = ""
                }
            }
            state.lastDailyResetDate = today
            state.dailyCompletions.clear()
            state.dailyProgress.clear()
        }

        // Weekly reset
        if (state.lastWeeklyResetDate != weekDate) {
            state.lastWeeklyResetDate = weekDate
            state.weeklyCompletions.clear()
            state.weeklyProgress.clear()
        }
    }

    /**
     * Returns a snapshot of the player's daily quests with progress.
     */
    fun getDailyQuestBoard(sessionId: SessionId): List<DailyQuestInfo> {
        val player = players.get(sessionId) ?: return emptyList()
        val state = player.dailyQuestState
        val quests = selectDailyQuests()
        return quests.mapIndexed { i, def ->
            DailyQuestInfo(
                index = i,
                definition = def,
                progress = state.dailyProgress[i] ?: 0,
                completed = i in state.dailyCompletions,
            )
        }
    }

    /**
     * Returns a snapshot of the player's weekly quests with progress.
     */
    fun getWeeklyQuestBoard(sessionId: SessionId): List<DailyQuestInfo> {
        val player = players.get(sessionId) ?: return emptyList()
        val state = player.dailyQuestState
        val quests = selectWeeklyQuests()
        return quests.mapIndexed { i, def ->
            DailyQuestInfo(
                index = i,
                definition = def,
                progress = state.weeklyProgress[i] ?: 0,
                completed = i in state.weeklyCompletions,
            )
        }
    }

    /** Returns the player's current streak day count. */
    fun getStreak(sessionId: SessionId): Int {
        val player = players.get(sessionId) ?: return 0
        return player.dailyQuestState.streakDays
    }

    /** Returns the streak bonus multiplier (e.g. 1.3 for 30% bonus). */
    fun streakMultiplier(streakDays: Int): Double {
        val cappedDays = streakDays.coerceAtMost(config.streakMaxDays)
        return 1.0 + (cappedDays * config.streakBonusPercent / 100.0)
    }

    /**
     * Called by game systems when a player performs an action that may advance
     * daily or weekly quest progress.
     *
     * @param type The quest objective type (e.g. "kill", "gather", "craft", "dungeon", "pvpKill", "illuminate").
     * @param count The number of actions to credit.
     * @return list of (questLabel, isDaily) pairs for quests that just completed
     */
    fun onEvent(
        sessionId: SessionId,
        type: String,
        count: Int = 1,
    ): List<CompletedDailyQuest> {
        if (!config.enabled) return emptyList()
        val player = players.get(sessionId) ?: return emptyList()
        checkReset(sessionId)
        val state = player.dailyQuestState
        val completed = mutableListOf<CompletedDailyQuest>()

        // Daily quests
        val dailies = selectDailyQuests()
        for ((i, def) in dailies.withIndex()) {
            if (i in state.dailyCompletions) continue
            if (def.type != type) continue
            val progress = (state.dailyProgress[i] ?: 0) + count
            state.dailyProgress[i] = progress
            if (progress >= def.targetCount) {
                state.dailyCompletions += i
                completed += CompletedDailyQuest(
                    description = def.description,
                    isDaily = true,
                    goldReward = def.goldReward,
                    xpReward = def.xpReward,
                )
            }
        }

        // Weekly quests
        val weeklies = selectWeeklyQuests()
        for ((i, def) in weeklies.withIndex()) {
            if (i in state.weeklyCompletions) continue
            if (def.type != type) continue
            val progress = (state.weeklyProgress[i] ?: 0) + count
            state.weeklyProgress[i] = progress
            if (progress >= def.targetCount) {
                state.weeklyCompletions += i
                completed += CompletedDailyQuest(
                    description = def.description,
                    isDaily = false,
                    goldReward = def.goldReward,
                    xpReward = def.xpReward,
                )
            }
        }

        return completed
    }

    /**
     * Awards rewards for completed quests, applying streak bonus.
     */
    suspend fun awardRewards(
        sessionId: SessionId,
        completions: List<CompletedDailyQuest>,
    ) {
        val player = players.get(sessionId) ?: return
        val streak = player.dailyQuestState.streakDays
        val multiplier = streakMultiplier(streak)

        for (quest in completions) {
            val bonusMultiplier = if (quest.isDaily) multiplier else 1.0
            val gold = (quest.goldReward * bonusMultiplier).toLong()
            val xp = (quest.xpReward * bonusMultiplier).toLong()

            player.gold += gold
            if (xp > 0) {
                progression.grantXp(player, xp)
            }
        }
    }

    /**
     * Formats the daily quest board for text output.
     */
    fun formatDailyBoard(sessionId: SessionId): String = buildString {
        val board = getDailyQuestBoard(sessionId)
        val streak = getStreak(sessionId)
        if (board.isEmpty()) {
            append("No daily quests available.")
            return@buildString
        }
        appendLine("=== Daily Quests ===")
        for (info in board) {
            val status = if (info.completed) "[DONE]" else "[${info.progress}/${info.definition.targetCount}]"
            val rewards = "${info.definition.goldReward}g, ${info.definition.xpReward}xp"
            appendLine("  $status ${info.definition.description} ($rewards)")
        }
        if (streak > 0) {
            val bonus = (streak * config.streakBonusPercent)
                .coerceAtMost(config.streakMaxDays * config.streakBonusPercent)
            appendLine("Streak: $streak day${if (streak != 1) "s" else ""} (+$bonus% bonus rewards)")
        }
    }.trimEnd()

    /**
     * Formats the weekly quest board for text output.
     */
    fun formatWeeklyBoard(sessionId: SessionId): String = buildString {
        val board = getWeeklyQuestBoard(sessionId)
        if (board.isEmpty()) {
            append("No weekly quests available.")
            return@buildString
        }
        appendLine("=== Weekly Quests ===")
        for (info in board) {
            val status = if (info.completed) "[DONE]" else "[${info.progress}/${info.definition.targetCount}]"
            val rewards = "${info.definition.goldReward}g, ${info.definition.xpReward}xp"
            appendLine("  $status ${info.definition.description} ($rewards)")
        }
    }.trimEnd()
}

/** Snapshot of a daily/weekly quest with the player's progress. */
data class DailyQuestInfo(
    val index: Int,
    val definition: DailyQuestDefinition,
    val progress: Int,
    val completed: Boolean,
)

/** Result of completing a daily/weekly quest, before reward application. */
data class CompletedDailyQuest(
    val description: String,
    val isDaily: Boolean,
    val goldReward: Long,
    val xpReward: Long,
)
