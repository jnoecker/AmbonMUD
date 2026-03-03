package dev.ambon.engine.commands

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.items.ItemSlot
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.CombatSystemConfig
import dev.ambon.engine.GroupSystem
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.CommandRouterHarness
import dev.ambon.test.TestWorlds
import dev.ambon.test.buildTestPlayerRegistry
import dev.ambon.test.drainAll
import dev.ambon.test.equipItemForTest
import dev.ambon.test.loginOrFail
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommandRouterScoreTest {
    @Test
    fun `score shows name level hp and damage range`() =
        runTest {
            // Needs CombatSystemConfig to assert specific damage range in score output
            val world = TestWorlds.testWorld
            val items = ItemRegistry()
            val players = buildTestPlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val outbound = LocalOutboundBus()
            val combat = CombatSystem(players, mobs, items, outbound, config = CombatSystemConfig(minDamage = 1, maxDamage = 4))
            val router = buildTestRouter(world, players, mobs, items, combat, outbound)

            val sid = SessionId(1)
            players.loginOrFail(sid, "Arandel")
            outbound.drainAll()

            router.handle(sid, Command.Score)

            val outs = outbound.drainAll()
            val text = outs.filterIsInstance<OutboundEvent.SendInfo>().joinToString("\n") { it.text }
            assertTrue(text.contains("Arandel"), "Missing name. text=$text")
            assertTrue(text.contains("Level 1"), "Missing level. text=$text")
            assertTrue(text.contains("HP"), "Missing HP. text=$text")
            assertTrue(text.contains("XP"), "Missing XP. text=$text")
            assertTrue(Regex("""\b1\D+4\b""").containsMatchIn(text), "Missing damage range. text=$text")
            assertTrue(outs.any { it is OutboundEvent.SendPrompt }, "Missing prompt. got=$outs")
        }

    @Test
    fun `score with equipped armor item lists it in Armor section`() =
        runTest {
            val h = CommandRouterHarness.create()
            val sid = SessionId(2)
            h.loginPlayer(sid, "Brenna")
            h.drain()

            equipItemForTest(
                h.items,
                sid,
                RoomId("test:room"),
                ItemInstance(ItemId("test:helm"), Item(keyword = "helm", displayName = "iron helm", slot = ItemSlot.HEAD, armor = 3)),
            )
            h.combat.syncPlayerDefense(sid)

            h.router.handle(sid, Command.Score)

            val outs = h.drain()
            val text = outs.filterIsInstance<OutboundEvent.SendInfo>().joinToString("\n") { it.text }
            assertTrue(text.contains("+3"), "Expected armor total +3. text=$text")
            assertTrue(text.contains("iron helm"), "Expected item name in armor detail. text=$text")
        }

    @Test
    fun `score at max level shows MAXED for XP`() =
        runTest {
            val h = CommandRouterHarness.create()
            val sid = SessionId(3)
            h.loginPlayer(sid, "Zara")
            h.drain()

            // Grant enough XP to reach max level
            val maxXp = h.progression.totalXpForLevel(h.progression.maxLevel)
            h.players.grantXp(sid, maxXp, h.progression)

            h.router.handle(sid, Command.Score)

            val outs = h.drain()
            val text = outs.filterIsInstance<OutboundEvent.SendInfo>().joinToString("\n") { it.text }
            assertTrue(text.contains("MAXED"), "Expected MAXED at max level. text=$text")
        }

    @Test
    fun `score shows group info when in a group`() =
        runTest {
            val world = TestWorlds.testWorld
            val repo = InMemoryPlayerRepository()
            val items = ItemRegistry()
            val players = buildTestPlayerRegistry(world.startRoom, repo, items)
            val outbound = LocalOutboundBus()
            val groupSystem = GroupSystem(players, outbound)
            val h = CommandRouterHarness.create(
                world = world,
                repo = repo,
                items = items,
                players = players,
                outbound = outbound,
                groupSystem = groupSystem,
            )

            val sid1 = SessionId(1)
            val sid2 = SessionId(2)
            h.players.loginOrFail(sid1, "Alice")
            h.players.loginOrFail(sid2, "Bob")
            h.outbound.drainAll()

            groupSystem.invite(sid1, "Bob")
            groupSystem.accept(sid2)
            h.outbound.drainAll()

            h.router.handle(sid1, Command.Score)

            val outs = h.outbound.drainAll()
            val text = outs.filterIsInstance<OutboundEvent.SendInfo>().joinToString("\n") { it.text }
            assertTrue(text.contains("Group"), "Expected Group line. text=$text")
            assertTrue(text.contains("2 members"), "Expected member count. text=$text")
            assertTrue(text.contains("Alice"), "Expected leader name. text=$text")
        }

    @Test
    fun `score does not show group line when ungrouped`() =
        runTest {
            val world = TestWorlds.testWorld
            val repo = InMemoryPlayerRepository()
            val items = ItemRegistry()
            val players = buildTestPlayerRegistry(world.startRoom, repo, items)
            val outbound = LocalOutboundBus()
            val groupSystem = GroupSystem(players, outbound)
            val h = CommandRouterHarness.create(
                world = world,
                repo = repo,
                items = items,
                players = players,
                outbound = outbound,
                groupSystem = groupSystem,
            )

            val sid = SessionId(1)
            h.players.loginOrFail(sid, "Alice")
            h.outbound.drainAll()

            h.router.handle(sid, Command.Score)

            val outs = h.outbound.drainAll()
            val text = outs.filterIsInstance<OutboundEvent.SendInfo>().joinToString("\n") { it.text }
            assertTrue(!text.contains("Group:"), "Should not contain Group line. text=$text")
        }
}
