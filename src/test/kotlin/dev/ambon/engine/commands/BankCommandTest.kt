package dev.ambon.engine.commands

import dev.ambon.config.BankConfig
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.items.ItemSlot
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.World
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.test.CommandRouterHarness
import dev.ambon.test.buildTestPlayerRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BankCommandTest {
    // ── Parser tests ─────────────────────────────────────────────────────

    @Nested
    inner class Parser {
        @Test
        fun `deposit gold parses to DepositGold`() {
            val result = CommandParser.parse("deposit 100 gold")
            assertTrue(result is Command.Bank.DepositGold)
            assertEquals(100L, (result as Command.Bank.DepositGold).amount)
        }

        @Test
        fun `deposit all gold parses with MAX_VALUE sentinel`() {
            val result = CommandParser.parse("deposit all gold")
            assertTrue(result is Command.Bank.DepositGold)
            assertEquals(Long.MAX_VALUE, (result as Command.Bank.DepositGold).amount)
        }

        @Test
        fun `deposit item parses to DepositItem`() {
            val result = CommandParser.parse("deposit sword")
            assertTrue(result is Command.Bank.DepositItem)
            assertEquals("sword", (result as Command.Bank.DepositItem).keyword)
        }

        @Test
        fun `withdraw gold parses to WithdrawGold`() {
            val result = CommandParser.parse("withdraw 50 gold")
            assertTrue(result is Command.Bank.WithdrawGold)
            assertEquals(50L, (result as Command.Bank.WithdrawGold).amount)
        }

        @Test
        fun `withdraw item parses to WithdrawItem`() {
            val result = CommandParser.parse("withdraw sword")
            assertTrue(result is Command.Bank.WithdrawItem)
            assertEquals("sword", (result as Command.Bank.WithdrawItem).keyword)
        }

        @Test
        fun `bank parses to Bank Balance`() {
            assertEquals(Command.Bank.Balance, CommandParser.parse("bank"))
        }

        @Test
        fun `deposit alone returns Invalid`() {
            assertTrue(CommandParser.parse("deposit") is Command.Invalid)
        }
    }

    // ── Router tests ─────────────────────────────────────────────────────

    companion object {
        private val bankRoom = RoomId("test:bank")
        private val nonBankRoom = RoomId("test:lobby")
    }

    private fun bankWorld(): World {
        val rooms = mapOf(
            bankRoom to Room(
                id = bankRoom,
                title = "Bank",
                description = "A bank.",
                exits = mapOf(dev.ambon.domain.world.Direction.SOUTH to nonBankRoom),
                bank = true,
            ),
            nonBankRoom to Room(
                id = nonBankRoom,
                title = "Lobby",
                description = "A lobby.",
                exits = mapOf(dev.ambon.domain.world.Direction.NORTH to bankRoom),
            ),
        )
        return World(rooms = rooms, startRoom = bankRoom)
    }

    private fun harness(): Pair<CommandRouterHarness, ItemRegistry> {
        val world = bankWorld()
        val items = ItemRegistry()
        val players = buildTestPlayerRegistry(world.startRoom, items = items)
        val h = CommandRouterHarness.create(
            world = world,
            items = items,
            players = players,
            bankConfig = BankConfig(maxItems = 5),
        )
        return h to items
    }

    private fun sword() = ItemInstance(
        id = ItemId("sword_1"),
        item = Item(keyword = "sword", displayName = "a copper sword", slot = ItemSlot.WEAPON, damage = 4),
    )

    @Nested
    inner class Router {
        @Test
        fun `deposit gold succeeds at bank`() = runTest {
            val (h, _) = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            h.players.get(sid)!!.gold = 100L
            h.drain()

            h.router.handle(sid, Command.Bank.DepositGold(50))

            val infos = h.drain().filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
            assertTrue(infos.any { it.contains("deposit") && it.contains("50") }, "got=$infos")
            assertEquals(50L, h.players.get(sid)!!.gold)
            assertEquals(50L, h.players.get(sid)!!.bankGold)
        }

        @Test
        fun `deposit gold fails when not enough gold`() = runTest {
            val (h, _) = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            h.players.get(sid)!!.gold = 10L
            h.drain()

            h.router.handle(sid, Command.Bank.DepositGold(50))

            val errors = h.drain().filterIsInstance<OutboundEvent.SendError>().map { it.text }
            assertTrue(errors.any { it.contains("only have") }, "got=$errors")
        }

        @Test
        fun `withdraw gold succeeds at bank`() = runTest {
            val (h, _) = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            h.players.get(sid)!!.bankGold = 100L
            h.drain()

            h.router.handle(sid, Command.Bank.WithdrawGold(75))

            val infos = h.drain().filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
            assertTrue(infos.any { it.contains("withdraw") && it.contains("75") }, "got=$infos")
            assertEquals(75L, h.players.get(sid)!!.gold)
            assertEquals(25L, h.players.get(sid)!!.bankGold)
        }

        @Test
        fun `deposit item moves item from inventory to bank`() = runTest {
            val (h, items) = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            items.addToInventory(sid, sword())
            h.drain()

            h.router.handle(sid, Command.Bank.DepositItem("sword"))

            val infos = h.drain().filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
            assertTrue(infos.any { it.contains("deposit") && it.contains("copper sword") }, "got=$infos")
            assertTrue(items.inventory(sid).isEmpty())
            assertEquals(1, h.players.get(sid)!!.bankItems.size)
        }

        @Test
        fun `withdraw item moves item from bank to inventory`() = runTest {
            val (h, items) = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            h.players.get(sid)!!.bankItems.add(sword())
            h.drain()

            h.router.handle(sid, Command.Bank.WithdrawItem("sword"))

            val infos = h.drain().filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
            assertTrue(infos.any { it.contains("withdraw") && it.contains("copper sword") }, "got=$infos")
            assertEquals(1, items.inventory(sid).size)
            assertTrue(h.players.get(sid)!!.bankItems.isEmpty())
        }

        @Test
        fun `deposit fails outside bank room`() = runTest {
            val (h, _) = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            h.players.get(sid)!!.gold = 100L
            h.players.get(sid)!!.roomId = nonBankRoom
            h.drain()

            h.router.handle(sid, Command.Bank.DepositGold(50))

            val errors = h.drain().filterIsInstance<OutboundEvent.SendError>().map { it.text }
            assertTrue(errors.any { it.contains("bank") }, "got=$errors")
            assertEquals(100L, h.players.get(sid)!!.gold)
        }

        @Test
        fun `balance shows bank contents`() = runTest {
            val (h, _) = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            h.players.get(sid)!!.bankGold = 200L
            h.players.get(sid)!!.bankItems.add(sword())
            h.drain()

            h.router.handle(sid, Command.Bank.Balance)

            val infos = h.drain().filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
            assertTrue(infos.any { it.contains("200") }, "got=$infos")
            assertTrue(infos.any { it.contains("copper sword") }, "got=$infos")
        }

        @Test
        fun `vault full prevents deposit`() = runTest {
            val (h, items) = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            // Fill vault to max (5 items)
            repeat(5) { i ->
                h.players.get(sid)!!.bankItems.add(
                    ItemInstance(id = ItemId("item_$i"), item = Item(keyword = "item$i", displayName = "item $i")),
                )
            }
            items.addToInventory(sid, sword())
            h.drain()

            h.router.handle(sid, Command.Bank.DepositItem("sword"))

            val errors = h.drain().filterIsInstance<OutboundEvent.SendError>().map { it.text }
            assertTrue(errors.any { it.contains("full") }, "got=$errors")
        }
    }
}
