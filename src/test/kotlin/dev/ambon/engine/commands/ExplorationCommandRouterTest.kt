package dev.ambon.engine.commands

import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.load.WorldLoader
import dev.ambon.engine.toPlayerRecord
import dev.ambon.engine.toPlayerState
import dev.ambon.persistence.PlayerId
import dev.ambon.test.CommandRouterHarness
import dev.ambon.test.drainAll
import dev.ambon.test.loginOrFail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExplorationCommandRouterTest {
    private fun createHarness(): CommandRouterHarness =
        CommandRouterHarness.create(world = WorldLoader.loadFromResource("world/ok_small.yaml"))

    @Test
    fun `looking marks the current room explored`() =
        runTest {
            val h = createHarness()
            val sid = SessionId(1)
            h.players.loginOrFail(sid, "Alice")
            h.outbound.drainAll()

            h.router.handle(sid, Command.Look)
            assertTrue("ok_small:a" in h.players.get(sid)!!.exploredRooms)
            assertFalse("ok_small:b" in h.players.get(sid)!!.exploredRooms)
        }

    @Test
    fun `moving marks each visited room explored`() =
        runTest {
            val h = createHarness()
            val sid = SessionId(2)
            h.players.loginOrFail(sid, "Bob")
            h.router.handle(sid, Command.Look)
            h.outbound.drainAll()

            h.router.handle(sid, Command.Move(Direction.NORTH))
            val explored = h.players.get(sid)!!.exploredRooms
            assertTrue("ok_small:a" in explored)
            assertTrue("ok_small:b" in explored)
        }

    @Test
    fun `exploredRooms round-trips through PlayerRecord`() =
        runTest {
            val h = createHarness()
            val sid = SessionId(3)
            h.players.loginOrFail(sid, "Carol")
            h.router.handle(sid, Command.Look)
            h.router.handle(sid, Command.Move(Direction.NORTH))

            val state = h.players.get(sid)!!
            state.playerId = PlayerId(7L)
            val record = state.toPlayerRecord(lastSeenEpochMs = 123L)
            assertEquals(setOf("ok_small:a", "ok_small:b"), record.exploredRooms)

            val restored = record.toPlayerState(SessionId(4))
            assertTrue("ok_small:a" in restored.exploredRooms)
            assertTrue("ok_small:b" in restored.exploredRooms)
            assertFalse("ok_small:c" in restored.exploredRooms)
        }
}
