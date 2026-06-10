package dev.ambon.engine

import dev.ambon.config.AkathavaeConfig
import dev.ambon.domain.StatMap
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.mob.MobRole
import dev.ambon.domain.mob.MobState
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.ItemSpawn
import dev.ambon.domain.world.MobDrop
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.World
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.test.CombatTestFixture
import dev.ambon.test.MutableClock
import dev.ambon.test.drainAll
import dev.ambon.test.loginOrFail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

/** Deterministic Random: returns queued rolls in order, then 0s. */
private class ScriptedRandom(
    vararg rolls: Int,
) : Random() {
    private val queue = ArrayDeque(rolls.toList())

    override fun nextInt(bound: Int): Int = queue.removeFirstOrNull() ?: 0
}

@OptIn(ExperimentalCoroutinesApi::class)
class AkathavaeIlluminateTest {
    private val roomA = RoomId("test:room")
    private val roomB = RoomId("test:hall")
    private val roomC = RoomId("test:attic")

    private fun testWorld(): World = World(
        rooms = mapOf(
            roomA to Room(roomA, "The Test Room", "A room.", exits = mapOf(Direction.NORTH to roomB)),
            roomB to Room(roomB, "The Hall", "A hall.", exits = mapOf(Direction.SOUTH to roomA, Direction.UP to roomC)),
            roomC to Room(roomC, "The Attic", "An attic.", exits = mapOf(Direction.DOWN to roomB)),
        ),
        startRoom = roomA,
    )

    private class Setup(
        val fixture: CombatTestFixture,
        val combat: CombatSystem,
        val system: AkathavaeSystem,
        val worldState: WorldStateRegistry,
        val clock: MutableClock,
    )

    private fun setup(
        rng: Random = ScriptedRandom(0),
        config: AkathavaeConfig = AkathavaeConfig(),
        onMobKilledByPlayer: suspend (SessionId, String) -> Unit = { _, _ -> },
    ): Setup {
        val clock = MutableClock(1_000_000L)
        val world = testWorld()
        val fixture = CombatTestFixture(roomId = roomA, clock = clock)
        val combat = fixture.buildCombat(rng = Random(1), onMobKilledByPlayer = onMobKilledByPlayer)
        val worldState = WorldStateRegistry(world)
        val system = AkathavaeSystem(
            players = fixture.players,
            items = fixture.items,
            world = world,
            outbound = fixture.outbound,
            combat = combat,
            worldState = worldState,
            clock = clock,
            rng = rng,
            config = config,
        )
        return Setup(fixture, combat, system, worldState, clock)
    }

    private fun wisp(
        id: String = "w1",
        templateKey: String = "test:wisp",
        xpReward: Long = 100L,
        drops: List<MobDrop> = emptyList(),
        role: MobRole = MobRole.COMBAT,
    ) = MobState(
        id = MobId("test:$id"),
        name = "a wandering wisp",
        roomId = roomA,
        hp = 10,
        maxHp = 10,
        xpReward = xpReward,
        templateKey = templateKey,
        drops = drops,
        role = role,
    )

    private suspend fun loginAkathavae(s: Setup, sid: SessionId, name: String): PlayerState {
        s.fixture.players.loginOrFail(sid, name)
        val me = s.fixture.players.get(sid)!!
        me.isAkathavae = true
        s.fixture.outbound.drainAll()
        return me
    }

    // ── Success math ─────────────────────────────────────────────────────

    @Test
    fun `success chance scales with stats and level gap`() {
        val s = setup()
        // Base 70, stats at base point, no gap.
        assertEquals(70, s.system.illuminationSuccessPct(10, 10, playerLevel = 5, mobLevel = 5))
        // +2% per success-stat point above 10.
        assertEquals(90, s.system.illuminationSuccessPct(20, 10, playerLevel = 5, mobLevel = 5))
        // -8% per level the subject is above the player.
        assertEquals(46, s.system.illuminationSuccessPct(10, 10, playerLevel = 5, mobLevel = 8))
        // Gap relief stat softens the penalty: 8 - 10*0.5 = 3%/level.
        assertEquals(61, s.system.illuminationSuccessPct(10, 20, playerLevel = 5, mobLevel = 8))
        // Clamped to the configured floor.
        assertEquals(5, s.system.illuminationSuccessPct(10, 10, playerLevel = 1, mobLevel = 50))
    }

    // ── Illumination outcomes ────────────────────────────────────────────

    @Test
    fun `successful illumination records the mob and grants kill-equivalent XP`() = runTest {
        val s = setup(rng = ScriptedRandom(0))
        val sid = SessionId(1L)
        val me = loginAkathavae(s, sid, "Thalen")
        s.fixture.mobs.upsert(wisp(xpReward = 100L))

        s.system.illuminate(sid, "wisp")

        assertTrue(me.arcanum.mobs.containsKey("test:wisp"), "journal should hold the wisp")
        assertNull(s.fixture.mobs.get(MobId("test:w1")), "subject should be removed like a kill")
        assertEquals(100L, me.xpTotal, "first illumination pays the full kill XP")
        val texts = s.fixture.outbound.drainAll().filterIsInstance<OutboundEvent.SendText>().map { it.text }
        assertTrue(texts.any { it.contains("illuminate") }, "got=$texts")
    }

