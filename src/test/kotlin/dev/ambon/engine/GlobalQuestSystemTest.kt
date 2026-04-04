package dev.ambon.engine

import dev.ambon.config.GlobalQuestObjectiveConfig
import dev.ambon.config.GlobalQuestsConfig
import dev.ambon.domain.ids.SessionId
import dev.ambon.test.MutableClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GlobalQuestSystemTest {
    private val sid1 = SessionId(1L)
    private val sid2 = SessionId(2L)
    private val sid3 = SessionId(3L)
    private val sid4 = SessionId(4L)

    private val names = mutableMapOf(
        sid1 to "Alice",
        sid2 to "Bob",
        sid3 to "Charlie",
        sid4 to "Dave",
    )

    private var onlineCount = 3
    private val clock = MutableClock(0L)

    private val defaultConfig = GlobalQuestsConfig(
        enabled = true,
        intervalMs = 1000L,
        durationMs = 5000L,
        announceIntervalMs = 2000L,
        minPlayersOnline = 2,
        rewardGoldFirst = 2000L,
        rewardGoldSecond = 1000L,
        rewardGoldThird = 500L,
        rewardXpFirst = 5000L,
        rewardXpSecond = 2500L,
        rewardXpThird = 1000L,
        objectives = listOf(
            GlobalQuestObjectiveConfig(type = "kill", targetCount = 5, description = "Slay 5 creatures"),
        ),
    )

    private lateinit var system: GlobalQuestSystem

    @BeforeEach
    fun setUp() {
        clock.set(0L)
        onlineCount = 3
        system = GlobalQuestSystem(
            config = defaultConfig,
            clock = clock,
            playerCount = { onlineCount },
            resolvePlayerName = { sid -> names[sid] },
        )
    }

    @Test
    fun `quest starts when interval elapsed and enough players online`() {
        clock.set(1000L) // interval elapsed
        val result = system.tick()
        assertTrue(result is GlobalQuestTickResult.QuestStarted)
        val started = result as GlobalQuestTickResult.QuestStarted
        assertTrue(started.announcement.contains("Slay 5 creatures"))
        assertNotNull(system.getStatus())
    }

    @Test
    fun `quest does not start with too few players`() {
        onlineCount = 1
        clock.set(1000L)
        val result = system.tick()
        assertTrue(result is GlobalQuestTickResult.Nothing)
        assertNull(system.getStatus())
    }

    @Test
    fun `quest does not start when disabled`() {
        val disabledSystem = GlobalQuestSystem(
            config = defaultConfig.copy(enabled = false),
            clock = clock,
            playerCount = { 5 },
            resolvePlayerName = { "test" },
        )
        clock.set(1000L)
        val result = disabledSystem.tick()
        assertTrue(result is GlobalQuestTickResult.Nothing)
    }

    @Test
    fun `progress tracking works`() {
        clock.set(1000L)
        system.tick() // start quest

        system.onEvent(sid1, GlobalQuestObjectiveType.KILL)
        system.onEvent(sid1, GlobalQuestObjectiveType.KILL)
        system.onEvent(sid2, GlobalQuestObjectiveType.KILL, 3)

        assertEquals(2, system.getPlayerProgress(sid1))
        assertEquals(3, system.getPlayerProgress(sid2))
        assertNull(system.getPlayerProgress(sid3))
    }

    @Test
    fun `wrong objective type is ignored`() {
        clock.set(1000L)
        system.tick() // start kill quest

        val completed = system.onEvent(sid1, GlobalQuestObjectiveType.GATHER, 10)
        assertEquals(false, completed)
        assertNull(system.getPlayerProgress(sid1))
    }

    @Test
    fun `first to target completes the quest`() {
        clock.set(1000L)
        system.tick() // start quest

        system.onEvent(sid1, GlobalQuestObjectiveType.KILL, 3)
        system.onEvent(sid2, GlobalQuestObjectiveType.KILL, 2)
        val hit = system.onEvent(sid1, GlobalQuestObjectiveType.KILL, 2) // sid1 now has 5
        assertTrue(hit)

        // Next tick should end the quest
        clock.advance(100L)
        val result = system.tick()
        assertTrue(result is GlobalQuestTickResult.QuestEnded)
        val ended = result as GlobalQuestTickResult.QuestEnded
        assertTrue(ended.announcement.contains("Alice"))
        assertEquals(2, ended.winners.size)
        assertEquals("Alice", ended.winners[0].playerName)
        assertEquals(5, ended.winners[0].progress)
        assertEquals(1, ended.winners[0].place)
        assertEquals(2000L, ended.winners[0].goldReward)
        assertEquals(5000L, ended.winners[0].xpReward)
        assertEquals("Bob", ended.winners[1].playerName)
        assertEquals(2, ended.winners[1].place)
        assertEquals(1000L, ended.winners[1].goldReward)
        assertEquals(2500L, ended.winners[1].xpReward)
    }

    @Test
    fun `quest ends on timeout with highest scorer winning`() {
        clock.set(1000L)
        system.tick() // start quest

        system.onEvent(sid1, GlobalQuestObjectiveType.KILL, 3)
        system.onEvent(sid2, GlobalQuestObjectiveType.KILL, 4)
        system.onEvent(sid3, GlobalQuestObjectiveType.KILL, 1)

        // Advance past duration (5000ms)
        clock.advance(5001L)
        val result = system.tick()
        assertTrue(result is GlobalQuestTickResult.QuestEnded)
        val ended = result as GlobalQuestTickResult.QuestEnded
        assertEquals(3, ended.winners.size)
        assertEquals("Bob", ended.winners[0].playerName) // highest at 4
        assertEquals("Alice", ended.winners[1].playerName) // 3
        assertEquals("Charlie", ended.winners[2].playerName) // 1
        assertEquals(500L, ended.winners[2].goldReward) // 3rd place
        assertEquals(1000L, ended.winners[2].xpReward)
    }

    @Test
    fun `quest ends with no participants`() {
        clock.set(1000L)
        system.tick() // start quest

        // No events, advance past duration
        clock.advance(5001L)
        val result = system.tick()
        assertTrue(result is GlobalQuestTickResult.QuestEnded)
        val ended = result as GlobalQuestTickResult.QuestEnded
        assertTrue(ended.announcement.contains("No participants"))
        assertTrue(ended.winners.isEmpty())
    }

    @Test
    fun `progress announcements fire at correct intervals`() {
        clock.set(1000L)
        system.tick() // start quest at t=1000

        system.onEvent(sid1, GlobalQuestObjectiveType.KILL, 2)

        // At t=2500, less than announceIntervalMs (2000) from start (1000) -> nothing
        clock.set(2500L)
        val r1 = system.tick()
        assertTrue(r1 is GlobalQuestTickResult.Nothing)

        // At t=3001, more than 2000ms from start -> progress update
        clock.set(3001L)
        val r2 = system.tick()
        assertTrue(r2 is GlobalQuestTickResult.ProgressUpdate)
        val update = r2 as GlobalQuestTickResult.ProgressUpdate
        assertTrue(update.announcement.contains("Alice"))
        assertTrue(update.announcement.contains("2/5"))
    }

    @Test
    fun `rewards are correctly assigned to top 3`() {
        clock.set(1000L)
        system.tick()

        system.onEvent(sid1, GlobalQuestObjectiveType.KILL, 4)
        system.onEvent(sid2, GlobalQuestObjectiveType.KILL, 3)
        system.onEvent(sid3, GlobalQuestObjectiveType.KILL, 2)
        system.onEvent(sid4, GlobalQuestObjectiveType.KILL, 1)

        clock.advance(5001L)
        val result = system.tick() as GlobalQuestTickResult.QuestEnded

        // Only top 3 get rewards
        assertEquals(3, result.winners.size)
        assertEquals(2000L, result.winners[0].goldReward)
        assertEquals(1000L, result.winners[1].goldReward)
        assertEquals(500L, result.winners[2].goldReward)
    }

    @Test
    fun `leaderboard shows sorted entries`() {
        clock.set(1000L)
        system.tick()

        system.onEvent(sid1, GlobalQuestObjectiveType.KILL, 1)
        system.onEvent(sid2, GlobalQuestObjectiveType.KILL, 3)
        system.onEvent(sid3, GlobalQuestObjectiveType.KILL, 2)

        val leaderboard = system.getLeaderboard()
        assertEquals(3, leaderboard.size)
        assertEquals("Bob", leaderboard[0].playerName)
        assertEquals(3, leaderboard[0].progress)
        assertEquals("Charlie", leaderboard[1].playerName)
        assertEquals("Alice", leaderboard[2].playerName)
    }

    @Test
    fun `no event tracking after quest completes`() {
        clock.set(1000L)
        system.tick()

        system.onEvent(sid1, GlobalQuestObjectiveType.KILL, 5) // completes
        clock.advance(100L)
        system.tick() // ends quest

        // Further events ignored
        val result = system.onEvent(sid1, GlobalQuestObjectiveType.KILL, 1)
        assertEquals(false, result)
    }

    @Test
    fun `new quest starts after interval from previous end`() {
        clock.set(1000L)
        system.tick() // start quest

        // End via timeout
        clock.advance(5001L)
        system.tick() // end quest at ~6001

        // Not enough time for new quest
        clock.advance(500L)
        val r1 = system.tick()
        assertTrue(r1 is GlobalQuestTickResult.Nothing)

        // Enough time for new quest
        clock.advance(600L) // total > 1000 from end
        val r2 = system.tick()
        assertTrue(r2 is GlobalQuestTickResult.QuestStarted)
    }

    @Test
    fun `getStatus returns null when no quest active`() {
        assertNull(system.getStatus())
    }

    @Test
    fun `getStatus returns correct information during active quest`() {
        clock.set(1000L)
        system.tick()

        system.onEvent(sid1, GlobalQuestObjectiveType.KILL, 2)

        val status = system.getStatus()
        assertNotNull(status)
        assertEquals(GlobalQuestObjectiveType.KILL, status!!.objective.type)
        assertEquals(5, status.objective.targetCount)
        assertEquals(false, status.completed)
        assertEquals(1, status.leaderboard.size)
        assertEquals("Alice", status.leaderboard[0].playerName)
        assertEquals(2, status.leaderboard[0].progress)
    }

    @Test
    fun `empty objectives list prevents quest start`() {
        val noObjSystem = GlobalQuestSystem(
            config = defaultConfig.copy(objectives = emptyList()),
            clock = clock,
            playerCount = { 5 },
            resolvePlayerName = { "test" },
        )
        clock.set(1000L)
        val result = noObjSystem.tick()
        assertTrue(result is GlobalQuestTickResult.Nothing)
    }
}
