package dev.ambon.engine.commands

import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.items.ItemSlot
import dev.ambon.domain.items.ItemUseEffect
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.test.CommandRouterHarness
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommandRouterItemsTest {
    @Test
    fun `look includes items here line`() =
        runTest {
            val h = CommandRouterHarness.create()
            h.items.setRoomItems(
                h.world.startRoom,
                listOf(ItemInstance(ItemId("test:lantern"), Item(keyword = "lantern", displayName = "a brass lantern"))),
            )

            val sid = SessionId(1L)
            h.loginPlayer(sid, "Player1")

            h.router.handle(sid, Command.Look)

            val outs = h.drain()
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
            val h = CommandRouterHarness.create()
            h.items.setRoomItems(
                h.world.startRoom,
                listOf(ItemInstance(ItemId("test:note"), Item(keyword = "note", displayName = "a crumpled note"))),
            )

            val sid = SessionId(2L)
            h.loginPlayer(sid, "Player2")

            h.router.handle(sid, Command.Get("note"))

            assertEquals(0, h.items.itemsInRoom(h.world.startRoom).size)
            assertEquals(1, h.items.inventory(sid).size)
            assertEquals("note", h.items.inventory(sid)[0].item.keyword)

            val outs = h.drain()
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.contains("pick up") },
                "Missing pickup message. got=$outs",
            )
        }

    @Test
    fun `inventory shows carried items`() =
        runTest {
            val h = CommandRouterHarness.create()

            val sid = SessionId(3L)
            h.loginPlayer(sid, "Player3")

            // give an item via registry
            h.items.dropToRoom(sid, h.world.startRoom, "lantern") // no-op, not in inv
            h.items.takeFromRoom(sid, h.world.startRoom, "lantern") // no-op
            // simplest: just set inventory by taking from a room
            h.items.setRoomItems(
                h.world.startRoom,
                listOf(ItemInstance(ItemId("test:lantern"), Item(keyword = "lantern", displayName = "a brass lantern"))),
            )
            h.items.takeFromRoom(sid, h.world.startRoom, "lantern")

            h.router.handle(sid, Command.Inventory)

            val outs = h.drain()
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
            val h = CommandRouterHarness.create()

            val sid = SessionId(4L)
            h.loginPlayer(sid, "Player4")

            // Put item into inventory by taking it from the room
            h.items.setRoomItems(
                h.world.startRoom,
                listOf(ItemInstance(ItemId("test:note"), Item(keyword = "note", displayName = "a crumpled note"))),
            )
            h.items.takeFromRoom(sid, h.world.startRoom, "note")
            assertEquals(1, h.items.inventory(sid).size)
            assertEquals(0, h.items.itemsInRoom(h.world.startRoom).size)

            h.router.handle(sid, Command.Drop("note"))

            assertEquals(0, h.items.inventory(sid).size)
            assertEquals(1, h.items.itemsInRoom(h.world.startRoom).size)
            assertEquals("note", h.items.itemsInRoom(h.world.startRoom)[0].item.keyword)

            val outs = h.drain()
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.contains("You drop") },
                "Missing drop message. got=$outs",
            )
        }

    @Test
    fun `drop is blocked for quest items`() =
        runTest {
            val h = CommandRouterHarness.create()

            val sid = SessionId(41L)
            h.loginPlayer(sid, "QuestHolder")

            h.items.setRoomItems(
                h.world.startRoom,
                listOf(
                    ItemInstance(
                        ItemId("test:relic"),
                        Item(
                            keyword = "relic",
                            displayName = "the Lost Relic",
                            questItem = true,
                        ),
                    ),
                ),
            )
            h.items.takeFromRoom(sid, h.world.startRoom, "relic")
            assertEquals(1, h.items.inventory(sid).size)

            h.router.handle(sid, Command.Drop("relic"))

            // Quest item stays in inventory; room still empty
            assertEquals(1, h.items.inventory(sid).size)
            assertEquals(0, h.items.itemsInRoom(h.world.startRoom).size)

            val outs = h.drain()
            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.text.contains("quest item") },
                "Expected quest-item error. got=$outs",
            )
        }

    @Test
    fun `wear moves item from inventory to equipment slot`() =
        runTest {
            val h = CommandRouterHarness.create()

            val sid = SessionId(5L)
            h.loginPlayer(sid, "Player5")

            h.items.setRoomItems(
                h.world.startRoom,
                listOf(
                    ItemInstance(
                        ItemId("test:cap"),
                        Item(keyword = "cap", displayName = "a leather cap", slot = ItemSlot.HEAD, armor = 1),
                    ),
                ),
            )
            h.items.takeFromRoom(sid, h.world.startRoom, "cap")

            h.router.handle(sid, Command.Wear("cap"))

            assertEquals(0, h.items.inventory(sid).size)
            val equipped = h.items.equipment(sid)
            assertEquals("cap", equipped.getValue(ItemSlot.HEAD).item.keyword)
            // Armor no longer doubles as a max-HP bonus — it mitigates incoming damage now,
            // so equipping a cap should leave both maxHp and current hp at their level-1 baseline.
            val player = h.players.get(sid)
            assertEquals(10, player!!.maxHp)
            assertEquals(10, player.hp)

            val outs = h.drain()
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.contains("You wear") },
                "Missing wear message. got=$outs",
            )
        }

    @Test
    fun `wear into a filled slot auto-swaps with the currently equipped item`() =
        runTest {
            val h = CommandRouterHarness.create()

            val sid = SessionId(55L)
            h.loginPlayer(sid, "Swapper")

            h.items.setRoomItems(
                h.world.startRoom,
                listOf(
                    ItemInstance(
                        ItemId("test:cap-old"),
                        Item(keyword = "cap", displayName = "a threadbare cap", slot = ItemSlot.HEAD, armor = 1),
                    ),
                    ItemInstance(
                        ItemId("test:helm"),
                        Item(keyword = "helm", displayName = "a steel helm", slot = ItemSlot.HEAD, armor = 4),
                    ),
                ),
            )
            h.items.takeFromRoom(sid, h.world.startRoom, "cap")
            h.items.takeFromRoom(sid, h.world.startRoom, "helm")
            h.router.handle(sid, Command.Wear("cap"))
            assertEquals("test:cap-old", h.items.equipment(sid).getValue(ItemSlot.HEAD).id.value)
            assertEquals(1, h.items.inventory(sid).size)
            h.drain()

            h.router.handle(sid, Command.Wear("helm"))

            val equipped = h.items.equipment(sid)
            assertEquals("test:helm", equipped.getValue(ItemSlot.HEAD).id.value)

            val inv = h.items.inventory(sid)
            assertEquals(1, inv.size, "previous helm should be back in inventory")
            assertEquals("test:cap-old", inv[0].id.value)

            val outs = h.drain()
            assertTrue(
                outs.any {
                    it is OutboundEvent.SendInfo &&
                        it.text.contains("You remove a threadbare cap") &&
                        it.text.contains("wear a steel helm")
                },
                "Expected swap message naming both items. got=$outs",
            )
        }

    @Test
    fun `remove moves item from equipment slot back to inventory`() =
        runTest {
            val h = CommandRouterHarness.create()

            val sid = SessionId(6L)
            h.loginPlayer(sid, "Player6")

            h.items.setRoomItems(
                h.world.startRoom,
                listOf(
                    ItemInstance(
                        ItemId("test:cap"),
                        Item(keyword = "cap", displayName = "a leather cap", slot = ItemSlot.HEAD, armor = 1),
                    ),
                ),
            )
            h.items.takeFromRoom(sid, h.world.startRoom, "cap")
            h.router.handle(sid, Command.Wear("cap"))
            h.drain()

            h.router.handle(sid, Command.Remove("head"))

            assertEquals(1, h.items.inventory(sid).size)
            assertTrue(h.items.equipment(sid).isEmpty())
            val player = h.players.get(sid)
            assertEquals(10, player!!.maxHp)
            assertEquals(10, player.hp)

            val outs = h.drain()
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.contains("You remove") },
                "Missing remove message. got=$outs",
            )
        }

    @Test
    fun `wear prefers wearable item when multiple inventory items share keyword`() =
        runTest {
            val h = CommandRouterHarness.create()

            val sid = SessionId(7L)
            h.loginPlayer(sid, "Player7")

            h.items.setRoomItems(
                h.world.startRoom,
                listOf(
                    ItemInstance(
                        ItemId("test:ring_plain"),
                        Item(keyword = "ring", displayName = "a plain ring"),
                    ),
                    ItemInstance(
                        ItemId("test:ring_spiked"),
                        Item(keyword = "ring", displayName = "a spiked ring", slot = ItemSlot.WEAPON, damage = 1),
                    ),
                ),
            )
            h.items.takeFromRoom(sid, h.world.startRoom, "ring")
            h.items.takeFromRoom(sid, h.world.startRoom, "ring")

            h.router.handle(sid, Command.Wear("ring"))

            val equipped = h.items.equipment(sid)
            assertEquals("ring", equipped.getValue(ItemSlot.WEAPON).item.keyword)
            assertEquals(1, h.items.inventory(sid).size)
            assertEquals("ring", h.items.inventory(sid)[0].item.keyword)

            val outs = h.drain()
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.contains("You wear") },
                "Missing wear message. got=$outs",
            )
        }

    @Test
    fun `use applies effects and consumes when charges reach zero`() =
        runTest {
            val h = CommandRouterHarness.create()
            val sid = SessionId(8L)
            h.loginPlayer(sid, "Player8")
            h.players.get(sid)!!.hp = 5

            h.items.setRoomItems(
                h.world.startRoom,
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
            h.items.takeFromRoom(sid, h.world.startRoom, "potion")

            h.router.handle(sid, Command.Use("potion"))

            assertEquals(8, h.players.get(sid)!!.hp)
            assertEquals(1, h.items.inventory(sid).size)
            val remainingPotion = h.items.inventory(sid).single()
            assertEquals(1, remainingPotion.item.charges)

            var outs = h.drain()
            assertTrue(outs.any { it is OutboundEvent.SendInfo && it.text.contains("You use a red potion") })
            assertTrue(outs.any { it is OutboundEvent.SendInfo && it.text.contains("recover 3 HP") })
            assertTrue(outs.any { it is OutboundEvent.SendInfo && it.text.contains("1 charge") })

            h.router.handle(sid, Command.Use("potion"))

            assertEquals(10, h.players.get(sid)!!.hp)
            assertTrue(h.items.inventory(sid).isEmpty())

            outs = h.drain()
            assertTrue(outs.any { it is OutboundEvent.SendInfo && it.text.contains("is consumed") })
        }

    @Test
    fun `use can consume equipped item and updates defense`() =
        runTest {
            val h = CommandRouterHarness.create()
            val sid = SessionId(9L)
            h.loginPlayer(sid, "Player9")

            h.items.setRoomItems(
                h.world.startRoom,
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
            h.items.takeFromRoom(sid, h.world.startRoom, "cap")
            h.router.handle(sid, Command.Wear("cap"))
            h.drain()

            // Armor no longer inflates maxHp — equipping the cap leaves the player at 10/10.
            assertEquals(10, h.players.get(sid)!!.maxHp)
            h.router.handle(sid, Command.Use("cap"))

            assertTrue(h.items.equipment(sid).isEmpty())
            assertEquals(10, h.players.get(sid)!!.maxHp)

            val outs = h.drain()
            assertTrue(outs.any { it is OutboundEvent.SendInfo && it.text.contains("You use a blessed cap") })
            assertTrue(outs.any { it is OutboundEvent.SendInfo && it.text.contains("is consumed") })
        }

    @Test
    fun `give moves item to nearby player inventory`() =
        runTest {
            val h = CommandRouterHarness.create()
            val aliceSid = SessionId(10L)
            val bobSid = SessionId(11L)
            h.loginPlayer(aliceSid, "Alice")
            h.loginPlayer(bobSid, "Bob")

            h.items.setRoomItems(
                h.world.startRoom,
                listOf(ItemInstance(ItemId("test:coin"), Item(keyword = "coin", displayName = "a silver coin"))),
            )
            h.items.takeFromRoom(aliceSid, h.world.startRoom, "coin")

            h.router.handle(aliceSid, Command.Give("coin", "Bob"))

            assertTrue(h.items.inventory(aliceSid).isEmpty())
            assertEquals(1, h.items.inventory(bobSid).size)
            val bobsItem = h.items.inventory(bobSid).single()
            assertEquals("coin", bobsItem.item.keyword)

            val outs = h.drain()
            assertTrue(outs.any { it is OutboundEvent.SendInfo && it.sessionId == aliceSid && it.text.contains("You give") })
            assertTrue(outs.any { it is OutboundEvent.SendInfo && it.sessionId == bobSid && it.text.contains("gives you") })
        }

    @Test
    fun `give removes equipped item and updates defense`() =
        runTest {
            val h = CommandRouterHarness.create()
            val aliceSid = SessionId(12L)
            val bobSid = SessionId(13L)
            h.loginPlayer(aliceSid, "Alice2")
            h.loginPlayer(bobSid, "Bob2")

            h.items.setRoomItems(
                h.world.startRoom,
                listOf(
                    ItemInstance(
                        ItemId("test:cap"),
                        Item(keyword = "cap", displayName = "a sturdy cap", slot = ItemSlot.HEAD, armor = 1),
                    ),
                ),
            )
            h.items.takeFromRoom(aliceSid, h.world.startRoom, "cap")
            h.router.handle(aliceSid, Command.Wear("cap"))
            h.drain()

            // Armor → maxHp coupling is gone; armor now only mitigates damage.
            assertEquals(10, h.players.get(aliceSid)!!.maxHp)
            h.router.handle(aliceSid, Command.Give("cap", "Bob2"))

            assertTrue(h.items.equipment(aliceSid).isEmpty())
            assertEquals(10, h.players.get(aliceSid)!!.maxHp)
            val bobsItem = h.items.inventory(bobSid).single()
            assertEquals("cap", bobsItem.item.keyword)

            val outs = h.drain()
            assertTrue(outs.any { it is OutboundEvent.SendInfo && it.sessionId == aliceSid && it.text.contains("You give") })
            assertTrue(outs.any { it is OutboundEvent.SendInfo && it.sessionId == bobSid && it.text.contains("gives you") })
        }

    @Test
    fun `give requires target in same room`() =
        runTest {
            val h = CommandRouterHarness.create()
            val aliceSid = SessionId(14L)
            val bobSid = SessionId(15L)
            h.loginPlayer(aliceSid, "Alice3")
            h.loginPlayer(bobSid, "Bob3")

            val startRoom = h.world.rooms.getValue(h.world.startRoom)
            val targetRoom = startRoom.exits.values.first()
            h.players.moveTo(bobSid, targetRoom)

            h.items.setRoomItems(
                h.world.startRoom,
                listOf(ItemInstance(ItemId("test:coin"), Item(keyword = "coin", displayName = "a silver coin"))),
            )
            h.items.takeFromRoom(aliceSid, h.world.startRoom, "coin")

            h.router.handle(aliceSid, Command.Give("coin", "Bob3"))

            assertEquals(1, h.items.inventory(aliceSid).size)
            assertTrue(h.items.inventory(bobSid).isEmpty())

            val outs = h.drain()
            assertTrue(outs.any { it is OutboundEvent.SendError && it.text.contains("not here") })
        }

    // ---- State-blocking tests (combat) ----

    @Test
    fun `wear blocked during combat`() =
        runTest {
            val h = CommandRouterHarness.create()
            val sid = SessionId(30L)
            h.loginPlayer(sid, "Fighter1")

            // Spawn a mob in the start room and start combat
            val mob = dev.ambon.domain.mob.MobState(
                id = dev.ambon.domain.ids.MobId("test:rat"),
                name = "rat",
                roomId = h.world.startRoom,
            )
            h.mobs.upsert(mob)
            h.combat.startCombat(sid, "rat")
            h.drain()

            h.router.handle(sid, Command.Wear("sword"))

            val outs = h.drain()
            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.text.contains("combat") },
                "Expected combat block error. got=$outs",
            )
        }

    @Test
    fun `drop blocked during combat`() =
        runTest {
            val h = CommandRouterHarness.create()
            val sid = SessionId(31L)
            h.loginPlayer(sid, "Fighter2")

            val mob = dev.ambon.domain.mob.MobState(
                id = dev.ambon.domain.ids.MobId("test:rat"),
                name = "rat",
                roomId = h.world.startRoom,
            )
            h.mobs.upsert(mob)
            h.combat.startCombat(sid, "rat")
            h.drain()

            h.router.handle(sid, Command.Drop("sword"))

            val outs = h.drain()
            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.text.contains("combat") },
                "Expected combat block error. got=$outs",
            )
        }

    @Test
    fun `give blocked during combat`() =
        runTest {
            val h = CommandRouterHarness.create()
            val sid = SessionId(32L)
            h.loginPlayer(sid, "Fighter3")

            val mob = dev.ambon.domain.mob.MobState(
                id = dev.ambon.domain.ids.MobId("test:rat"),
                name = "rat",
                roomId = h.world.startRoom,
            )
            h.mobs.upsert(mob)
            h.combat.startCombat(sid, "rat")
            h.drain()

            h.router.handle(sid, Command.Give("sword", "Someone"))

            val outs = h.drain()
            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.text.contains("combat") },
                "Expected combat block error. got=$outs",
            )
        }

    @Test
    fun `use allowed during combat`() =
        runTest {
            val h = CommandRouterHarness.create()
            val sid = SessionId(33L)
            h.loginPlayer(sid, "Fighter4")

            val mob = dev.ambon.domain.mob.MobState(
                id = dev.ambon.domain.ids.MobId("test:rat"),
                name = "rat",
                roomId = h.world.startRoom,
            )
            h.mobs.upsert(mob)
            h.combat.startCombat(sid, "rat")
            h.drain()

            // Use should NOT be blocked by combat (potions)
            h.router.handle(sid, Command.Use("potion"))

            val outs = h.drain()
            // Should get "not carrying" error, NOT a combat block error
            assertTrue(
                outs.none { it is OutboundEvent.SendError && it.text.contains("combat") },
                "Use should not be blocked by combat. got=$outs",
            )
        }
}
