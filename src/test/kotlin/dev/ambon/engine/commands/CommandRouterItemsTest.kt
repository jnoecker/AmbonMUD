package dev.ambon.engine.commands

import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.items.ItemSlot
import dev.ambon.domain.world.WorldFactory
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.LoginResult
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
            val mobs = MobRegistry()
            val router = CommandRouter(world, players, mobs, items, CombatSystem(players, mobs, items, outbound), outbound)

            val sid = SessionId(1L)
            login(players, sid, "Player1")

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
            val mobs = MobRegistry()
            val router = CommandRouter(world, players, mobs, items, CombatSystem(players, mobs, items, outbound), outbound)

            val sid = SessionId(2L)
            login(players, sid, "Player2")

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
            val mobs = MobRegistry()
            val router = CommandRouter(world, players, mobs, items, CombatSystem(players, mobs, items, outbound), outbound)

            val sid = SessionId(3L)
            login(players, sid, "Player3")

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
            val mobs = MobRegistry()
            val router = CommandRouter(world, players, mobs, items, CombatSystem(players, mobs, items, outbound), outbound)

            val sid = SessionId(4L)
            login(players, sid, "Player4")

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

    @Test
    fun `wear moves item from inventory to equipment slot`() =
        runTest {
            val world = WorldFactory.demoWorld()
            val items = ItemRegistry()

            val players = PlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val outbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val mobs = MobRegistry()
            val router = CommandRouter(world, players, mobs, items, CombatSystem(players, mobs, items, outbound), outbound)

            val sid = SessionId(5L)
            login(players, sid, "Player5")

            items.setRoomItems(
                world.startRoom,
                listOf(
                    ItemInstance(
                        ItemId("test:cap"),
                        Item(keyword = "cap", displayName = "a leather cap", slot = ItemSlot.HEAD, armor = 1),
                    ),
                ),
            )
            items.takeFromRoom(sid, world.startRoom, "cap")

            router.handle(sid, Command.Wear("cap"))

            assertEquals(0, items.inventory(sid).size)
            val equipped = items.equipment(sid)
            assertEquals("cap", equipped.getValue(ItemSlot.HEAD).item.keyword)

            val outs = drain(outbound)
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.contains("You wear") },
                "Missing wear message. got=$outs",
            )
        }

    @Test
    fun `remove moves item from equipment slot back to inventory`() =
        runTest {
            val world = WorldFactory.demoWorld()
            val items = ItemRegistry()

            val players = PlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val outbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val mobs = MobRegistry()
            val router = CommandRouter(world, players, mobs, items, CombatSystem(players, mobs, items, outbound), outbound)

            val sid = SessionId(6L)
            login(players, sid, "Player6")

            items.setRoomItems(
                world.startRoom,
                listOf(
                    ItemInstance(
                        ItemId("test:cap"),
                        Item(keyword = "cap", displayName = "a leather cap", slot = ItemSlot.HEAD, armor = 1),
                    ),
                ),
            )
            items.takeFromRoom(sid, world.startRoom, "cap")
            router.handle(sid, Command.Wear("cap"))
            drain(outbound)

            router.handle(sid, Command.Remove(ItemSlot.HEAD))

            assertEquals(1, items.inventory(sid).size)
            assertTrue(items.equipment(sid).isEmpty())

            val outs = drain(outbound)
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.contains("You remove") },
                "Missing remove message. got=$outs",
            )
        }

    @Test
    fun `wear prefers wearable item when multiple inventory items share keyword`() =
        runTest {
            val world = WorldFactory.demoWorld()
            val items = ItemRegistry()

            val players = PlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val outbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val mobs = MobRegistry()
            val router = CommandRouter(world, players, mobs, items, CombatSystem(players, mobs, items, outbound), outbound)

            val sid = SessionId(7L)
            login(players, sid, "Player7")

            items.setRoomItems(
                world.startRoom,
                listOf(
                    ItemInstance(
                        ItemId("test:ring_plain"),
                        Item(keyword = "ring", displayName = "a plain ring"),
                    ),
                    ItemInstance(
                        ItemId("test:ring_spiked"),
                        Item(keyword = "ring", displayName = "a spiked ring", slot = ItemSlot.HAND, damage = 1),
                    ),
                ),
            )
            items.takeFromRoom(sid, world.startRoom, "ring")
            items.takeFromRoom(sid, world.startRoom, "ring")

            router.handle(sid, Command.Wear("ring"))

            val equipped = items.equipment(sid)
            assertEquals("ring", equipped.getValue(ItemSlot.HAND).item.keyword)
            assertEquals(1, items.inventory(sid).size)
            assertEquals("ring", items.inventory(sid)[0].item.keyword)

            val outs = drain(outbound)
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.contains("You wear") },
                "Missing wear message. got=$outs",
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

    private suspend fun login(
        players: PlayerRegistry,
        sessionId: SessionId,
        name: String,
    ) {
        val res = players.login(sessionId, name, "password")
        require(res == LoginResult.Ok) { "Login failed: $res" }
    }
}
