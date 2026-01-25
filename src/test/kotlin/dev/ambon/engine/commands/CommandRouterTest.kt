package dev.ambon.engine.commands

import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.WorldFactory
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.events.OutboundEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommandRouterTest {
    @Test
    fun `look emits room title description exits and prompt`() =
        runTest {
            val world = WorldFactory.demoWorld()
            val players = PlayerRegistry(world.startRoom)

            val outbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val router = CommandRouter(world, players, outbound)

            val sid = SessionId(1)
            players.connect(sid)

            val startRoom = world.rooms.getValue(world.startRoom)

            router.handle(sid, Command.Look)

            val outs = drain(outbound)

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
    fun `move north changes room and then look describes new room`() =
        runTest {
            val world = WorldFactory.demoWorld()
            val players = PlayerRegistry(world.startRoom)

            val outbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val router = CommandRouter(world, players, outbound)

            val sid = SessionId(2)
            players.connect(sid)

            val startRoom = world.rooms.getValue(world.startRoom)
            val northTargetId = startRoom.exits[Direction.NORTH]

            // If the demo world doesn't have a north exit, that's a test setup issue; fail loudly.
            Assertions.assertNotNull(
                northTargetId,
                "Demo world start room '${startRoom.id}' must have a NORTH exit for this test",
            )

            val northRoom = world.rooms.getValue(northTargetId!!)

            router.handle(sid, Command.Move(Direction.NORTH))

            val outs = drain(outbound)

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
    fun `move blocked emits can't go that way and prompt`() =
        runTest {
            val world = WorldFactory.demoWorld()
            val players = PlayerRegistry(world.startRoom)

            val outbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val router = CommandRouter(world, players, outbound)

            val sid = SessionId(3)
            players.connect(sid)

            // From The Foyer, WEST is not an exit in the demo world
            router.handle(sid, Command.Move(Direction.WEST))

            val outs = drain(outbound)

            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.text.contains("can't go that way", ignoreCase = true) },
                "Expected blocked movement message. got=$outs",
            )
            assertTrue(outs.any { it is OutboundEvent.SendPrompt }, "Missing prompt. got=$outs")
        }

    @Test
    fun `tell to unknown name emits error to sender only`() =
        runTest {
            val world = WorldFactory.demoWorld()
            val players = PlayerRegistry(world.startRoom)
            val outbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val router = CommandRouter(world, players, outbound)

            val alice = SessionId(1)
            val bob = SessionId(2)
            players.connect(alice)
            players.connect(bob)

            router.handle(alice, Command.Name("Alice"))
            router.handle(bob, Command.Name("Bob"))
            drain(outbound)

            router.handle(alice, Command.Tell("Charlie", "hi"))
            val outs = drain(outbound)

            assertTrue(
                outs.any {
                    it is OutboundEvent.SendError && it.sessionId == alice &&
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
            val world = WorldFactory.demoWorld()
            val players = PlayerRegistry(world.startRoom)
            val outbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val router = CommandRouter(world, players, outbound)

            val alice = SessionId(1)
            val bob = SessionId(2)
            val eve = SessionId(3)
            players.connect(alice)
            players.connect(bob)
            players.connect(eve)

            router.handle(alice, Command.Name("Alice"))
            router.handle(bob, Command.Name("Bob"))
            router.handle(eve, Command.Name("Eve"))
            drain(outbound)

            router.handle(alice, Command.Tell("Bob", "secret"))
            val outs = drain(outbound)

            assertTrue(
                outs.any {
                    it is OutboundEvent.SendText && it.sessionId == alice && it.text.contains("You tell", ignoreCase = true) &&
                        it.text.contains("secret")
                },
                "Sender should get confirmation. got=$outs",
            )
            assertTrue(
                outs.any {
                    it is OutboundEvent.SendText && it.sessionId == bob && it.text.contains("tells you", ignoreCase = true) &&
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
            val world = WorldFactory.demoWorld()
            val players = PlayerRegistry(world.startRoom)
            val outbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val router = CommandRouter(world, players, outbound)

            val alice = SessionId(1)
            val bob = SessionId(2)
            players.connect(alice)
            players.connect(bob)

            router.handle(alice, Command.Name("Alice"))
            router.handle(bob, Command.Name("Bob"))
            drain(outbound)

            router.handle(alice, Command.Say("hello"))
            val outs = drain(outbound)

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
            val world = WorldFactory.demoWorld()
            val players = PlayerRegistry(world.startRoom)
            val outbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val router = CommandRouter(world, players, outbound)

            val alice = SessionId(1)
            val bob = SessionId(2)
            val eve = SessionId(3)
            players.connect(alice)
            players.connect(bob)
            players.connect(eve)

            router.handle(alice, Command.Name("Alice"))
            router.handle(bob, Command.Name("Bob"))
            router.handle(eve, Command.Name("Eve"))
            drain(outbound)

            router.handle(alice, Command.Gossip("hello all"))
            val outs = drain(outbound)

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
    fun `name uniqueness is case-insensitive`() =
        runTest {
            val world = WorldFactory.demoWorld()
            val players = PlayerRegistry(world.startRoom)
            val outbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val router = CommandRouter(world, players, outbound)

            val a = SessionId(1)
            val b = SessionId(2)
            players.connect(a)
            players.connect(b)

            router.handle(a, Command.Name("Alice"))
            router.handle(b, Command.Name("alice"))

            val outs = drain(outbound)

            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.sessionId == b && it.text.contains("taken", ignoreCase = true) },
                "Expected taken error. got=$outs",
            )
        }

    @Test
    fun `exits emits exits line and prompt only`() =
        runTest {
            val world = WorldFactory.demoWorld()
            val players = PlayerRegistry(world.startRoom)

            val outbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val router = CommandRouter(world, players, outbound)

            val sid = SessionId(10)
            players.connect(sid)

            router.handle(sid, Command.Exits)

            val outs = drain(outbound)

            // Only exits + prompt (no title/description)
            assertTrue(outs.any { it is OutboundEvent.SendInfo && it.text.startsWith("Exits:") }, "Missing exits line. got=$outs")
            assertTrue(outs.any { it is OutboundEvent.SendPrompt }, "Missing prompt. got=$outs")
            assertFalse(outs.any { it is OutboundEvent.SendText }, "Should not send title/desc for exits. got=$outs")
        }

    @Test
    fun `look dir shows adjacent room title when exit exists`() =
        runTest {
            val world = WorldFactory.demoWorld()
            val players = PlayerRegistry(world.startRoom)

            val outbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val router = CommandRouter(world, players, outbound)

            val sid = SessionId(11)
            players.connect(sid)

            val startRoom = world.rooms.getValue(world.startRoom)
            val (dir, targetId) =
                startRoom.exits.entries.firstOrNull()
                    ?: error("Demo world start room must have at least one exit for this test")

            val target = world.rooms.getValue(targetId)

            router.handle(sid, Command.LookDir(dir))

            val outs = drain(outbound)
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.text == target.title },
                "Expected target title '${target.title}'. got=$outs",
            )
            assertTrue(outs.any { it is OutboundEvent.SendPrompt }, "Missing prompt. got=$outs")
        }

    @Test
    fun `look dir shows message when no exit exists`() =
        runTest {
            val world = WorldFactory.demoWorld()
            val players = PlayerRegistry(world.startRoom)

            val outbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val router = CommandRouter(world, players, outbound)

            val sid = SessionId(12)
            players.connect(sid)

            val startRoom = world.rooms.getValue(world.startRoom)
            val missingDir =
                Direction.values().firstOrNull { it !in startRoom.exits.keys }
                    ?: error("Demo world start room must be missing at least one direction for this test")

            router.handle(sid, Command.LookDir(missingDir))

            val outs = drain(outbound)
            assertTrue(
                outs.any {
                    it is OutboundEvent.SendError && it.text.contains("nothing", ignoreCase = true)
                },
                "Expected 'nothing that way' message. got=$outs",
            )
            assertTrue(outs.any { it is OutboundEvent.SendPrompt }, "Missing prompt. got=$outs")
        }

    private fun drain(ch: Channel<OutboundEvent>): List<OutboundEvent> {
        val out = mutableListOf<OutboundEvent>()
        while (true) {
            val ev = ch.tryReceive().getOrNull() ?: break
            out += ev
        }
        return out
    }
}