    @Test
    fun `successful illumination captures drops into inventory and records them`() = runTest {
        val s = setup(rng = ScriptedRandom(0, 0))
        val sid = SessionId(1L)
        val me = loginAkathavae(s, sid, "Thalen")
        s.fixture.items.loadSpawns(
            listOf(ItemSpawn(instance = ItemInstance(ItemId("test:dust"), Item(keyword = "dust", displayName = "glittering dust")))),
        )
        s.fixture.mobs.upsert(wisp(drops = listOf(MobDrop(ItemId("test:dust"), 1.0))))

        s.system.illuminate(sid, "wisp")

        assertTrue(
            s.fixture.items.inventory(sid).any { it.id.value == "test:dust" },
            "drop should land in inventory, not the room",
        )
        assertTrue(me.arcanum.items.containsKey("test:dust"), "captured drop should be recorded")
    }

    @Test
    fun `illumination success fires quest kill credit`() = runTest {
        val credited = mutableListOf<String>()
        val s = setup(rng = ScriptedRandom(0), onMobKilledByPlayer = { _, templateKey -> credited += templateKey })
        val sid = SessionId(1L)
        loginAkathavae(s, sid, "Thalen")
        s.fixture.mobs.upsert(wisp())

        s.system.illuminate(sid, "wisp")

        assertEquals(listOf("test:wisp"), credited, "a recorded creature counts as a slain one")
    }

    @Test
    fun `failed illumination angers the subject and locks retries`() = runTest {
        val s = setup(rng = ScriptedRandom(99))
        val sid = SessionId(1L)
        val me = loginAkathavae(s, sid, "Thalen")
        val mob = wisp()
        s.fixture.mobs.upsert(mob)

        s.system.illuminate(sid, "wisp")

        assertTrue(s.combat.isInCombat(sid), "failed subject should attack the Akathavae")
        assertFalse(me.arcanum.mobs.containsKey("test:wisp"))

        // Flee the fight, then immediately retry — the subject is still wary.
        s.combat.flee(sid)
        s.fixture.outbound.drainAll()
        s.system.illuminate(sid, "wisp")
        val errors = s.fixture.outbound.drainAll().filterIsInstance<OutboundEvent.SendError>().map { it.text }
        assertTrue(errors.any { it.contains("wary") }, "got=$errors")

        // After the cooldown the retry is allowed (and succeeds: queue exhausted → roll 0).
        s.clock.advance(AkathavaeConfig().failRetryCooldownMs + 1)
        s.system.illuminate(sid, "wisp")
        assertTrue(me.arcanum.mobs.containsKey("test:wisp"))
    }

    @Test
    fun `high escape stat talks the player out of a failed illumination`() = runTest {
        val s = setup(rng = ScriptedRandom(99, 0))
        val sid = SessionId(1L)
        val me = loginAkathavae(s, sid, "Thalen")
        me.stats = StatMap.of("STR" to 10, "DEX" to 10, "CON" to 10, "INT" to 10, "WIS" to 10, "CHA" to 20)
        s.fixture.mobs.upsert(wisp())

        s.system.illuminate(sid, "wisp")

        assertFalse(s.combat.isInCombat(sid), "a silver tongue should defuse the failure")
        val texts = s.fixture.outbound.drainAll().filterIsInstance<OutboundEvent.SendText>().map { it.text }
        assertTrue(texts.any { it.contains("soothing words") }, "got=$texts")
    }

    @Test
    fun `repeat illumination pays reduced XP and honors the cooldown`() = runTest {
        val s = setup(rng = ScriptedRandom(0, 0, 0))
        val sid = SessionId(1L)
        val me = loginAkathavae(s, sid, "Thalen")

        s.fixture.mobs.upsert(wisp(id = "w1", xpReward = 100L))
        s.system.illuminate(sid, "wisp")
        assertEquals(100L, me.xpTotal)

        // Second of the same template inside the repeat cooldown: recorded, no XP.
        s.clock.advance(2_000)
        s.fixture.mobs.upsert(wisp(id = "w2", xpReward = 100L))
        s.system.illuminate(sid, "wisp")
        assertEquals(100L, me.xpTotal, "repeat inside cooldown yields no XP")
        assertEquals(2, me.arcanum.mobs["test:wisp"]!!.timesRecorded)

        // Past the cooldown: 20% of the kill XP.
        s.clock.advance(AkathavaeConfig().repeatXpCooldownMs + 1)
        s.fixture.mobs.upsert(wisp(id = "w3", xpReward = 100L))
        s.system.illuminate(sid, "wisp")
        assertEquals(120L, me.xpTotal, "repeat after cooldown pays the reduced fraction")
    }

