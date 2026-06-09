package dev.ambon.engine.commands

import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.items.ItemSlot
import dev.ambon.domain.mail.MailMessage
import dev.ambon.engine.commands.handlers.EngineContext
import dev.ambon.engine.commands.handlers.MailHandler
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.CommandRouterHarness
import dev.ambon.test.MutableClock
import dev.ambon.test.TestWorlds
import dev.ambon.test.buildTestPlayerRegistry
import dev.ambon.test.createTestPlayer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MailHandlerTest {
    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun harness(repo: InMemoryPlayerRepository = InMemoryPlayerRepository()): CommandRouterHarness {
        val players = buildTestPlayerRegistry(TestWorlds.testWorld.startRoom, repo)
        return CommandRouterHarness.create(players = players, repo = repo)
    }

    // -------------------------------------------------------------------------
    // mail list
    // -------------------------------------------------------------------------

    @Test
    fun `mail list shows empty inbox message when no messages`() =
        runTest {
            val h = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            h.drain()

            h.router.handle(sid, Command.Mail.List)

            val texts = h.drain().filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
            assertTrue(texts.any { it.contains("empty", ignoreCase = true) }, "Expected empty-inbox message. got=$texts")
        }

    @Test
    fun `mail list shows messages with unread markers`() =
        runTest {
            val h = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            val ps = h.players.get(sid)!!

            ps.inbox.add(MailMessage(id = "1", fromName = "Bob", body = "Hello!", sentAtEpochMs = 1000L, read = false))
            ps.inbox.add(MailMessage(id = "2", fromName = "Eve", body = "Hey", sentAtEpochMs = 2000L, read = true))
            h.drain()

            h.router.handle(sid, Command.Mail.List)

            val texts = h.drain().filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
            assertTrue(texts.any { it.contains("[NEW]") && it.contains("Bob") }, "Expected [NEW] marker for unread. got=$texts")
            assertTrue(texts.any { it.contains("Eve") && !it.contains("[NEW]") }, "Expected no marker for read. got=$texts")
        }

    // -------------------------------------------------------------------------
    // mail read
    // -------------------------------------------------------------------------

    @Test
    fun `mail read displays message body and marks it as read`() =
        runTest {
            val h = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            val ps = h.players.get(sid)!!
            ps.inbox.add(MailMessage(id = "1", fromName = "Bob", body = "Hi there!", sentAtEpochMs = 0L, read = false))
            h.drain()

            h.router.handle(sid, Command.Mail.Read(1))

            val texts = h.drain().filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
            assertTrue(texts.any { it.contains("Hi there!") }, "Expected message body. got=$texts")
            assertTrue(ps.inbox[0].read, "Message should be marked as read")
        }

    @Test
    fun `mail read returns error for out-of-range index`() =
        runTest {
            val h = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            h.drain()

            h.router.handle(sid, Command.Mail.Read(99))

            val errors = h.drain().filterIsInstance<OutboundEvent.SendError>()
            assertTrue(errors.isNotEmpty(), "Expected error for invalid index")
        }

    // -------------------------------------------------------------------------
    // mail delete
    // -------------------------------------------------------------------------

    @Test
    fun `mail delete removes message at given index`() =
        runTest {
            val h = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            val ps = h.players.get(sid)!!
            ps.inbox.add(MailMessage(id = "1", fromName = "Bob", body = "Delete me", sentAtEpochMs = 0L))
            ps.inbox.add(MailMessage(id = "2", fromName = "Eve", body = "Keep me", sentAtEpochMs = 0L))
            h.drain()

            h.router.handle(sid, Command.Mail.Delete(1))

            assertEquals(1, ps.inbox.size, "Inbox should have 1 message after delete")
            assertEquals("Eve", ps.inbox[0].fromName, "Remaining message should be from Eve")
            val texts = h.drain().filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
            assertTrue(texts.any { it.contains("deleted", ignoreCase = true) }, "Expected deletion confirmation. got=$texts")
        }

    @Test
    fun `mail delete returns error for out-of-range index`() =
        runTest {
            val h = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            h.drain()

            h.router.handle(sid, Command.Mail.Delete(5))

            val errors = h.drain().filterIsInstance<OutboundEvent.SendError>()
            assertTrue(errors.isNotEmpty(), "Expected error for invalid delete index")
        }

    // -------------------------------------------------------------------------
    // compose flow: mail send → lines → .
    // -------------------------------------------------------------------------

    @Test
    fun `mail send starts compose mode`() =
        runTest {
            val h = harness()
            val alice = SessionId(1)
            val bob = SessionId(2)
            h.loginPlayer(alice, "Alice")
            h.loginPlayer(bob, "Bob")
            h.drain()

            h.router.handle(alice, Command.Mail.Send("Bob"))

            val alicePs = h.players.get(alice)!!
            assertNotNull(alicePs.mailCompose, "Alice should be in compose mode")
            assertEquals("Bob", alicePs.mailCompose?.recipientName)
        }

    @Test
    fun `compose lines accumulate until dot terminates and delivers to online recipient`() =
        runTest {
            val repo = InMemoryPlayerRepository()
            val clock = MutableClock(1000L)
            val players = buildTestPlayerRegistry(TestWorlds.testWorld.startRoom, repo, clock = clock)
            val h = CommandRouterHarness.create(players = players, repo = repo)

            val alice = SessionId(1)
            val bob = SessionId(2)
            h.loginPlayer(alice, "Alice")
            h.loginPlayer(bob, "Bob")
            h.drain()

            // Start compose
            h.router.handle(alice, Command.Mail.Send("Bob"))
            h.drain()

            // Simulate compose lines via the mail handler directly
            val mailHandler = dev.ambon.engine.commands.handlers.MailHandler(
                ctx = dev.ambon.engine.commands.handlers.EngineContext(
                    players = players,
                    mobs = h.mobs,
                    world = h.world,
                    items = h.items,
                    outbound = h.outbound,
                    combat = h.combat,
                    gmcpEmitter = null,
                    worldState = null,
                ),
                clock = clock,
            )
            mailHandler.handleComposeLine(alice, "Hello Bob,")
            mailHandler.handleComposeLine(alice, "How are you?")
            mailHandler.handleComposeLine(alice, ".")

            val bobPs = h.players.get(bob)!!
            assertEquals(1, bobPs.inbox.size, "Bob should have 1 message")
            assertEquals("Alice", bobPs.inbox[0].fromName)
            assertEquals("Hello Bob,\nHow are you?", bobPs.inbox[0].body)
            assertFalse(bobPs.inbox[0].read, "Message should be unread")

            val alicePs = h.players.get(alice)!!
            assertEquals(null, alicePs.mailCompose, "Alice should not be in compose mode after sending")
        }

    @Test
    fun `dot with empty body does not send mail`() =
        runTest {
            val h = harness()
            val alice = SessionId(1)
            val bob = SessionId(2)
            h.loginPlayer(alice, "Alice")
            h.loginPlayer(bob, "Bob")
            h.drain()

            h.router.handle(alice, Command.Mail.Send("Bob"))
            h.drain()

            val mailHandler = dev.ambon.engine.commands.handlers.MailHandler(
                ctx = dev.ambon.engine.commands.handlers.EngineContext(
                    players = h.players,
                    mobs = h.mobs,
                    world = h.world,
                    items = h.items,
                    outbound = h.outbound,
                    combat = h.combat,
                    gmcpEmitter = null,
                    worldState = null,
                ),
            )
            mailHandler.handleComposeLine(alice, ".")

            val bobPs = h.players.get(bob)!!
            assertEquals(0, bobPs.inbox.size, "Bob should have 0 messages for empty body")

            val errors = h.drain().filterIsInstance<OutboundEvent.SendError>()
            assertTrue(errors.any { it.text.contains("empty", ignoreCase = true) }, "Expected empty-body error. got=$errors")
        }

    // -------------------------------------------------------------------------
    // mail abort
    // -------------------------------------------------------------------------

    @Test
    fun `mail abort cancels in-progress compose`() =
        runTest {
            val h = harness()
            val alice = SessionId(1)
            val bob = SessionId(2)
            h.loginPlayer(alice, "Alice")
            h.loginPlayer(bob, "Bob")
            h.drain()

            h.router.handle(alice, Command.Mail.Send("Bob"))
            h.drain()

            h.router.handle(alice, Command.Mail.Abort)

            val alicePs = h.players.get(alice)!!
            assertEquals(null, alicePs.mailCompose, "Compose state should be cleared after abort")
            val texts = h.drain().filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
            assertTrue(texts.any { it.contains("cancel", ignoreCase = true) }, "Expected cancellation message. got=$texts")
        }

    @Test
    fun `mail abort when not composing returns error`() =
        runTest {
            val h = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            h.drain()

            h.router.handle(sid, Command.Mail.Abort)

            val errors = h.drain().filterIsInstance<OutboundEvent.SendError>()
            assertTrue(errors.isNotEmpty(), "Expected error when aborting without active compose")
        }

    // -------------------------------------------------------------------------
    // Offline delivery
    // -------------------------------------------------------------------------

    @Test
    fun `mail delivers to offline player via repository`() =
        runTest {
            val repo = InMemoryPlayerRepository()
            // Pre-create offline player record
            repo.createTestPlayer("Bob", TestWorlds.testWorld.startRoom)

            val players = buildTestPlayerRegistry(TestWorlds.testWorld.startRoom, repo)
            val h = CommandRouterHarness.create(players = players, repo = repo)

            val alice = SessionId(1)
            h.loginPlayer(alice, "Alice")
            h.drain()

            h.router.handle(alice, Command.Mail.Send("Bob"))
            h.drain()

            val mailHandler = dev.ambon.engine.commands.handlers.MailHandler(
                ctx = dev.ambon.engine.commands.handlers.EngineContext(
                    players = players,
                    mobs = h.mobs,
                    world = h.world,
                    items = h.items,
                    outbound = h.outbound,
                    combat = h.combat,
                    gmcpEmitter = null,
                    worldState = null,
                ),
            )
            mailHandler.handleComposeLine(alice, "Offline message")
            mailHandler.handleComposeLine(alice, ".")

            val bobRecord = repo.findByName("Bob")
            assertNotNull(bobRecord, "Bob's record should still exist")
            assertEquals(1, bobRecord!!.inbox.size, "Bob's record should have 1 mail")
            assertEquals("Alice", bobRecord.inbox[0].fromName)
            assertEquals("Offline message", bobRecord.inbox[0].body)
        }

    @Test
    fun `mail to unknown player returns error`() =
        runTest {
            val h = harness()
            val alice = SessionId(1)
            h.loginPlayer(alice, "Alice")
            h.drain()

            h.router.handle(alice, Command.Mail.Send("NoSuchPlayer"))
            h.drain()

            val mailHandler = dev.ambon.engine.commands.handlers.MailHandler(
                ctx = dev.ambon.engine.commands.handlers.EngineContext(
                    players = h.players,
                    mobs = h.mobs,
                    world = h.world,
                    items = h.items,
                    outbound = h.outbound,
                    combat = h.combat,
                    gmcpEmitter = null,
                    worldState = null,
                ),
            )
            mailHandler.handleComposeLine(alice, "Hello?")
            mailHandler.handleComposeLine(alice, ".")

            val errors = h.drain().filterIsInstance<OutboundEvent.SendError>()
            assertTrue(
                errors.any { it.text.contains("not found", ignoreCase = true) || it.text.contains("No player", ignoreCase = true) },
                "Expected not-found error. got=$errors",
            )
        }

    // -------------------------------------------------------------------------
    // Double-compose guard
    // -------------------------------------------------------------------------

    @Test
    fun `mail send while already composing returns error`() =
        runTest {
            val h = harness()
            val alice = SessionId(1)
            val bob = SessionId(2)
            h.loginPlayer(alice, "Alice")
            h.loginPlayer(bob, "Bob")
            h.drain()

            h.router.handle(alice, Command.Mail.Send("Bob"))
            h.drain()

            // Try to start another compose while one is active
            h.router.handle(alice, Command.Mail.Send("Bob"))

            val errors = h.drain().filterIsInstance<OutboundEvent.SendError>()
            assertTrue(errors.isNotEmpty(), "Expected error when starting compose while already composing")
        }

    // -------------------------------------------------------------------------
    // Attachments — gold + item
    // -------------------------------------------------------------------------

    /** A standalone handler over the harness's state, for driving compose lines. */
    private fun composeHandler(h: CommandRouterHarness): MailHandler =
        MailHandler(
            ctx = EngineContext(
                players = h.players,
                mobs = h.mobs,
                world = h.world,
                items = h.items,
                outbound = h.outbound,
                combat = h.combat,
                gmcpEmitter = null,
                worldState = null,
            ),
        )

    private fun sword() = ItemInstance(
        id = ItemId("sword_1"),
        item = Item(keyword = "sword", displayName = "a copper sword", slot = ItemSlot.WEAPON, damage = 4),
    )

    @Test
    fun `mail send with gold and item consumes from sender and attaches to recipient`() =
        runTest {
            val h = harness()
            val alice = SessionId(1)
            val bob = SessionId(2)
            h.loginPlayer(alice, "Alice")
            h.loginPlayer(bob, "Bob")
            h.players.get(alice)!!.gold = 1000L
            h.items.addToInventory(alice, sword())
            h.drain()

            h.router.handle(alice, Command.Mail.Send("Bob", gold = 100L, itemKeyword = "sword"))
            h.drain()
            composeHandler(h).run {
                handleComposeLine(alice, "A gift for you")
                handleComposeLine(alice, ".")
            }

            assertEquals(900L, h.players.get(alice)!!.gold, "sender's gold should be consumed")
            assertTrue(h.items.inventory(alice).isEmpty(), "sender's item should be consumed")
            val msg = h.players.get(bob)!!.inbox[0]
            assertEquals(100L, msg.gold)
            assertNotNull(msg.item)
            assertFalse(msg.claimed, "attachment should start unclaimed")
        }

    @Test
    fun `claim grants gold and item to recipient and marks claimed`() =
        runTest {
            val h = harness()
            val alice = SessionId(1)
            val bob = SessionId(2)
            h.loginPlayer(alice, "Alice")
            h.loginPlayer(bob, "Bob")
            h.players.get(alice)!!.gold = 1000L
            h.items.addToInventory(alice, sword())
            h.drain()
            h.router.handle(alice, Command.Mail.Send("Bob", gold = 100L, itemKeyword = "sword"))
            h.drain()
            composeHandler(h).run {
                handleComposeLine(alice, "Gift")
                handleComposeLine(alice, ".")
            }
            val bobGoldBefore = h.players.get(bob)!!.gold
            h.drain()

            h.router.handle(bob, Command.Mail.Claim(1))

            assertEquals(bobGoldBefore + 100L, h.players.get(bob)!!.gold, "recipient should gain the gold")
            assertEquals(1, h.items.inventory(bob).size, "recipient should gain the item")
            assertTrue(h.players.get(bob)!!.inbox[0].claimed, "message should be marked claimed")

            // A second claim finds nothing.
            h.drain()
            h.router.handle(bob, Command.Mail.Claim(1))
            val errors = h.drain().filterIsInstance<OutboundEvent.SendError>()
            assertTrue(errors.any { it.text.contains("nothing to claim", ignoreCase = true) }, "got=$errors")
        }

    @Test
    fun `cannot delete a message with an unclaimed gift but can after claiming`() =
        runTest {
            val h = harness()
            val alice = SessionId(1)
            val bob = SessionId(2)
            h.loginPlayer(alice, "Alice")
            h.loginPlayer(bob, "Bob")
            h.players.get(alice)!!.gold = 1000L
            h.drain()
            h.router.handle(alice, Command.Mail.Send("Bob", gold = 50L))
            h.drain()
            composeHandler(h).run {
                handleComposeLine(alice, "Coins")
                handleComposeLine(alice, ".")
            }
            h.drain()

            // Delete is refused while the gift is unclaimed.
            h.router.handle(bob, Command.Mail.Delete(1))
            assertEquals(1, h.players.get(bob)!!.inbox.size, "message should survive a blocked delete")
            val errors = h.drain().filterIsInstance<OutboundEvent.SendError>()
            assertTrue(errors.any { it.text.contains("claim", ignoreCase = true) }, "got=$errors")

            // After claiming, delete works.
            h.router.handle(bob, Command.Mail.Claim(1))
            h.drain()
            h.router.handle(bob, Command.Mail.Delete(1))
            assertEquals(0, h.players.get(bob)!!.inbox.size, "message should be deletable after claim")
        }

    @Test
    fun `attaching more gold than held is rejected before composing`() =
        runTest {
            val h = harness()
            val alice = SessionId(1)
            val bob = SessionId(2)
            h.loginPlayer(alice, "Alice")
            h.loginPlayer(bob, "Bob")
            h.players.get(alice)!!.gold = 30L
            h.drain()

            h.router.handle(alice, Command.Mail.Send("Bob", gold = 100L))

            val errors = h.drain().filterIsInstance<OutboundEvent.SendError>()
            assertTrue(errors.any { it.text.contains("only have", ignoreCase = true) }, "got=$errors")
            assertEquals(null, h.players.get(alice)!!.mailCompose, "compose should not start")
            assertEquals(30L, h.players.get(alice)!!.gold, "gold should be untouched")
        }
}
