package dev.ambon.engine

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.items.ItemSlot
import dev.ambon.domain.mob.MobState
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.Room
import dev.ambon.engine.abilities.AbilityDefinition
import dev.ambon.engine.abilities.AbilityEffect
import dev.ambon.engine.abilities.AbilityId
import dev.ambon.engine.abilities.TargetType
import dev.ambon.engine.events.OutboundEvent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GmcpEmitterTest {
    private val sid = SessionId(1L)
    private val outbound = LocalOutboundBus()

    private val progression = PlayerProgression()

    private fun emitter(vararg supported: String): GmcpEmitter {
        val packages = supported.toSet()
        return GmcpEmitter(
            outbound = outbound,
            supportsPackage = { _, pkg ->
                packages.any { s -> pkg == s || pkg.startsWith("$s.") }
            },
            progression = progression,
        )
    }

    private fun drain(): List<OutboundEvent> {
        val events = mutableListOf<OutboundEvent>()
        while (true) {
            val result = outbound.tryReceive()
            if (result.isSuccess) events.add(result.getOrNull()!!) else break
        }
        return events
    }

    private fun drainGmcp(): List<OutboundEvent.GmcpData> = drain().filterIsInstance<OutboundEvent.GmcpData>()

    private fun player(
        name: String = "Alice",
        hp: Int = 50,
        maxHp: Int = 100,
        mana: Int = 30,
        maxMana: Int = 60,
        level: Int = 5,
        xpTotal: Long = 1234L,
        race: String = "HUMAN",
        playerClass: String = "WARRIOR",
    ) = PlayerState(
        sessionId = sid,
        name = name,
        roomId = RoomId("test:room1"),
        hp = hp,
        maxHp = maxHp,
        mana = mana,
        maxMana = maxMana,
        level = level,
        xpTotal = xpTotal,
        race = race,
        playerClass = playerClass,
    )

    private fun room() =
        Room(
            id = RoomId("forest:clearing"),
            title = "A Sunny Clearing",
            description = "Sunlight streams through the trees.",
            exits =
                mapOf(
                    Direction.NORTH to RoomId("forest:path"),
                    Direction.EAST to RoomId("forest:brook"),
                ),
        )

    private fun item(
        id: String = "forest:sword",
        name: String = "Iron Sword",
        slot: ItemSlot? = ItemSlot.HAND,
        damage: Int = 3,
        armor: Int = 0,
    ) = ItemInstance(
        id = ItemId(id),
        item =
            Item(
                keyword = "sword",
                displayName = name,
                slot = slot,
                damage = damage,
                armor = armor,
            ),
    )

    private fun ability(
        id: String = "firebolt",
        name: String = "Firebolt",
        manaCost: Int = 8,
        cooldownMs: Long = 5000L,
    ) = AbilityDefinition(
        id = AbilityId(id),
        displayName = name,
        description = "A bolt of fire.",
        manaCost = manaCost,
        cooldownMs = cooldownMs,
        levelRequired = 1,
        targetType = TargetType.ENEMY,
        effect = AbilityEffect.DirectDamage(minDamage = 5, maxDamage = 10),
    )

    // ── Char.Vitals ──

    @Test
    fun `sendCharVitals emits correct JSON when supported`() =
        runTest {
            val e = emitter("Char.Vitals")
            e.sendCharVitals(sid, player())
            val events = drainGmcp()
            assertEquals(1, events.size)
            val data = events[0]
            assertEquals("Char.Vitals", data.gmcpPackage)
            assertTrue(data.jsonData.contains("\"hp\":50"))
            assertTrue(data.jsonData.contains("\"maxHp\":100"))
            assertTrue(data.jsonData.contains("\"mana\":30"))
            assertTrue(data.jsonData.contains("\"level\":5"))
            assertTrue(data.jsonData.contains("\"xp\":1234"))
            // xpTotal=1234: level 4 floor=900, level 5 floor=1600 → into=334, span=700
            assertTrue(data.jsonData.contains("\"xpIntoLevel\":334"))
            assertTrue(data.jsonData.contains("\"xpToNextLevel\":700"))
            assertTrue(data.jsonData.contains("\"inCombat\":false"))
        }

    @Test
    fun `sendCharVitals does nothing when not supported`() =
        runTest {
            val e = emitter()
            e.sendCharVitals(sid, player())
            assertTrue(drainGmcp().isEmpty())
        }

    // ── Room.Info ──

    @Test
    fun `sendRoomInfo emits correct JSON with exits`() =
        runTest {
            val e = emitter("Room.Info")
            e.sendRoomInfo(sid, room())
            val events = drainGmcp()
            assertEquals(1, events.size)
            val data = events[0]
            assertEquals("Room.Info", data.gmcpPackage)
            assertTrue(data.jsonData.contains("\"title\":\"A Sunny Clearing\""))
            assertTrue(data.jsonData.contains("\"north\":\"forest:path\""))
            assertTrue(data.jsonData.contains("\"east\":\"forest:brook\""))
        }

    @Test
    fun `sendRoomInfo JSON-escapes special characters`() =
        runTest {
            val e = emitter("Room.Info")
            val r =
                Room(
                    id = RoomId("test:special"),
                    title = "Room with \"quotes\"",
                    description = "Line1\nLine2",
                    exits = emptyMap(),
                )
            e.sendRoomInfo(sid, r)
            val data = drainGmcp()[0]
            assertTrue(data.jsonData.contains("""Room with \"quotes\""""))
            assertTrue(data.jsonData.contains("""\n"""))
        }

    // ── Char.StatusVars ──

    @Test
    fun `sendCharStatusVars emits field labels`() =
        runTest {
            val e = emitter("Char.StatusVars")
            e.sendCharStatusVars(sid)
            val data = drainGmcp()[0]
            assertEquals("Char.StatusVars", data.gmcpPackage)
            assertTrue(data.jsonData.contains("\"hp\":\"HP\""))
        }

    // ── Char.Items.List ──

    @Test
    fun `sendCharItemsList emits inventory and equipment JSON`() =
        runTest {
            val e = emitter("Char.Items")
            val sword = item()
            val helmet = item(id = "forest:helmet", name = "Iron Helmet", slot = ItemSlot.HEAD, damage = 0, armor = 2)
            e.sendCharItemsList(sid, listOf(sword), mapOf(ItemSlot.HEAD to helmet))
            val data = drainGmcp()[0]
            assertEquals("Char.Items.List", data.gmcpPackage)
            assertTrue(data.jsonData.contains("\"inventory\":["))
            assertTrue(data.jsonData.contains("\"Iron Sword\""))
            assertTrue(data.jsonData.contains("\"head\":"))
            assertTrue(data.jsonData.contains("\"Iron Helmet\""))
        }

    @Test
    fun `sendCharItemsList with empty inventory and equipment`() =
        runTest {
            val e = emitter("Char.Items")
            e.sendCharItemsList(sid, emptyList(), emptyMap())
            val data = drainGmcp()[0]
            assertTrue(data.jsonData.contains("\"inventory\":[]"))
            assertTrue(data.jsonData.contains("\"head\":null"))
            assertTrue(data.jsonData.contains("\"body\":null"))
            assertTrue(data.jsonData.contains("\"hand\":null"))
        }

    @Test
    fun `sendCharItemsList skipped when not supported`() =
        runTest {
            val e = emitter()
            e.sendCharItemsList(sid, emptyList(), emptyMap())
            assertTrue(drainGmcp().isEmpty())
        }

    // ── Char.Items.Add ──

    @Test
    fun `sendCharItemsAdd emits single item JSON`() =
        runTest {
            val e = emitter("Char.Items")
            e.sendCharItemsAdd(sid, item())
            val data = drainGmcp()[0]
            assertEquals("Char.Items.Add", data.gmcpPackage)
            assertTrue(data.jsonData.contains("\"name\":\"Iron Sword\""))
            assertTrue(data.jsonData.contains("\"slot\":\"hand\""))
            assertTrue(data.jsonData.contains("\"damage\":3"))
        }

    // ── Char.Items.Remove ──

    @Test
    fun `sendCharItemsRemove emits id and name`() =
        runTest {
            val e = emitter("Char.Items")
            e.sendCharItemsRemove(sid, item())
            val data = drainGmcp()[0]
            assertEquals("Char.Items.Remove", data.gmcpPackage)
            assertTrue(data.jsonData.contains("\"id\":\"forest:sword\""))
            assertTrue(data.jsonData.contains("\"name\":\"Iron Sword\""))
        }

    // ── Room.Players ──

    @Test
    fun `sendRoomPlayers emits player list excluding self`() =
        runTest {
            val e = emitter("Room.Players")
            val alice = player(name = "Alice")
            val bob =
                PlayerState(
                    sessionId = SessionId(2L),
                    name = "Bob",
                    roomId = RoomId("test:room1"),
                    level = 3,
                )
            e.sendRoomPlayers(sid, listOf(alice, bob))
            val data = drainGmcp()[0]
            assertEquals("Room.Players", data.gmcpPackage)
            // Should exclude self (Alice with sid=1)
            assertTrue(data.jsonData.contains("\"name\":\"Bob\""))
            assertTrue(data.jsonData.contains("\"level\":3"))
        }

    @Test
    fun `sendRoomPlayers with empty room emits empty array`() =
        runTest {
            val e = emitter("Room.Players")
            e.sendRoomPlayers(sid, listOf(player()))
            val data = drainGmcp()[0]
            assertEquals("[]", data.jsonData)
        }

    // ── Room.AddPlayer ──

    @Test
    fun `sendRoomAddPlayer emits single player`() =
        runTest {
            val e = emitter("Room.Players")
            e.sendRoomAddPlayer(sid, player(name = "Bob", level = 7))
            val data = drainGmcp()[0]
            assertEquals("Room.AddPlayer", data.gmcpPackage)
            assertTrue(data.jsonData.contains("\"name\":\"Bob\""))
            assertTrue(data.jsonData.contains("\"level\":7"))
        }

    // ── Room.RemovePlayer ──

    @Test
    fun `sendRoomRemovePlayer emits name only`() =
        runTest {
            val e = emitter("Room.Players")
            e.sendRoomRemovePlayer(sid, "Charlie")
            val data = drainGmcp()[0]
            assertEquals("Room.RemovePlayer", data.gmcpPackage)
            assertEquals("{\"name\":\"Charlie\"}", data.jsonData)
        }

    // ── Char.Skills ──

    @Test
    fun `sendCharSkills emits ability list JSON`() =
        runTest {
            val e = emitter("Char.Skills")
            e.sendCharSkills(sid, listOf(ability())) { 2300L }
            val data = drainGmcp()[0]
            assertEquals("Char.Skills", data.gmcpPackage)
            assertTrue(data.jsonData.contains("\"id\":\"firebolt\""))
            assertTrue(data.jsonData.contains("\"name\":\"Firebolt\""))
            assertTrue(data.jsonData.contains("\"description\":\"A bolt of fire.\""))
            assertTrue(data.jsonData.contains("\"manaCost\":8"))
            assertTrue(data.jsonData.contains("\"cooldownMs\":5000"))
            assertTrue(data.jsonData.contains("\"cooldownRemainingMs\":2300"))
            assertTrue(data.jsonData.contains("\"levelRequired\":1"))
            assertTrue(data.jsonData.contains("\"targetType\":\"ENEMY\""))
            assertTrue(data.jsonData.contains("\"classRestriction\":null"))
        }

    @Test
    fun `sendCharSkills with no abilities emits empty array`() =
        runTest {
            val e = emitter("Char.Skills")
            e.sendCharSkills(sid, emptyList())
            val data = drainGmcp()[0]
            assertEquals("[]", data.jsonData)
        }

    @Test
    fun `sendCharSkills skipped when not supported`() =
        runTest {
            val e = emitter()
            e.sendCharSkills(sid, listOf(ability())) { 2300L }
            assertTrue(drainGmcp().isEmpty())
        }

    // ── Char.Name ──

    @Test
    fun `sendCharName emits name race class level`() =
        runTest {
            val e = emitter("Char.Name")
            e.sendCharName(sid, player(name = "Alice", race = "ELF", playerClass = "MAGE", level = 10))
            val data = drainGmcp()[0]
            assertEquals("Char.Name", data.gmcpPackage)
            assertTrue(data.jsonData.contains("\"name\":\"Alice\""))
            assertTrue(data.jsonData.contains("\"race\":\"ELF\""))
            assertTrue(data.jsonData.contains("\"class\":\"MAGE\""))
            assertTrue(data.jsonData.contains("\"level\":10"))
        }

    // ── Comm.Channel ──

    @Test
    fun `sendCommChannel emits channel sender message`() =
        runTest {
            val e = emitter("Comm.Channel")
            e.sendCommChannel(sid, "gossip", "Alice", "hello world")
            val data = drainGmcp()[0]
            assertEquals("Comm.Channel", data.gmcpPackage)
            assertTrue(data.jsonData.contains("\"channel\":\"gossip\""))
            assertTrue(data.jsonData.contains("\"sender\":\"Alice\""))
            assertTrue(data.jsonData.contains("\"message\":\"hello world\""))
        }

    @Test
    fun `sendCommChannel JSON-escapes message content`() =
        runTest {
            val e = emitter("Comm.Channel")
            e.sendCommChannel(sid, "say", "Bob", "He said \"hi\" to me")
            val data = drainGmcp()[0]
            assertTrue(data.jsonData.contains("""He said \"hi\" to me"""))
        }

    // ── Core.Ping ──

    @Test
    fun `sendCorePing emits empty object`() =
        runTest {
            val e = emitter("Core.Ping")
            e.sendCorePing(sid)
            val data = drainGmcp()[0]
            assertEquals("Core.Ping", data.gmcpPackage)
            assertEquals("{}", data.jsonData)
        }

    @Test
    fun `sendCorePing skipped when not supported`() =
        runTest {
            val e = emitter()
            e.sendCorePing(sid)
            assertTrue(drainGmcp().isEmpty())
        }

    // ── Prefix matching ──

    @Test
    fun `prefix matching enables sub-packages`() =
        runTest {
            // Subscribing to "Char.Items" should enable "Char.Items.List", "Char.Items.Add", etc.
            val e = emitter("Char.Items")
            e.sendCharItemsList(sid, emptyList(), emptyMap())
            e.sendCharItemsAdd(sid, item())
            e.sendCharItemsRemove(sid, item())
            assertEquals(3, drainGmcp().size)
        }

    @Test
    fun `prefix matching enables Room sub-packages`() =
        runTest {
            val e = emitter("Room.Players")
            e.sendRoomPlayers(sid, emptyList())
            e.sendRoomAddPlayer(sid, player())
            e.sendRoomRemovePlayer(sid, "Bob")
            assertEquals(3, drainGmcp().size)
        }

    @Test
    fun `sendRoomItems emits item list JSON`() =
        runTest {
            val e = emitter("Room.Items")
            e.sendRoomItems(
                sid,
                listOf(
                    item(id = "zone:apple", name = "a red apple", slot = null),
                    item(id = "zone:helm", name = "an iron helm", slot = ItemSlot.HEAD),
                ),
            )
            val data = drainGmcp()[0]
            assertEquals("Room.Items", data.gmcpPackage)
            assertTrue(data.jsonData.contains("\"id\":\"zone:apple\""))
            assertTrue(data.jsonData.contains("\"name\":\"a red apple\""))
            assertTrue(data.jsonData.contains("\"id\":\"zone:helm\""))
        }

    @Test
    fun `sendRoomItems with empty list emits empty array`() =
        runTest {
            val e = emitter("Room.Items")
            e.sendRoomItems(sid, emptyList())
            val data = drainGmcp()[0]
            assertEquals("Room.Items", data.gmcpPackage)
            assertEquals("[]", data.jsonData)
        }

    @Test
    fun `sendRoomItems skipped when not supported`() =
        runTest {
            val e = emitter()
            e.sendRoomItems(sid, listOf(item()))
            assertTrue(drainGmcp().isEmpty())
        }

    @Test
    fun `item without slot emits null for slot`() =
        runTest {
            val e = emitter("Char.Items")
            val noSlotItem = item(slot = null)
            e.sendCharItemsAdd(sid, noSlotItem)
            val data = drainGmcp()[0]
            assertTrue(data.jsonData.contains("\"slot\":null"))
        }

    // ── Room.Mobs ──

    private fun mob(
        id: String = "zone:rat",
        name: String = "a rat",
        hp: Int = 8,
        maxHp: Int = 10,
    ) = MobState(
        id = MobId(id),
        name = name,
        roomId = RoomId("test:room1"),
        hp = hp,
        maxHp = maxHp,
    )

    @Test
    fun `sendRoomMobs emits mob list JSON`() =
        runTest {
            val e = emitter("Room.Mobs")
            e.sendRoomMobs(sid, listOf(mob(), mob(id = "zone:wolf", name = "a wolf", hp = 20, maxHp = 20)))
            val data = drainGmcp()[0]
            assertEquals("Room.Mobs", data.gmcpPackage)
            assertTrue(data.jsonData.contains("\"name\":\"a rat\""))
            assertTrue(data.jsonData.contains("\"hp\":8"))
            assertTrue(data.jsonData.contains("\"maxHp\":10"))
            assertTrue(data.jsonData.contains("\"name\":\"a wolf\""))
            assertTrue(data.jsonData.contains("\"id\":\"zone:rat\""))
        }

    @Test
    fun `sendRoomMobs with empty list emits empty array`() =
        runTest {
            val e = emitter("Room.Mobs")
            e.sendRoomMobs(sid, emptyList())
            val data = drainGmcp()[0]
            assertEquals("[]", data.jsonData)
        }

    @Test
    fun `sendRoomMobs skipped when not supported`() =
        runTest {
            val e = emitter()
            e.sendRoomMobs(sid, listOf(mob()))
            assertTrue(drainGmcp().isEmpty())
        }

    // ── Room.AddMob ──

    @Test
    fun `sendRoomAddMob emits single mob JSON`() =
        runTest {
            val e = emitter("Room.Mobs")
            e.sendRoomAddMob(sid, mob(name = "a wolf", hp = 15, maxHp = 20))
            val data = drainGmcp()[0]
            assertEquals("Room.AddMob", data.gmcpPackage)
            assertTrue(data.jsonData.contains("\"name\":\"a wolf\""))
            assertTrue(data.jsonData.contains("\"hp\":15"))
            assertTrue(data.jsonData.contains("\"maxHp\":20"))
        }

    // ── Room.UpdateMob ──

    @Test
    fun `sendRoomUpdateMob emits updated mob JSON`() =
        runTest {
            val e = emitter("Room.Mobs")
            e.sendRoomUpdateMob(sid, mob(hp = 3, maxHp = 10))
            val data = drainGmcp()[0]
            assertEquals("Room.UpdateMob", data.gmcpPackage)
            assertTrue(data.jsonData.contains("\"hp\":3"))
            assertTrue(data.jsonData.contains("\"maxHp\":10"))
            assertTrue(data.jsonData.contains("\"id\":\"zone:rat\""))
        }

    // ── Room.RemoveMob ──

    @Test
    fun `sendRoomRemoveMob emits id only`() =
        runTest {
            val e = emitter("Room.Mobs")
            e.sendRoomRemoveMob(sid, "zone:rat")
            val data = drainGmcp()[0]
            assertEquals("Room.RemoveMob", data.gmcpPackage)
            assertEquals("{\"id\":\"zone:rat\"}", data.jsonData)
        }

    @Test
    fun `prefix matching enables Room Mobs sub-packages`() =
        runTest {
            val e = emitter("Room.Mobs")
            e.sendRoomMobs(sid, emptyList())
            e.sendRoomAddMob(sid, mob())
            e.sendRoomUpdateMob(sid, mob())
            e.sendRoomRemoveMob(sid, "zone:rat")
            assertEquals(4, drainGmcp().size)
        }

    // ── Group.Info ──

    @Test
    fun `sendGroupInfo emits leader and members JSON when supported`() =
        runTest {
            val e = emitter("Group.Info")
            val alice = player(name = "Alice", level = 5, hp = 50, maxHp = 100, playerClass = "WARRIOR")
            val bob =
                PlayerState(
                    sessionId = SessionId(2L),
                    name = "Bob",
                    roomId = RoomId("test:room1"),
                    level = 3,
                    hp = 30,
                    maxHp = 80,
                    playerClass = "MAGE",
                )
            e.sendGroupInfo(sid, "Alice", listOf(alice, bob))
            val data = drainGmcp()[0]
            assertEquals("Group.Info", data.gmcpPackage)
            assertTrue(data.jsonData.contains("\"leader\":\"Alice\""))
            assertTrue(data.jsonData.contains("\"name\":\"Alice\""))
            assertTrue(data.jsonData.contains("\"name\":\"Bob\""))
            assertTrue(data.jsonData.contains("\"level\":3"))
            assertTrue(data.jsonData.contains("\"hp\":30"))
            assertTrue(data.jsonData.contains("\"maxHp\":80"))
            assertTrue(data.jsonData.contains("\"class\":\"MAGE\""))
        }

    @Test
    fun `sendGroupInfo with null leader emits null`() =
        runTest {
            val e = emitter("Group.Info")
            e.sendGroupInfo(sid, null, emptyList())
            val data = drainGmcp()[0]
            assertEquals("Group.Info", data.gmcpPackage)
            assertTrue(data.jsonData.contains("\"leader\":null"))
            assertTrue(data.jsonData.contains("\"members\":[]"))
        }

    @Test
    fun `sendGroupInfo skipped when not supported`() =
        runTest {
            val e = emitter()
            e.sendGroupInfo(sid, "Alice", listOf(player()))
            assertTrue(drainGmcp().isEmpty())
        }
}
