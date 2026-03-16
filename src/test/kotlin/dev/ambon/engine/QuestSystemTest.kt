package dev.ambon.engine

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.quest.QuestDef
import dev.ambon.domain.quest.QuestObjectiveDef
import dev.ambon.domain.quest.QuestRewards
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.test.SystemTestComponents
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
                        type = "kill",
                        targetId = mobTemplateKey,
                        count = 3,
                        description = "Kill 3 target mobs",
                    ),
                ),
            rewards = QuestRewards(xp = 100L, gold = 20L),
            completionType = "auto",
        )

    private fun setup(): Triple<QuestSystem, PlayerRegistry, LocalOutboundBus> {
        val c = SystemTestComponents(clockInitialMs = 1_000L)
        val registry = QuestRegistry()
        registry.register(killQuest)
        val questSystem =
            QuestSystem(
                registry = registry,
                players = c.players,
                items = c.items,
                outbound = c.outbound,
                clock = c.clock,
            )
        return Triple(questSystem, c.players, c.outbound)
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
            assertEquals(100L, ps.xpTotal, "Player should have received XP reward")

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

    @Test
    fun `onItemCollected increments collect objective`() =
        runTest {
            val c = SystemTestComponents(clockInitialMs = 1_000L)
            val registry = QuestRegistry()
            val collectItemId = "zone:shiny_rock"
            val collectQuestId = "zone:collect_quest"
            val collectQuest =
                QuestDef(
                    id = collectQuestId,
                    name = "Collect Rocks",
                    description = "Gather shiny rocks.",
                    giverMobId = "zone:quest_giver",
                    objectives =
                        listOf(
                            QuestObjectiveDef(
                                type = "collect",
                                targetId = collectItemId,
                                count = 2,
                                description = "Collect 2 shiny rocks",
                            ),
                        ),
                    rewards = QuestRewards(xp = 50L, gold = 10L),
                    completionType = "auto",
                )
            registry.register(collectQuest)
            val qs =
                QuestSystem(
                    registry = registry,
                    players = c.players,
                    items = c.items,
                    outbound = c.outbound,
                    clock = c.clock,
                )

            val sid = SessionId(2L)
            c.players.loginOrFail(sid, "Gatherer")
            qs.acceptQuest(sid, collectQuestId)

            val rock = ItemInstance(id = ItemId(collectItemId), item = Item(keyword = "rock", displayName = "a shiny rock"))
            c.items.addToInventory(sid, rock)
            qs.onItemCollected(sid, rock)

            val ps = c.players.get(sid)!!
            assertEquals(1, ps.activeQuests[collectQuestId]!!.objectives[0].current, "Collecting one item should advance progress to 1")
        }

    @Test
    fun `formatQuestLog shows multiple quests on separate lines`() =
        runTest {
            val (qs, players, _) = setup()
            val sid = SessionId(1L)
            players.loginOrFail(sid, "Hero")

            val secondQuestId = "zone:bonus_quest"
            val registry = QuestRegistry()
            registry.register(killQuest)
            val secondQuest =
                QuestDef(
                    id = secondQuestId,
                    name = "Bonus Quest",
                    description = "Do extra stuff.",
                    giverMobId = "zone:quest_giver",
                    objectives =
                        listOf(
                            QuestObjectiveDef(
                                type = "kill",
                                targetId = mobTemplateKey,
                                count = 1,
                                description = "Kill 1 target mob",
                            ),
                        ),
                    rewards = QuestRewards(xp = 50L),
                    completionType = "auto",
                )
            registry.register(secondQuest)
            val c2 = SystemTestComponents(clockInitialMs = 1_000L)
            val qs2 =
                QuestSystem(
                    registry = registry,
                    players = c2.players,
                    items = c2.items,
                    outbound = c2.outbound,
                    clock = c2.clock,
                )

            val sid2 = SessionId(3L)
            c2.players.loginOrFail(sid2, "Hero2")
            qs2.acceptQuest(sid2, questId)
            qs2.acceptQuest(sid2, secondQuestId)

            val log = qs2.formatQuestLog(sid2)
            assertTrue(log.contains("Kill Quest"), "Log should contain Kill Quest")
            assertTrue(log.contains("Bonus Quest"), "Log should contain Bonus Quest")
            // Each quest name must appear on its own line (preceded by a newline + indent)
            assertTrue(log.contains("\n  Bonus Quest"), "Bonus Quest must start on its own line")
        }
}
