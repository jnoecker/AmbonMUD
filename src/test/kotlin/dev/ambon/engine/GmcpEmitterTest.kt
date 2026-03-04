package dev.ambon.engine

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.DamageRange
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
import dev.ambon.test.TEST_SESSION_ID
import dev.ambon.test.drainAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GmcpEmitterTest {
    private val sid = TEST_SESSION_ID
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

    private fun drainGmcp(): List<OutboundEvent.GmcpData> = outbound.drainAll().filterIsInstance<OutboundEvent.GmcpData>()

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
        effect = AbilityEffect.DirectDamage(damage = DamageRange(5, 10)),
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
        image: String? = null,
    ) = MobState(
        id = MobId(id),
        name = name,
        roomId = RoomId("test:room1"),
        hp = hp,
        maxHp = maxHp,
        image = image,
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

    // ── Guild.Info ──

    @Test
    fun `sendGuildInfo emits guild name tag rank motd and counts`() =
        runTest {
            val e = emitter("Guild.Info")
            e.sendGuildInfo(sid, "Knights", "KNT", "LEADER", "Welcome!", 5, 50)
            val data = drainGmcp()[0]
            assertEquals("Guild.Info", data.gmcpPackage)
            assertTrue(data.jsonData.contains("\"name\":\"Knights\""))
            assertTrue(data.jsonData.contains("\"tag\":\"KNT\""))
            assertTrue(data.jsonData.contains("\"rank\":\"LEADER\""))
            assertTrue(data.jsonData.contains("\"motd\":\"Welcome!\""))
            assertTrue(data.jsonData.contains("\"memberCount\":5"))
            assertTrue(data.jsonData.contains("\"maxSize\":50"))
        }

    @Test
    fun `sendGuildInfo with null fields emits null values`() =
        runTest {
            val e = emitter("Guild.Info")
            e.sendGuildInfo(sid, null, null, null, null, 0, 50)
            val data = drainGmcp()[0]
            assertTrue(data.jsonData.contains("\"name\":null"))
            assertTrue(data.jsonData.contains("\"tag\":null"))
            assertTrue(data.jsonData.contains("\"rank\":null"))
            assertTrue(data.jsonData.contains("\"memberCount\":0"))
        }

    @Test
    fun `sendGuildInfo skipped when not supported`() =
        runTest {
            val e = emitter()
            e.sendGuildInfo(sid, "Knights", "KNT", "LEADER", null, 5, 50)
            assertTrue(drainGmcp().isEmpty())
        }

    // ── Guild.Members ──

    @Test
    fun `sendGuildMembers emits member list with ranks and online status`() =
        runTest {
            val e = emitter("Guild.Info")
            val members = listOf(
                GuildMemberInfo(name = "Alice", rank = "LEADER", online = true, level = 10),
                GuildMemberInfo(name = "Bob", rank = "MEMBER", online = false, level = 5),
            )
            e.sendGuildMembers(sid, members)
            val data = drainGmcp()[0]
            assertEquals("Guild.Members", data.gmcpPackage)
            assertTrue(data.jsonData.contains("\"name\":\"Alice\""))
            assertTrue(data.jsonData.contains("\"rank\":\"LEADER\""))
            assertTrue(data.jsonData.contains("\"online\":true"))
            assertTrue(data.jsonData.contains("\"name\":\"Bob\""))
            assertTrue(data.jsonData.contains("\"online\":false"))
            assertTrue(data.jsonData.contains("\"level\":5"))
        }

    @Test
    fun `sendGuildMembers uses Guild Info as support check`() =
        runTest {
            val e = emitter()
            e.sendGuildMembers(sid, listOf(GuildMemberInfo("Alice", "LEADER", true, 10)))
            assertTrue(drainGmcp().isEmpty())
        }

    // ── Guild.Chat ──

    @Test
    fun `sendGuildChat emits sender and message`() =
        runTest {
            val e = emitter("Guild.Info")
            e.sendGuildChat(sid, "Alice", "hello guild!")
            val data = drainGmcp()[0]
            assertEquals("Guild.Chat", data.gmcpPackage)
            assertTrue(data.jsonData.contains("\"sender\":\"Alice\""))
            assertTrue(data.jsonData.contains("\"message\":\"hello guild!\""))
        }

    @Test
    fun `sendGuildChat skipped when not supported`() =
        runTest {
            val e = emitter()
            e.sendGuildChat(sid, "Alice", "hello")
            assertTrue(drainGmcp().isEmpty())
        }

    // ── Dialogue ──

    @Test
    fun `sendDialogueNode emits correct JSON with choices`() =
        runTest {
            val e = emitter("Dialogue")
            e.sendDialogueNode(
                sid,
                "a wise sage",
                "Hello there!",
                listOf(1 to "Tell me more.", 2 to "Goodbye."),
            )
            val events = drainGmcp()
            assertEquals(1, events.size)
            val data = events[0]
            assertEquals("Dialogue.Node", data.gmcpPackage)
            assertTrue(data.jsonData.contains("\"mobName\":\"a wise sage\""))
            assertTrue(data.jsonData.contains("\"text\":\"Hello there!\""))
            assertTrue(data.jsonData.contains("\"index\":1"))
            assertTrue(data.jsonData.contains("\"index\":2"))
            assertTrue(data.jsonData.contains("\"text\":\"Tell me more.\""))
            assertTrue(data.jsonData.contains("\"text\":\"Goodbye.\""))
        }

    @Test
    fun `sendDialogueNode emits empty choices array`() =
        runTest {
            val e = emitter("Dialogue")
            e.sendDialogueNode(sid, "a sage", "End of dialogue.", emptyList())
            val events = drainGmcp()
            assertEquals(1, events.size)
            assertTrue(events[0].jsonData.contains("\"choices\":[]"))
        }

    @Test
    fun `sendDialogueNode skipped when not supported`() =
        runTest {
            val e = emitter()
            e.sendDialogueNode(sid, "a sage", "Hello!", listOf(1 to "Hi"))
            assertTrue(drainGmcp().isEmpty())
        }

    @Test
    fun `sendDialogueEnd emits correct JSON with reason`() =
        runTest {
            val e = emitter("Dialogue")
            e.sendDialogueEnd(sid, "a wise sage", "ended")
            val events = drainGmcp()
            assertEquals(1, events.size)
            val data = events[0]
            assertEquals("Dialogue.End", data.gmcpPackage)
            assertTrue(data.jsonData.contains("\"mobName\":\"a wise sage\""))
            assertTrue(data.jsonData.contains("\"reason\":\"ended\""))
        }

    @Test
    fun `sendDialogueEnd skipped when not supported`() =
        runTest {
            val e = emitter()
            e.sendDialogueEnd(sid, "a sage", "moved")
            assertTrue(drainGmcp().isEmpty())
        }

    // ── Image field tests ──

    @Test
    fun `sendRoomInfo includes image when set`() =
        runTest {
            val e = emitter("Room.Info")
            val r = Room(
                id = RoomId("forest:clearing"),
                title = "A Sunny Clearing",
                description = "Sunlight.",
                exits = emptyMap(),
                image = "/images/forest/clearing.png",
            )
            e.sendRoomInfo(sid, r)
            val data = drainGmcp()[0]
            assertTrue(data.jsonData.contains("\"image\":\"/images/forest/clearing.png\""))
        }

    @Test
    fun `sendRoomInfo omits image when null`() =
        runTest {
            val e = emitter("Room.Info")
            e.sendRoomInfo(sid, room())
            val data = drainGmcp()[0]
            assertTrue(data.jsonData.contains("\"image\":null"))
        }

    @Test
    fun `sendRoomMobs includes image when set on mob`() =
        runTest {
            val e = emitter("Room.Mobs")
            e.sendRoomMobs(sid, listOf(mob(image = "/images/mobs/rat.png")))
            val data = drainGmcp()[0]
            assertTrue(data.jsonData.contains("\"image\":\"/images/mobs/rat.png\""))
        }

    @Test
    fun `sendRoomItems includes image when set on item`() =
        runTest {
            val e = emitter("Room.Items")
            val itemWithImage = ItemInstance(
                id = ItemId("forest:sword"),
                item = Item(
                    keyword = "sword",
                    displayName = "Iron Sword",
                    slot = ItemSlot.HAND,
                    damage = 3,
                    armor = 0,
                    image = "/images/items/sword.png",
                ),
            )
            e.sendRoomItems(sid, listOf(itemWithImage))
            val data = drainGmcp()[0]
            assertTrue(data.jsonData.contains("\"image\":\"/images/items/sword.png\""))
        }

    @Test
    fun `sendCharItemsList includes image when set on item`() =
        runTest {
            val e = emitter("Char.Items")
            val itemWithImage = ItemInstance(
                id = ItemId("forest:axe"),
                item = Item(
                    keyword = "axe",
                    displayName = "Battle Axe",
                    slot = ItemSlot.HAND,
                    damage = 5,
                    armor = 0,
                    image = "/images/items/axe.png",
                ),
            )
            e.sendCharItemsList(sid, listOf(itemWithImage), emptyMap())
            val data = drainGmcp()[0]
            assertTrue(data.jsonData.contains("\"image\":\"/images/items/axe.png\""))
        }

    // ── Char.Combat ──

    private fun combatEmitter(target: CombatTargetInfo? = null, vararg supported: String): GmcpEmitter {
        val packages = supported.toSet()
        return GmcpEmitter(
            outbound = outbound,
            supportsPackage = { _, pkg ->
                packages.any { s -> pkg == s || pkg.startsWith("$s.") }
            },
            progression = progression,
            getCombatTarget = { target },
        )
    }

    @Test
    fun `sendCharCombat emits target data when in combat`() =
        runTest {
            val target = CombatTargetInfo(
                id = "mob-1",
                name = "Goblin",
                hp = 15,
                maxHp = 30,
                image = "/images/goblin.png",
            )
            val e = combatEmitter(target, "Char")
            e.sendCharCombat(sid)
            val events = drainGmcp()
            assertEquals(1, events.size)
            val data = events[0]
            assertEquals("Char.Combat", data.gmcpPackage)
            assertTrue(data.jsonData.contains("\"targetId\":\"mob-1\""))
            assertTrue(data.jsonData.contains("\"targetName\":\"Goblin\""))
            assertTrue(data.jsonData.contains("\"targetHp\":15"))
            assertTrue(data.jsonData.contains("\"targetMaxHp\":30"))
            assertTrue(data.jsonData.contains("\"targetImage\":\"/images/goblin.png\""))
        }

    @Test
    fun `sendCharCombat emits null target when not in combat`() =
        runTest {
            val e = combatEmitter(null, "Char")
            e.sendCharCombat(sid)
            val events = drainGmcp()
            assertEquals(1, events.size)
            val data = events[0]
            assertEquals("Char.Combat", data.gmcpPackage)
            assertTrue(data.jsonData.contains("\"targetId\":null"))
            assertTrue(data.jsonData.contains("\"targetName\":null"))
            assertTrue(data.jsonData.contains("\"targetHp\":null"))
            assertTrue(data.jsonData.contains("\"targetMaxHp\":null"))
        }

    @Test
    fun `sendCharCombat does nothing when not supported`() =
        runTest {
            val target = CombatTargetInfo(id = "mob-1", name = "Goblin", hp = 15, maxHp = 30)
            val e = combatEmitter(target)
            e.sendCharCombat(sid)
            assertTrue(drainGmcp().isEmpty())
        }

    // ── Shop ──

    @Test
    fun `sendShopList emits correct JSON when supported`() =
        runTest {
            val e = emitter("Shop")
            val items = listOf(
                ItemId("zone:sword") to Item(
                    keyword = "sword",
                    displayName = "Iron Sword",
                    description = "A sturdy blade.",
                    slot = ItemSlot.HAND,
                    damage = 5,
                    armor = 0,
                    basePrice = 100,
                    consumable = false,
                ),
                ItemId("zone:potion") to Item(
                    keyword = "potion",
                    displayName = "Health Potion",
                    description = "Restores health.",
                    consumable = true,
                    basePrice = 20,
                ),
            )
            e.sendShopList(sid, "Market Stall", items, buyMultiplier = 1.5, sellMultiplier = 0.5)
            val events = drainGmcp()
            assertEquals(1, events.size)
            val data = events[0]
            assertEquals("Shop.List", data.gmcpPackage)
            assertTrue(data.jsonData.contains("\"name\":\"Market Stall\""))
            assertTrue(data.jsonData.contains("\"sellMultiplier\":0.5"))
            // sword: 100 * 1.5 = 150
            assertTrue(data.jsonData.contains("\"buyPrice\":150"))
            assertTrue(data.jsonData.contains("\"keyword\":\"sword\""))
            assertTrue(data.jsonData.contains("\"slot\":\"hand\""))
            assertTrue(data.jsonData.contains("\"damage\":5"))
            // potion: 20 * 1.5 = 30
            assertTrue(data.jsonData.contains("\"buyPrice\":30"))
            assertTrue(data.jsonData.contains("\"consumable\":true"))
        }

    @Test
    fun `sendShopList skipped when not supported`() =
        runTest {
            val e = emitter()
            e.sendShopList(sid, "Shop", emptyList(), buyMultiplier = 1.0, sellMultiplier = 0.5)
            assertTrue(drainGmcp().isEmpty())
        }

    @Test
    fun `sendShopClose emits empty object when supported`() =
        runTest {
            val e = emitter("Shop")
            e.sendShopClose(sid)
            val events = drainGmcp()
            assertEquals(1, events.size)
            val data = events[0]
            assertEquals("Shop.Close", data.gmcpPackage)
            assertEquals("{}", data.jsonData)
        }

    @Test
    fun `sendShopClose skipped when not supported`() =
        runTest {
            val e = emitter()
            e.sendShopClose(sid)
            assertTrue(drainGmcp().isEmpty())
        }

    @Test
    fun `basePrice appears in Char Items List`() =
        runTest {
            val e = emitter("Char.Items")
            val priced = ItemInstance(
                id = ItemId("zone:shield"),
                item = Item(
                    keyword = "shield",
                    displayName = "Iron Shield",
                    slot = ItemSlot.BODY,
                    damage = 0,
                    armor = 5,
                    basePrice = 75,
                ),
            )
            e.sendCharItemsList(sid, listOf(priced), emptyMap())
            val data = drainGmcp()[0]
            assertTrue(data.jsonData.contains("\"basePrice\":75"), "Expected basePrice in Char.Items.List. got=${data.jsonData}")
        }
}
