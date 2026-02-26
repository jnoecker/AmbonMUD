package dev.ambon.engine.commands

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.load.WorldLoader
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.GroupSystem
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.drainAll
import dev.ambon.test.loginOrFail
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
            val world = WorldLoader.loadFromResource("world/test_world.yaml")
            val items = ItemRegistry()
            val players = PlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val outbound = LocalOutboundBus()
            val router = buildTestRouter(world, players, mobs, items, CombatSystem(players, mobs, items, outbound), outbound)

            val a = SessionId(1)
            val b = SessionId(2)
            players.loginOrFail(a, "Player1")
            players.loginOrFail(b, "Player2")

            router.handle(a, Command.Say("hello"))

            val outs = outbound.drainAll()

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
            val world = WorldLoader.loadFromResource("world/test_world.yaml")
            val items = ItemRegistry()
            val players = PlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val outbound = LocalOutboundBus()
            val router = buildTestRouter(world, players, mobs, items, CombatSystem(players, mobs, items, outbound), outbound)

            val a = SessionId(1)
            val b = SessionId(2)
            players.loginOrFail(a, "Player1")
            players.loginOrFail(b, "Player2")

            // Move b north into a different room
            router.handle(b, Command.Move(dev.ambon.domain.world.Direction.NORTH))
            outbound.drainAll() // ignore look output

            router.handle(a, Command.Say("psst"))
            val outs = outbound.drainAll()

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
            val world = WorldLoader.loadFromResource("world/test_world.yaml")
            val items = ItemRegistry()
            val players = PlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val outbound = LocalOutboundBus()
            val router = buildTestRouter(world, players, mobs, items, CombatSystem(players, mobs, items, outbound), outbound)

            val a = SessionId(1)
            val b = SessionId(2)
            players.loginOrFail(a, "Player1")
            players.loginOrFail(b, "Player2")

            router.handle(a, Command.Who)
            val outs = outbound.drainAll()

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
    fun `who shows G indicator for grouped players`() =
        runTest {
            val world = WorldLoader.loadFromResource("world/test_world.yaml")
            val items = ItemRegistry()
            val players = PlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val outbound = LocalOutboundBus()
            val groupSystem = GroupSystem(players, outbound)
            val router =
                buildTestRouter(
                    world,
                    players,
                    mobs,
                    items,
                    CombatSystem(players, mobs, items, outbound),
                    outbound,
                    groupSystem = groupSystem,
                )

            val a = SessionId(1)
            val b = SessionId(2)
            val c = SessionId(3)
            players.loginOrFail(a, "Alice")
            players.loginOrFail(b, "Bob")
            players.loginOrFail(c, "Charlie")
            outbound.drainAll()

            groupSystem.invite(a, "Bob")
            groupSystem.accept(b)
            outbound.drainAll()

            router.handle(c, Command.Who)
            val outs = outbound.drainAll()

            val msg =
                outs
                    .filterIsInstance<OutboundEvent.SendInfo>()
                    .firstOrNull { it.sessionId == c }
                    ?.text

            assertNotNull(msg, "Expected SendInfo for who. got=$outs")
            assertTrue(msg!!.contains("[G] Alice"), "Expected [G] for grouped Alice. got=$msg")
            assertTrue(msg.contains("[G] Bob"), "Expected [G] for grouped Bob. got=$msg")
            assertFalse(msg.contains("[G] Charlie"), "Charlie should not have [G]. got=$msg")
        }
}
