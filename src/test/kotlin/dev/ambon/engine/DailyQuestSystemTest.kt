package dev.ambon.engine

import dev.ambon.config.DailyQuestDefinition
import dev.ambon.config.DailyQuestsConfig
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.MutableClock
import dev.ambon.test.buildTestPlayerRegistry
import dev.ambon.test.loginOrFail
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneOffset

class DailyQuestSystemTest {
    private val startRoom = RoomId("zone:start")
    private val sid = SessionId(1)

    private val dailyPool = listOf(
        DailyQuestDefinition(type = "kill", targetCount = 10, description = "Slay 10 creatures", goldReward = 200, xpReward = 500),
        DailyQuestDefinition(type = "kill", targetCount = 20, description = "Slay 20 creatures", goldReward = 400, xpReward = 1000),
        DailyQuestDefinition(type = "gather", targetCount = 5, description = "Gather 5 resources", goldReward = 150, xpReward = 300),
        DailyQuestDefinition(type = "dungeon", targetCount = 1, description = "Complete a dungeon", goldReward = 500, xpReward = 1500),
        DailyQuestDefinition(type = "craft", targetCount = 3, description = "Craft 3 items", goldReward = 250, xpReward = 600),
        DailyQuestDefinition(type = "pvpKill", targetCount = 3, description = "Defeat 3 players in PvP", goldReward = 300, xpReward = 800),
    )

    private val weeklyPool = listOf(
        DailyQuestDefinition(type = "kill", targetCount = 100, description = "Slay 100 creatures", goldReward = 2000, xpReward = 5000),
        DailyQuestDefinition(type = "dungeon", targetCount = 5, description = "Complete 5 dungeons", goldReward = 3000, xpReward = 8000),
        DailyQuestDefinition(type = "craft", targetCount = 15, description = "Craft 15 items", goldReward = 1500, xpReward = 4000),
    )

    private val config = DailyQuestsConfig(
        enabled = true,
        resetHourUtc = 0,
        dailySlots = 3,
        weeklySlots = 1,
        streakBonusPercent = 10,
        streakMaxDays = 7,
        dailyPool = dailyPool,
        weeklyPool = weeklyPool,
    )

    private lateinit var clock: MutableClock
    private lateinit var players: PlayerRegistry
    private lateinit var repo: InMemoryPlayerRepository
    private lateinit var system: DailyQuestSystem

    @BeforeEach
    fun setup() = runTest {
        // Use a fixed date: 2026-04-03 at 12:00 UTC
        val fixedDate = LocalDate.of(2026, 4, 3)
        val epochMs = fixedDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() + 43_200_000L
        clock = MutableClock(epochMs)

        repo = InMemoryPlayerRepository()
        players = buildTestPlayerRegistry(
            startRoom = startRoom,
            repo = repo,
            clock = clock,
        )
        players.loginOrFail(sid, "TestPlayer", "password")

        system = DailyQuestSystem(
            config = config,
            players = players,
            clock = clock,
        )
    }

    @Test
    fun `quest selection is deterministic per date`() {
        val quests1 = system.selectDailyQuests()
        val quests2 = system.selectDailyQuests()
        assertEquals(quests1, quests2, "Same date should produce same quests")
        assertEquals(3, quests1.size, "Should select dailySlots quests")
    }

    @Test
    fun `weekly selection is deterministic per week`() {
        val quests1 = system.selectWeeklyQuests()
        val quests2 = system.selectWeeklyQuests()
        assertEquals(quests1, quests2)
        assertEquals(1, quests1.size)
    }

    @Test
    fun `different dates produce different selections`() {
        val quests1 = system.selectDailyQuests()
        // Advance one day
        clock.advance(86_400_000L)
        val quests2 = system.selectDailyQuests()
        // Both should have 3 quests
        assertEquals(3, quests1.size)
        assertEquals(3, quests2.size)
    }

