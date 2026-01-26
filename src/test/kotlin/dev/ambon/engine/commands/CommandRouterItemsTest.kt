package dev.ambon.engine.commands

import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.domain.world.WorldFactory
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.persistence.InMemoryPlayerRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock

class CommandRouterItemsTest {
    @Test
    fun `look includes items here line`() =
        runTest {
            val world = WorldFactory.demoWorld()

            // ensure start room has an item (or add one if your factory doesn't yet)
            val start = world.rooms.getValue(world.startRoom)
            start.items.clear()
            start.items.add(Item(keyword = "lantern", displayName = "a brass lantern"))

            val players =
                PlayerRegistry(
                    world.startRoom,
                    repo = InMemoryPlayerRepository(),
                    clock = Clock.systemUTC(),
                )
            val outbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val router = CommandRouter(world, players, MobRegistry(), outbound)

            val sid = SessionId(1L)
            players.connect(sid)

            router.handle(sid, Command.Look)

            val outs = drain(outbound)

            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.contains("Items here:") && it.text.contains("a brass lantern") },
                "Missing items line. got=$outs",
            )
        }

    @Test
    fun `get moves item from room to inventory`() =
        runTest {
            val world = WorldFactory.demoWorld()
            val start = world.rooms.getValue(world.startRoom)
            start.items.clear()
            start.items.add(Item(keyword = "note", displayName = "a crumpled note"))

            val players = PlayerRegistry(world.startRoom, InMemoryPlayerRepository())
            val outbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val router = CommandRouter(world, players, MobRegistry(), outbound)

            val sid = SessionId(2L)
            players.connect(sid)

            router.handle(sid, Command.Get("note"))

            val me = players.get(sid)!!
            assertEquals(1, me.inventory.size)
            assertEquals("note", me.inventory[0].keyword)
            assertTrue(start.items.isEmpty())

            val outs = drain(outbound)
            assertTrue(outs.any { it is OutboundEvent.SendInfo && it.text.contains("pick up") }, "Missing pickup message. got=$outs")
        }

    @Test
    fun `inventory shows carried items`() =
        runTest {
            val world = WorldFactory.demoWorld()
            val players = PlayerRegistry(world.startRoom, InMemoryPlayerRepository())
            val outbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val router = CommandRouter(world, players, MobRegistry(), outbound)

            val sid = SessionId(3L)
            players.connect(sid)

            // inject item directly
            players.get(sid)!!.inventory.add(Item(keyword = "lantern", displayName = "a brass lantern"))

            router.handle(sid, Command.Inventory)

            val outs = drain(outbound)
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.contains("You are carrying:") && it.text.contains("a brass lantern") },
                "Missing inventory listing. got=$outs",
            )
        }

    @Test
    fun `drop moves item from inventory to room`() =
        runTest {
            val world = WorldFactory.demoWorld()
            val start = world.rooms.getValue(world.startRoom)
            start.items.clear()

            val players = PlayerRegistry(world.startRoom, InMemoryPlayerRepository())
            val outbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val router = CommandRouter(world, players, MobRegistry(), outbound)

            val sid = SessionId(4L)
            players.connect(sid)

            val me = players.get(sid)!!
            me.inventory.add(Item(keyword = "note", displayName = "a crumpled note"))

            router.handle(sid, Command.Drop("note"))

            assertTrue(me.inventory.isEmpty())
            assertEquals(1, start.items.size)
            assertEquals("note", start.items[0].keyword)

            val outs = drain(outbound)
            assertTrue(outs.any { it is OutboundEvent.SendInfo && it.text.contains("You drop") }, "Missing drop message. got=$outs")
        }

    private fun drain(ch: Channel<OutboundEvent>): List<OutboundEvent> {
        val out = mutableListOf<OutboundEvent>()
        while (true) {
            val v = ch.tryReceive().getOrNull() ?: break
            out.add(v)
        }
        return out
    }
}
