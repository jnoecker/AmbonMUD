package dev.ambon.engine

import dev.ambon.config.AutoQuestsConfig
import dev.ambon.domain.DamageRange
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.MobSpawn
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.World
import dev.ambon.engine.dialogue.DialogueNode
import dev.ambon.engine.dialogue.DialogueTree
import dev.ambon.test.MutableClock
import dev.ambon.test.SystemTestComponents
import dev.ambon.test.loginOrFail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class AutoQuestSystemTest {
    private val testZone = "test_zone"
    private val testRoomId = RoomId("$testZone:town_square")
    private val mobTemplateId = "$testZone:goblin"
    private val sid = SessionId(1L)

    private val testMobSpawn = MobSpawn(
        id = MobId(mobTemplateId),
        name = "Goblin",
        roomId = testRoomId,
        maxHp = 20,
        damage = DamageRange(1, 4),
        xpReward = 30L,
    )

    private val defaultConfig = AutoQuestsConfig(
        enabled = true,
        timeLimitMs = 600_000L,
        cooldownMs = 60_000L,
        rewardGoldBase = 50L,
        rewardGoldPerLevel = 10L,
        rewardXpBase = 100L,
        rewardXpPerLevel = 25L,
        killCountMin = 3,
        killCountMax = 3,
    )

    private fun setup(
        config: AutoQuestsConfig = defaultConfig,
        clockMs: Long = 1_000L,
    ): Triple<AutoQuestSystem, PlayerRegistry, MutableClock> {
        val c = SystemTestComponents(roomId = testRoomId, clockInitialMs = clockMs)
        val world = World(
            rooms = mapOf(testRoomId to Room(testRoomId, "Town Square", "A busy square.", emptyMap())),
            startRoom = testRoomId,
            mobSpawns = listOf(testMobSpawn),
        )
        val system = AutoQuestSystem(
            config = config,
            world = world,
            players = c.players,
            clock = c.clock,
            random = Random(42),
        )
        return Triple(system, c.players, c.clock)
    }

    @Test
    fun `requestQuest generates valid quest from zone mobs`() = runTest {
        val (system, players, _) = setup()
        players.loginOrFail(sid, "Hero")

        val result = system.requestQuest(sid)
        assertTrue(result.isSuccess)

        val quest = result.getOrThrow()
        assertEquals("Goblin", quest.targetMobName)
        assertEquals(mobTemplateId, quest.targetMobTemplateId)
        assertEquals(3, quest.killsRequired)
        assertEquals(0, quest.killsCompleted)
        assertEquals(60L, quest.rewardGold) // 50 + 10*1
        assertEquals(125L, quest.rewardXp) // 100 + 25*1
    }

    @Test
    fun `requestQuest fails when already active`() = runTest {
        val (system, players, _) = setup()
        players.loginOrFail(sid, "Hero")

        system.requestQuest(sid)
        val result = system.requestQuest(sid)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("already have") == true)
    }

    @Test
    fun `requestQuest fails during cooldown`() = runTest {
        val (system, players, clock) = setup()
        players.loginOrFail(sid, "Hero")

        // Get and complete a quest to trigger cooldown
        system.requestQuest(sid)
        repeat(3) { system.onMobKill(sid, mobTemplateId) }
        system.completeQuest(sid)

        // Still in cooldown
        val result = system.requestQuest(sid)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("wait") == true)

        // Advance past cooldown
        clock.advance(60_001)
        val result2 = system.requestQuest(sid)
        assertTrue(result2.isSuccess)
    }

    @Test
    fun `requestQuest fails when disabled`() = runTest {
        val (system, players, _) = setup(config = defaultConfig.copy(enabled = false))
        players.loginOrFail(sid, "Hero")

        val result = system.requestQuest(sid)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("not available") == true)
    }

    @Test
    fun `onMobKill increments progress`() = runTest {
        val (system, players, _) = setup()
        players.loginOrFail(sid, "Hero")
        system.requestQuest(sid)

        assertFalse(system.onMobKill(sid, mobTemplateId))
        assertEquals(1, system.getActiveQuest(sid)?.killsCompleted)

        assertFalse(system.onMobKill(sid, mobTemplateId))
        assertEquals(2, system.getActiveQuest(sid)?.killsCompleted)
    }

    @Test
    fun `onMobKill returns true when quest completed`() = runTest {
        val (system, players, _) = setup()
        players.loginOrFail(sid, "Hero")
        system.requestQuest(sid)

        system.onMobKill(sid, mobTemplateId)
        system.onMobKill(sid, mobTemplateId)
        assertTrue(system.onMobKill(sid, mobTemplateId))
    }

    @Test
    fun `onMobKill ignores wrong mob template`() = runTest {
        val (system, players, _) = setup()
        players.loginOrFail(sid, "Hero")
        system.requestQuest(sid)

        assertFalse(system.onMobKill(sid, "wrong_zone:wrong_mob"))
        assertEquals(0, system.getActiveQuest(sid)?.killsCompleted)
    }

    @Test
    fun `onMobKill no-ops without active quest`() = runTest {
        val (system, players, _) = setup()
        players.loginOrFail(sid, "Hero")

        assertFalse(system.onMobKill(sid, mobTemplateId))
    }

    @Test
    fun `tick expires quests past time limit`() = runTest {
        val (system, players, clock) = setup()
        players.loginOrFail(sid, "Hero")
        system.requestQuest(sid)

        // Not expired yet
        val expired1 = system.tick(clock.millis() + 599_999)
        assertTrue(expired1.isEmpty())
        assertNotNull(system.getActiveQuest(sid))

        // Expired
        val expired2 = system.tick(clock.millis() + 600_000)
        assertEquals(listOf(sid), expired2)
        assertNull(system.getActiveQuest(sid))
    }

    @Test
    fun `completeQuest removes quest and sets cooldown`() = runTest {
        val (system, players, _) = setup()
        players.loginOrFail(sid, "Hero")
        system.requestQuest(sid)

        val quest = system.completeQuest(sid)
        assertNotNull(quest)
        assertNull(system.getActiveQuest(sid))

        // Cooldown should be active
        val result = system.requestQuest(sid)
        assertTrue(result.isFailure)
    }

    @Test
    fun `abandonQuest clears active quest`() = runTest {
        val (system, players, _) = setup()
        players.loginOrFail(sid, "Hero")
        system.requestQuest(sid)

        assertTrue(system.abandonQuest(sid))
        assertNull(system.getActiveQuest(sid))
    }

    @Test
    fun `abandonQuest returns false when no quest`() = runTest {
        val (system, players, _) = setup()
        players.loginOrFail(sid, "Hero")

        assertFalse(system.abandonQuest(sid))
    }

    @Test
    fun `abandonQuest sets cooldown`() = runTest {
        val (system, players, _) = setup()
        players.loginOrFail(sid, "Hero")
        system.requestQuest(sid)
        system.abandonQuest(sid)

        val result = system.requestQuest(sid)
        assertTrue(result.isFailure)
    }

    @Test
    fun `onPlayerDisconnected cleans up`() = runTest {
        val (system, players, _) = setup()
        players.loginOrFail(sid, "Hero")
        system.requestQuest(sid)

        system.onPlayerDisconnected(sid)
        assertNull(system.getActiveQuest(sid))
    }

    @Test
    fun `timeRemainingMs returns correct value`() = runTest {
        val (system, players, clock) = setup(clockMs = 10_000L)
        players.loginOrFail(sid, "Hero")
        system.requestQuest(sid)

        val remaining = system.timeRemainingMs(sid)
        assertNotNull(remaining)
        assertEquals(600_000L, remaining)

        clock.advance(100_000)
        assertEquals(500_000L, system.timeRemainingMs(sid))
    }

    @Test
    fun `timeRemainingMs returns null without quest`() = runTest {
        val (system, players, _) = setup()
        players.loginOrFail(sid, "Hero")

        assertNull(system.timeRemainingMs(sid))
    }

    @Test
    fun `requestQuest fails when no mobs in zone`() = runTest {
        val emptyRoomId = RoomId("empty_zone:room1")
        val c = SystemTestComponents(roomId = emptyRoomId, clockInitialMs = 1_000L)
        val world = World(
            rooms = mapOf(emptyRoomId to Room(emptyRoomId, "Empty", "Empty room.", emptyMap())),
            startRoom = emptyRoomId,
            mobSpawns = emptyList(),
        )
        val system = AutoQuestSystem(
            config = defaultConfig,
            world = world,
            players = c.players,
            clock = c.clock,
        )
        c.players.loginOrFail(sid, "Hero")

        val result = system.requestQuest(sid)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("No suitable targets") == true)
    }

    @Test
    fun `reward scales with player level`() = runTest {
        val (system, players, _) = setup()
        players.loginOrFail(sid, "Hero")
        val ps = players.get(sid)!!
        ps.level = 10

        val result = system.requestQuest(sid)
        assertTrue(result.isSuccess)
        val quest = result.getOrThrow()
        assertEquals(150L, quest.rewardGold) // 50 + 10*10
        assertEquals(350L, quest.rewardXp) // 100 + 25*10
    }

    @Test
    fun `mobs with dialogue are excluded from candidates`() = runTest {
        val c = SystemTestComponents(roomId = testRoomId, clockInitialMs = 1_000L)
        val dialogueMob = MobSpawn(
            id = MobId("$testZone:innkeeper"),
            name = "Innkeeper",
            roomId = testRoomId,
            maxHp = 100,
            xpReward = 0L,
            dialogue = DialogueTree(
                rootNodeId = "root",
                nodes = mapOf(
                    "root" to DialogueNode(
                        text = "Welcome!",
                        choices = emptyList(),
                    ),
                ),
            ),
        )
        val world = World(
            rooms = mapOf(testRoomId to Room(testRoomId, "Town Square", "A busy square.", emptyMap())),
            startRoom = testRoomId,
            mobSpawns = listOf(dialogueMob),
        )
        val system = AutoQuestSystem(
            config = defaultConfig,
            world = world,
            players = c.players,
            clock = c.clock,
        )
        c.players.loginOrFail(sid, "Hero")

        val result = system.requestQuest(sid)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("No suitable targets") == true)
    }

    @Test
    fun `remapSession transfers quest to new session`() = runTest {
        val (system, players, _) = setup()
        players.loginOrFail(sid, "Hero")
        system.requestQuest(sid)

        val newSid = SessionId(2L)
        system.remapSession(sid, newSid)

        assertNull(system.getActiveQuest(sid))
        assertNotNull(system.getActiveQuest(newSid))
        assertEquals(newSid, system.getActiveQuest(newSid)?.sessionId)
    }
}
