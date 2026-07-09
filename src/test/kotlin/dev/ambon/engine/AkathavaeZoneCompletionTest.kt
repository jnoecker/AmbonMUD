package dev.ambon.engine

import dev.ambon.config.AkathavaeConfig
import dev.ambon.domain.arcanum.ArcanumJournal
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobRole
import dev.ambon.domain.mob.MobState
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.MobTemplateDef
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.World
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.persistence.jsonMapper
import dev.ambon.test.CombatTestFixture
import dev.ambon.test.MutableClock
import dev.ambon.test.drainAll
import dev.ambon.test.loginOrFail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

/** Deterministic Random: always rolls 0, so every illumination succeeds. */
private class AlwaysSucceedRandom : Random() {
    override fun nextInt(bound: Int): Int = 0
}

/**
 * Zone-difficulty-scaled room discovery XP and the one-time zone-completion
 * bundle (XP by zone size + gold), issue #1395.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AkathavaeZoneCompletionTest {
    private val config = AkathavaeConfig()

    // "lair": one room, one level-10 combat template, one observable NPC template.
    private val lairDen = RoomId("lair:den")

    // "field": two rooms, no mob templates — completes on rooms alone, pays flat room XP.
    private val fieldMeadow = RoomId("field:meadow")
    private val fieldBrook = RoomId("field:brook")

    private fun testWorld(lairTemplates: Map<MobId, MobTemplateDef> = lairWispOnly()): World = World(
        rooms = mapOf(
            lairDen to Room(lairDen, "The Den", "A den.", exits = mapOf(Direction.EAST to fieldMeadow)),
            fieldMeadow to Room(fieldMeadow, "The Meadow", "A meadow.", exits = mapOf(Direction.WEST to lairDen)),
            fieldBrook to Room(fieldBrook, "The Brook", "A brook.", exits = mapOf(Direction.SOUTH to fieldMeadow)),
        ),
        startRoom = lairDen,
        mobTemplates = lairTemplates,
    )

    private fun lairWispOnly(): Map<MobId, MobTemplateDef> = mapOf(
        MobId("lair:wisp") to MobTemplateDef(id = MobId("lair:wisp"), name = "a wandering wisp", level = 10),
    )

    private class Setup(
        val fixture: CombatTestFixture,
        val system: AkathavaeSystem,
        val clock: MutableClock,
    )

    private fun setup(world: World = testWorld()): Setup {
        val clock = MutableClock(1_000_000L)
        val fixture = CombatTestFixture(roomId = lairDen, clock = clock)
        val combat = fixture.buildCombat(rng = Random(1))
        val system = AkathavaeSystem(
            players = fixture.players,
            items = fixture.items,
            world = world,
            outbound = fixture.outbound,
            combat = combat,
            worldState = WorldStateRegistry(world),
            clock = clock,
            rng = AlwaysSucceedRandom(),
            config = config,
        )
        return Setup(fixture, system, clock)
    }

    private suspend fun loginAkathavae(s: Setup, sid: SessionId, name: String): PlayerState {
        s.fixture.players.loginOrFail(sid, name)
        val me = s.fixture.players.get(sid)!!
        me.isAkathavae = true
        s.fixture.outbound.drainAll()
        return me
    }

    private fun wisp(
        id: String = "w1",
        templateKey: String = "lair:wisp",
        role: MobRole = MobRole.COMBAT,
    ) = MobState(
        id = MobId("lair:$id"),
        name = "a wandering wisp",
        roomId = lairDen,
        hp = 10,
        maxHp = 10,
        xpReward = 100L,
        templateKey = templateKey,
        drops = emptyList(),
        role = role,
    )

    // ── Zone-difficulty-scaled room XP ───────────────────────────────────

    @Test
    fun `room discovery XP scales with the zone's average mob level`() = runTest {
        val s = setup()
        val sid = SessionId(1L)
        val me = loginAkathavae(s, sid, "Thalen")

        // "lair" averages level 10 → 15 + 10*5 = 65 XP for its room.
        s.system.onRoomVisited(sid)
        val scaled = config.roomDiscoveryXp + 10 * config.roomDiscoveryXpPerZoneLevel
        assertEquals(scaled, me.xpTotal, "high-level zone rooms pay level-scaled XP")

        // "field" has no mob templates → the flat base only.
        s.clock.advance(config.discoveryXpThrottleMs + 1)
        me.roomId = fieldMeadow
        s.system.onRoomVisited(sid)
        assertEquals(scaled + config.roomDiscoveryXp, me.xpTotal, "mobless zones fall back to the flat base")
    }

    // ── Zone completion bundle ───────────────────────────────────────────

    @Test
    fun `completing a zone's last room pays XP and gold once and bypasses the throttle`() = runTest {
        val s = setup()
        val sid = SessionId(1L)
        val bob = SessionId(2L)
        val me = loginAkathavae(s, sid, "Thalen")
        val other = loginAkathavae(s, bob, "Bob")
        other.roomId = fieldBrook
        val goldBefore = me.gold
        s.fixture.mobs.upsert(wisp())

        // Record the zone's only mob (arms the discovery throttle) …
        s.system.illuminate(sid, "wisp")
        assertEquals(100L, me.xpTotal)
        s.fixture.outbound.drainAll()

        // … then its only room, immediately, still inside the throttle window:
        // room XP is swallowed, but the completion bundle must not be.
        s.system.onRoomVisited(sid)

        assertTrue("lair" in me.arcanum.completedZones)
        val completionXp = 1 * config.zoneCompletionXpPerRoom
        assertEquals(100L + completionXp, me.xpTotal, "completion XP bypasses the anti-speedrun throttle")
        assertEquals(goldBefore + config.zoneCompletionGold, me.gold, "completion pays the gold faucet")

        val myInfos = s.fixture.outbound.drainAll().filterIsInstance<OutboundEvent.SendInfo>()
        assertTrue(
            myInfos.any { it.sessionId == sid && it.text.contains("record of lair is complete") },
            "got=$myInfos",
        )
        assertTrue(
            myInfos.any { it.sessionId == bob && it.text.contains("completed the Arcanum record of lair") },
            "everyone online hears about a zone completion, got=$myInfos",
        )
    }

    @Test
    fun `completion never re-fires on repeat visits or illuminations`() = runTest {
        val s = setup()
        val sid = SessionId(1L)
        val me = loginAkathavae(s, sid, "Thalen")
        s.fixture.mobs.upsert(wisp())
        s.system.illuminate(sid, "wisp")
        s.system.onRoomVisited(sid)
        val goldAfter = me.gold
        val xpAfter = me.xpTotal

        // Past every cooldown: a repeat illumination pays repeat XP but no second bundle.
        s.clock.advance(config.repeatXpCooldownMs + 1)
        s.fixture.mobs.upsert(wisp(id = "w2"))
        s.system.illuminate(sid, "wisp")
        s.system.onRoomVisited(sid)

        assertEquals(goldAfter, me.gold, "the bundle's gold pays exactly once")
        val repeatXp = (100L * config.repeatXpFraction).toLong()
        assertEquals(xpAfter + repeatXp, me.xpTotal, "only the repeat-illumination XP is paid")
        assertEquals(setOf("lair"), me.arcanum.completedZones)
    }

    @Test
    fun `completing a zone's last creature via illumination fires the bundle`() = runTest {
        val s = setup()
        val sid = SessionId(1L)
        val me = loginAkathavae(s, sid, "Thalen")
        val goldBefore = me.gold
        s.fixture.mobs.upsert(wisp())

        s.system.onRoomVisited(sid)
        s.clock.advance(config.discoveryXpThrottleMs + 1)
        s.system.illuminate(sid, "wisp")

        assertTrue("lair" in me.arcanum.completedZones)
        assertEquals(goldBefore + config.zoneCompletionGold, me.gold)
    }

    @Test
    fun `completing a zone's last creature via observation fires the bundle`() = runTest {
        val world = testWorld(
            lairTemplates = mapOf(
                MobId("lair:merchant") to MobTemplateDef(
                    id = MobId("lair:merchant"),
                    name = "a lair merchant",
                    level = 10,
                    role = MobRole.VENDOR,
                ),
            ),
        )
        val s = setup(world)
        val sid = SessionId(1L)
        val me = loginAkathavae(s, sid, "Thalen")
        val goldBefore = me.gold
        s.fixture.mobs.upsert(wisp(templateKey = "lair:merchant", role = MobRole.VENDOR))

        s.system.onRoomVisited(sid)
        s.clock.advance(config.discoveryXpThrottleMs + 1)
        s.system.illuminate(sid, "wisp")

        assertTrue("lair" in me.arcanum.completedZones)
        assertEquals(goldBefore + config.zoneCompletionGold, me.gold)
    }

    @Test
    fun `a zone with nothing to record never auto-completes`() = runTest {
        val s = setup()
        val sid = SessionId(1L)
        val me = loginAkathavae(s, sid, "Thalen")
        val goldBefore = me.gold
        // Template key from a zone with no rooms and no templates in this world.
        s.fixture.mobs.upsert(wisp(templateKey = "ghost:wisp"))

        s.system.illuminate(sid, "wisp")

        assertTrue(me.arcanum.mobs.containsKey("ghost:wisp"), "the record itself still lands")
        assertTrue(me.arcanum.completedZones.isEmpty(), "0/0 zones must not auto-complete")
        assertEquals(goldBefore, me.gold)
    }

    // ── Persistence ──────────────────────────────────────────────────────

    @Test
    fun `completed zones survive a serialization round-trip`() {
        val journal = ArcanumJournal(completedZones = mutableSetOf("lair", "field"))
        val restored = jsonMapper.readValue(jsonMapper.writeValueAsString(journal), ArcanumJournal::class.java)
        assertEquals(setOf("lair", "field"), restored.completedZones)
    }

    @Test
    fun `legacy arcanum blobs without completedZones still deserialize`() {
        // A verbatim pre-#1395 blob: no completedZones field.
        val legacy =
            """
            {"mobs":{"academy:rat":{"firstRecordedAtMs":123,"timesRecorded":2,"lastXpAtMs":456,"source":"illuminated"}},
             "items":{"academy:sword":{"firstRecordedAtMs":123,"timesRecorded":1,"lastXpAtMs":123,"source":"purchased"}},
             "rooms":{"academy:gate":{"firstRecordedAtMs":123,"timesRecorded":1,"lastXpAtMs":123,"source":"visited"}}}
            """.trimIndent()
        val journal = jsonMapper.readValue(legacy, ArcanumJournal::class.java)
        assertTrue(journal.completedZones.isEmpty(), "missing field defaults to empty")
        assertEquals(2, journal.mobs["academy:rat"]!!.timesRecorded)
        assertEquals(1, journal.rooms.size)
        assertEquals(1, journal.items.size)
    }
}
