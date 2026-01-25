package dev.ambon.engine.commands

import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.WorldFactory
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.events.OutboundEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
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

            router.handle(sid, Command.Look)

            val outs = drain(outbound)

            // Expected: SendText(title), SendText(desc), SendInfo(exits), SendPrompt
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.text == "The Foyer" },
                "Missing title. got=$outs",
            )
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.text.contains("A small foyer") },
                "Missing description. got=$outs",
            )
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.startsWith("Exits:") },
                "Missing exits. got=$outs",
            )
            assertTrue(outs.any { it is OutboundEvent.SendPrompt }, "Missing prompt. got=$outs")
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

            router.handle(sid, Command.Move(Direction.NORTH))

            val outs = drain(outbound)

            // After moving north, demo world room 2 title is "A Quiet Hallway"
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.text == "A Quiet Hallway" },
                "Expected to see new room title after moving north. got=$outs",
            )
            assertTrue(outs.any { it is OutboundEvent.SendPrompt }, "Missing prompt. got=$outs")
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
    fun `say does not broadcast across rooms`() =
        runTest {
            val world = WorldFactory.demoWorld()
            val players = PlayerRegistry(world.startRoom)
            val outbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val router = CommandRouter(world, players, outbound)

            val a = SessionId(1)
            val b = SessionId(2)
            players.connect(a)
            players.connect(b)

            // Move b north into a different room
            router.handle(b, Command.Move(Direction.NORTH))
            drain(outbound)

            router.handle(a, Command.Say("psst"))
            val outs = drain(outbound)

            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.sessionId == a && it.text == "You say: psst" },
                "Sender should see self echo. got=$outs",
            )
            assertFalse(
                outs.any { it is OutboundEvent.SendText && it.sessionId == b && it.text.contains("psst") },
                "Player in another room should not receive broadcast. got=$outs",
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
    fun `who lists connected players`() =
        runTest {
            val world = WorldFactory.demoWorld()
            val players = PlayerRegistry(world.startRoom)
            val outbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val router = CommandRouter(world, players, outbound)

            val a = SessionId(1)
            val b = SessionId(2)
            players.connect(a)
            players.connect(b)

            router.handle(a, Command.Who)
            val outs = drain(outbound)

            val msg =
                outs
                    .filterIsInstance<OutboundEvent.SendInfo>()
                    .firstOrNull { it.sessionId == a }
                    ?.text

            assertNotNull(msg, "Expected SendInfo for who. got=$outs")
            assertTrue(msg!!.contains("Player1"), "Expected Player1 in who output. got=$msg")
            assertTrue(msg.contains("Player2"), "Expected Player2 in who output. got=$msg")
        }

    @Test
    fun `name sets and who reflects new name`() =
        runTest {
            val world = WorldFactory.demoWorld()
            val players = PlayerRegistry(world.startRoom)
            val outbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val router = CommandRouter(world, players, outbound)

            val a = SessionId(1)
            players.connect(a)

            router.handle(a, Command.Name("Alice"))
            router.handle(a, Command.Who)

            val outs = drain(outbound)

            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.sessionId == a && it.text.contains("Name set to Alice") },
                "Expected confirmation. got=$outs",
            )
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.sessionId == a && it.text.contains("Alice") && it.text.contains("Online:") },
                "Expected who to show Alice. got=$outs",
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
    fun `tell across rooms still works`() =
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
            router.handle(b, Command.Name("Bob"))
            drain(outbound)

            // Move Bob north so he is not in Alice's room
            router.handle(b, Command.Move(Direction.NORTH))
            drain(outbound)

            router.handle(a, Command.Tell("Bob", "still works"))
            val outs = drain(outbound)

            assertTrue(outs.any { it is OutboundEvent.SendText && it.sessionId == b && it.text.contains("still works") })
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
