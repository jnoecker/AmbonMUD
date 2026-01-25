package dev.ambon.engine.commands

import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.WorldFactory
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.persistence.InMemoryPlayerRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommandRouterBroadcastTest {
    @Test
    fun `say broadcasts to other players in same room and echoes to sender`() =
        runTest {
            val world = WorldFactory.demoWorld()
            val players = PlayerRegistry(world.startRoom, InMemoryPlayerRepository())
            val outbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val router = CommandRouter(world, players, outbound)

            val a = SessionId(1)
            val b = SessionId(2)
            players.connect(a)
            players.connect(b)

            router.handle(a, Command.Say("hello"))

            val outs = drain(outbound)

            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.sessionId == a && it.text == "You say: hello" },
                "Sender should see 'You say'. got=$outs",
            )

            // b should see "Player1 says: hello" (since a connected first)
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.sessionId == b && it.text.contains("says: hello") },
                "Other player should see broadcast. got=$outs",
            )
        }

    @Test
    fun `say does not broadcast across rooms`() =
        runTest {
            val world = WorldFactory.demoWorld()
            val players = PlayerRegistry(world.startRoom, InMemoryPlayerRepository())
            val outbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val router = CommandRouter(world, players, outbound)

            val a = SessionId(1)
            val b = SessionId(2)
            players.connect(a)
            players.connect(b)

            // Move b north into a different room
            router.handle(b, Command.Move(dev.ambon.domain.world.Direction.NORTH))
            drain(outbound) // ignore look output

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
    fun `who lists connected players`() =
        runTest {
            val world = WorldFactory.demoWorld()
            val players = PlayerRegistry(world.startRoom, InMemoryPlayerRepository())
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

    private fun drain(ch: Channel<OutboundEvent>): List<OutboundEvent> {
        val out = mutableListOf<OutboundEvent>()
        while (true) {
            val ev = ch.tryReceive().getOrNull() ?: break
            out += ev
        }
        return out
    }
}
