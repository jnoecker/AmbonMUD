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
            Assertions.assertTrue(
                outs.any { it is OutboundEvent.SendText && it.text == "The Foyer" },
                "Missing title. got=$outs",
            )
            Assertions.assertTrue(
                outs.any { it is OutboundEvent.SendText && it.text.contains("A small foyer") },
                "Missing description. got=$outs",
            )
            Assertions.assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.startsWith("Exits:") },
                "Missing exits. got=$outs",
            )
            Assertions.assertTrue(outs.any { it is OutboundEvent.SendPrompt }, "Missing prompt. got=$outs")
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
            Assertions.assertTrue(
                outs.any { it is OutboundEvent.SendText && it.text == "A Quiet Hallway" },
                "Expected to see new room title after moving north. got=$outs",
            )
            Assertions.assertTrue(outs.any { it is OutboundEvent.SendPrompt }, "Missing prompt. got=$outs")
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

            Assertions.assertTrue(
                outs.any { it is OutboundEvent.SendText && it.text.contains("can't go that way", ignoreCase = true) },
                "Expected blocked movement message. got=$outs",
            )
            Assertions.assertTrue(outs.any { it is OutboundEvent.SendPrompt }, "Missing prompt. got=$outs")
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
