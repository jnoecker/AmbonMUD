package dev.ambon.engine

import dev.ambon.config.LeaderboardConfig
import dev.ambon.domain.crafting.CraftingSkillState
import dev.ambon.domain.ids.RoomId
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.persistence.PlayerId
import dev.ambon.persistence.PlayerRecord
import dev.ambon.test.MutableClock
import dev.ambon.test.TEST_ROOM_ID
import dev.ambon.test.buildTestPlayerRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LeaderboardSystemTest {
    private val repo = InMemoryPlayerRepository()
    private val clock = MutableClock(0L)
    private val registry = buildTestPlayerRegistry(TEST_ROOM_ID, repo, clock = clock)
    private val system = LeaderboardSystem(
        playerRepo = repo,
        playerRegistry = registry,
        config = LeaderboardConfig(topN = 3),
    )

    @BeforeEach
    fun setUp() {
        repo.clear()
    }

    private suspend fun savePlayer(
        id: Long,
        name: String,
        level: Int = 1,
        achievements: Int = 0,
        craftLevel: Int = 0,
        dungeons: Int = 0,
        kills: Long = 0L,
        isStaff: Boolean = false,
    ) {
        val record = PlayerRecord(
            id = PlayerId(id),
            name = name,
            roomId = RoomId("test:room"),
            createdAtEpochMs = 0L,
            lastSeenEpochMs = 0L,
            level = level,
            unlockedAchievementIds = (1..achievements).map { "ach$it" }.toSet(),
            craftingSkills = if (craftLevel > 0) mapOf("smithing" to CraftingSkillState(level = craftLevel)) else emptyMap(),
            dungeonsCompleted = dungeons,
            mobsKilledTotal = kills,
            isStaff = isStaff,
        )
        repo.save(record)
    }

    @Test
    fun `level leaderboard ranks by level descending`() =
        runTest {
            savePlayer(1, "Alpha", level = 5)
            savePlayer(2, "Beta", level = 10)
            savePlayer(3, "Gamma", level = 3)

            system.refresh()

            val entries = system.getEntries(LeaderboardSystem.Category.LEVEL)
            assertEquals(3, entries.size)
            assertEquals("Beta", entries[0].playerName)
            assertEquals(10L, entries[0].score)
            assertEquals(1, entries[0].rank)
            assertEquals("Alpha", entries[1].playerName)
            assertEquals("Gamma", entries[2].playerName)
        }

    @Test
    fun `achievements leaderboard ranks by achievement count`() =
        runTest {
            savePlayer(1, "Alpha", achievements = 3)
            savePlayer(2, "Beta", achievements = 7)
            savePlayer(3, "Gamma", achievements = 1)

            system.refresh()

            val entries = system.getEntries(LeaderboardSystem.Category.ACHIEVEMENTS)
            assertEquals("Beta", entries[0].playerName)
            assertEquals(7L, entries[0].score)
        }

    @Test
    fun `kills leaderboard ranks by mob kill total`() =
        runTest {
            savePlayer(1, "Hunter", kills = 150L)
            savePlayer(2, "Slayer", kills = 500L)
            savePlayer(3, "Novice", kills = 10L)

            system.refresh()

            val entries = system.getEntries(LeaderboardSystem.Category.KILLS)
            assertEquals("Slayer", entries[0].playerName)
            assertEquals(500L, entries[0].score)
            assertEquals(3, entries.size)
        }

    @Test
    fun `dungeons leaderboard ranks by dungeons completed`() =
        runTest {
            savePlayer(1, "Delver", dungeons = 8)
            savePlayer(2, "Casual", dungeons = 2)

            system.refresh()

            val entries = system.getEntries(LeaderboardSystem.Category.DUNGEONS)
            assertEquals("Delver", entries[0].playerName)
            assertEquals(8L, entries[0].score)
        }

    @Test
    fun `crafting leaderboard uses max skill level`() =
        runTest {
            savePlayer(1, "Smith", craftLevel = 10)
            savePlayer(2, "Novice", craftLevel = 2)
            savePlayer(3, "Master", craftLevel = 15)

            system.refresh()

            val entries = system.getEntries(LeaderboardSystem.Category.CRAFTING)
            assertEquals("Master", entries[0].playerName)
            assertEquals(15L, entries[0].score)
        }

    @Test
    fun `topN limits results`() =
        runTest {
            for (i in 1..10) savePlayer(i.toLong(), "Player$i", level = i)

            system.refresh()

            val entries = system.getEntries(LeaderboardSystem.Category.LEVEL)
            assertEquals(3, entries.size)
        }

    @Test
    fun `empty leaderboard returns empty list before refresh`() {
        val entries = system.getEntries(LeaderboardSystem.Category.LEVEL)
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `staff players are excluded from leaderboards`() =
        runTest {
            savePlayer(1, "Admin", level = 99, isStaff = true)
            savePlayer(2, "Player", level = 5)

            system.refresh()

            val entries = system.getEntries(LeaderboardSystem.Category.LEVEL)
            assertEquals(1, entries.size)
            assertEquals("Player", entries[0].playerName)
        }

    @Test
    fun `Category fromKey resolves by exact and prefix match`() {
        assertEquals(LeaderboardSystem.Category.LEVEL, LeaderboardSystem.Category.fromKey("level"))
        assertEquals(LeaderboardSystem.Category.KILLS, LeaderboardSystem.Category.fromKey("kills"))
        assertEquals(null, LeaderboardSystem.Category.fromKey("nonexistent"))
    }

    @Test
    fun `rank indexes start at 1`() =
        runTest {
            savePlayer(1, "Alpha", level = 5)
            savePlayer(2, "Beta", level = 3)

            system.refresh()

            val entries = system.getEntries(LeaderboardSystem.Category.LEVEL)
            assertEquals(1, entries[0].rank)
            assertEquals(2, entries[1].rank)
        }
}
