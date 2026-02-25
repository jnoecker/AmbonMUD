package dev.ambon.engine

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.quest.CompletionType
import dev.ambon.domain.quest.ObjectiveType
import dev.ambon.domain.quest.QuestDef
import dev.ambon.domain.quest.QuestObjectiveDef
import dev.ambon.domain.quest.QuestRewards
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.MutableClock
import dev.ambon.test.drainAll
import dev.ambon.test.loginOrFail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QuestSystemTest {
    private val roomId = RoomId("zone:room")
    private val questId = "zone:kill_quest"
    private val mobTemplateKey = "zone:target_mob"
    private val killQuest =
        QuestDef(
            id = questId,
            name = "Kill Quest",
            description = "Kill some mobs.",
            giverMobId = "zone:quest_giver",
            objectives =
                listOf(
                    QuestObjectiveDef(
                        type = ObjectiveType.KILL,
                        targetId = mobTemplateKey,
                        count = 3,
                        description = "Kill 3 target mobs",
                    ),
                ),
            rewards = QuestRewards(xp = 100L, gold = 20L),
            completionType = CompletionType.AUTO,
        )

    private fun setup(): Triple<QuestSystem, PlayerRegistry, LocalOutboundBus> {
        val items = ItemRegistry()
        val players = PlayerRegistry(roomId, InMemoryPlayerRepository(), items)
        val outbound = LocalOutboundBus()
        val clock = MutableClock(1_000L)
        val registry = QuestRegistry()
        registry.register(killQuest)
        val questSystem =
            QuestSystem(
                registry = registry,
                players = players,
                items = items,
                outbound = outbound,
                clock = clock,
            )
        return Triple(questSystem, players, outbound)
    }

    @Test
    fun `acceptQuest adds quest to active quests`() =
        runTest {
            val (qs, players, outbound) = setup()
            val sid = SessionId(1L)
            players.loginOrFail(sid, "Hero")

            val err = qs.acceptQuest(sid, questId)
            assertNull(err, "Expected no error on accept")

            val ps = players.get(sid)!!
            assertTrue(ps.activeQuests.containsKey(questId))
            assertEquals(0, ps.activeQuests[questId]!!.objectives[0].current)
            assertEquals(3, ps.activeQuests[questId]!!.objectives[0].required)
        }

    @Test
    fun `acceptQuest fails if already active`() =
        runTest {
            val (qs, players, _) = setup()
            val sid = SessionId(1L)
            players.loginOrFail(sid, "Hero")

            qs.acceptQuest(sid, questId)
            val err = qs.acceptQuest(sid, questId)
            assertNotNull(err, "Expected error when accepting already-active quest")
        }

    @Test
    fun `onMobKilled increments kill objective`() =
        runTest {
            val (qs, players, _) = setup()
            val sid = SessionId(1L)
            players.loginOrFail(sid, "Hero")
            qs.acceptQuest(sid, questId)

            qs.onMobKilled(sid, mobTemplateKey)

            val ps = players.get(sid)!!
            assertEquals(1, ps.activeQuests[questId]!!.objectives[0].current)
        }

    @Test
    fun `quest auto-completes and grants rewards when all objectives done`() =
        runTest {
            val (qs, players, outbound) = setup()
            val sid = SessionId(1L)
            players.loginOrFail(sid, "Hero")
            qs.acceptQuest(sid, questId)
            outbound.drainAll() // clear accept messages

            repeat(3) { qs.onMobKilled(sid, mobTemplateKey) }

            val ps = players.get(sid)!!
            assertFalse(ps.activeQuests.containsKey(questId), "Quest should be removed from active")
            assertTrue(ps.completedQuestIds.contains(questId), "Quest should be in completed set")
            assertEquals(20L, ps.gold, "Player should have received gold reward")

            val events = outbound.drainAll()
            val texts =
                events.filterIsInstance<OutboundEvent.SendText>().map { it.text } +
                    events.filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
            assertTrue(texts.any { it.contains("complete", ignoreCase = true) })
        }

    @Test
    fun `abandonQuest removes active quest`() =
        runTest {
            val (qs, players, _) = setup()
            val sid = SessionId(1L)
            players.loginOrFail(sid, "Hero")
            qs.acceptQuest(sid, questId)

            val err = qs.abandonQuest(sid, "Kill")
            assertNull(err, "Expected no error on abandon")

            val ps = players.get(sid)!!
            assertFalse(ps.activeQuests.containsKey(questId))
        }

    @Test
    fun `abandonQuest returns error for unknown quest`() =
        runTest {
            val (qs, players, _) = setup()
            val sid = SessionId(1L)
            players.loginOrFail(sid, "Hero")

            val err = qs.abandonQuest(sid, "NoSuchQuest")
            assertNotNull(err, "Expected error for unknown quest")
        }

    @Test
    fun `availableQuests filters out completed and active quests`() =
        runTest {
            val (qs, players, _) = setup()
            val sid = SessionId(1L)
            players.loginOrFail(sid, "Hero")

            val available = qs.availableQuests(sid, "zone:quest_giver")
            assertEquals(1, available.size)

            qs.acceptQuest(sid, questId)
            val afterAccept = qs.availableQuests(sid, "zone:quest_giver")
            assertEquals(0, afterAccept.size, "Active quest should not appear as available")
        }

    @Test
    fun `formatQuestLog shows active quests`() =
        runTest {
            val (qs, players, _) = setup()
            val sid = SessionId(1L)
            players.loginOrFail(sid, "Hero")
            qs.acceptQuest(sid, questId)

            val log = qs.formatQuestLog(sid)
            assertTrue(log.contains("Kill Quest"), "Log should contain quest name")
            assertTrue(log.contains("0/3"), "Log should show objective progress")
        }

    @Test
    fun `formatQuestInfo shows quest details`() =
        runTest {
            val (qs, players, _) = setup()
            val sid = SessionId(1L)
            players.loginOrFail(sid, "Hero")
            qs.acceptQuest(sid, questId)

            val info = qs.formatQuestInfo(sid, "Kill")
            assertTrue(info.contains("Kill Quest"))
            assertTrue(info.contains("Kill some mobs"))
        }

    @Test
    fun `mob kill with wrong templateKey does not advance quest`() =
        runTest {
            val (qs, players, _) = setup()
            val sid = SessionId(1L)
            players.loginOrFail(sid, "Hero")
            qs.acceptQuest(sid, questId)

            qs.onMobKilled(sid, "zone:wrong_mob")

            val ps = players.get(sid)!!
            assertEquals(0, ps.activeQuests[questId]!!.objectives[0].current)
        }

    @Test
    fun `completed quest cannot be accepted again`() =
        runTest {
            val (qs, players, _) = setup()
            val sid = SessionId(1L)
            players.loginOrFail(sid, "Hero")
            qs.acceptQuest(sid, questId)
            repeat(3) { qs.onMobKilled(sid, mobTemplateKey) }

            val ps = players.get(sid)!!
            assertTrue(ps.completedQuestIds.contains(questId))

            val err = qs.acceptQuest(sid, questId)
            assertNotNull(err, "Should not be able to accept a completed quest")
        }
}
