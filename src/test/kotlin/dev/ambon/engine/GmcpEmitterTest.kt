package dev.ambon.engine

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.config.EquipmentConfig
import dev.ambon.domain.DamageRange
import dev.ambon.domain.StatMap
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.items.ItemSlot
import dev.ambon.domain.items.ItemUseEffect
import dev.ambon.domain.mob.MobState
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.Room
import dev.ambon.engine.abilities.AbilityDefinition
import dev.ambon.engine.abilities.AbilityEffect
import dev.ambon.engine.abilities.AbilityId
import dev.ambon.engine.events.CombatEvent
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.test.TEST_SESSION_ID
import dev.ambon.test.buildTestPlayerRegistry
import dev.ambon.test.drainAll
import dev.ambon.test.loginOrFail
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GmcpEmitterTest {
    private val sid = TEST_SESSION_ID
    private val outbound = LocalOutboundBus()

    private val progression =
        PlayerProgression(
            dev.ambon.config.ProgressionConfig(
                maxLevel = 50,
                xp = dev.ambon.config.XpCurveConfig(baseXp = 100L, exponent = 2.0, linearXp = 0L),
            ),
        )

    private val defaultSlotRegistry = EquipmentSlotRegistry(EquipmentConfig())

    private fun emitter(vararg supported: String): GmcpEmitter {
        val packages = supported.toSet()
        return GmcpEmitter(
            outbound = outbound,
            supportsPackage = { _, pkg ->
                packages.any { s -> pkg == s || pkg.startsWith("$s.") }
            },
            progression = progression,
            equipmentSlotRegistry = defaultSlotRegistry,
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
        slot: ItemSlot? = ItemSlot.WEAPON,
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
        skillPointCost: Int = 1,
    ) = AbilityDefinition(
        id = AbilityId(id),
        displayName = name,
        description = "A bolt of fire.",
        manaCost = manaCost,
        cooldownMs = cooldownMs,
        levelRequired = 1,
        targetType = "enemy",
        effect = AbilityEffect.DirectDamage(damage = DamageRange(5, 10)),
        skillPointCost = skillPointCost,
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

    @Test
    fun `sendCharVitals suppresses duplicate emits when payload unchanged`() =
        runTest {
            val e = emitter("Char.Vitals")
            val p = player()
            e.sendCharVitals(sid, p)
            e.sendCharVitals(sid, p)
            e.sendCharVitals(sid, p)
            val events = drainGmcp()
            assertEquals(1, events.size, "Unchanged vitals should only emit once")
        }

    @Test
    fun `sendCharVitals re-emits when payload changes`() =
        runTest {
            val e = emitter("Char.Vitals")
            e.sendCharVitals(sid, player(hp = 50))
            e.sendCharVitals(sid, player(hp = 40))
            assertEquals(2, drainGmcp().size)
        }

    @Test
    fun `forgetSession clears vitals dirty-diff cache`() =
        runTest {
            val e = emitter("Char.Vitals")
            val p = player()
            e.sendCharVitals(sid, p)
            e.forgetSession(sid)
            e.sendCharVitals(sid, p)
            assertEquals(2, drainGmcp().size, "After forget, identical vitals should emit again")
        }

    // ── broadcast* helpers: serialize-once, send-N semantics ─────────────
    //
    // The broadcast* methods route every recipient through broadcastSerialized,
    // which builds the payload + writeValueAsString once regardless of recipient
    // count. These tests assert that a multi-recipient broadcast emits N GmcpData
    // events with *identical* jsonData — catches both "missed a recipient" and
    // "somehow diverged the payloads" regressions without needing to introspect
    // the private helper.

    @Test
    fun `broadcastRoomUpdateMob emits identical payload to all recipients`() =
        runTest {
            val e = emitter("Room.Mobs")
            val roomId = RoomId("test:room1")
            val players = buildTestPlayerRegistry(roomId)
            players.loginOrFail(SessionId(1L), "Alice")
            players.loginOrFail(SessionId(2L), "Bob")
            players.loginOrFail(SessionId(3L), "Carol")
            outbound.drainAll() // discard login-side events

            val mob = MobState(
                id = MobId("mob-1"),
                name = "a rat",
                description = "a rat",
                hp = 10,
                maxHp = 20,
                roomId = roomId,
            )
            e.broadcastRoomUpdateMob(roomId, mob, players)

            val events = drainGmcp().filter { it.gmcpPackage == "Room.UpdateMob" }
            assertEquals(3, events.size, "Expected one GMCP event per room occupant")
            val payloads = events.map { it.jsonData }.toSet()
            assertEquals(1, payloads.size, "All recipients should receive byte-identical payload")
            assertTrue(events[0].jsonData.contains("\"hp\":10"))
            assertTrue(events[0].jsonData.contains("\"maxHp\":20"))
        }

    @Test
    fun `broadcastWorldTime emits identical payload to all online players`() =
        runTest {
            val e = emitter("World.Time")
            val roomId = RoomId("test:room1")
            val players = buildTestPlayerRegistry(roomId)
            players.loginOrFail(SessionId(1L), "Alice")
            players.loginOrFail(SessionId(2L), "Bob")
            outbound.drainAll()

            e.broadcastWorldTime(
                GmcpEmitter.WorldTimePayload(period = "noon", hour = 12, minute = 30),
                players,
            )

            val events = drainGmcp().filter { it.gmcpPackage == "World.Time" }
            assertEquals(2, events.size)
            assertEquals(1, events.map { it.jsonData }.toSet().size, "All recipients share one serialized payload")
            assertTrue(events[0].jsonData.contains("\"hour\":12"))
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
            assertTrue(data.jsonData.contains("\"weapon\":null"))
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
            assertTrue(data.jsonData.contains("\"slot\":\"weapon\""))
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
            assertTrue(data.jsonData.contains("\"skillPointCost\":1"))
            assertTrue(data.jsonData.contains("\"manaCost\":8"))
            assertTrue(data.jsonData.contains("\"cooldownMs\":5000"))
            assertTrue(data.jsonData.contains("\"cooldownRemainingMs\":2300"))
            assertTrue(data.jsonData.contains("\"levelRequired\":1"))
            assertTrue(data.jsonData.contains("\"targetType\":\"enemy\""))
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
                    slot = ItemSlot.WEAPON,
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
                    slot = ItemSlot.WEAPON,
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

    // ── Char.Combat.Event ──

    @Test
    fun `sendCombatEvent emits meleeHit JSON`() =
        runTest {
            val e = emitter("Char.Combat.Event")
            e.sendCombatEvent(sid, CombatEvent.MeleeHit("Goblin", "mob-1", 12, sourceIsPlayer = true))
            val events = drainGmcp()
            assertEquals(1, events.size)
            assertEquals("Char.Combat.Event", events[0].gmcpPackage)
            assertTrue(events[0].jsonData.contains("\"type\":\"meleeHit\""))
            assertTrue(events[0].jsonData.contains("\"targetName\":\"Goblin\""))
            assertTrue(events[0].jsonData.contains("\"damage\":12"))
            assertTrue(events[0].jsonData.contains("\"sourceIsPlayer\":true"))
        }

    @Test
    fun `sendCombatEvent emits kill JSON`() =
        runTest {
            val e = emitter("Char.Combat.Event")
            e.sendCombatEvent(sid, CombatEvent.Kill("Goblin", "mob-1", xpGained = 100L, goldGained = 25L))
            val events = drainGmcp()
            assertEquals(1, events.size)
            assertTrue(events[0].jsonData.contains("\"type\":\"kill\""))
            assertTrue(events[0].jsonData.contains("\"xpGained\":100"))
            assertTrue(events[0].jsonData.contains("\"goldGained\":25"))
        }

    @Test
    fun `sendCombatEvent does nothing when not supported`() =
        runTest {
            val e = emitter()
            e.sendCombatEvent(sid, CombatEvent.MeleeHit("Goblin", "mob-1", 10, sourceIsPlayer = true))
            assertTrue(drainGmcp().isEmpty())
        }

    @Test
    fun `sendCombatEvent matches prefix Char-Combat-Event`() =
        runTest {
            val e = emitter("Char.Combat")
            e.sendCombatEvent(sid, CombatEvent.Dodge("Goblin", "mob-1", sourceIsPlayer = true))
            val events = drainGmcp()
            // "Char.Combat.Event" starts with "Char.Combat." so it should match
            assertEquals(1, events.size)
            assertTrue(events[0].jsonData.contains("\"type\":\"dodge\""))
        }

    // ── Char.Stats ──

    @Test
    fun `sendCharStats emits correct JSON`() =
        runTest {
            val e = emitter("Char.Stats")
            val p = player()
            e.sendCharStats(
                sid,
                p,
                StatMap.of("STR" to 16, "DEX" to 14),
                baseDamageMin = 3,
                baseDamageMax = 8,
                armor = 5,
                dodgePercent = 12,
            )
            val events = drainGmcp()
            assertEquals(1, events.size)
            assertEquals("Char.Stats", events[0].gmcpPackage)
            assertTrue(events[0].jsonData.contains("\"id\":\"STR\""))
            assertTrue(events[0].jsonData.contains("\"effective\":16"))
            assertTrue(events[0].jsonData.contains("\"baseDamageMin\":3"))
            assertTrue(events[0].jsonData.contains("\"armor\":5"))
            assertTrue(events[0].jsonData.contains("\"dodgePercent\":12"))
        }

    @Test
    fun `sendCharStats does nothing when not supported`() =
        runTest {
            val e = emitter()
            e.sendCharStats(sid, player(), StatMap.EMPTY, 0, 0, 0, 0)
            assertTrue(drainGmcp().isEmpty())
        }

    // ── Quest ──

    @Test
    fun `sendQuestList emits correct JSON`() =
        runTest {
            val e = emitter("Quest")
            e.sendQuestList(
                sid,
                listOf(
                    QuestListEntry(
                        id = "slay_goblins",
                        name = "Goblin Menace",
                        description = "Kill goblins.",
                        objectives = listOf(QuestObjectiveEntry("Kill 5 goblins", 3, 5)),
                    ),
                ),
            )
            val events = drainGmcp()
            assertEquals(1, events.size)
            assertEquals("Quest.List", events[0].gmcpPackage)
            assertTrue(events[0].jsonData.contains("\"id\":\"slay_goblins\""))
            assertTrue(events[0].jsonData.contains("\"name\":\"Goblin Menace\""))
            assertTrue(events[0].jsonData.contains("\"current\":3"))
            assertTrue(events[0].jsonData.contains("\"required\":5"))
        }

    @Test
    fun `sendQuestUpdate emits correct JSON`() =
        runTest {
            val e = emitter("Quest")
            e.sendQuestUpdate(sid, "slay_goblins", 0, 4, 5)
            val events = drainGmcp()
            assertEquals(1, events.size)
            assertEquals("Quest.Update", events[0].gmcpPackage)
            assertTrue(events[0].jsonData.contains("\"questId\":\"slay_goblins\""))
            assertTrue(events[0].jsonData.contains("\"objectiveIndex\":0"))
            assertTrue(events[0].jsonData.contains("\"current\":4"))
        }

    @Test
    fun `sendQuestComplete emits correct JSON`() =
        runTest {
            val e = emitter("Quest")
            e.sendQuestComplete(sid, "slay_goblins", "Goblin Menace")
            val events = drainGmcp()
            assertEquals(1, events.size)
            assertEquals("Quest.Complete", events[0].gmcpPackage)
            assertTrue(events[0].jsonData.contains("\"questId\":\"slay_goblins\""))
            assertTrue(events[0].jsonData.contains("\"questName\":\"Goblin Menace\""))
        }

    @Test
    fun `quest GMCP does nothing when not supported`() =
        runTest {
            val e = emitter()
            e.sendQuestList(sid, emptyList())
            e.sendQuestUpdate(sid, "q", 0, 1, 5)
            e.sendQuestComplete(sid, "q", "Q")
            assertTrue(drainGmcp().isEmpty())
        }

    // ── Char.Cooldown ──

    @Test
    fun `sendCharCooldown emits correct JSON`() =
        runTest {
            val e = emitter("Char.Cooldown")
            e.sendCharCooldown(sid, "fireball", 3000L)
            val events = drainGmcp()
            assertEquals(1, events.size)
            assertEquals("Char.Cooldown", events[0].gmcpPackage)
            assertTrue(events[0].jsonData.contains("\"abilityId\":\"fireball\""))
            assertTrue(events[0].jsonData.contains("\"cooldownMs\":3000"))
        }

    @Test
    fun `sendCharCooldown does nothing when not supported`() =
        runTest {
            val e = emitter()
            e.sendCharCooldown(sid, "fireball", 3000L)
            assertTrue(drainGmcp().isEmpty())
        }

    // ── Char.Gain ──

    @Test
    fun `sendCharGain emits xp gain JSON`() =
        runTest {
            val e = emitter("Char.Gain")
            e.sendCharGain(sid, "xp", 250L, "Goblin")
            val events = drainGmcp()
            assertEquals(1, events.size)
            assertEquals("Char.Gain", events[0].gmcpPackage)
            assertTrue(events[0].jsonData.contains("\"type\":\"xp\""))
            assertTrue(events[0].jsonData.contains("\"amount\":250"))
            assertTrue(events[0].jsonData.contains("\"source\":\"Goblin\""))
        }

    @Test
    fun `sendCharGain emits levelUp JSON`() =
        runTest {
            val e = emitter("Char.Gain")
            e.sendCharGain(sid, "levelUp", 0L, newLevel = 6, hpGained = 8, manaGained = 4)
            val events = drainGmcp()
            assertEquals(1, events.size)
            assertTrue(events[0].jsonData.contains("\"type\":\"levelUp\""))
            assertTrue(events[0].jsonData.contains("\"newLevel\":6"))
            assertTrue(events[0].jsonData.contains("\"hpGained\":8"))
            assertTrue(events[0].jsonData.contains("\"manaGained\":4"))
        }

    @Test
    fun `sendCharGain does nothing when not supported`() =
        runTest {
            val e = emitter()
            e.sendCharGain(sid, "xp", 100L, "Goblin")
            assertTrue(drainGmcp().isEmpty())
        }

    // ── Char.LevelUp ──

    @Test
    fun `sendCharLevelUp emits level-up JSON with hp mana and abilities`() =
        runTest {
            val e = emitter("Char.LevelUp")
            e.sendCharLevelUp(
                sid,
                previousLevel = 4,
                newLevel = 5,
                levelsGained = 1,
                hpGained = 12,
                manaGained = 6,
                newAbilities = listOf("Fireball", "Ice Shard"),
                skillPointsAvailable = 2,
                isMilestone = false,
            )
            val events = drainGmcp()
            assertEquals(1, events.size)
            assertEquals("Char.LevelUp", events[0].gmcpPackage)
            val body = events[0].jsonData
            assertTrue(body.contains("\"previousLevel\":4"), body)
            assertTrue(body.contains("\"newLevel\":5"), body)
            assertTrue(body.contains("\"levelsGained\":1"), body)
            assertTrue(body.contains("\"hpGained\":12"), body)
            assertTrue(body.contains("\"manaGained\":6"), body)
            assertTrue(body.contains("\"newAbilities\":[\"Fireball\",\"Ice Shard\"]"), body)
            assertTrue(body.contains("\"skillPointsAvailable\":2"), body)
            assertTrue(body.contains("\"isMilestone\":false"), body)
        }

    @Test
    fun `sendCharLevelUp marks milestone levels`() =
        runTest {
            val e = emitter("Char.LevelUp")
            e.sendCharLevelUp(
                sid,
                previousLevel = 9,
                newLevel = 10,
                levelsGained = 1,
                hpGained = 20,
                manaGained = 10,
                isMilestone = true,
            )
            val events = drainGmcp()
            assertEquals(1, events.size)
            assertTrue(events[0].jsonData.contains("\"isMilestone\":true"))
        }

    @Test
    fun `sendCharLevelUp does nothing when not supported`() =
        runTest {
            val e = emitter()
            e.sendCharLevelUp(
                sid,
                previousLevel = 4,
                newLevel = 5,
                levelsGained = 1,
                hpGained = 10,
                manaGained = 5,
            )
            assertTrue(drainGmcp().isEmpty())
        }

    // ── Room.MobInfo ──

    @Test
    fun `sendRoomMobInfo emits correct JSON`() =
        runTest {
            val e = emitter("Room.MobInfo")
            e.sendRoomMobInfo(
                sid,
                listOf(
                    MobInfoEntry(
                        id = "forest:goblin_1",
                        level = 3,
                        tier = "standard",
                        questGiver = true,
                        questAvailable = true,
                        questComplete = false,
                        shopKeeper = false,
                        dialogue = true,
                        aggressive = false,
                    ),
                ),
            )
            val events = drainGmcp()
            assertEquals(1, events.size)
            assertEquals("Room.MobInfo", events[0].gmcpPackage)
            assertTrue(events[0].jsonData.contains("\"id\":\"forest:goblin_1\""))
            assertTrue(events[0].jsonData.contains("\"level\":3"))
            assertTrue(events[0].jsonData.contains("\"questGiver\":true"))
            assertTrue(events[0].jsonData.contains("\"shopKeeper\":false"))
            assertTrue(events[0].jsonData.contains("\"dialogue\":true"))
        }

    @Test
    fun `sendRoomMobInfo does nothing when not supported`() =
        runTest {
            val e = emitter()
            e.sendRoomMobInfo(sid, emptyList())
            assertTrue(drainGmcp().isEmpty())
        }

    // ── Group.Info mana ──

    @Test
    fun `sendGroupInfo includes mana fields`() =
        runTest {
            val e = emitter("Group.Info")
            val members = listOf(player(name = "Alice", mana = 30, maxMana = 60))
            e.sendGroupInfo(sid, "Alice", members)
            val events = drainGmcp()
            assertEquals(1, events.size)
            assertTrue(events[0].jsonData.contains("\"mana\":30"))
            assertTrue(events[0].jsonData.contains("\"maxMana\":60"))
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
                    slot = ItemSlot.WEAPON,
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
            assertTrue(data.jsonData.contains("\"slot\":\"weapon\""))
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

    @Test
    fun `consumable flag and useEffect appear in Char Items List`() =
        runTest {
            val e = emitter("Char.Items")
            val potion = ItemInstance(
                id = ItemId("zone:potion"),
                item = Item(
                    keyword = "potion",
                    displayName = "Healing Potion",
                    consumable = true,
                    onUse = ItemUseEffect(healHp = 15, grantXp = 25L),
                ),
            )
            e.sendCharItemsList(sid, listOf(potion), emptyMap())
            val data = drainGmcp()[0]
            assertTrue(
                data.jsonData.contains("\"consumable\":true"),
                "Expected consumable=true in Char.Items.List. got=${data.jsonData}",
            )
            assertTrue(
                data.jsonData.contains("\"useEffect\":\"Restores 15 HP, Grants 25 XP\""),
                "Expected useEffect description in Char.Items.List. got=${data.jsonData}",
            )
        }

    @Test
    fun `non-consumable equipment omits consumable and useEffect in Char Items List`() =
        runTest {
            val e = emitter("Char.Items")
            val sword = item()
            e.sendCharItemsList(sid, listOf(sword), emptyMap())
            val data = drainGmcp()[0]
            // consumable/useEffect are serialized as null for non-consumables; never "true" or a value string.
            assertTrue(
                !data.jsonData.contains("\"consumable\":true"),
                "Non-consumable should not have consumable=true. got=${data.jsonData}",
            )
            assertTrue(
                !data.jsonData.contains("\"useEffect\":\""),
                "Non-consumable should not have a useEffect string. got=${data.jsonData}",
            )
        }

    @Test
    fun `consumable with only healHp produces heal-only description`() =
        runTest {
            val e = emitter("Char.Items")
            val food = ItemInstance(
                id = ItemId("zone:rations"),
                item = Item(
                    keyword = "rations",
                    displayName = "Traveler's Rations",
                    consumable = true,
                    onUse = ItemUseEffect(healHp = 30),
                ),
            )
            e.sendCharItemsList(sid, listOf(food), emptyMap())
            val data = drainGmcp()[0]
            assertTrue(data.jsonData.contains("\"consumable\":true"))
            assertTrue(
                data.jsonData.contains("\"useEffect\":\"Restores 30 HP\""),
                "Expected heal-only useEffect. got=${data.jsonData}",
            )
        }

    @Test
    fun `sendServerAssets emits resolved asset URLs`() =
        runTest {
            val e =
                GmcpEmitter(
                    outbound = outbound,
                    supportsPackage = { _, pkg -> pkg.startsWith("Server") },
                    imagesBaseUrl = "https://cdn.example.com/img/",
                    globalAssets = mapOf(
                        "shop_kiosk" to "global_assets/shop_kiosk.png",
                        "custom_zone_art" to "zone_art/hero.png",
                    ),
                )
            e.sendServerAssets(sid)
            val data = drainGmcp()
            assertEquals(1, data.size)
            assertEquals("Server.Assets", data[0].gmcpPackage)
            // Bundled assets (global_assets/, defaults/) resolve locally
            assertTrue(
                data[0].jsonData.contains("/images/global_assets/shop_kiosk.png"),
                "Expected bundled asset to resolve locally. got=${data[0].jsonData}",
            )
            // Non-bundled assets use the CDN base URL
            assertTrue(
                data[0].jsonData.contains("https://cdn.example.com/img/zone_art/hero.png"),
                "Expected non-bundled asset to use CDN. got=${data[0].jsonData}",
            )
        }

    @Test
    fun `sendServerAssets skips when no assets configured`() =
        runTest {
            val e =
                GmcpEmitter(
                    outbound = outbound,
                    supportsPackage = { _, _ -> true },
                    globalAssets = emptyMap(),
                )
            e.sendServerAssets(sid)
            assertTrue(drainGmcp().isEmpty())
        }

    // ── Server.Commands ──

    @Test
    fun `sendServerCommands emits filtered command manifest for non-staff`() =
        runTest {
            val entries = linkedMapOf(
                "look" to dev.ambon.config.CommandMetadata(usage = "look/l", category = "navigation"),
                "goto" to dev.ambon.config.CommandMetadata(usage = "goto <room>", category = "admin", staff = true),
                "kill" to dev.ambon.config.CommandMetadata(usage = "kill <mob>", category = "combat"),
            )
            val e = GmcpEmitter(
                outbound = outbound,
                supportsPackage = { _, _ -> true },
                commandEntries = entries,
            )
            e.sendServerCommands(sid, isStaff = false)
            val data = drainGmcp()
            assertEquals(1, data.size)
            assertEquals("Server.Commands", data[0].gmcpPackage)
            val json = data[0].jsonData
            assertTrue(json.contains("\"look\""), "should contain look")
            assertTrue(json.contains("\"kill\""), "should contain kill")
            assertTrue(!json.contains("\"goto\""), "should not contain staff command goto")
        }

    @Test
    fun `sendServerCommands includes staff commands for staff players`() =
        runTest {
            val entries = linkedMapOf(
                "look" to dev.ambon.config.CommandMetadata(usage = "look/l", category = "navigation"),
                "goto" to dev.ambon.config.CommandMetadata(usage = "goto <room>", category = "admin", staff = true),
            )
            val e = GmcpEmitter(
                outbound = outbound,
                supportsPackage = { _, _ -> true },
                commandEntries = entries,
            )
            e.sendServerCommands(sid, isStaff = true)
            val data = drainGmcp()
            assertEquals(1, data.size)
            val json = data[0].jsonData
            assertTrue(json.contains("\"look\""), "should contain look")
            assertTrue(json.contains("\"goto\""), "should contain staff command goto for staff player")
            assertTrue(json.contains("\"staff\":true"), "goto entry should have staff=true")
        }

    @Test
    fun `sendServerCommands skips when no entries configured`() =
        runTest {
            val e = GmcpEmitter(
                outbound = outbound,
                supportsPackage = { _, _ -> true },
                commandEntries = emptyMap(),
            )
            e.sendServerCommands(sid, isStaff = false)
            assertTrue(drainGmcp().isEmpty())
        }

    // ── Zone.Map ──

    private fun zoneRoom(
        id: String,
        exits: Map<Direction, RoomId> = emptyMap(),
        mapX: Int = 0,
        mapY: Int = 0,
    ) = Room(
        id = RoomId(id),
        title = "Room",
        description = "",
        exits = exits,
        mapX = mapX,
        mapY = mapY,
    )

    @Test
    fun `sendZoneMap emits rooms with horizontal exits only`() =
        runTest {
            val e = emitter("Zone.Map")
            val rooms = listOf(
                zoneRoom(
                    "z:a",
                    exits = mapOf(
                        Direction.NORTH to RoomId("z:b"),
                        Direction.UP to RoomId("z:c"),
                        Direction.EAST to RoomId("other:x"),
                    ),
                    mapX = 0,
                    mapY = 0,
                ),
                zoneRoom("z:b", mapX = 0, mapY = -1),
            )
            e.sendZoneMap(sid, "z", rooms)
            val data = drainGmcp()
            assertEquals(1, data.size)
            assertEquals("Zone.Map", data[0].gmcpPackage)
            val json = data[0].jsonData
            // North exit to same zone should be present
            assertTrue(json.contains("\"north\":\"z:b\""), "Expected north exit. got=$json")
            // Up exit should be filtered out
            assertTrue(!json.contains("\"up\""), "Up exit should be excluded. got=$json")
            // Cross-zone exit should be filtered out
            assertTrue(!json.contains("other:x"), "Cross-zone exit should be excluded. got=$json")
            // Coordinates should be present
            assertTrue(json.contains("\"x\":0"), "Expected x coordinate. got=$json")
            assertTrue(json.contains("\"y\":-1"), "Expected y=-1 coordinate. got=$json")
        }

    @Test
    fun `sendZoneMap skipped when Zone Map not supported`() =
        runTest {
            val e = emitter("Char")
            e.sendZoneMap(sid, "z", listOf(zoneRoom("z:a")))
            assertTrue(drainGmcp().isEmpty())
        }

    @Test
    fun `trackZoneChange returns true for first zone and zone changes`() {
        val e = emitter("Room")
        assertTrue(e.trackZoneChange(sid, "forest"), "First zone should return true")
        assertTrue(!e.trackZoneChange(sid, "forest"), "Same zone should return false")
        assertTrue(e.trackZoneChange(sid, "desert"), "New zone should return true")
    }

    @Test
    fun `forgetSession clears tracked zone`() {
        val e = emitter("Room")
        e.trackZoneChange(sid, "forest")
        e.forgetSession(sid)
        assertTrue(e.trackZoneChange(sid, "forest"), "After forget, same zone should return true again")
    }

    // ---------- Server.Who ----------

    @Test
    fun `sendServerWho emits structured player list`() = runTest {
        val e = emitter("Server.Who")
        val entries = listOf(
            WhoEntry(
                name = "Alice",
                level = 10,
                race = "HUMAN",
                playerClass = "WARRIOR",
                title = "Champion",
                guild = "KoR",
                groupSize = 3,
                idleSeconds = 120,
            ),
            WhoEntry(
                name = "Bob",
                level = 5,
                race = "ELF",
                playerClass = "MAGE",
                title = null,
                guild = null,
                groupSize = 0,
                idleSeconds = 0,
            ),
        )
        e.sendServerWho(sid, entries)

        val gmcp = drainGmcp()
        assertEquals(1, gmcp.size)
        assertEquals("Server.Who", gmcp[0].gmcpPackage)

        val json = gmcp[0].jsonData
        assertTrue(json.contains("\"Alice\""), "Should contain Alice's name")
        assertTrue(json.contains("\"Bob\""), "Should contain Bob's name")
        assertTrue(json.contains("\"Champion\""), "Should contain title")
        assertTrue(json.contains("\"KoR\""), "Should contain guild tag")
        assertTrue(json.contains("\"WARRIOR\""), "Should contain class as 'class' JSON key")
    }

    @Test
    fun `sendServerWho not sent without support`() = runTest {
        val e = emitter("Char.Vitals")
        e.sendServerWho(sid, listOf())
        assertTrue(drainGmcp().isEmpty())
    }

    // ---------- Zone.Instances ----------

    @Test
    fun `sendZoneInstances emits instance list`() = runTest {
        val e = emitter("Zone.Instances")
        e.sendZoneInstances(
            sid,
            zone = "forest",
            currentEngineId = "e1",
            instances = listOf(
                ZoneInstanceEntry(engineId = "e1", playerCount = 10, capacity = 200),
                ZoneInstanceEntry(engineId = "e2", playerCount = 25, capacity = 200),
            ),
        )

        val gmcp = drainGmcp()
        assertEquals(1, gmcp.size)
        assertEquals("Zone.Instances", gmcp[0].gmcpPackage)

        val json = gmcp[0].jsonData
        assertTrue(json.contains("\"forest\""), "Should contain zone name")
        assertTrue(json.contains("\"e1\""), "Should contain engine ID e1")
        assertTrue(json.contains("\"e2\""), "Should contain engine ID e2")
        assertTrue(json.contains("\"isCurrent\":true"), "e1 should be current")
    }

    @Test
    fun `clearZoneInstances emits empty list`() = runTest {
        val e = emitter("Zone.Instances")
        e.clearZoneInstances(sid)

        val gmcp = drainGmcp()
        assertEquals(1, gmcp.size)
        assertTrue(gmcp[0].jsonData.contains("\"instances\":[]"), "Should have empty instances")
    }

    @Test
    fun `sendPrestigeInfoForPlayer emits current prestige progression state`() = runTest {
        val e =
            GmcpEmitter(
                outbound = outbound,
                supportsPackage = { _, pkg -> pkg == "Prestige" },
                progression = progression,
                prestigeEnabled = { true },
                prestigeMaxRank = { 3 },
                prestigeAvailableXp = { player -> player.xpTotal - progression.totalXpForLevel(player.level) },
                prestigeNextCost = { rank -> 100L * (rank + 1) },
                prestigePerkPayloads = { currentRank, maxRank ->
                    (1..maxRank).map { rank ->
                        PrestigePerkPayload(
                            rank = rank,
                            type = "STAT_BONUS",
                            description = "Rank $rank perk",
                            earned = rank <= currentRank,
                        )
                    }
                },
            )
        val p =
            player(
                level = progression.maxLevel,
                xpTotal = progression.totalXpForLevel(progression.maxLevel) + 350L,
            ).apply {
                prestigeLevel = 1
            }

        e.sendPrestigeInfoForPlayer(sid, p)

        val gmcp = drainGmcp()
        assertEquals(1, gmcp.size)
        assertEquals("Prestige.Info", gmcp[0].gmcpPackage)
        assertTrue(gmcp[0].jsonData.contains("\"enabled\":true"), "Expected enabled prestige payload. got=${gmcp[0].jsonData}")
        assertTrue(gmcp[0].jsonData.contains("\"currentRank\":1"), "Expected current rank in payload. got=${gmcp[0].jsonData}")
        assertTrue(gmcp[0].jsonData.contains("\"maxRank\":3"), "Expected max rank in payload. got=${gmcp[0].jsonData}")
        assertTrue(gmcp[0].jsonData.contains("\"availableXp\":350"), "Expected available XP in payload. got=${gmcp[0].jsonData}")
        assertTrue(gmcp[0].jsonData.contains("\"nextRankCost\":200"), "Expected next rank cost in payload. got=${gmcp[0].jsonData}")
        assertTrue(gmcp[0].jsonData.contains("\"earned\":true"), "Expected earned perk state in payload. got=${gmcp[0].jsonData}")
        assertTrue(gmcp[0].jsonData.contains("\"earned\":false"), "Expected future perk state in payload. got=${gmcp[0].jsonData}")
    }

    @Test
    fun `emit sends normal-sized payload`() = runTest {
        val e = emitter("Char.Name")
        e.sendCharName(sid, player())
        val gmcp = drainGmcp()
        assertEquals(1, gmcp.size, "Normal-sized payload should be emitted")
    }

    // ── Dungeon.Info ──

    @Test
    fun `sendDungeonInfo emits completed=true when dungeon boss has been defeated`() =
        runTest {
            val e = emitter("Dungeon")
            e.sendDungeonInfo(
                sessionId = sid,
                active = true,
                instanceId = "abc12345",
                name = "Test Crypt",
                difficulty = "Normal",
                totalRooms = 5,
                completed = true,
                memberCount = 2,
            )
            val events = drainGmcp()
            assertEquals(1, events.size)
            val data = events[0]
            assertEquals("Dungeon.Info", data.gmcpPackage)
            assertTrue(data.jsonData.contains("\"active\":true"), "got=${data.jsonData}")
            assertTrue(data.jsonData.contains("\"completed\":true"), "got=${data.jsonData}")
            assertTrue(data.jsonData.contains("\"name\":\"Test Crypt\""), "got=${data.jsonData}")
            assertTrue(data.jsonData.contains("\"instanceId\":\"abc12345\""), "got=${data.jsonData}")
        }

    @Test
    fun `sendDungeonInfo emits active=false when leaving dungeon`() =
        runTest {
            val e = emitter("Dungeon")
            e.sendDungeonInfo(sessionId = sid, active = false)
            val events = drainGmcp()
            assertEquals(1, events.size)
            assertEquals("Dungeon.Info", events[0].gmcpPackage)
            assertTrue(events[0].jsonData.contains("\"active\":false"), "got=${events[0].jsonData}")
        }

    @Test
    fun `sendDungeonInfo does nothing when Dungeon package not supported`() =
        runTest {
            val e = emitter()
            e.sendDungeonInfo(sessionId = sid, active = true, completed = true)
            assertTrue(drainGmcp().isEmpty())
        }

    @Test
    fun `MAX_GMCP_PAYLOAD_BYTES is 64KB`() {
        assertEquals(65_536, GmcpEmitter.MAX_GMCP_PAYLOAD_BYTES)
    }
}
