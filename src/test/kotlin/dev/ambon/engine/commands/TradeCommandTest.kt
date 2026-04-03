package dev.ambon.engine.commands

import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.items.ItemSlot
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.World
import dev.ambon.engine.TradeSystem
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.test.CommandRouterHarness
import dev.ambon.test.buildTestPlayerRegistry
import dev.ambon.test.errorMessages
import dev.ambon.test.infoMessages
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TradeCommandTest {
    private val roomA = RoomId("test:market")
    private val roomB = RoomId("test:alley")

    private fun tradeWorld(): World {
        val rooms = mapOf(
            roomA to Room(
                id = roomA,
                title = "Market",
                description = "A bustling market.",
                exits = mapOf(Direction.NORTH to roomB),
            ),
            roomB to Room(
                id = roomB,
                title = "Alley",
                description = "A dark alley.",
                exits = mapOf(Direction.SOUTH to roomA),
            ),
        )
        return World(rooms = rooms, startRoom = roomA)
    }

    private fun sword(suffix: String = "1") = ItemInstance(
        id = ItemId("sword_$suffix"),
        item = Item(keyword = "sword", displayName = "a copper sword", slot = ItemSlot.HAND, damage = 4),
    )

    private fun shield() = ItemInstance(
        id = ItemId("shield_1"),
        item = Item(keyword = "shield", displayName = "a wooden shield", slot = ItemSlot.HAND, armor = 2),
    )

    private fun harness(): Triple<CommandRouterHarness, ItemRegistry, TradeSystem> {
        val world = tradeWorld()
        val items = ItemRegistry()
        val tradeSystem = TradeSystem(items)
        val players = buildTestPlayerRegistry(world.startRoom, items = items)
        val h = CommandRouterHarness.create(
            world = world,
            items = items,
            players = players,
            tradeSystem = tradeSystem,
        )
        return Triple(h, items, tradeSystem)
    }

    @Test
    fun `initiate trade sends notifications to both players`() = runTest {
        val (h, _, _) = harness()
        val alice = SessionId(1)
        val bob = SessionId(2)
        h.loginPlayer(alice, "Alice")
        h.loginPlayer(bob, "Bob")
        h.drain()

        h.router.handle(alice, Command.TradeRequest("Bob"))

        val events = h.drain()
        val aliceInfos = events.infoMessages(alice)
        val bobInfos = events.infoMessages(bob)

        assertTrue(aliceInfos.any { it.contains("initiate") && it.contains("Bob") }, "got=$aliceInfos")
        assertTrue(bobInfos.any { it.contains("Alice") && it.contains("trade") }, "got=$bobInfos")
    }

    @Test
    fun `cannot trade with yourself`() = runTest {
        val (h, _, _) = harness()
        val alice = SessionId(1)
        h.loginPlayer(alice, "Alice")
        h.drain()

        h.router.handle(alice, Command.TradeRequest("Alice"))

        val errors = h.drain().errorMessages(alice)
        assertTrue(errors.any { it.contains("yourself") }, "got=$errors")
    }

    @Test
    fun `offer item removes from inventory and adds to trade`() = runTest {
        val (h, items, ts) = harness()
        val alice = SessionId(1)
        val bob = SessionId(2)
        h.loginPlayer(alice, "Alice")
        h.loginPlayer(bob, "Bob")
        items.addToInventory(alice, sword())
        h.drain()

        h.router.handle(alice, Command.TradeRequest("Bob"))
        h.drain()

        h.router.handle(alice, Command.TradeOffer("sword"))

        val events = h.drain()
        val aliceInfos = events.infoMessages(alice)
        val bobInfos = events.infoMessages(bob)

        assertTrue(aliceInfos.any { it.contains("offer") && it.contains("copper sword") }, "got=$aliceInfos")
        assertTrue(bobInfos.any { it.contains("Alice") && it.contains("copper sword") }, "got=$bobInfos")
        assertTrue(items.inventory(alice).isEmpty(), "Item should be removed from inventory (held in escrow)")

        val session = ts.getSession(alice)!!
        assertEquals(1, session.initiatorItems.size, "Item should be in trade session")
    }

    @Test
    fun `both accept completes trade and transfers items`() = runTest {
        val (h, items, _) = harness()
        val alice = SessionId(1)
        val bob = SessionId(2)
        h.loginPlayer(alice, "Alice")
        h.loginPlayer(bob, "Bob")
        items.addToInventory(alice, sword())
        items.addToInventory(bob, shield())
        h.drain()

        // Initiate trade
        h.router.handle(alice, Command.TradeRequest("Bob"))
        h.drain()

        // Offer items
        h.router.handle(alice, Command.TradeOffer("sword"))
        h.drain()
        h.router.handle(bob, Command.TradeOffer("shield"))
        h.drain()

        // Both accept
        h.router.handle(alice, Command.TradeAccept)
        h.drain()
        h.router.handle(bob, Command.TradeAccept)

        val events = h.drain()
        val aliceInfos = events.infoMessages(alice)
        val bobInfos = events.infoMessages(bob)

        assertTrue(aliceInfos.any { it.contains("Trade complete") }, "got=$aliceInfos")
        assertTrue(bobInfos.any { it.contains("Trade complete") }, "got=$bobInfos")

        // Verify items transferred
        val aliceInv = items.inventory(alice).map { it.item.keyword }
        val bobInv = items.inventory(bob).map { it.item.keyword }
        assertTrue("shield" in aliceInv, "Alice should have the shield. got=$aliceInv")
        assertTrue("sword" in bobInv, "Bob should have the sword. got=$bobInv")
    }

    @Test
    fun `one-sided accept waits for other player`() = runTest {
        val (h, _, ts) = harness()
        val alice = SessionId(1)
        val bob = SessionId(2)
        h.loginPlayer(alice, "Alice")
        h.loginPlayer(bob, "Bob")
        h.drain()

        h.router.handle(alice, Command.TradeRequest("Bob"))
        h.drain()

        h.router.handle(alice, Command.TradeAccept)

        val events = h.drain()
        val aliceInfos = events.infoMessages(alice)
        assertTrue(aliceInfos.any { it.contains("Waiting") }, "got=$aliceInfos")

        val session = ts.getSession(alice)!!
        assertTrue(session.initiatorAccepted)
        assertFalse(session.targetAccepted)
    }

    @Test
    fun `cancel returns escrowed items to original owners`() = runTest {
        val (h, items, ts) = harness()
        val alice = SessionId(1)
        val bob = SessionId(2)
        h.loginPlayer(alice, "Alice")
        h.loginPlayer(bob, "Bob")
        items.addToInventory(alice, sword())
        h.drain()

        h.router.handle(alice, Command.TradeRequest("Bob"))
        h.drain()

        h.router.handle(alice, Command.TradeOffer("sword"))
        h.drain()
        assertTrue(items.inventory(alice).isEmpty(), "Sword should be in escrow")

        h.router.handle(bob, Command.TradeCancel)

        val events = h.drain()
        val bobInfos = events.infoMessages(bob)
        assertTrue(bobInfos.any { it.contains("cancel") }, "got=$bobInfos")

        // Verify item returned
        val aliceInv = items.inventory(alice).map { it.item.keyword }
        assertTrue("sword" in aliceInv, "Sword should be returned to Alice. got=$aliceInv")
        assertFalse(ts.isInTrade(alice), "Alice should no longer be in trade")
        assertFalse(ts.isInTrade(bob), "Bob should no longer be in trade")
    }

    @Test
    fun `cannot start trade while already in one`() = runTest {
        val (h, _, _) = harness()
        val alice = SessionId(1)
        val bob = SessionId(2)
        val charlie = SessionId(3)
        h.loginPlayer(alice, "Alice")
        h.loginPlayer(bob, "Bob")
        h.loginPlayer(charlie, "Charlie")
        h.drain()

        h.router.handle(alice, Command.TradeRequest("Bob"))
        h.drain()

        h.router.handle(alice, Command.TradeRequest("Charlie"))

        val errors = h.drain().errorMessages(alice)
        assertTrue(errors.any { it.contains("already") }, "got=$errors")
    }

    @Test
    fun `offer gold works and shows in status`() = runTest {
        val (h, _, _) = harness()
        val alice = SessionId(1)
        val bob = SessionId(2)
        h.loginPlayer(alice, "Alice")
        h.loginPlayer(bob, "Bob")
        h.players.get(alice)!!.gold = 500L
        h.drain()

        h.router.handle(alice, Command.TradeRequest("Bob"))
        h.drain()
        h.router.handle(alice, Command.TradeOfferGold(100))
        h.drain()

        h.router.handle(alice, Command.TradeStatus)

        val events = h.drain()
        val infos = events.infoMessages(alice)
        assertTrue(infos.any { it.contains("100 gold") }, "Status should show gold offered. got=$infos")
    }

    @Test
    fun `offer more gold than available fails`() = runTest {
        val (h, _, _) = harness()
        val alice = SessionId(1)
        val bob = SessionId(2)
        h.loginPlayer(alice, "Alice")
        h.loginPlayer(bob, "Bob")
        h.players.get(alice)!!.gold = 10L
        h.drain()

        h.router.handle(alice, Command.TradeRequest("Bob"))
        h.drain()

        h.router.handle(alice, Command.TradeOfferGold(100))

        val errors = h.drain().errorMessages(alice)
        assertTrue(errors.any { it.contains("only have") }, "got=$errors")
    }

    @Test
    fun `disconnect during trade returns escrowed items`() = runTest {
        val (h, items, ts) = harness()
        val alice = SessionId(1)
        val bob = SessionId(2)
        h.loginPlayer(alice, "Alice")
        h.loginPlayer(bob, "Bob")
        items.addToInventory(alice, sword())
        items.addToInventory(bob, shield())
        h.drain()

        h.router.handle(alice, Command.TradeRequest("Bob"))
        h.drain()
        h.router.handle(alice, Command.TradeOffer("sword"))
        h.drain()
        h.router.handle(bob, Command.TradeOffer("shield"))
        h.drain()

        // Simulate disconnect
        ts.cancelForPlayer(alice)

        // Verify items returned
        assertTrue(items.inventory(alice).any { it.item.keyword == "sword" }, "Sword should return to Alice")
        assertTrue(items.inventory(bob).any { it.item.keyword == "shield" }, "Shield should return to Bob")
        assertFalse(ts.isInTrade(alice))
        assertFalse(ts.isInTrade(bob))
    }

    @Test
    fun `trade not registered when system is null produces no handler output`() = runTest {
        val world = tradeWorld()
        val h = CommandRouterHarness.create(world = world)
        val sid = SessionId(1)
        h.loginPlayer(sid, "Alice")
        h.drain()

        h.router.handle(sid, Command.TradeRequest("Bob"))

        val events = h.drain()
        // With no TradeHandler registered, the command is silently ignored (only a prompt is sent)
        val errors = events.errorMessages(sid)
        val infos = events.infoMessages(sid)
        assertTrue(errors.isEmpty() && infos.isEmpty(), "No handler output expected. errors=$errors infos=$infos")
    }
}
