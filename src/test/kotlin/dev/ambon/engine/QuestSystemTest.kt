package dev.ambon.engine

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.config.FactionConfig
import dev.ambon.config.FactionDefinition
import dev.ambon.config.QuestDifficulty
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.quest.QuestDef
import dev.ambon.domain.quest.QuestObjectiveDef
import dev.ambon.domain.quest.QuestRewards
import dev.ambon.domain.world.ReputationRequirement
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

    private fun setup(
        quest: QuestDef = killQuest,
        progression: PlayerProgression? = null,
        world: dev.ambon.domain.world.World? = null,
    ): Triple<QuestSystem, PlayerRegistry, LocalOutboundBus> {
        val c = SystemTestComponents(clockInitialMs = 1_000L)
        val registry = QuestRegistry()
        registry.register(quest)
        val questSystem =
            QuestSystem(
                registry = registry,
                players = c.players,
                items = c.items,
                outbound = c.outbound,
                clock = c.clock,
                progression = progression,
                world = world,
            )
        return Triple(questSystem, c.players, c.outbound)
    }

    @Test
    fun `acceptQuest adds quest to active quests`() =
        runTest {
            val (qs, players, _) = setup()
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
    fun `quest XP diminishes when player is far above quest level`() =
        runTest {
            val progression = PlayerProgression()
            val leveledQuest = killQuest.copy(level = 1, rewards = QuestRewards(xp = 1000L, gold = 0L))
            val (qs, players, outbound) = setup(leveledQuest, progression)
            val sid = SessionId(1L)
            players.loginOrFail(sid, "Hero")
            players.setLevel(sid, 10)
            val xpAtLevel10 = players.get(sid)!!.xpTotal
            qs.acceptQuest(sid, questId)
            outbound.drainAll()

            repeat(3) { qs.onMobKilled(sid, mobTemplateKey) }

            val ps = players.get(sid)!!
            assertTrue(
                ps.completedQuestIds.contains(questId),
                "Quest should complete regardless of XP diminishing",
            )
            val xpGained = ps.xpTotal - xpAtLevel10
            assertTrue(
                xpGained < 1000L,
                "Overleveled quest XP should be reduced (got $xpGained, expected << 1000)",
            )
        }

    @Test
    fun `quest XP is computed from difficulty when rewards xp is zero`() =
        runTest {
            val progression = PlayerProgression()
            val tieredQuest =
                killQuest.copy(
                    level = 3,
                    difficulty = QuestDifficulty.STANDARD,
                    rewards = QuestRewards(xp = 0L, gold = 0L),
                )
            val (qs, players, outbound) = setup(tieredQuest, progression)
            val sid = SessionId(1L)
            players.loginOrFail(sid, "Hero")
            players.setLevel(sid, 3)
            val xpBefore = players.get(sid)!!.xpTotal
            qs.acceptQuest(sid, questId)
            outbound.drainAll()

            repeat(3) { qs.onMobKilled(sid, mobTemplateKey) }

            val ps = players.get(sid)!!
            assertTrue(ps.completedQuestIds.contains(questId))
            val xpGained = ps.xpTotal - xpBefore
            // STANDARD × (50 + 20*2) = 90 at level 3
            assertEquals(90L, xpGained, "Quest XP should be computed from difficulty baseline")
        }

    @Test
    fun `quest XP scales to player level when zone is PLAYER-scaled`() =
        runTest {
            val progression = PlayerProgression()
            val tieredQuest =
                killQuest.copy(
                    level = 1, // authored at level 1
                    difficulty = dev.ambon.config.QuestDifficulty.STANDARD,
                    rewards = QuestRewards(xp = 0L, gold = 0L),
                )
            val world = dev.ambon.domain.world.World(
                rooms = emptyMap(),
                startRoom = dev.ambon.domain.ids.RoomId("zone:start"),
                zoneScaling = mapOf(
                    "zone" to dev.ambon.domain.world.ZoneScaling(
                        mode = dev.ambon.domain.world.ScalingMode.PLAYER,
                    ),
                ),
            )
            val (qs, players, outbound) = setup(tieredQuest, progression, world)
            val sid = SessionId(1L)
            players.loginOrFail(sid, "Hero")
            players.setLevel(sid, 10)
            val xpBefore = players.get(sid)!!.xpTotal
            qs.acceptQuest(sid, questId)
            outbound.drainAll()

            repeat(3) { qs.onMobKilled(sid, mobTemplateKey) }

            val ps = players.get(sid)!!
            val xpGained = ps.xpTotal - xpBefore
            // STANDARD at level 10 = (50 + 20*9) = 230 (scaled to player, not authored level 1)
            assertEquals(230L, xpGained)
        }

    @Test
    fun `quest XP clamps to zone bounds when BOUNDED-scaled`() =
        runTest {
            val progression = PlayerProgression()
            val tieredQuest =
                killQuest.copy(
                    level = 3, // authored — irrelevant in bounded mode
                    difficulty = dev.ambon.config.QuestDifficulty.STANDARD,
                    rewards = QuestRewards(xp = 0L, gold = 0L),
                )
            val world = dev.ambon.domain.world.World(
                rooms = emptyMap(),
                startRoom = dev.ambon.domain.ids.RoomId("zone:start"),
                zoneScaling = mapOf(
                    "zone" to dev.ambon.domain.world.ZoneScaling(
                        mode = dev.ambon.domain.world.ScalingMode.BOUNDED,
                        levelRange = 3..8,
                    ),
                ),
            )
            val (qs, players, outbound) = setup(tieredQuest, progression, world)
            val sid = SessionId(1L)
            players.loginOrFail(sid, "Hero")
            players.setLevel(sid, 30) // overleveled → clamp to 8
            val xpBefore = players.get(sid)!!.xpTotal
            qs.acceptQuest(sid, questId)
            outbound.drainAll()

            repeat(3) { qs.onMobKilled(sid, mobTemplateKey) }

            val ps = players.get(sid)!!
            val xpGained = ps.xpTotal - xpBefore
            // STANDARD at clamped level 8 = (50 + 20*7) = 190
            assertEquals(190L, xpGained)
        }

    @Test
    fun `authored quest XP overrides computed difficulty XP`() =
        runTest {
            val progression = PlayerProgression()
            val tieredQuest =
                killQuest.copy(
                    level = 3,
                    difficulty = QuestDifficulty.EPIC,
                    rewards = QuestRewards(xp = 42L, gold = 0L),
                )
            val (qs, players, outbound) = setup(tieredQuest, progression)
            val sid = SessionId(1L)
            players.loginOrFail(sid, "Hero")
            players.setLevel(sid, 3)
            val xpBefore = players.get(sid)!!.xpTotal
            qs.acceptQuest(sid, questId)
            outbound.drainAll()

            repeat(3) { qs.onMobKilled(sid, mobTemplateKey) }

            val ps = players.get(sid)!!
            val xpGained = ps.xpTotal - xpBefore
            assertEquals(42L, xpGained, "Authored rewards.xp should win over the tier")
        }

    @Test
    fun `quest XP ignores diminishing when level is not declared`() =
        runTest {
            val progression = PlayerProgression()
            val (qs, players, outbound) = setup(progression = progression)
            val sid = SessionId(1L)
            players.loginOrFail(sid, "Hero")
            players.setLevel(sid, 20)
            val xpAtLevel20 = players.get(sid)!!.xpTotal
            qs.acceptQuest(sid, questId)
            outbound.drainAll()

            repeat(3) { qs.onMobKilled(sid, mobTemplateKey) }

            val ps = players.get(sid)!!
            val xpGained = ps.xpTotal - xpAtLevel20
            assertEquals(100L, xpGained, "Quest with no level should award the full flat reward")
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

    private fun gatedSetup(
        requirement: ReputationRequirement,
        startingRep: Int,
    ): Triple<QuestSystem, PlayerRegistry, SessionId> {
        val factionConfig = FactionConfig(
            definitions = mapOf(
                "royal_court" to FactionDefinition(name = "Royal Court", description = ""),
            ),
        )
        val c = SystemTestComponents(clockInitialMs = 1_000L)
        val gatedQuest = killQuest.copy(id = "zone:gated_quest", name = "Gated", requiredReputation = requirement)
        val registry = QuestRegistry().also { it.register(gatedQuest) }
        val repSystem = ReputationSystem(factionConfig)
        val qs = QuestSystem(
            registry = registry,
            players = c.players,
            items = c.items,
            outbound = c.outbound,
            clock = c.clock,
            reputationSystem = repSystem,
        )
        val sid = SessionId(1L)
        kotlinx.coroutines.runBlocking { c.players.loginOrFail(sid, "Hero") }
        val ps = c.players.get(sid)!!
        ps.factionStandings["royal_court"] = startingRep
        return Triple(qs, c.players, sid)
    }

    @Test
    fun `quest with max rep exceeded disappears from availableQuests`() =
        runTest {
            val (qs, _, sid) = gatedSetup(
                ReputationRequirement(faction = "royal_court", max = -500),
                startingRep = 0,
            )
            val available = qs.availableQuests(sid, "zone:quest_giver")
            assertTrue(available.none { it.id == "zone:gated_quest" })
            val hints = qs.hintedQuests(sid, "zone:quest_giver")
            assertTrue(hints.isEmpty(), "max-exceeded quests should not appear as hints either")
        }

    @Test
    fun `quest with min rep unmet is hinted but not acceptable`() =
        runTest {
            val (qs, _, sid) = gatedSetup(
                ReputationRequirement(faction = "royal_court", min = 250),
                startingRep = 0,
            )
            val available = qs.availableQuests(sid, "zone:quest_giver")
            assertTrue(available.none { it.id == "zone:gated_quest" })
            val hints = qs.hintedQuests(sid, "zone:quest_giver")
            assertEquals(1, hints.size)
            val err = qs.acceptQuest(sid, "zone:gated_quest")
            assertNotNull(err)
            assertTrue(err!!.contains("Royal Court"), "Error message should surface faction name: $err")
        }

    @Test
    fun `quest with met reputation is acceptable`() =
        runTest {
            val (qs, _, sid) = gatedSetup(
                ReputationRequirement(faction = "royal_court", min = 250),
                startingRep = 300,
            )
            val available = qs.availableQuests(sid, "zone:quest_giver")
            assertEquals(1, available.size)
            val err = qs.acceptQuest(sid, "zone:gated_quest")
            assertNull(err)
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
    fun `acceptQuest seeds collect progress from existing inventory`() =
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
                                count = 3,
                                description = "Collect 3 shiny rocks",
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
            val sid = SessionId(10L)
            c.players.loginOrFail(sid, "Gatherer")

            // Player already holds 2 of the 3 required rocks BEFORE accepting the quest.
            repeat(2) {
                val rock = ItemInstance(id = ItemId(collectItemId), item = Item(keyword = "rock", displayName = "a shiny rock"))
                c.items.addToInventory(sid, rock)
            }

            val err = qs.acceptQuest(sid, collectQuestId)
            assertNull(err)

            val ps = c.players.get(sid)!!
            val prog = ps.activeQuests[collectQuestId]!!.objectives[0]
            assertEquals(
                2,
                prog.current,
                "Existing inventory should seed collect objective progress to 2/3 on accept",
            )
            assertEquals(3, prog.required)
            assertFalse(prog.isComplete)
        }

    @Test
    fun `collect quest completes after one more pickup when 2 of 3 were already held`() =
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
                                count = 3,
                                description = "Collect 3 shiny rocks",
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
            val sid = SessionId(11L)
            c.players.loginOrFail(sid, "Gatherer")

            // Pre-existing inventory: 2 rocks already in bag.
            repeat(2) {
                val rock = ItemInstance(id = ItemId(collectItemId), item = Item(keyword = "rock", displayName = "a shiny rock"))
                c.items.addToInventory(sid, rock)
            }

            qs.acceptQuest(sid, collectQuestId)

            // Confirm seeded to 2/3.
            assertEquals(2, c.players.get(sid)!!.activeQuests[collectQuestId]!!.objectives[0].current)

            // Picking up one more rock (delta of 1) should finish the quest at 3/3 — not jump.
            val thirdRock = ItemInstance(id = ItemId(collectItemId), item = Item(keyword = "rock", displayName = "a shiny rock"))
            c.items.addToInventory(sid, thirdRock)
            qs.onItemCollected(sid, thirdRock)

            val ps = c.players.get(sid)!!
            assertFalse(ps.activeQuests.containsKey(collectQuestId), "Quest should auto-complete")
            assertTrue(ps.completedQuestIds.contains(collectQuestId))
        }

    @Test
    fun `accepting collect quest with full required inventory auto-completes`() =
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
            val sid = SessionId(12L)
            c.players.loginOrFail(sid, "Gatherer")

            repeat(2) {
                val rock = ItemInstance(id = ItemId(collectItemId), item = Item(keyword = "rock", displayName = "a shiny rock"))
                c.items.addToInventory(sid, rock)
            }

            qs.acceptQuest(sid, collectQuestId)

            val ps = c.players.get(sid)!!
            assertFalse(ps.activeQuests.containsKey(collectQuestId), "Quest with all items already held should auto-complete on accept")
            assertTrue(ps.completedQuestIds.contains(collectQuestId))
        }

    @Test
    fun `formatQuestLog shows multiple quests on separate lines`() =
        runTest {
            val (_, players, _) = setup()
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

    @Test
    fun `quest completion XP that levels up invokes onLevelUp callback`() =
        runTest {
            val progression = PlayerProgression()
            // A huge XP reward guarantees a level-up from level 1.
            val fatQuest = killQuest.copy(rewards = QuestRewards(xp = 10_000L, gold = 0L))
            val (qs, players, _) = setup(fatQuest, progression)
            val sid = SessionId(1L)
            players.loginOrFail(sid, "Hero")

            var captured: LevelUpResult? = null
            qs.onLevelUp = { _, result -> captured = result }

            qs.acceptQuest(sid, questId)
            repeat(3) { qs.onMobKilled(sid, mobTemplateKey) }

            assertTrue(
                players.get(sid)!!.completedQuestIds.contains(questId),
                "Quest should complete",
            )
            val result = captured
            assertNotNull(result, "onLevelUp should fire when quest XP causes a level-up")
            assertTrue(result!!.levelsGained > 0)
            assertTrue(result.newLevel > result.previousLevel)
        }

    @Test
    fun `turn-in quest does not auto-complete and requires NPC in room`() =
        runTest {
            val turnInQuest = killQuest.copy(completionType = "npc_turn_in")
            val (qs, players, _) = setup(turnInQuest)
            val sid = SessionId(1L)
            players.loginOrFail(sid, "Hero")

            qs.acceptQuest(sid, questId)
            repeat(3) { qs.onMobKilled(sid, mobTemplateKey) }

            val ps = players.get(sid)!!
            assertTrue(ps.activeQuests.containsKey(questId), "Quest should remain active pending turn-in")
            assertFalse(ps.completedQuestIds.contains(questId), "Quest must not auto-complete")

            val wrongRoomErr = qs.turnInQuest(sid, "Kill Quest", emptyList())
            assertNotNull(wrongRoomErr, "Turn-in without giver in room should fail")

            val ok = qs.turnInQuest(sid, "Kill Quest", listOf("zone:quest_giver"))
            assertNull(ok, "Turn-in with giver in room should succeed")
            assertTrue(players.get(sid)!!.completedQuestIds.contains(questId))
        }

    @Test
    fun `turn-in rejects when objectives are incomplete`() =
        runTest {
            val turnInQuest = killQuest.copy(completionType = "npc_turn_in")
            val (qs, players, _) = setup(turnInQuest)
            val sid = SessionId(1L)
            players.loginOrFail(sid, "Hero")

            qs.acceptQuest(sid, questId)
            qs.onMobKilled(sid, mobTemplateKey) // only 1/3

            val err = qs.turnInQuest(sid, "Kill Quest", listOf("zone:quest_giver"))
            assertNotNull(err, "Turn-in with unfinished objectives should fail")
            assertFalse(players.get(sid)!!.completedQuestIds.contains(questId))
        }

    @Test
    fun `turn-in rejects quests whose completion type is auto`() =
        runTest {
            val (qs, players, _) = setup() // killQuest with completionType = "auto"
            val sid = SessionId(1L)
            players.loginOrFail(sid, "Hero")

            qs.acceptQuest(sid, questId)
            // Quest auto-completes — turnInQuest should report no matching active quest.
            val err = qs.turnInQuest(sid, "Kill Quest", listOf("zone:quest_giver"))
            assertNotNull(err)
        }

    @Test
    fun `isReadyToTurnIn reflects completion handler and objectives`() =
        runTest {
            val turnInQuest = killQuest.copy(completionType = "npc_turn_in")
            val (qs, players, _) = setup(turnInQuest)
            val sid = SessionId(1L)
            players.loginOrFail(sid, "Hero")

            qs.acceptQuest(sid, questId)
            val stateInProgress = players.get(sid)!!.activeQuests[questId]!!
            assertFalse(qs.isReadyToTurnIn(turnInQuest, stateInProgress))

            repeat(3) { qs.onMobKilled(sid, mobTemplateKey) }
            val stateReady = players.get(sid)!!.activeQuests[questId]!!
            assertTrue(qs.isReadyToTurnIn(turnInQuest, stateReady))
        }
}
