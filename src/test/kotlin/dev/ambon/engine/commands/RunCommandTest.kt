package dev.ambon.engine.commands

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.Direction
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.LoginResult
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.WorldStateRegistry
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.drainAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RunCommandTest {
    private val world = dev.ambon.test.TestWorlds.okSmall
    private val roomA: RoomId = world.startRoom
    private val roomB = RoomId("ok_small:b")

    @Test
    fun `run executes multiple moves in sequence`() =
        runTest {
            val h = harness()
            // a -> b -> a (north then south)
            h.router.handle(h.sid, Command.Run(listOf(Direction.NORTH, Direction.SOUTH)))
            h.outbound.drainAll()
            assertEquals(roomA, h.players.get(h.sid)?.roomId)
        }

    @Test
    fun `run keeps trying after an invalid move`() =
        runTest {
            val h = harness()
            // From a, can go north to b. Going north again fails. Then south goes back to a.
            h.router.handle(h.sid, Command.Run(listOf(Direction.NORTH, Direction.NORTH, Direction.SOUTH)))
            val outs = h.outbound.drainAll()
            assertEquals(roomA, h.players.get(h.sid)?.roomId)
            // The middle step should have failed with "can't go that way".
            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.text.contains("can't go", ignoreCase = true) },
                "Expected a failed-move error among outputs",
            )
        }

    @Test
    fun `run with empty steps sends usage error`() =
        runTest {
            val h = harness()
            h.router.handle(h.sid, Command.Run(emptyList()))
            val outs = h.outbound.drainAll()
            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.text.contains("Usage", ignoreCase = true) },
                "Expected usage error for empty run",
            )
            assertEquals(roomA, h.players.get(h.sid)?.roomId)
        }

    @Test
    fun `parser run command moves player to destination`() =
        runTest {
            val h = harness()
            val cmd = CommandParser.parse("run 1n") as Command.Run
            h.router.handle(h.sid, cmd)
            h.outbound.drainAll()
            assertEquals(roomB, h.players.get(h.sid)?.roomId)
        }

    private data class Harness(
        val sid: SessionId,
        val players: dev.ambon.engine.PlayerRegistry,
        val router: CommandRouter,
        val outbound: LocalOutboundBus,
    )

    private suspend fun harness(): Harness {
        val items = ItemRegistry()
        items.loadSpawns(world.itemSpawns)
        val outbound = LocalOutboundBus()
        val players = dev.ambon.test.buildTestPlayerRegistry(roomA, InMemoryPlayerRepository(), items)
        val mobs = MobRegistry()
        val worldState = WorldStateRegistry(world)
        val router =
            buildTestRouter(
                world = world,
                players = players,
                mobs = mobs,
                items = items,
                combat = CombatSystem(players, mobs, items, outbound),
                outbound = outbound,
                worldState = worldState,
            )
        val sid = SessionId(1)
        val res = players.login(sid, "Runner", "password")
        require(res == LoginResult.Ok)
        outbound.drainAll()
        return Harness(sid, players, router, outbound)
    }
}
