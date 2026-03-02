package dev.ambon.engine.commands

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.items.ItemSlot
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.CombatSystemConfig
import dev.ambon.engine.GroupSystem
import dev.ambon.engine.LoginResult
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerProgression
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommandRouterScoreTest {
    @Test
    fun `score shows name level hp and damage range`() =
        runTest {
            val world = dev.ambon.test.TestWorlds.testWorld
            val items = ItemRegistry()
            val players = dev.ambon.test.buildTestPlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val outbound = LocalOutboundBus()
            val combat = CombatSystem(players, mobs, items, outbound, config = CombatSystemConfig(minDamage = 1, maxDamage = 4))
            val router = buildTestRouter(world, players, mobs, items, combat, outbound)

            val sid = SessionId(1)
            login(players, sid, "Arandel")
            drain(outbound)

            router.handle(sid, Command.Score)

            val outs = drain(outbound)
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
            val world = dev.ambon.test.TestWorlds.testWorld
            val items = ItemRegistry()
            val players = dev.ambon.test.buildTestPlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val outbound = LocalOutboundBus()
            val combat = CombatSystem(players, mobs, items, outbound, config = CombatSystemConfig(minDamage = 1, maxDamage = 4))
            val router = buildTestRouter(world, players, mobs, items, combat, outbound)

            val sid = SessionId(2)
            login(players, sid, "Brenna")
            drain(outbound)

            equipItem(items, sid, ItemId("test:helm"), Item(keyword = "helm", displayName = "iron helm", slot = ItemSlot.HEAD, armor = 3))
            combat.syncPlayerDefense(sid)

            router.handle(sid, Command.Score)

            val outs = drain(outbound)
            val text = outs.filterIsInstance<OutboundEvent.SendInfo>().joinToString("\n") { it.text }
            assertTrue(text.contains("+3"), "Expected armor total +3. text=$text")
            assertTrue(text.contains("iron helm"), "Expected item name in armor detail. text=$text")
        }

    @Test
    fun `score at max level shows MAXED for XP`() =
        runTest {
            val world = dev.ambon.test.TestWorlds.testWorld
            val items = ItemRegistry()
            val players = dev.ambon.test.buildTestPlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val outbound = LocalOutboundBus()
            val combat = CombatSystem(players, mobs, items, outbound)
            val progression = PlayerProgression()
            val router = buildTestRouter(world, players, mobs, items, combat, outbound, progression = progression)

            val sid = SessionId(3)
            login(players, sid, "Zara")
            drain(outbound)

            // Grant enough XP to reach max level
            val maxXp = progression.totalXpForLevel(progression.maxLevel)
            players.grantXp(sid, maxXp, progression)

            router.handle(sid, Command.Score)

            val outs = drain(outbound)
            val text = outs.filterIsInstance<OutboundEvent.SendInfo>().joinToString("\n") { it.text }
            assertTrue(text.contains("MAXED"), "Expected MAXED at max level. text=$text")
        }

    private fun drain(ch: LocalOutboundBus): List<OutboundEvent> {
        val out = mutableListOf<OutboundEvent>()
        while (true) {
            val ev = ch.tryReceive().getOrNull() ?: break
            out += ev
        }
        return out
    }

    private suspend fun login(
        players: PlayerRegistry,
        sessionId: SessionId,
        name: String,
    ) {
        val res = players.login(sessionId, name, "password")
        require(res == LoginResult.Ok) { "Login failed: $res" }
    }

    @Test
    fun `score shows group info when in a group`() =
        runTest {
            val world = dev.ambon.test.TestWorlds.testWorld
            val items = ItemRegistry()
            val players = dev.ambon.test.buildTestPlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val outbound = LocalOutboundBus()
            val groupSystem = GroupSystem(players, outbound)
            val combat = CombatSystem(players, mobs, items, outbound)
            val router =
                buildTestRouter(
                    world,
                    players,
                    mobs,
                    items,
                    combat,
                    outbound,
                    groupSystem = groupSystem,
                )

            val sid1 = SessionId(1)
            val sid2 = SessionId(2)
            login(players, sid1, "Alice")
            login(players, sid2, "Bob")
            drain(outbound)

            groupSystem.invite(sid1, "Bob")
            groupSystem.accept(sid2)
            drain(outbound)

            router.handle(sid1, Command.Score)

            val outs = drain(outbound)
            val text = outs.filterIsInstance<OutboundEvent.SendInfo>().joinToString("\n") { it.text }
            assertTrue(text.contains("Group"), "Expected Group line. text=$text")
            assertTrue(text.contains("2 members"), "Expected member count. text=$text")
            assertTrue(text.contains("Alice"), "Expected leader name. text=$text")
        }

    @Test
    fun `score does not show group line when ungrouped`() =
        runTest {
            val world = dev.ambon.test.TestWorlds.testWorld
            val items = ItemRegistry()
            val players = dev.ambon.test.buildTestPlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val outbound = LocalOutboundBus()
            val groupSystem = GroupSystem(players, outbound)
            val combat = CombatSystem(players, mobs, items, outbound)
            val router =
                buildTestRouter(
                    world,
                    players,
                    mobs,
                    items,
                    combat,
                    outbound,
                    groupSystem = groupSystem,
                )

            val sid = SessionId(1)
            login(players, sid, "Alice")
            drain(outbound)

            router.handle(sid, Command.Score)

            val outs = drain(outbound)
            val text = outs.filterIsInstance<OutboundEvent.SendInfo>().joinToString("\n") { it.text }
            assertTrue(!text.contains("Group:"), "Should not contain Group line. text=$text")
        }

    private fun equipItem(
        items: ItemRegistry,
        sessionId: SessionId,
        itemId: ItemId,
        item: Item,
    ) {
        val instance = ItemInstance(itemId, item)
        items.ensurePlayer(sessionId)
        items.addRoomItem(
            dev.ambon.domain.ids
                .RoomId("test:room"),
            instance,
        )
        items.takeFromRoom(
            sessionId,
            dev.ambon.domain.ids
                .RoomId("test:room"),
            item.keyword,
        )
        items.equipFromInventory(sessionId, item.keyword)
    }
}
