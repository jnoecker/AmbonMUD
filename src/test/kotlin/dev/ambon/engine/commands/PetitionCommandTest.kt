package dev.ambon.engine.commands

import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.World
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.test.CommandRouterHarness
import dev.ambon.test.MutableClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PetitionCommandTest {
    private val hub = RoomId("test_zone:hub")
    private val outpost = RoomId("test_zone:outpost")
    private val pbraeStart = RoomId("pbrae:gravel_road")

    private fun worldWithPetitionZone(): World {
        val rooms = mapOf(
            hub to Room(id = hub, title = "Hub", description = "Hub room", exits = mapOf()),
            outpost to Room(id = outpost, title = "Outpost", description = "Outpost room", exits = mapOf()),
            pbraeStart to Room(id = pbraeStart, title = "Gravel Road", description = "A gravel road.", exits = mapOf()),
        )
        return World(rooms = rooms, startRoom = hub)
    }

    @Test
    fun `petition with valid keyword teleports player`() = runTest {
        val world = worldWithPetitionZone()
        val h = CommandRouterHarness.create(world = world, clock = MutableClock(0L))
        val sid = SessionId(1)
        h.loginPlayer(sid, "Hero")
        h.drain()

        h.router.handle(sid, Command.Petition("peanut"))
        val outs = h.drain()

        assertEquals(pbraeStart, h.players.get(sid)!!.roomId)
        assertTrue(
            outs.any { it is OutboundEvent.SendText && it.text.contains("world shifts") },
            "Expected teleport flavor text. got=$outs",
        )
    }

    @Test
    fun `petition with unknown keyword sends error`() = runTest {
        val h = CommandRouterHarness.create(clock = MutableClock(0L))
        val sid = SessionId(1)
        h.loginPlayer(sid, "Hero")
        h.drain()

        h.router.handle(sid, Command.Petition("nonsense"))
        val outs = h.drain()

        assertTrue(
            outs.any { it is OutboundEvent.SendError && it.text.contains("unanswered") },
            "Expected unanswered message. got=$outs",
        )
    }

    @Test
    fun `petition blocked while in combat`() = runTest {
        val world = worldWithPetitionZone()
        val h = CommandRouterHarness.create(world = world, clock = MutableClock(0L))
        val sid = SessionId(1)
        h.loginPlayer(sid, "Hero")

        val mobId = MobId("test_zone:grunt")
        h.mobs.upsert(MobState(id = mobId, name = "a grunt", roomId = hub, hp = 10, maxHp = 10))
        h.router.handle(sid, Command.Kill("grunt"))
        h.drain()

        h.router.handle(sid, Command.Petition("peanut"))
        val outs = h.drain()

        assertTrue(
            outs.any { it is OutboundEvent.SendError && it.text.contains("combat") },
            "Expected combat-block message. got=$outs",
        )
        assertEquals(hub, h.players.get(sid)!!.roomId)
    }
}
