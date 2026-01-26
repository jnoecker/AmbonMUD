package dev.ambon.engine.commands

import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.world.WorldFactory
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommandRouterItemsTest {
    @Test
    fun `look includes items here line`() =
        runTest {
            val world = WorldFactory.demoWorld()
            val items = ItemRegistry()
            items.setRoomItems(
                world.startRoom,
                listOf(ItemInstance(ItemId("test:lantern"), Item(keyword = "lantern", displayName = "a brass lantern"))),
            )

            val players = PlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val outbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val router = CommandRouter(world, players, MobRegistry(), items, outbound)

            val sid = SessionId(1L)
            players.connect(sid)

            router.handle(sid, Command.Look)

            val outs = drain(outbound)
            assertTrue(
                outs.any {
                    it is OutboundEvent.SendInfo &&
                        it.text.contains("Items here:") &&
                        it.text.contains("a brass lantern")
                },
                "Missing items line. got=$outs",
            )
        }

    @Test
    fun `get moves item from room to inventory`() =
        runTest {
            val world = WorldFactory.demoWorld()
            val items = ItemRegistry()
            items.setRoomItems(
                world.startRoom,
                listOf(ItemInstance(ItemId("test:note"), Item(keyword = "note", displayName = "a crumpled note"))),
            )

            val players = PlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val outbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val router = CommandRouter(world, players, MobRegistry(), items, outbound)

            val sid = SessionId(2L)
            players.connect(sid)

            router.handle(sid, Command.Get("note"))

            assertEquals(0, items.itemsInRoom(world.startRoom).size)
            assertEquals(1, items.inventory(sid).size)
            assertEquals("note", items.inventory(sid)[0].item.keyword)

            val outs = drain(outbound)
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.contains("pick up") },
                "Missing pickup message. got=$outs",
            )
        }

    @Test
    fun `inventory shows carried items`() =
        runTest {
            val world = WorldFactory.demoWorld()
            val items = ItemRegistry()

            val players = PlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val outbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val router = CommandRouter(world, players, MobRegistry(), items, outbound)

            val sid = SessionId(3L)
            players.connect(sid)

            // give an item via registry
            items.dropToRoom(sid, world.startRoom, "lantern") // no-op, not in inv
            items.takeFromRoom(sid, world.startRoom, "lantern") // no-op
            // simplest: just set inventory by taking from a room
            items.setRoomItems(
                world.startRoom,
                listOf(ItemInstance(ItemId("test:lantern"), Item(keyword = "lantern", displayName = "a brass lantern"))),
            )
            items.takeFromRoom(sid, world.startRoom, "lantern")

            router.handle(sid, Command.Inventory)

            val outs = drain(outbound)
            assertTrue(
                outs.any {
                    it is OutboundEvent.SendInfo &&
                        it.text.contains("You are carrying:") &&
                        it.text.contains("a brass lantern")
                },
                "Missing inventory listing. got=$outs",
            )
        }

    @Test
    fun `drop moves item from inventory to room`() =
        runTest {
            val world = WorldFactory.demoWorld()
            val items = ItemRegistry()

            val players = PlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val outbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val router = CommandRouter(world, players, MobRegistry(), items, outbound)

            val sid = SessionId(4L)
            players.connect(sid)

            // Put item into inventory by taking it from the room
            items.setRoomItems(
                world.startRoom,
                listOf(ItemInstance(ItemId("test:note"), Item(keyword = "note", displayName = "a crumpled note"))),
            )
            items.takeFromRoom(sid, world.startRoom, "note")
            assertEquals(1, items.inventory(sid).size)
            assertEquals(0, items.itemsInRoom(world.startRoom).size)

            router.handle(sid, Command.Drop("note"))

            assertEquals(0, items.inventory(sid).size)
            assertEquals(1, items.itemsInRoom(world.startRoom).size)
            assertEquals("note", items.itemsInRoom(world.startRoom)[0].item.keyword)

            val outs = drain(outbound)
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.contains("You drop") },
                "Missing drop message. got=$outs",
            )
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