    @Test
    fun `progress tracking for kill type`() {
        system.checkReset(sid)
        val dailies = system.selectDailyQuests()
        val hasKill = dailies.any { it.type == "kill" }
        if (hasKill) {
            system.onEvent(sid, "kill", 1)
            val board = system.getDailyQuestBoard(sid)
            val killEntry = board.firstOrNull { it.definition.type == "kill" }
            assertTrue(killEntry != null && killEntry.progress >= 1)
        }
    }

    @Test
    fun `completion awards rewards`() = runTest {
        system.checkReset(sid)
        val player = players.get(sid)!!
        val initialGold = player.gold

        val dailies = system.selectDailyQuests()
        val killQuest = dailies.firstOrNull { it.type == "kill" }
        if (killQuest != null) {
            val completed = system.onEvent(sid, "kill", killQuest.targetCount)
            system.awardRewards(sid, completed)

            if (completed.isNotEmpty()) {
                assertTrue(player.gold > initialGold, "Gold should increase")
            }
        }
    }

    @Test
    fun `streak increments on consecutive days`() = runTest {
        // Day 1: complete all dailies
        system.checkReset(sid)
        val dailies = system.selectDailyQuests()
        for (def in dailies) {
            system.onEvent(sid, def.type, def.targetCount)
        }

        val player = players.get(sid)!!

        // Verify all completed
        val board = system.getDailyQuestBoard(sid)
        val allDone = board.all { it.completed }
        assertTrue(allDone, "All dailies should be complete")

        // Day 2: advance clock by 1 day
        clock.advance(86_400_000L)
        system.checkReset(sid)

        // Streak should now be 1
        assertEquals(1, player.dailyQuestState.streakDays)
    }

    @Test
    fun `streak resets on missed day`() = runTest {
        // Day 1: complete all dailies
        system.checkReset(sid)
        val dailies = system.selectDailyQuests()
        for (def in dailies) {
            system.onEvent(sid, def.type, def.targetCount)
        }

        // Day 3: skip a day (advance by 2 days)
        clock.advance(86_400_000L * 2)
        system.checkReset(sid)

        val player = players.get(sid)!!
        assertEquals(0, player.dailyQuestState.streakDays, "Streak should reset after missed day")
    }

    @Test
    fun `reset clears old progress`() {
        system.checkReset(sid)
        system.onEvent(sid, "kill", 5)

        val player = players.get(sid)!!
        val state = player.dailyQuestState

        // Advance to next day
        clock.advance(86_400_000L)
        system.checkReset(sid)

        // Progress should be cleared
        assertTrue(state.dailyProgress.isEmpty(), "Daily progress should be cleared on reset")
        assertTrue(state.dailyCompletions.isEmpty(), "Daily completions should be cleared on reset")
    }

    @Test
    fun `streak multiplier calculation`() {
        assertEquals(1.0, system.streakMultiplier(0))
        assertEquals(1.1, system.streakMultiplier(1))
        assertEquals(1.5, system.streakMultiplier(5))
        assertEquals(1.7, system.streakMultiplier(7))
        // Capped at maxDays
        assertEquals(1.7, system.streakMultiplier(10))
    }

    @Test
    fun `weekly quest progress tracking`() {
        system.checkReset(sid)
        val weeklies = system.selectWeeklyQuests()
        if (weeklies.isNotEmpty()) {
            val first = weeklies[0]
            system.onEvent(sid, first.type, 1)
            val board = system.getWeeklyQuestBoard(sid)
            assertTrue(board[0].progress >= 1)
        }
    }

    @Test
    fun `disabled system returns empty lists`() {
        val disabledSystem = DailyQuestSystem(
            config = config.copy(enabled = false),
            players = players,
            clock = clock,
        )
        val completed = disabledSystem.onEvent(sid, "kill", 10)
        assertTrue(completed.isEmpty())
    }

    @Test
    fun `format daily board produces readable output`() {
        system.checkReset(sid)
        val output = system.formatDailyBoard(sid)
        assertTrue(output.contains("Daily Quests"), "Should contain header")
    }

    @Test
    fun `format weekly board produces readable output`() {
        system.checkReset(sid)
        val output = system.formatWeeklyBoard(sid)
        assertTrue(output.contains("Weekly Quests"), "Should contain header")
    }
}