    @Test
    fun `non-combat NPCs are observed not removed`() = runTest {
        val s = setup()
        val sid = SessionId(1L)
        val me = loginAkathavae(s, sid, "Thalen")
        s.fixture.mobs.upsert(wisp(role = MobRole.VENDOR, templateKey = "test:merchant"))

        s.system.illuminate(sid, "wisp")

        assertTrue(me.arcanum.mobs.containsKey("test:merchant"))
        assertNotNull(s.fixture.mobs.get(MobId("test:w1")), "observed NPCs must never be removed")
        assertEquals(AkathavaeConfig().observeNpcXp, me.xpTotal)
    }

    @Test
    fun `unpledged players cannot illuminate`() = runTest {
        val s = setup()
        val sid = SessionId(1L)
        s.fixture.players.loginOrFail(sid, "Bruiser")
        s.fixture.mobs.upsert(wisp())
        s.fixture.outbound.drainAll()

        s.system.illuminate(sid, "wisp")

        val errors = s.fixture.outbound.drainAll().filterIsInstance<OutboundEvent.SendError>().map { it.text }
        assertTrue(errors.any { it.contains("pledge") }, "got=$errors")
        assertNotNull(s.fixture.mobs.get(MobId("test:w1")))
    }

    // ── Passive discovery ────────────────────────────────────────────────

    @Test
    fun `visiting a new room records it and awards XP once`() = runTest {
        val s = setup()
        val sid = SessionId(1L)
        val me = loginAkathavae(s, sid, "Thalen")

        s.system.onRoomVisited(sid)
        assertTrue(me.arcanum.rooms.containsKey(roomA.value))
        assertEquals(AkathavaeConfig().roomDiscoveryXp, me.xpTotal)

        // Re-visiting records nothing new and pays nothing.
        s.clock.advance(10_000)
        s.system.onRoomVisited(sid)
        assertEquals(AkathavaeConfig().roomDiscoveryXp, me.xpTotal)
        assertEquals(1, me.arcanum.rooms.size)
    }

    @Test
    fun `discovery XP is throttled against speedrunning but entries still record`() = runTest {
        val s = setup()
        val sid = SessionId(1L)
        val me = loginAkathavae(s, sid, "Thalen")
        val perRoom = AkathavaeConfig().roomDiscoveryXp

        s.system.onRoomVisited(sid)
        me.roomId = roomB
        s.system.onRoomVisited(sid) // inside the throttle window — recorded, no XP

        assertEquals(perRoom, me.xpTotal, "second discovery inside throttle pays nothing")
        assertEquals(2, me.arcanum.rooms.size, "both rooms are still recorded")

        s.clock.advance(AkathavaeConfig().discoveryXpThrottleMs + 1)
        me.roomId = roomC
        s.system.onRoomVisited(sid)
        assertEquals(perRoom * 2, me.xpTotal, "throttle expiry restores XP flow")
    }

    @Test
    fun `room discovery ignores the unpledged`() = runTest {
        val s = setup()
        val sid = SessionId(1L)
        s.fixture.players.loginOrFail(sid, "Bruiser")

        s.system.onRoomVisited(sid)

        assertTrue(s.fixture.players.get(sid)!!.arcanum.rooms.isEmpty())
    }

    // ── World firsts ─────────────────────────────────────────────────────

    @Test
    fun `the first illuminator is stamped permanently and only once`() = runTest {
        val s = setup(rng = ScriptedRandom(0, 0))
        val alice = SessionId(1L)
        val bob = SessionId(2L)
        loginAkathavae(s, alice, "Alice")
        loginAkathavae(s, bob, "Bob")

        s.fixture.mobs.upsert(wisp(id = "w1"))
        s.system.illuminate(alice, "wisp")
        val aliceOut = s.fixture.outbound.drainAll().filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
        assertTrue(aliceOut.any { it.contains("First illuminated by Alice") }, "got=$aliceOut")

        s.clock.advance(10_000)
        s.fixture.mobs.upsert(wisp(id = "w2"))
        s.system.illuminate(bob, "wisp")

        val credit = s.worldState.getArcanumFirst("mob:test:wisp")
        assertNotNull(credit)
        assertEquals("Alice", credit!!.first, "world-firsts are immutable once written")
    }

    @Test
    fun `world firsts survive a snapshot round-trip`() {
        val s = setup()
        s.worldState.recordArcanumFirst("mob:test:wisp", "Thalen", 1_953L)

        val snapshot = s.worldState.buildSnapshot()
        val restored = WorldStateRegistry(testWorld())
        restored.applySnapshot(snapshot) { null }

        assertEquals("Thalen" to 1_953L, restored.getArcanumFirst("mob:test:wisp"))
    }
}
