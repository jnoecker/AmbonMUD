package dev.ambon.engine.commands

import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.WorldFactory
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.LoginResult
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NamesTellGossipTest {
    @Test
    fun `name sets and who reflects new name`() =
        runTest {
            val world = WorldFactory.demoWorld()
            val items = ItemRegistry()
            val players = PlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val outbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val router = CommandRouter(world, players, mobs, items, CombatSystem(players, mobs, items, outbound), outbound)

            val a = SessionId(1)
            login(players, a, "Alice")
            router.handle(a, Command.Who)

            val outs = drain(outbound)

            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.sessionId == a && it.text.contains("Alice") && it.text.contains("Online:") },
                "Expected who to show Alice. got=$outs",
            )
        }

    @Test
    fun `name must be unique case-insensitively`() =
        runTest {
            val world = WorldFactory.demoWorld()
            val items = ItemRegistry()
            val players = PlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)

            val a = SessionId(1)
            val b = SessionId(2)
            login(players, a, "Alice")

            val res = players.login(b, "alice", "password")
            assertTrue(res == LoginResult.Taken, "Expected taken for duplicate login. got=$res")
        }

    @Test
    fun `tell delivers to target only`() =
        runTest {
            val world = WorldFactory.demoWorld()
            val items = ItemRegistry()
            val players = PlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val outbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val mobs = MobRegistry()
            val router = CommandRouter(world, players, mobs, items, CombatSystem(players, mobs, items, outbound), outbound)

            val a = SessionId(1)
            val b = SessionId(2)
            val c = SessionId(3)
            login(players, a, "Alice")
            login(players, b, "Bob")
            login(players, c, "Eve")
            drain(outbound)

            router.handle(a, Command.Tell("Bob", "hi there"))
            val outs = drain(outbound)

            assertTrue(
                outs.any {
                    it is OutboundEvent.SendText &&
                        it.sessionId == a &&
                        it.text.contains(
                            "You tell",
                        ) &&
                        it.text.contains("hi there")
                },
                "Sender should see confirmation. got=$outs",
            )
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.sessionId == b && it.text.contains("Alice tells you: hi there") },
                "Target should receive tell. got=$outs",
            )
            assertFalse(
                outs.any { it is OutboundEvent.SendText && it.sessionId == c && it.text.contains("hi there") },
                "Non-target should not receive tell. got=$outs",
            )
        }

    @Test
    fun `gossip broadcasts to all connected`() =
        runTest {
            val world = WorldFactory.demoWorld()
            val items = ItemRegistry()
            val players = PlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val outbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val router = CommandRouter(world, players, mobs, items, CombatSystem(players, mobs, items, outbound), outbound)

            val a = SessionId(1)
            val b = SessionId(2)
            login(players, a, "Alice")
            login(players, b, "Bob")
            drain(outbound)

            router.handle(a, Command.Gossip("hello everyone"))
            val outs = drain(outbound)

            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.sessionId == a && it.text.contains("You gossip: hello everyone") },
                "Sender should see self message. got=$outs",
            )
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.sessionId == b && it.text.contains("[GOSSIP] Alice: hello everyone") },
                "Other should see gossip broadcast. got=$outs",
            )
        }

    @Test
    fun `tell across rooms still works`() =
        runTest {
            val world = WorldFactory.demoWorld()
            val items = ItemRegistry()
            val players = PlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val outbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val router = CommandRouter(world, players, mobs, items, CombatSystem(players, mobs, items, outbound), outbound)

            val a = SessionId(1)
            val b = SessionId(2)
            login(players, a, "Alice")
            login(players, b, "Bob")
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

    private suspend fun login(
        players: PlayerRegistry,
        sessionId: SessionId,
        name: String,
    ) {
        val res = players.login(sessionId, name, "password")
        require(res == LoginResult.Ok) { "Login failed: $res" }
    }
}
