package dev.ambon.engine.commands

import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.Direction
import dev.ambon.engine.LoginResult
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.test.CommandRouterHarness
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommandRouterTest {
    @Test
    fun `look emits room title description exits and prompt`() =
        runTest {
            val h = CommandRouterHarness.create()

            val sid = SessionId(1)
            h.loginPlayer(sid, "Player1")

            val startRoom = h.world.rooms.getValue(h.world.startRoom)

            h.router.handle(sid, Command.Look)

            val outs = h.drain()

            // Expected: SendText(title), SendText(desc), SendInfo(exits), SendPrompt
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.text == startRoom.title },
                "Missing title '${startRoom.title}'. got=$outs",
            )
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.text.contains(startRoom.description.take(10)) },
                "Missing description containing '${startRoom.description.take(10)}...'. got=$outs",
            )
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.startsWith("Exits:") },
                "Missing exits. got=$outs",
            )
            assertTrue(
                outs.any { it is OutboundEvent.SendPrompt },
                "Missing prompt. got=$outs",
            )
        }

    @Test
    fun `look includes players currently in room`() =
        runTest {
            val h = CommandRouterHarness.create()

            val alice = SessionId(1)
            val bob = SessionId(2)
            h.loginPlayer(alice, "Alice")
            h.loginPlayer(bob, "Bob")
            h.drain()

            h.router.handle(alice, Command.Look)

            val outs = h.drain()
            assertTrue(
                outs.any {
                    it is OutboundEvent.SendInfo && it.text == "Players here: Alice, Bob"
                },
                "Expected room roster line. got=$outs",
            )
        }

    @Test
    fun `move north changes room and then look describes new room`() =
        runTest {
            val h = CommandRouterHarness.create()

            val sid = SessionId(2)
            h.loginPlayer(sid, "Player2")

            val startRoom = h.world.rooms.getValue(h.world.startRoom)
            val northTargetId = startRoom.exits[Direction.NORTH]

            // If the demo world doesn't have a north exit, that's a test setup issue; fail loudly.
            Assertions.assertNotNull(
                northTargetId,
                "Demo world start room '${startRoom.id}' must have a NORTH exit for this test",
            )

            val northRoom = h.world.rooms.getValue(northTargetId!!)

            h.router.handle(sid, Command.Move(Direction.NORTH))

            val outs = h.drain()

            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.text == northRoom.title },
                "Expected to see new room title '${northRoom.title}' after moving north. got=$outs",
            )
            assertTrue(
                outs.any { it is OutboundEvent.SendPrompt },
                "Missing prompt. got=$outs",
            )
        }

    @Test
    fun `move broadcasts leave and enter to room members`() =
        runTest {
            val h = CommandRouterHarness.create()

            val alice = SessionId(1)
            val bob = SessionId(2)
            val charlie = SessionId(3)
            h.loginPlayer(alice, "Alice")
            h.loginPlayer(bob, "Bob")
            h.loginPlayer(charlie, "Charlie")

            val startRoom = h.world.rooms.getValue(h.world.startRoom)
            val northTargetId = startRoom.exits[Direction.NORTH]
            Assertions.assertNotNull(
                northTargetId,
                "Demo world start room '${startRoom.id}' must have a NORTH exit for this test",
            )

            // Move Charlie to the north room so Alice will "enter" his room.
            h.router.handle(charlie, Command.Move(Direction.NORTH))
            h.drain()

            h.router.handle(alice, Command.Move(Direction.NORTH))
            val outs = h.drain()

            assertTrue(
                outs.any {
                    it is OutboundEvent.SendText && it.sessionId == bob && it.text == "Alice leaves."
                },
                "Bob should see leave broadcast. got=$outs",
            )
            assertTrue(
                outs.any {
                    it is OutboundEvent.SendText && it.sessionId == charlie && it.text == "Alice enters."
                },
                "Charlie should see enter broadcast. got=$outs",
            )
        }

    @Test
    fun `move blocked emits can't go that way and prompt`() =
        runTest {
            val h = CommandRouterHarness.create()

            val sid = SessionId(3)
            h.loginPlayer(sid, "Player3")

            val startRoom = h.world.rooms.getValue(h.world.startRoom)
            val missingDir =
                Direction.entries.firstOrNull { it !in startRoom.exits.keys }
                    ?: error("Demo world start room must be missing at least one direction for this test")

            h.router.handle(sid, Command.Move(missingDir))

            val outs = h.drain()

            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.text.contains("can't go that way", ignoreCase = true) },
                "Expected blocked movement message. got=$outs",
            )
            assertTrue(outs.any { it is OutboundEvent.SendPrompt }, "Missing prompt. got=$outs")
        }

    @Test
    fun `tell to unknown name emits error to sender only`() =
        runTest {
            val h = CommandRouterHarness.create()

            val alice = SessionId(1)
            val bob = SessionId(2)
            h.loginPlayer(alice, "Alice")
            h.loginPlayer(bob, "Bob")
            h.drain()

            h.router.handle(alice, Command.Tell("Charlie", "hi"))
            val outs = h.drain()

            assertTrue(
                outs.any {
                    it is OutboundEvent.SendError &&
                        it.sessionId == alice &&
                        it.text.contains(
                            "No such player",
                            ignoreCase = true,
                        )
                },
                "Expected error to sender for unknown name. got=$outs",
            )
            assertFalse(
                outs.any { it is OutboundEvent.SendText && it.sessionId == bob && it.text.contains("hi") },
                "Other players should not receive tells to unknown. got=$outs",
            )
        }

    @Test
    fun `tell delivers to target only and not to third party`() =
        runTest {
            val h = CommandRouterHarness.create()

            val alice = SessionId(1)
            val bob = SessionId(2)
            val eve = SessionId(3)
            h.loginPlayer(alice, "Alice")
            h.loginPlayer(bob, "Bob")
            h.loginPlayer(eve, "Eve")
            h.drain()

            h.router.handle(alice, Command.Tell("Bob", "secret"))
            val outs = h.drain()

            assertTrue(
                outs.any {
                    it is OutboundEvent.SendText &&
                        it.sessionId == alice &&
                        it.text.contains("You tell", ignoreCase = true) &&
                        it.text.contains("secret")
                },
                "Sender should get confirmation. got=$outs",
            )
            assertTrue(
                outs.any {
                    it is OutboundEvent.SendText &&
                        it.sessionId == bob &&
                        it.text.contains("tells you", ignoreCase = true) &&
                        it.text.contains("secret")
                },
                "Target should receive tell. got=$outs",
            )
            assertFalse(
                outs.any { it is OutboundEvent.SendText && it.sessionId == eve && it.text.contains("secret") },
                "Third party should not see tell. got=$outs",
            )
        }

    @Test
    fun `say broadcasts only to room members and echoes to sender`() =
        runTest {
            val h = CommandRouterHarness.create()

            val alice = SessionId(1)
            val bob = SessionId(2)
            h.loginPlayer(alice, "Alice")
            h.loginPlayer(bob, "Bob")
            h.drain()

            h.router.handle(alice, Command.Say("hello"))
            val outs = h.drain()

            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.sessionId == alice && it.text == "You say: hello" },
                "Sender should see 'You say'. got=$outs",
            )
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.sessionId == bob && it.text == "Alice says: hello" },
                "Other room member should see broadcast. got=$outs",
            )
        }

    @Test
    fun `gossip broadcasts to all connected`() =
        runTest {
            val h = CommandRouterHarness.create()

            val alice = SessionId(1)
            val bob = SessionId(2)
            val eve = SessionId(3)
            h.loginPlayer(alice, "Alice")
            h.loginPlayer(bob, "Bob")
            h.loginPlayer(eve, "Eve")
            h.drain()

            h.router.handle(alice, Command.Gossip("hello all"))
            val outs = h.drain()

            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.sessionId == alice && it.text.contains("You gossip: hello all") },
                "Sender should see self-gossip line. got=$outs",
            )
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.sessionId == bob && it.text.contains("[GOSSIP] Alice: hello all") },
                "Other players should see gossip. got=$outs",
            )
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.sessionId == eve && it.text.contains("[GOSSIP] Alice: hello all") },
                "All players should see gossip. got=$outs",
            )
        }

    @Test
    fun `login name uniqueness is case-insensitive`() =
        runTest {
            val h = CommandRouterHarness.create()

            val a = SessionId(1)
            val b = SessionId(2)

            val res1 = h.players.login(a, "Alice", "password")
            val res2 = h.players.login(b, "alice", "password")

            assertEquals(LoginResult.Ok, res1)
            assertTrue(res2 is LoginResult.Takeover, "Expected Takeover for duplicate name with correct password. got=$res2")
        }

    @Test
    fun `exits emits exits line and prompt only`() =
        runTest {
            val h = CommandRouterHarness.create()

            val sid = SessionId(10)
            h.loginPlayer(sid, "Player10")

            h.router.handle(sid, Command.Exits)

            val outs = h.drain()

            // Only exits + prompt (no title/description)
            assertTrue(outs.any { it is OutboundEvent.SendInfo && it.text.startsWith("Exits:") }, "Missing exits line. got=$outs")
            assertTrue(outs.any { it is OutboundEvent.SendPrompt }, "Missing prompt. got=$outs")
            assertFalse(outs.any { it is OutboundEvent.SendText }, "Should not send title/desc for exits. got=$outs")
        }

    @Test
    fun `look dir shows adjacent room title when exit exists`() =
        runTest {
            val h = CommandRouterHarness.create()

            val sid = SessionId(11)
            h.loginPlayer(sid, "Player11")

            val startRoom = h.world.rooms.getValue(h.world.startRoom)
            val (dir, targetId) =
                startRoom.exits.entries.firstOrNull()
                    ?: error("Demo world start room must have at least one exit for this test")

            val target = h.world.rooms.getValue(targetId)

            h.router.handle(sid, Command.LookDir(dir))

            val outs = h.drain()
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.text == target.title },
                "Expected target title '${target.title}'. got=$outs",
            )
            assertTrue(outs.any { it is OutboundEvent.SendPrompt }, "Missing prompt. got=$outs")
        }

    @Test
    fun `look dir shows message when no exit exists`() =
        runTest {
            val h = CommandRouterHarness.create()

            val sid = SessionId(12)
            h.loginPlayer(sid, "Player12")

            val startRoom = h.world.rooms.getValue(h.world.startRoom)
            val missingDir =
                Direction.entries.firstOrNull { it !in startRoom.exits.keys }
                    ?: error("Demo world start room must be missing at least one direction for this test")

            h.router.handle(sid, Command.LookDir(missingDir))

            val outs = h.drain()
            assertTrue(
                outs.any {
                    it is OutboundEvent.SendError && it.text.contains("nothing", ignoreCase = true)
                },
                "Expected 'nothing that way' message. got=$outs",
            )
            assertTrue(outs.any { it is OutboundEvent.SendPrompt }, "Missing prompt. got=$outs")
        }
}
