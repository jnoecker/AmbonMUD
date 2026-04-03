package dev.ambon.engine

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.achievement.AchievementCriterion
import dev.ambon.domain.achievement.AchievementDef
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.items.ItemSlot
import dev.ambon.domain.quest.QuestDef
import dev.ambon.domain.quest.QuestObjectiveDef
import dev.ambon.domain.quest.QuestRewards
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.World
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.status.StatusEffectDefinition
import dev.ambon.engine.status.StatusEffectId
import dev.ambon.engine.status.StatusEffectRegistry
import dev.ambon.engine.status.StatusEffectSystem
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.MutableClock
import dev.ambon.test.buildTestPlayerRegistry
import dev.ambon.test.drainAll
import dev.ambon.test.loginOrFail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests cross-system interactions that span multiple game systems.
 * These tests verify that side-effects propagate correctly when
 * events trigger chains across DuelSystem, TradeSystem, GroupSystem,
 * StatusEffectSystem, and the ability cooldown system.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MultiSystemIntegrationTest {
    private val startRoom = RoomId("test:start")

    private fun testWorld(): World {
        val rooms = mapOf(
            startRoom to Room(
                id = startRoom,
                title = "Test Room",
                description = "A test room.",
                exits = emptyMap(),
            ),
        )
        return World(rooms = rooms, startRoom = startRoom)
    }

    private fun sword() = ItemInstance(
        id = ItemId("sword_1"),
        item = Item(keyword = "sword", displayName = "a copper sword", slot = ItemSlot.HAND, damage = 4),
    )

    private fun shield() = ItemInstance(
        id = ItemId("shield_1"),
        item = Item(keyword = "shield", displayName = "a wooden shield", slot = ItemSlot.HAND, armor = 2),
    )

    // ── Death cleanup chain ──────────────────────────────────────────────

    @Test
    fun `player death cancels active trade and returns escrowed items`() = runTest {
        val items = ItemRegistry()
        val tradeSystem = TradeSystem(items)
        val repo = InMemoryPlayerRepository()
        val players = buildTestPlayerRegistry(startRoom, repo, items)

        val alice = SessionId(1)
        val bob = SessionId(2)
        players.loginOrFail(alice, "Alice")
        players.loginOrFail(bob, "Bob")

        // Give items and start trade
        items.addToInventory(alice, sword())
        items.addToInventory(bob, shield())

        tradeSystem.createSession(alice, bob)
        tradeSystem.offerItem(alice, "sword")
        tradeSystem.offerItem(bob, "shield")

        // Verify items are in escrow
        assertTrue(items.inventory(alice).isEmpty(), "Alice's sword should be in escrow")
        assertTrue(items.inventory(bob).isEmpty(), "Bob's shield should be in escrow")

        // Simulate death cleanup: cancel trade
        tradeSystem.cancelForPlayer(alice)

        // Verify items returned
        assertTrue(items.inventory(alice).any { it.item.keyword == "sword" }, "Sword should return to Alice")
        assertTrue(items.inventory(bob).any { it.item.keyword == "shield" }, "Shield should return to Bob")
        assertFalse(tradeSystem.isInTrade(alice))
        assertFalse(tradeSystem.isInTrade(bob))
    }

    @Test
    fun `player death ends active duel`() = runTest {
        val clock = MutableClock(0L)
        val duelSystem = DuelSystem(clock)

        val alice = SessionId(1)
        val bob = SessionId(2)

        // Set up and accept a duel
        duelSystem.challenge(alice, bob)
        duelSystem.accept(bob)
        assertTrue(duelSystem.isInDuel(alice))
        assertTrue(duelSystem.isInDuel(bob))

        // Alice dies — end the duel
        val endedDuel = duelSystem.endDuel(alice)
        assertFalse(duelSystem.isInDuel(alice))
        assertFalse(duelSystem.isInDuel(bob))
        assertEquals(alice, endedDuel!!.player1)
    }

    @Test
    fun `player death removes from group`() = runTest {
        val outbound = LocalOutboundBus()
        val repo = InMemoryPlayerRepository()
        val items = ItemRegistry()
        val players = buildTestPlayerRegistry(startRoom, repo, items)
        val groupSystem = GroupSystem(players, outbound)

        val alice = SessionId(1)
        val bob = SessionId(2)
        players.loginOrFail(alice, "Alice")
        players.loginOrFail(bob, "Bob")

        // Form a group
        groupSystem.invite(alice, "Bob")
        groupSystem.accept(bob)
        assertTrue(groupSystem.getGroup(alice) != null, "Group should exist")
        assertTrue(groupSystem.getGroup(bob) != null, "Bob should be in group")

        outbound.drainAll()

        // Alice dies — leave group
        groupSystem.leave(alice)

        // Bob should still have a group (as leader); Alice should not
        assertNull(groupSystem.getGroup(alice), "Alice should not be in a group after death")
    }

    @Test
    fun `player death clears status effects`() = runTest {
        val clock = MutableClock(0L)
        val outbound = LocalOutboundBus()
        val repo = InMemoryPlayerRepository()
        val items = ItemRegistry()
        val players = buildTestPlayerRegistry(startRoom, repo, items, clock = clock)
        val mobs = MobRegistry()
        val effectRegistry = StatusEffectRegistry()
        effectRegistry.register(
            StatusEffectDefinition(
                id = StatusEffectId("poison"),
                displayName = "Poison",
                durationMs = 10_000L,
                tickIntervalMs = 2_000L,
                effectType = "dot",
                tickMinValue = 5,
                tickMaxValue = 5,
            ),
        )

        val statusEffects = StatusEffectSystem(
            registry = effectRegistry,
            players = players,
            mobs = mobs,
            outbound = outbound,
            clock = clock,
        )

        val alice = SessionId(1)
        players.loginOrFail(alice, "Alice")

        // Apply a DOT
        statusEffects.applyToPlayer(alice, StatusEffectId("poison"))
        assertTrue(statusEffects.activePlayerEffects(alice).isNotEmpty(), "Alice should have poison effect")

        // Death: remove all effects
        statusEffects.removeAllFromPlayer(alice)

        assertTrue(statusEffects.activePlayerEffects(alice).isEmpty(), "Effects should be cleared after death")
    }

    // ── Combined chain: death triggers multi-system cleanup ──────────────

    @Test
    fun `death cleanup chain clears trade duel group and effects together`() = runTest {
        val clock = MutableClock(0L)
        val items = ItemRegistry()
        val repo = InMemoryPlayerRepository()
        val outbound = LocalOutboundBus()
        val players = buildTestPlayerRegistry(startRoom, repo, items, clock = clock)
        val mobs = MobRegistry()
        val tradeSystem = TradeSystem(items)
        val duelSystem = DuelSystem(clock)
        val groupSystem = GroupSystem(players, outbound)
        val effectRegistry = StatusEffectRegistry()
        effectRegistry.register(
            StatusEffectDefinition(
                id = StatusEffectId("regen"),
                displayName = "Regeneration",
                durationMs = 60_000L,
                tickIntervalMs = 5_000L,
                effectType = "hot",
                tickMinValue = 10,
                tickMaxValue = 10,
            ),
        )
        val statusEffects = StatusEffectSystem(
            registry = effectRegistry,
            players = players,
            mobs = mobs,
            outbound = outbound,
            clock = clock,
        )

        val alice = SessionId(1)
        val bob = SessionId(2)
        val charlie = SessionId(3)
        players.loginOrFail(alice, "Alice")
        players.loginOrFail(bob, "Bob")
        players.loginOrFail(charlie, "Charlie")
        outbound.drainAll()

        // Set up trade between Alice and Bob
        items.addToInventory(alice, sword())
        tradeSystem.createSession(alice, bob)
        tradeSystem.offerItem(alice, "sword")

        // Set up group: Alice + Charlie
        groupSystem.invite(alice, "Charlie")
        groupSystem.accept(charlie)
        outbound.drainAll()

        // Apply a status effect to Alice
        statusEffects.applyToPlayer(alice, StatusEffectId("regen"))

        // Verify pre-conditions
        assertTrue(tradeSystem.isInTrade(alice), "Alice should be in trade")
        assertTrue(groupSystem.getGroup(alice) != null, "Alice should be in group")
        assertTrue(statusEffects.activePlayerEffects(alice).isNotEmpty(), "Alice should have regen")

        // ── Simulate the death cleanup chain (same as GameEngine.cleanupOnPlayerDeath) ──
        tradeSystem.cancelForPlayer(alice)
        statusEffects.removeAllFromPlayer(alice)
        groupSystem.leave(alice)

        // Verify everything is cleaned up
        assertFalse(tradeSystem.isInTrade(alice), "Trade should be cancelled")
        assertFalse(tradeSystem.isInTrade(bob), "Bob's trade should be cancelled too")
        assertTrue(items.inventory(alice).any { it.item.keyword == "sword" }, "Sword should return from escrow")
        assertTrue(statusEffects.activePlayerEffects(alice).isEmpty(), "Effects should be cleared")
        assertNull(groupSystem.getGroup(alice), "Alice should not be in group")
    }

    // ── Quest completion → achievement unlock chain ──────────────────────

    @Test
    fun `quest completion triggers achievement check`() = runTest {
        val clock = MutableClock(0L)
        val outbound = LocalOutboundBus()
        val repo = InMemoryPlayerRepository()
        val items = ItemRegistry()
        val players = buildTestPlayerRegistry(startRoom, repo, items, clock = clock)

        val achievementRegistry = AchievementRegistry()
        val categoryRegistry = AchievementCategoryRegistry(dev.ambon.config.AchievementCategoriesConfig())
        achievementRegistry.register(
            AchievementDef(
                id = "quests/first_quest",
                displayName = "Quest Master",
                description = "Complete your first quest.",
                category = "quests",
                criteria = listOf(
                    AchievementCriterion(
                        type = "quest_complete",
                        targetId = "test_quest",
                        count = 1,
                    ),
                ),
            ),
        )
        val achievementSystem = AchievementSystem(
            registry = achievementRegistry,
            players = players,
            outbound = outbound,
            categoryRegistry = categoryRegistry,
        )

        val alice = SessionId(1)
        players.loginOrFail(alice, "Alice")
        outbound.drainAll()

        // Simulate quest completion triggering achievement
        achievementSystem.onQuestCompleted(alice, "test_quest")

        // Check that the achievement was unlocked
        val ps = players.get(alice)!!
        assertTrue(
            "quests/first_quest" in ps.unlockedAchievementIds,
            "Achievement should be unlocked. Unlocked=${ps.unlockedAchievementIds}",
        )

        // Check for the achievement notification
        val events = outbound.drainAll()
        val infos = events.filterIsInstance<OutboundEvent.SendInfo>()
            .filter { it.sessionId == alice }
            .map { it.text }
        assertTrue(infos.any { it.contains("Quest Master") || it.contains("Achievement") }, "got=$infos")
    }

    // ── Mob kill → quest progress → quest completion chain ──────────────

    @Test
    fun `mob kill advances quest objective and completes quest when target met`() = runTest {
        val clock = MutableClock(0L)
        val outbound = LocalOutboundBus()
        val repo = InMemoryPlayerRepository()
        val items = ItemRegistry()
        val players = buildTestPlayerRegistry(startRoom, repo, items, clock = clock)

        val questDef = QuestDef(
            id = "slay_goblins",
            name = "Goblin Slayer",
            giverMobId = "guard",
            description = "Kill 2 goblins.",
            objectives = listOf(
                QuestObjectiveDef(
                    description = "Kill goblins",
                    type = "kill",
                    targetId = "goblin",
                    count = 2,
                ),
            ),
            rewards = QuestRewards(xp = 100, gold = 50),
        )
        val questRegistry = QuestRegistry()
        questRegistry.register(questDef)

        val questSystem = QuestSystem(
            registry = questRegistry,
            players = players,
            items = items,
            outbound = outbound,
            clock = clock,
        )

        var completedQuestId: String? = null
        questSystem.onQuestCompleted = { _, questId -> completedQuestId = questId }

        val alice = SessionId(1)
        players.loginOrFail(alice, "Alice")
        outbound.drainAll()

        // Accept the quest
        questSystem.acceptQuest(alice, "slay_goblins")
        outbound.drainAll()

        // Kill first goblin
        questSystem.onMobKilled(alice, "goblin")
        val ps1 = players.get(alice)!!
        val questState = ps1.activeQuests["slay_goblins"]!!
        assertEquals(1, questState.objectives[0].current, "First kill should register")

        outbound.drainAll()

        // Kill second goblin — should complete the quest
        questSystem.onMobKilled(alice, "goblin")

        assertEquals("slay_goblins", completedQuestId, "Quest should be marked complete")

        val events = outbound.drainAll()
        val infos = events.filterIsInstance<OutboundEvent.SendInfo>()
            .filter { it.sessionId == alice }
            .map { it.text }
        assertTrue(infos.any { it.contains("complete") || it.contains("Goblin Slayer") }, "got=$infos")
    }
}
