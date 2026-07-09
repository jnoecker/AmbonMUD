package dev.ambon.engine

import dev.ambon.config.AkathavaeConfig
import dev.ambon.config.DailyQuestDefinition
import dev.ambon.config.DailyQuestsConfig
import dev.ambon.domain.StatMap
import dev.ambon.domain.achievement.AchievementCriterion
import dev.ambon.domain.achievement.AchievementDef
import dev.ambon.domain.arcanum.ArcanumEntry
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.items.ItemSlot
import dev.ambon.domain.mob.MobRole
import dev.ambon.domain.mob.MobState
import dev.ambon.domain.quest.QuestDef
import dev.ambon.domain.quest.QuestObjectiveDef
import dev.ambon.domain.quest.QuestRewards
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.ItemSpawn
import dev.ambon.domain.world.MobDrop
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.World
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.test.CombatTestFixture
import dev.ambon.test.INSTANT_SKETCH_AKATHAVAE
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

    // A fourth, never-visited room keeps the zone below 100% so these tests
    // never trip the zone-completion bundle (covered by AkathavaeZoneCompletionTest).
    private val roomD = RoomId("test:cellar")

    private fun testWorld(): World = World(
        rooms = mapOf(
            roomA to Room(roomA, "The Test Room", "A room.", exits = mapOf(Direction.NORTH to roomB)),
            roomB to Room(roomB, "The Hall", "A hall.", exits = mapOf(Direction.SOUTH to roomA, Direction.UP to roomC)),
            roomC to Room(roomC, "The Attic", "An attic.", exits = mapOf(Direction.DOWN to roomB)),
            roomD to Room(roomD, "The Cellar", "A cellar.", exits = mapOf(Direction.UP to roomA)),
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
        config: AkathavaeConfig = INSTANT_SKETCH_AKATHAVAE,
        onMobKilledByPlayer: suspend (SessionId, String) -> Unit = { _, _ -> },
        refreshRoomMobInfo: (suspend (SessionId) -> Unit)? = null,
        achievements: List<AchievementDef> = emptyList(),
        onIlluminated: (suspend (SessionId) -> Unit)? = null,
    ): Setup {
        val clock = MutableClock(1_000_000L)
        val world = testWorld()
        val fixture = CombatTestFixture(roomId = roomA, clock = clock)
        val combat = fixture.buildCombat(rng = Random(1), onMobKilledByPlayer = onMobKilledByPlayer)
        val worldState = WorldStateRegistry(world)
        val achievementRegistry = AchievementRegistry()
        achievements.forEach { achievementRegistry.register(it) }
        val achievementSystem = AchievementSystem(
            registry = achievementRegistry,
            players = fixture.players,
            outbound = fixture.outbound,
        )
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
            refreshRoomMobInfo = refreshRoomMobInfo,
            onArcanumRecorded = { sid -> achievementSystem.onArcanumRecorded(sid) },
            onIlluminated = onIlluminated,
        )
        return Setup(fixture, combat, system, worldState, clock)
    }

    private fun wisp(
        id: String = "w1",
        templateKey: String = "test:wisp",
        xpReward: Long = 100L,
        drops: List<MobDrop> = emptyList(),
        role: MobRole = MobRole.COMBAT,
        name: String = "a wandering wisp",
        level: Int = 1,
    ) = MobState(
        id = MobId("test:$id"),
        name = name,
        roomId = roomA,
        hp = 10,
        maxHp = 10,
        xpReward = xpReward,
        templateKey = templateKey,
        drops = drops,
        role = role,
        level = level,
    )

    private suspend fun loginAkathavae(s: Setup, sid: SessionId, name: String): PlayerState {
        s.fixture.players.loginOrFail(sid, name)
        val me = s.fixture.players.get(sid)!!
        me.isAkathavae = true
        s.fixture.outbound.drainAll()
        return me
    }

    // ── Zone completion ──────────────────────────────────────────────────

    @Test
    fun `zone completion counts item templates and recorded items for the zone`() = runTest {
        val s = setup()
        s.fixture.items.loadSpawns(
            listOf(
                ItemSpawn(instance = ItemInstance(ItemId("test:hood"), Item(keyword = "hood", displayName = "a leather hood"))),
                ItemSpawn(instance = ItemInstance(ItemId("test:ring"), Item(keyword = "ring", displayName = "a copper ring"))),
                // Another zone's template must not count toward "test".
                ItemSpawn(instance = ItemInstance(ItemId("other:gem"), Item(keyword = "gem", displayName = "a dull gem"))),
            ),
        )
        val me = loginAkathavae(s, SessionId(1L), "Thalen")
        me.arcanum.items["test:hood"] = ArcanumEntry(firstRecordedAtMs = 1L)
        me.arcanum.items["other:gem"] = ArcanumEntry(firstRecordedAtMs = 1L)

        val c = s.system.zoneCompletion(me, "test")

        assertEquals(1, c.itemsRecorded, "only the test-zone recording counts")
        assertEquals(2, c.itemsTotal, "only test-zone templates count toward the total")
    }

    @Test
    fun `recorded zones include zones known only through items`() = runTest {
        val s = setup()
        val me = loginAkathavae(s, SessionId(1L), "Thalen")
        me.arcanum.rooms["test:room"] = ArcanumEntry(firstRecordedAtMs = 1L)
        me.arcanum.items["bazaar:lamp"] = ArcanumEntry(firstRecordedAtMs = 1L)

        assertEquals(listOf("bazaar", "test"), s.system.recordedZones(me))
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

    @Test
    fun `illuminationOddsFor resolves the player's current effective stats`() = runTest {
        val s = setup()
        val sid = SessionId(1L)
        val me = loginAkathavae(s, sid, "Thalen")
        me.level = 5

        // Base stats, equal level: the configured base chance.
        assertEquals(70, s.system.illuminationOddsFor(me, wisp(level = 5)))

        // +2% per success-stat (INT) point above base.
        me.stats = me.stats.with("INT", 20)
        assertEquals(90, s.system.illuminationOddsFor(me, wisp(level = 5)))

        // Subject above the player: the level-gap penalty applies (70 - 3*8).
        me.stats = me.stats.with("INT", 10)
        assertEquals(46, s.system.illuminationOddsFor(me, wisp(level = 8)))

        // The gap-relief stat (STR) softens the penalty (70 - 3*(8 - 10*0.5)).
        me.stats = me.stats.with("STR", 20)
        assertEquals(61, s.system.illuminationOddsFor(me, wisp(level = 8)))
    }

    @Test
    fun `illuminationOddsFor includes equipment bonuses`() = runTest {
        val s = setup()
        val sid = SessionId(1L)
        val me = loginAkathavae(s, sid, "Thalen")
        me.level = 5
        s.fixture.items.setEquippedItem(
            sid,
            ItemSlot.HEAD,
            ItemInstance(
                id = ItemId("test:circlet"),
                item = Item(
                    keyword = "circlet",
                    displayName = "a scholar's circlet",
                    slot = ItemSlot.HEAD,
                    stats = StatMap.of("INT" to 5),
                ),
            ),
        )

        // Effective INT 15 -> 70 + 5*2 = 80.
        assertEquals(80, s.system.illuminationOddsFor(me, wisp(level = 5)))
    }

    // ── Illumination outcomes ────────────────────────────────────────────

    @Test
    fun `successful illumination records the mob without removing it`() = runTest {
        val s = setup(rng = ScriptedRandom(0))
        val sid = SessionId(1L)
        val me = loginAkathavae(s, sid, "Thalen")
        s.fixture.mobs.upsert(wisp(xpReward = 100L))

        s.system.illuminate(sid, "wisp")

        assertTrue(me.arcanum.mobs.containsKey("test:wisp"), "journal should hold the wisp")
        assertNotNull(s.fixture.mobs.get(MobId("test:w1")), "illumination must never remove the subject")
        assertEquals(100L, me.xpTotal, "first illumination pays the full discovery XP")
        val texts = s.fixture.outbound.drainAll().filterIsInstance<OutboundEvent.SendText>().map { it.text }
        assertTrue(texts.any { it.contains("illuminate") }, "got=$texts")
    }

    @Test
    fun `successful illumination catalogues the subject's drops without taking them`() = runTest {
        val s = setup(rng = ScriptedRandom(0, 0))
        val sid = SessionId(1L)
        val me = loginAkathavae(s, sid, "Thalen")
        s.fixture.items.loadSpawns(
            listOf(ItemSpawn(instance = ItemInstance(ItemId("test:dust"), Item(keyword = "dust", displayName = "glittering dust")))),
        )
        s.fixture.mobs.upsert(wisp(drops = listOf(MobDrop(ItemId("test:dust"), 1.0))))

        s.system.illuminate(sid, "wisp")

        assertTrue(me.arcanum.items.containsKey("test:dust"), "the creature's drop should be recorded as a page")
        assertTrue(s.fixture.items.inventory(sid).isEmpty(), "nothing is taken from a living subject")
        assertNotNull(s.fixture.mobs.get(MobId("test:w1")), "the subject is unharmed and remains")
    }

    @Test
    fun `first illumination pays item-discovery XP for every drop in the same action`() = runTest {
        val s = setup(rng = ScriptedRandom(0, 0))
        val sid = SessionId(1L)
        val me = loginAkathavae(s, sid, "Thalen")
        s.fixture.items.loadSpawns(
            listOf(
                ItemSpawn(instance = ItemInstance(ItemId("test:dust"), Item(keyword = "dust", displayName = "glittering dust"))),
                ItemSpawn(instance = ItemInstance(ItemId("test:mote"), Item(keyword = "mote", displayName = "a pale mote"))),
            ),
        )
        val drops = listOf(MobDrop(ItemId("test:dust"), 1.0), MobDrop(ItemId("test:mote"), 0.5))
        s.fixture.mobs.upsert(wisp(id = "w1", xpReward = 100L, drops = drops))

        s.system.illuminate(sid, "wisp")

        val perItem = AkathavaeConfig().itemDiscoveryXp
        assertEquals(
            100L + 2 * perItem,
            me.xpTotal,
            "first illumination pays the mob XP plus item XP for each drop, same action",
        )
        assertTrue(me.arcanum.items.containsKey("test:dust"))
        assertTrue(me.arcanum.items.containsKey("test:mote"))

        // Repeat illumination: items already recorded → no further item XP, and the
        // mob's repeat cooldown suppresses its XP too.
        s.clock.advance(AkathavaeConfig().discoveryXpThrottleMs + 1)
        s.fixture.mobs.upsert(wisp(id = "w2", xpReward = 100L, drops = drops))
        s.system.illuminate(sid, "wisp")
        assertEquals(100L + 2 * perItem, me.xpTotal, "repeat illumination pays no item XP for known pages")
    }

    @Test
    fun `drop-catalogue bypass does not defeat the cross-action discovery throttle`() = runTest {
        val s = setup(rng = ScriptedRandom(0))
        val sid = SessionId(1L)
        val me = loginAkathavae(s, sid, "Thalen")
        s.fixture.items.loadSpawns(
            listOf(ItemSpawn(instance = ItemInstance(ItemId("test:dust"), Item(keyword = "dust", displayName = "glittering dust")))),
        )
        s.fixture.mobs.upsert(wisp(xpReward = 100L, drops = listOf(MobDrop(ItemId("test:dust"), 1.0))))

        s.system.illuminate(sid, "wisp")
        val afterIllumination = 100L + AkathavaeConfig().itemDiscoveryXp
        assertEquals(afterIllumination, me.xpTotal)

        // A separate discovery inside the throttle window: recorded, but no XP.
        me.roomId = roomB
        s.system.onRoomVisited(sid)
        assertEquals(afterIllumination, me.xpTotal, "the intra-action bypass must not open a farming window")
        assertTrue(me.arcanum.rooms.containsKey(roomB.value), "the room is still recorded")

        // Past the throttle, XP flows again.
        s.clock.advance(AkathavaeConfig().discoveryXpThrottleMs + 1)
        me.roomId = roomC
        s.system.onRoomVisited(sid)
        assertEquals(afterIllumination + AkathavaeConfig().roomDiscoveryXp, me.xpTotal)
    }

    @Test
    fun `illumination fires quest kill credit so the pledged complete the same quests`() = runTest {
        val credited = mutableListOf<String>()
        val s = setup(rng = ScriptedRandom(0), onMobKilledByPlayer = { _, templateKey -> credited += templateKey })
        val sid = SessionId(1L)
        loginAkathavae(s, sid, "Thalen")
        s.fixture.mobs.upsert(wisp())

        s.system.illuminate(sid, "wisp")

        assertEquals(listOf("test:wisp"), credited, "a recorded creature counts as a defeated one")
    }

    @Test
    fun `re-illuminating the same living instance does not double-credit the kill`() = runTest {
        val credited = mutableListOf<String>()
        val s = setup(rng = ScriptedRandom(0, 0), onMobKilledByPlayer = { _, templateKey -> credited += templateKey })
        val sid = SessionId(1L)
        loginAkathavae(s, sid, "Thalen")
        s.fixture.mobs.upsert(wisp())

        s.system.illuminate(sid, "wisp")
        // The subject is unharmed and stays — illuminate it again immediately.
        s.system.illuminate(sid, "wisp")

        assertEquals(listOf("test:wisp"), credited, "a persistent subject grants kill credit only once")
    }

    // ── Illumination commissions (daily-quest hook) ──────────────────────

    @Test
    fun `illumination fires the commission hook once per living instance`() = runTest {
        var credits = 0
        val s = setup(rng = ScriptedRandom(0, 0), onIlluminated = { credits += 1 })
        val sid = SessionId(1L)
        loginAkathavae(s, sid, "Thalen")
        s.fixture.mobs.upsert(wisp())

        s.system.illuminate(sid, "wisp")
        s.system.illuminate(sid, "wisp")

        assertEquals(1, credits, "re-illuminating the same living instance must not advance a commission")
    }

    @Test
    fun `distinct living subjects each advance a commission`() = runTest {
        var credits = 0
        val s = setup(rng = ScriptedRandom(0, 0, 0), onIlluminated = { credits += 1 })
        val sid = SessionId(1L)
        loginAkathavae(s, sid, "Thalen")
        s.fixture.mobs.upsert(wisp(id = "n1", templateKey = "test:newt", name = "a red newt"))
        s.fixture.mobs.upsert(wisp(id = "c1", templateKey = "test:crab", name = "a blue crab"))

        s.system.illuminate(sid, "newt")
        s.system.illuminate(sid, "crab")

        assertEquals(2, credits, "each distinct living subject counts toward a commission")
    }

    @Test
    fun `failed illumination does not advance a commission`() = runTest {
        var credits = 0
        val s = setup(rng = ScriptedRandom(99, 99), onIlluminated = { credits += 1 })
        val sid = SessionId(1L)
        loginAkathavae(s, sid, "Thalen")
        s.fixture.mobs.upsert(wisp())

        s.system.illuminate(sid, "wisp")

        assertEquals(0, credits, "only successful recordings count")
    }

    @Test
    fun `first-time observation advances a commission but repeats do not`() = runTest {
        var credits = 0
        val s = setup(onIlluminated = { credits += 1 })
        val sid = SessionId(1L)
        loginAkathavae(s, sid, "Thalen")
        s.fixture.mobs.upsert(wisp(role = MobRole.VENDOR, templateKey = "test:merchant"))

        s.system.illuminate(sid, "wisp")
        assertEquals(1, credits, "commissions are about recording, so first observations count")

        s.system.illuminate(sid, "wisp")
        assertEquals(1, credits, "re-observing an already-recorded NPC must not advance a commission")
    }

    @Test
    fun `illuminate commission completes after recording distinct subjects`() = runTest {
        var hooked: DailyQuestSystem? = null
        val s = setup(
            rng = ScriptedRandom(0, 0, 0, 0),
            onIlluminated = { sid -> hooked?.onEvent(sid, "illuminate") },
        )
        val sid = SessionId(1L)
        loginAkathavae(s, sid, "Thalen")
        val dailyQuests = DailyQuestSystem(
            config = DailyQuestsConfig(
                enabled = true,
                dailySlots = 1,
                weeklySlots = 0,
                dailyPool = listOf(
                    DailyQuestDefinition(
                        type = "illuminate",
                        targetCount = 3,
                        description = "The Arcanum requests three fresh accounts of living creatures.",
                        goldReward = 150,
                        xpReward = 400,
                    ),
                ),
            ),
            players = s.fixture.players,
            clock = s.clock,
        )
        hooked = dailyQuests
        dailyQuests.checkReset(sid)

        s.fixture.mobs.upsert(wisp(id = "n1", templateKey = "test:newt", name = "a red newt"))
        s.fixture.mobs.upsert(wisp(id = "c1", templateKey = "test:crab", name = "a blue crab"))
        s.fixture.mobs.upsert(wisp(id = "t1", templateKey = "test:toad", name = "a green toad"))

        s.system.illuminate(sid, "newt")
        s.system.illuminate(sid, "newt") // same living instance — must not count twice
        assertEquals(1, dailyQuests.getDailyQuestBoard(sid).first().progress)

        s.system.illuminate(sid, "crab")
        s.system.illuminate(sid, "toad")

        val board = dailyQuests.getDailyQuestBoard(sid).first()
        assertEquals(3, board.progress)
        assertTrue(board.completed, "three distinct recordings should complete the commission")
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
    fun `successful illumination refreshes the room mob info so badges flip live`() = runTest {
        val refreshed = mutableListOf<SessionId>()
        val s = setup(rng = ScriptedRandom(0), refreshRoomMobInfo = { refreshed += it })
        val sid = SessionId(1L)
        loginAkathavae(s, sid, "Thalen")
        s.fixture.mobs.upsert(wisp())

        s.system.illuminate(sid, "wisp")

        assertEquals(listOf(sid), refreshed, "Room.MobInfo must be re-emitted after a successful illumination")
    }

    @Test
    fun `first observation refreshes the room mob info but repeats do not`() = runTest {
        val refreshed = mutableListOf<SessionId>()
        val s = setup(refreshRoomMobInfo = { refreshed += it })
        val sid = SessionId(1L)
        loginAkathavae(s, sid, "Thalen")
        s.fixture.mobs.upsert(wisp(role = MobRole.VENDOR, templateKey = "test:merchant"))

        s.system.illuminate(sid, "wisp")
        s.system.illuminate(sid, "wisp")

        assertEquals(listOf(sid), refreshed, "only the first observation changes the badge state")
    }

    // ── Unpledged field journaling ───────────────────────────────────────

    private suspend fun loginUnpledged(s: Setup, sid: SessionId, name: String): PlayerState {
        s.fixture.players.loginOrFail(sid, name)
        s.fixture.outbound.drainAll()
        return s.fixture.players.get(sid)!!
    }

    @Test
    fun `unpledged illumination keeps a field journal at reduced XP`() = runTest {
        val credited = mutableListOf<String>()
        val s = setup(rng = ScriptedRandom(0), onMobKilledByPlayer = { _, templateKey -> credited += templateKey })
        val sid = SessionId(1L)
        val me = loginUnpledged(s, sid, "Bruiser")
        s.fixture.items.loadSpawns(
            listOf(ItemSpawn(instance = ItemInstance(ItemId("test:dust"), Item(keyword = "dust", displayName = "glittering dust")))),
        )
        s.fixture.mobs.upsert(wisp(xpReward = 100L, drops = listOf(MobDrop(ItemId("test:dust"), 1.0))))

        s.system.illuminate(sid, "wisp")

        assertTrue(me.arcanum.mobs.containsKey("test:wisp"), "anyone may keep a field journal")
        assertNotNull(s.fixture.mobs.get(MobId("test:w1")), "illumination must never remove the subject")
        assertEquals(25L, me.xpTotal, "unpledged discovery XP is scaled by the multiplier")
        assertTrue(credited.isEmpty(), "a sketch must not bypass the fight for kill credit")
        assertTrue(me.arcanum.items.isEmpty(), "drop cataloguing belongs to the pledged")
        assertTrue(s.fixture.items.inventory(sid).isEmpty(), "no quest rubbings for the unpledged")
    }

    @Test
    fun `unpledged illumination cannot seal a world-first`() = runTest {
        val s = setup(rng = ScriptedRandom(0, 0))
        val bruiser = SessionId(1L)
        loginUnpledged(s, bruiser, "Bruiser")
        s.fixture.mobs.upsert(wisp(id = "w1"))

        s.system.illuminate(bruiser, "wisp")

        assertNull(s.worldState.getArcanumFirst("mob:test:wisp"), "only a pledged journal is accepted at the shrines")
        val infos = s.fixture.outbound.drainAll().filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
        assertTrue(infos.any { it.contains("unsealed") }, "got=$infos")

        // A pledged chronicler can still claim the page afterward.
        val alice = SessionId(2L)
        loginAkathavae(s, alice, "Alice")
        s.clock.advance(10_000)
        s.system.illuminate(alice, "wisp")
        assertEquals("Alice", s.worldState.getArcanumFirst("mob:test:wisp")?.first)
    }

    @Test
    fun `illuminationOddsFor scales down for the unpledged`() = runTest {
        val s = setup()
        val sid = SessionId(1L)
        val me = loginUnpledged(s, sid, "Bruiser")
        me.level = 5

        assertEquals(35, s.system.illuminationOddsFor(me, wisp(level = 5)), "the base 70% halves for a field journal")

        me.isAkathavae = true
        assertEquals(70, s.system.illuminationOddsFor(me, wisp(level = 5)), "pledging restores the practiced hand")
    }

    @Test
    fun `unpledged failure message carries no pledge language`() = runTest {
        val s = setup(rng = ScriptedRandom(99, 99))
        val sid = SessionId(1L)
        loginUnpledged(s, sid, "Bruiser")
        s.fixture.mobs.upsert(wisp())

        s.system.illuminate(sid, "wisp")

        assertTrue(s.combat.isInCombat(sid), "a failed sketch still angers the subject")
        val texts = s.fixture.outbound.drainAll().filterIsInstance<OutboundEvent.SendText>().map { it.text }
        assertTrue(texts.any { it.contains("turns on you!") && !it.contains("pledge") }, "got=$texts")
    }

    // ── Timed sketching ──────────────────────────────────────────────────

    @Test
    fun `illuminate starts a timed sketch that resolves on tick`() = runTest {
        val s = setup(rng = ScriptedRandom(0), config = AkathavaeConfig())
        val sid = SessionId(1L)
        val me = loginAkathavae(s, sid, "Thalen")
        s.fixture.mobs.upsert(wisp(xpReward = 100L))

        s.system.illuminate(sid, "wisp")
        val texts = s.fixture.outbound.drainAll().filterIsInstance<OutboundEvent.SendText>().map { it.text }
        assertTrue(texts.any { it.contains("set your quill") }, "got=$texts")
        assertFalse(me.arcanum.mobs.containsKey("test:wisp"), "the roll lands at the end, not the start")

        // Below the 2s minimum clamp: still sketching.
        s.clock.advance(1_999)
        s.system.tick()
        assertFalse(me.arcanum.mobs.containsKey("test:wisp"))

        // Past the 10s maximum clamp: every sketch has resolved.
        s.clock.advance(10_001)
        s.system.tick()
        assertTrue(me.arcanum.mobs.containsKey("test:wisp"), "the sketch resolves once its time is up")
        assertEquals(100L, me.xpTotal)
        assertNotNull(s.fixture.mobs.get(MobId("test:w1")), "illumination still never removes the subject")
    }

    @Test
    fun `a second illuminate while sketching is refused`() = runTest {
        val s = setup(rng = ScriptedRandom(0), config = AkathavaeConfig())
        val sid = SessionId(1L)
        loginAkathavae(s, sid, "Thalen")
        s.fixture.mobs.upsert(wisp())

        s.system.illuminate(sid, "wisp")
        s.system.illuminate(sid, "wisp")

        val errors = s.fixture.outbound.drainAll().filterIsInstance<OutboundEvent.SendError>().map { it.text }
        assertTrue(errors.any { it.contains("already busy") }, "got=$errors")
    }

    @Test
    fun `moving rooms abandons the sketch`() = runTest {
        val s = setup(rng = ScriptedRandom(0), config = AkathavaeConfig())
        val sid = SessionId(1L)
        val me = loginAkathavae(s, sid, "Thalen")
        s.fixture.mobs.upsert(wisp())

        s.system.illuminate(sid, "wisp")
        me.roomId = roomB
        s.system.onRoomVisited(sid)

        val texts = s.fixture.outbound.drainAll().filterIsInstance<OutboundEvent.SendText>().map { it.text }
        assertTrue(texts.any { it.contains("moved on") }, "got=$texts")

        s.clock.advance(20_000)
        s.system.tick()
        assertFalse(me.arcanum.mobs.containsKey("test:wisp"), "an abandoned sketch never resolves")
    }

    @Test
    fun `entering combat cancels the sketch`() = runTest {
        val s = setup(rng = ScriptedRandom(0), config = AkathavaeConfig())
        val sid = SessionId(1L)
        val me = loginAkathavae(s, sid, "Thalen")
        s.fixture.mobs.upsert(wisp(id = "w1"))
        s.fixture.mobs.upsert(wisp(id = "w2", templateKey = "test:brute"))

        s.system.illuminate(sid, "wisp")
        s.combat.engageMobCombat(sid, s.fixture.mobs.get(MobId("test:w2"))!!)
        s.fixture.outbound.drainAll()

        s.clock.advance(50)
        s.system.tick()

        val texts = s.fixture.outbound.drainAll().filterIsInstance<OutboundEvent.SendText>().map { it.text }
        assertTrue(texts.any { it.contains("shatters your concentration") }, "got=$texts")
        s.clock.advance(20_000)
        s.system.tick()
        assertFalse(me.arcanum.mobs.containsKey("test:wisp"), "a ruined sketch never resolves")
    }

    @Test
    fun `a subject that dies mid-sketch leaves the page unfinished`() = runTest {
        val s = setup(rng = ScriptedRandom(0), config = AkathavaeConfig())
        val sid = SessionId(1L)
        val me = loginAkathavae(s, sid, "Thalen")
        s.fixture.mobs.upsert(wisp())

        s.system.illuminate(sid, "wisp")
        s.fixture.mobs.remove(MobId("test:w1"))
        s.clock.advance(20_000)
        s.system.tick()

        val texts = s.fixture.outbound.drainAll().filterIsInstance<OutboundEvent.SendText>().map { it.text }
        assertTrue(texts.any { it.contains("subject is gone") }, "got=$texts")
        assertFalse(me.arcanum.mobs.containsKey("test:wisp"))
    }

    @Test
    fun `observing a non-combat NPC uses the flat observe sketch time`() = runTest {
        val s = setup(config = AkathavaeConfig())
        val sid = SessionId(1L)
        val me = loginAkathavae(s, sid, "Thalen")
        s.fixture.mobs.upsert(wisp(role = MobRole.VENDOR, templateKey = "test:merchant"))

        s.system.illuminate(sid, "wisp")
        s.clock.advance(AkathavaeConfig().observeSketchMs - 1)
        s.system.tick()
        assertFalse(me.arcanum.mobs.containsKey("test:merchant"))

        s.clock.advance(2)
        s.system.tick()
        assertTrue(me.arcanum.mobs.containsKey("test:merchant"))
        assertEquals(AkathavaeConfig().observeNpcXp, me.xpTotal)
    }

    @Test
    fun `re-observing a recorded NPC skips the sketch entirely`() = runTest {
        val s = setup(config = AkathavaeConfig())
        val sid = SessionId(1L)
        val me = loginAkathavae(s, sid, "Thalen")
        me.arcanum.mobs["test:merchant"] = ArcanumEntry(firstRecordedAtMs = 1L)
        s.fixture.mobs.upsert(wisp(role = MobRole.VENDOR, templateKey = "test:merchant"))

        s.system.illuminate(sid, "wisp")

        val texts = s.fixture.outbound.drainAll().filterIsInstance<OutboundEvent.SendText>().map { it.text }
        assertTrue(texts.any { it.contains("already holds a page") }, "got=$texts")
        assertFalse(texts.any { it.contains("set your quill") }, "no sketch should start; got=$texts")
    }

    // ── First slain ──────────────────────────────────────────────────────

    @Test
    fun `the first killer is stamped permanently and only once`() = runTest {
        val s = setup()
        val grog = SessionId(1L)
        val mira = SessionId(2L)
        loginUnpledged(s, grog, "Grog")
        loginUnpledged(s, mira, "Mira")

        s.system.onMobSlain(grog, wisp(id = "w1"))
        val infos = s.fixture.outbound.drainAll().filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
        assertTrue(infos.any { it.contains("First slain by Grog") }, "got=$infos")

        s.clock.advance(10_000)
        s.system.onMobSlain(mira, wisp(id = "w2"))

        assertEquals("Grog", s.worldState.getArcanumFirst("slain:test:wisp")?.first, "slain firsts are immutable once written")
    }

    @Test
    fun `first-slain records skip pets and non-combatants and bank no illumination firsts`() = runTest {
        val s = setup()
        val sid = SessionId(1L)
        val me = loginUnpledged(s, sid, "Grog")

        s.system.onMobSlain(sid, wisp(id = "w1").copy(ownerSessionId = SessionId(99L)))
        s.system.onMobSlain(sid, wisp(id = "w2", role = MobRole.VENDOR, templateKey = "test:merchant"))

        assertNull(s.worldState.getArcanumFirst("slain:test:wisp"), "pets never stamp a slain first")
        assertNull(s.worldState.getArcanumFirst("slain:test:merchant"), "non-combatants never stamp a slain first")

        s.system.onMobSlain(sid, wisp(id = "w3"))
        assertNotNull(s.worldState.getArcanumFirst("slain:test:wisp"))
        assertEquals(0, me.worldFirstsCount, "slain firsts do not bank illumination world-first achievements")
    }

    // ── Quest collect bridge (#1392) ─────────────────────────────────────

    private val dustId = ItemId("test:dust")

    private fun registerDustTemplate(s: Setup) {
        s.fixture.items.loadSpawns(
            listOf(ItemSpawn(instance = ItemInstance(dustId, Item(keyword = "dust", displayName = "glittering dust")))),
        )
    }

    private fun collectDustQuest(count: Int) = QuestDef(
        id = "test:gather_dust",
        name = "Gather Dust",
        description = "Bring back glittering dust.",
        giverMobId = "test:quest_giver",
        objectives = listOf(
            QuestObjectiveDef(type = "collect", targetId = "test:dust", count = count, description = "Collect $count dust"),
        ),
        rewards = QuestRewards(),
        completionType = "npc_turn_in",
    )

    /** Builds a QuestSystem over the fixture's components and wires it as the illumination bridge. */
    private fun attachQuests(s: Setup, quest: QuestDef): QuestSystem {
        val registry = QuestRegistry()
        registry.register(quest)
        val quests = QuestSystem(
            registry = registry,
            players = s.fixture.players,
            items = s.fixture.items,
            outbound = s.fixture.outbound,
            clock = s.clock,
        )
        s.system.quests = quests
        return quests
    }

    @Test
    fun `illumination grants a needed collect item and turn-in consumes it`() = runTest {
        val s = setup(rng = ScriptedRandom(0, 0))
        val sid = SessionId(1L)
        val me = loginAkathavae(s, sid, "Thalen")
        registerDustTemplate(s)
        val quests = attachQuests(s, collectDustQuest(count = 2))
        assertNull(quests.acceptQuest(sid, "test:gather_dust"))
        s.fixture.outbound.drainAll()

        // Deterministic grant: the drop's 0.25 chance is never rolled.
        s.fixture.mobs.upsert(wisp(id = "w1", drops = listOf(MobDrop(dustId, 0.25))))
        s.system.illuminate(sid, "wisp")

        assertEquals(1, s.fixture.items.inventory(sid).size, "one rubbing per living instance")
        assertEquals(1, me.activeQuests["test:gather_dust"]!!.objectives[0].current)
        val texts = s.fixture.outbound.drainAll().filterIsInstance<OutboundEvent.SendText>().map { it.text }
        assertTrue(texts.any { it.contains("careful rubbing") }, "got=$texts")

        // A second living instance completes the objective.
        s.fixture.mobs.remove(MobId("test:w1"))
        s.fixture.mobs.upsert(wisp(id = "w2", drops = listOf(MobDrop(dustId, 0.25))))
        s.system.illuminate(sid, "wisp")
        assertEquals(2, s.fixture.items.inventory(sid).size)
        assertTrue(me.activeQuests["test:gather_dust"]!!.objectives[0].isComplete)

        // Turn-in finds and consumes the granted items, exactly like looted ones.
        assertNull(quests.turnInQuest(sid, "Gather Dust", listOf("test:quest_giver")))
        assertTrue(me.completedQuestIds.contains("test:gather_dust"))
        assertTrue(s.fixture.items.inventory(sid).isEmpty(), "turn-in hands over the rubbings")
    }

    @Test
    fun `illumination grants nothing without an active collect objective`() = runTest {
        val s = setup(rng = ScriptedRandom(0))
        val sid = SessionId(1L)
        val me = loginAkathavae(s, sid, "Thalen")
        registerDustTemplate(s)
        attachQuests(s, collectDustQuest(count = 2)) // quest exists but is never accepted
        s.fixture.mobs.upsert(wisp(drops = listOf(MobDrop(dustId, 1.0))))

        s.system.illuminate(sid, "wisp")

        assertTrue(s.fixture.items.inventory(sid).isEmpty(), "no quest, no rubbing")
        assertTrue(me.arcanum.items.containsKey("test:dust"), "journal cataloguing is unchanged")
    }

    @Test
    fun `illumination grants nothing once the objective is complete`() = runTest {
        val s = setup(rng = ScriptedRandom(0, 0))
        val sid = SessionId(1L)
        val me = loginAkathavae(s, sid, "Thalen")
        registerDustTemplate(s)
        val quests = attachQuests(s, collectDustQuest(count = 1))
        assertNull(quests.acceptQuest(sid, "test:gather_dust"))

        s.fixture.mobs.upsert(wisp(id = "w1", drops = listOf(MobDrop(dustId, 1.0))))
        s.system.illuminate(sid, "wisp")
        assertTrue(me.activeQuests["test:gather_dust"]!!.objectives[0].isComplete)

        // Objective satisfied — a fresh instance yields no sellable surplus.
        s.fixture.mobs.remove(MobId("test:w1"))
        s.fixture.mobs.upsert(wisp(id = "w2", drops = listOf(MobDrop(dustId, 1.0))))
        s.system.illuminate(sid, "wisp")

        assertEquals(1, s.fixture.items.inventory(sid).size, "grants are capped at the remaining count")
    }

    @Test
    fun `re-illuminating the same living instance grants no second item`() = runTest {
        val s = setup(rng = ScriptedRandom(0, 0))
        val sid = SessionId(1L)
        val me = loginAkathavae(s, sid, "Thalen")
        registerDustTemplate(s)
        val quests = attachQuests(s, collectDustQuest(count = 2))
        assertNull(quests.acceptQuest(sid, "test:gather_dust"))

        s.fixture.mobs.upsert(wisp(id = "w1", drops = listOf(MobDrop(dustId, 1.0))))
        s.system.illuminate(sid, "wisp")
        s.system.illuminate(sid, "wisp") // same living instance, already credited

        assertEquals(1, s.fixture.items.inventory(sid).size, "a living creature yields quest items at most once")
        assertEquals(1, me.activeQuests["test:gather_dust"]!!.objectives[0].current)
    }

    @Test
    fun `duplicate drop entries cannot overshoot the remaining count`() = runTest {
        val s = setup(rng = ScriptedRandom(0))
        val sid = SessionId(1L)
        val me = loginAkathavae(s, sid, "Thalen")
        registerDustTemplate(s)
        val quests = attachQuests(s, collectDustQuest(count = 1))
        assertNull(quests.acceptQuest(sid, "test:gather_dust"))

        // The mob lists the same drop twice, but the quest only needs one.
        s.fixture.mobs.upsert(wisp(drops = listOf(MobDrop(dustId, 1.0), MobDrop(dustId, 1.0))))
        s.system.illuminate(sid, "wisp")

        assertEquals(1, s.fixture.items.inventory(sid).size, "the grant re-checks the remaining need per drop entry")
        assertTrue(me.activeQuests["test:gather_dust"]!!.objectives[0].isComplete)
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
    fun `world firsts increment the persistent counter only for the first recorder`() = runTest {
        val s = setup(rng = ScriptedRandom(0, 0))
        val alice = SessionId(1L)
        val bob = SessionId(2L)
        val aliceState = loginAkathavae(s, alice, "Alice")
        val bobState = loginAkathavae(s, bob, "Bob")

        s.fixture.mobs.upsert(wisp(id = "w1"))
        s.system.illuminate(alice, "wisp")
        assertEquals(1, aliceState.worldFirstsCount, "the first recorder banks a world-first")

        s.clock.advance(10_000)
        s.fixture.mobs.upsert(wisp(id = "w2"))
        s.system.illuminate(bob, "wisp")
        assertEquals(0, bobState.worldFirstsCount, "a later recorder earns no world-first")
    }

    // ── Achievements ─────────────────────────────────────────────────────

    private fun arcanumAchievement(id: String, type: String, count: Int) = AchievementDef(
        id = id,
        displayName = id.substringAfter('/'),
        description = "Arcanum test achievement.",
        category = "exploration",
        criteria = listOf(AchievementCriterion(type = type, targetId = "", count = count)),
    )

    @Test
    fun `first illumination unlocks an illuminate achievement immediately`() = runTest {
        val s = setup(
            rng = ScriptedRandom(0),
            achievements = listOf(arcanumAchievement("arcanum/first_light", "illuminate", 1)),
        )
        val sid = SessionId(1L)
        val me = loginAkathavae(s, sid, "Thalen")
        s.fixture.mobs.upsert(wisp())

        s.system.illuminate(sid, "wisp")

        assertTrue(me.unlockedAchievementIds.contains("arcanum/first_light"), "unlock must fire on the illuminating action itself")
        val infos = s.fixture.outbound.drainAll().filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
        assertTrue(infos.any { it.contains("[Achievement] first_light") }, "got=$infos")
    }

    @Test
    fun `room and world-first records unlock exploration achievements immediately`() = runTest {
        val s = setup(
            achievements = listOf(
                arcanumAchievement("arcanum/wanderer", "explore_rooms", 1),
                arcanumAchievement("arcanum/pioneer", "world_first", 1),
            ),
        )
        val sid = SessionId(1L)
        val me = loginAkathavae(s, sid, "Thalen")

        s.system.onRoomVisited(sid)

        assertEquals(1, me.worldFirstsCount)
        assertTrue(me.unlockedAchievementIds.contains("arcanum/wanderer"))
        assertTrue(me.unlockedAchievementIds.contains("arcanum/pioneer"))
    }

    @Test
    fun `item discovery unlocks a discover_items achievement immediately`() = runTest {
        val s = setup(
            rng = ScriptedRandom(0),
            achievements = listOf(arcanumAchievement("arcanum/collector", "discover_items", 1)),
        )
        val sid = SessionId(1L)
        val me = loginAkathavae(s, sid, "Thalen")
        s.fixture.items.loadSpawns(
            listOf(ItemSpawn(instance = ItemInstance(ItemId("test:dust"), Item(keyword = "dust", displayName = "glittering dust")))),
        )
        s.fixture.mobs.upsert(wisp(drops = listOf(MobDrop(ItemId("test:dust"), 1.0))))

        s.system.illuminate(sid, "wisp")

        assertTrue(me.unlockedAchievementIds.contains("arcanum/collector"))
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
