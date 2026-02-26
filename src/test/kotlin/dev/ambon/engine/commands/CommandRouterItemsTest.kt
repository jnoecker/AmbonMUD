package dev.ambon.engine.commands

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.items.ItemSlot
import dev.ambon.domain.items.ItemUseEffect
import dev.ambon.domain.world.load.WorldLoader
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.LoginResult
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommandRouterItemsTest {
    @Test
    fun `look includes items here line`() =
        runTest {
            val world = WorldLoader.loadFromResource("world/test_world.yaml")
            val items = ItemRegistry()
            items.setRoomItems(
                world.startRoom,
                listOf(ItemInstance(ItemId("test:lantern"), Item(keyword = "lantern", displayName = "a brass lantern"))),
            )

            val players = PlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val outbound = LocalOutboundBus()
            val mobs = MobRegistry()
            val router = buildTestRouter(world, players, mobs, items, CombatSystem(players, mobs, items, outbound), outbound)

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
            val world = WorldLoader.loadFromResource("world/test_world.yaml")
            val items = ItemRegistry()
            items.setRoomItems(
                world.startRoom,
                listOf(ItemInstance(ItemId("test:note"), Item(keyword = "note", displayName = "a crumpled note"))),
            )

            val players = PlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val outbound = LocalOutboundBus()
            val mobs = MobRegistry()
            val router = buildTestRouter(world, players, mobs, items, CombatSystem(players, mobs, items, outbound), outbound)

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
            val world = WorldLoader.loadFromResource("world/test_world.yaml")
            val items = ItemRegistry()

            val players = PlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val outbound = LocalOutboundBus()
            val mobs = MobRegistry()
            val router = buildTestRouter(world, players, mobs, items, CombatSystem(players, mobs, items, outbound), outbound)

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
            val world = WorldLoader.loadFromResource("world/test_world.yaml")
            val items = ItemRegistry()

            val players = PlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val outbound = LocalOutboundBus()
            val mobs = MobRegistry()
            val router = buildTestRouter(world, players, mobs, items, CombatSystem(players, mobs, items, outbound), outbound)

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
            val world = WorldLoader.loadFromResource("world/test_world.yaml")
            val items = ItemRegistry()

            val players = PlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val outbound = LocalOutboundBus()
            val mobs = MobRegistry()
            val router = buildTestRouter(world, players, mobs, items, CombatSystem(players, mobs, items, outbound), outbound)

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
            val player = players.get(sid)
            assertEquals(11, player!!.maxHp)
            assertEquals(11, player.hp)

            val outs = drain(outbound)
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.contains("You wear") },
                "Missing wear message. got=$outs",
            )
        }

    @Test
    fun `remove moves item from equipment slot back to inventory`() =
        runTest {
            val world = WorldLoader.loadFromResource("world/test_world.yaml")
            val items = ItemRegistry()

            val players = PlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val outbound = LocalOutboundBus()
            val mobs = MobRegistry()
            val router = buildTestRouter(world, players, mobs, items, CombatSystem(players, mobs, items, outbound), outbound)

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
            val player = players.get(sid)
            assertEquals(10, player!!.maxHp)
            assertEquals(10, player.hp)

            val outs = drain(outbound)
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.contains("You remove") },
                "Missing remove message. got=$outs",
            )
        }

    @Test
    fun `wear prefers wearable item when multiple inventory items share keyword`() =
        runTest {
            val world = WorldLoader.loadFromResource("world/test_world.yaml")
            val items = ItemRegistry()

            val players = PlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val outbound = LocalOutboundBus()
            val mobs = MobRegistry()
            val router = buildTestRouter(world, players, mobs, items, CombatSystem(players, mobs, items, outbound), outbound)

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

    @Test
    fun `use applies effects and consumes when charges reach zero`() =
        runTest {
            val world = WorldLoader.loadFromResource("world/test_world.yaml")
            val items = ItemRegistry()
            val players = PlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val outbound = LocalOutboundBus()
            val mobs = MobRegistry()
            val router = buildTestRouter(world, players, mobs, items, CombatSystem(players, mobs, items, outbound), outbound)

            val sid = SessionId(8L)
            login(players, sid, "Player8")
            players.get(sid)!!.hp = 5

            items.setRoomItems(
                world.startRoom,
                listOf(
                    ItemInstance(
                        ItemId("test:potion"),
                        Item(
                            keyword = "potion",
                            displayName = "a red potion",
                            consumable = true,
                            charges = 2,
                            onUse = ItemUseEffect(healHp = 3),
                        ),
                    ),
                ),
            )
            items.takeFromRoom(sid, world.startRoom, "potion")

            router.handle(sid, Command.Use("potion"))

            assertEquals(8, players.get(sid)!!.hp)
            assertEquals(1, items.inventory(sid).size)
            val remainingPotion = items.inventory(sid).single()
            assertEquals(1, remainingPotion.item.charges)

            var outs = drain(outbound)
            assertTrue(outs.any { it is OutboundEvent.SendInfo && it.text.contains("You use a red potion") })
            assertTrue(outs.any { it is OutboundEvent.SendInfo && it.text.contains("recover 3 HP") })
            assertTrue(outs.any { it is OutboundEvent.SendInfo && it.text.contains("1 charge") })

            router.handle(sid, Command.Use("potion"))

            assertEquals(10, players.get(sid)!!.hp)
            assertTrue(items.inventory(sid).isEmpty())

            outs = drain(outbound)
            assertTrue(outs.any { it is OutboundEvent.SendInfo && it.text.contains("is consumed") })
        }

    @Test
    fun `use can consume equipped item and updates defense`() =
        runTest {
            val world = WorldLoader.loadFromResource("world/test_world.yaml")
            val items = ItemRegistry()
            val players = PlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val outbound = LocalOutboundBus()
            val mobs = MobRegistry()
            val router = buildTestRouter(world, players, mobs, items, CombatSystem(players, mobs, items, outbound), outbound)

            val sid = SessionId(9L)
            login(players, sid, "Player9")

            items.setRoomItems(
                world.startRoom,
                listOf(
                    ItemInstance(
                        ItemId("test:blessed_cap"),
                        Item(
                            keyword = "cap",
                            displayName = "a blessed cap",
                            slot = ItemSlot.HEAD,
                            armor = 1,
                            consumable = true,
                            charges = 1,
                            onUse = ItemUseEffect(healHp = 1),
                        ),
                    ),
                ),
            )
            items.takeFromRoom(sid, world.startRoom, "cap")
            router.handle(sid, Command.Wear("cap"))
            drain(outbound)

            assertEquals(11, players.get(sid)!!.maxHp)
            router.handle(sid, Command.Use("cap"))

            assertTrue(items.equipment(sid).isEmpty())
            assertEquals(10, players.get(sid)!!.maxHp)

            val outs = drain(outbound)
            assertTrue(outs.any { it is OutboundEvent.SendInfo && it.text.contains("You use a blessed cap") })
            assertTrue(outs.any { it is OutboundEvent.SendInfo && it.text.contains("is consumed") })
        }

    @Test
    fun `give moves item to nearby player inventory`() =
        runTest {
            val world = WorldLoader.loadFromResource("world/test_world.yaml")
            val items = ItemRegistry()
            val players = PlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val outbound = LocalOutboundBus()
            val mobs = MobRegistry()
            val router = buildTestRouter(world, players, mobs, items, CombatSystem(players, mobs, items, outbound), outbound)

            val aliceSid = SessionId(10L)
            val bobSid = SessionId(11L)
            login(players, aliceSid, "Alice")
            login(players, bobSid, "Bob")

            items.setRoomItems(
                world.startRoom,
                listOf(ItemInstance(ItemId("test:coin"), Item(keyword = "coin", displayName = "a silver coin"))),
            )
            items.takeFromRoom(aliceSid, world.startRoom, "coin")

            router.handle(aliceSid, Command.Give("coin", "Bob"))

            assertTrue(items.inventory(aliceSid).isEmpty())
            assertEquals(1, items.inventory(bobSid).size)
            val bobsItem = items.inventory(bobSid).single()
            assertEquals("coin", bobsItem.item.keyword)

            val outs = drain(outbound)
            assertTrue(outs.any { it is OutboundEvent.SendInfo && it.sessionId == aliceSid && it.text.contains("You give") })
            assertTrue(outs.any { it is OutboundEvent.SendInfo && it.sessionId == bobSid && it.text.contains("gives you") })
        }

    @Test
    fun `give removes equipped item and updates defense`() =
        runTest {
            val world = WorldLoader.loadFromResource("world/test_world.yaml")
            val items = ItemRegistry()
            val players = PlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val outbound = LocalOutboundBus()
            val mobs = MobRegistry()
            val router = buildTestRouter(world, players, mobs, items, CombatSystem(players, mobs, items, outbound), outbound)

            val aliceSid = SessionId(12L)
            val bobSid = SessionId(13L)
            login(players, aliceSid, "Alice2")
            login(players, bobSid, "Bob2")

            items.setRoomItems(
                world.startRoom,
                listOf(
                    ItemInstance(
                        ItemId("test:cap"),
                        Item(keyword = "cap", displayName = "a sturdy cap", slot = ItemSlot.HEAD, armor = 1),
                    ),
                ),
            )
            items.takeFromRoom(aliceSid, world.startRoom, "cap")
            router.handle(aliceSid, Command.Wear("cap"))
            drain(outbound)

            assertEquals(11, players.get(aliceSid)!!.maxHp)
            router.handle(aliceSid, Command.Give("cap", "Bob2"))

            assertTrue(items.equipment(aliceSid).isEmpty())
            assertEquals(10, players.get(aliceSid)!!.maxHp)
            val bobsItem = items.inventory(bobSid).single()
            assertEquals("cap", bobsItem.item.keyword)

            val outs = drain(outbound)
            assertTrue(outs.any { it is OutboundEvent.SendInfo && it.sessionId == aliceSid && it.text.contains("You give") })
            assertTrue(outs.any { it is OutboundEvent.SendInfo && it.sessionId == bobSid && it.text.contains("gives you") })
        }

    @Test
    fun `give requires target in same room`() =
        runTest {
            val world = WorldLoader.loadFromResource("world/test_world.yaml")
            val items = ItemRegistry()
            val players = PlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val outbound = LocalOutboundBus()
            val mobs = MobRegistry()
            val router = buildTestRouter(world, players, mobs, items, CombatSystem(players, mobs, items, outbound), outbound)

            val aliceSid = SessionId(14L)
            val bobSid = SessionId(15L)
            login(players, aliceSid, "Alice3")
            login(players, bobSid, "Bob3")

            val startRoom = world.rooms.getValue(world.startRoom)
            val targetRoom = startRoom.exits.values.first()
            players.moveTo(bobSid, targetRoom)

            items.setRoomItems(
                world.startRoom,
                listOf(ItemInstance(ItemId("test:coin"), Item(keyword = "coin", displayName = "a silver coin"))),
            )
            items.takeFromRoom(aliceSid, world.startRoom, "coin")

            router.handle(aliceSid, Command.Give("coin", "Bob3"))

            assertEquals(1, items.inventory(aliceSid).size)
            assertTrue(items.inventory(bobSid).isEmpty())

            val outs = drain(outbound)
            assertTrue(outs.any { it is OutboundEvent.SendError && it.text.contains("not here") })
        }

    private fun drain(ch: LocalOutboundBus): List<OutboundEvent> {
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
