package dev.ambon.engine.commands

import dev.ambon.domain.DamageRange
import dev.ambon.domain.dungeon.DungeonMobPoolDef
import dev.ambon.domain.dungeon.DungeonRoomTemplateDef
import dev.ambon.domain.dungeon.DungeonRoomType
import dev.ambon.domain.dungeon.DungeonTemplateDef
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.MobSpawn
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.World
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.dungeon.DungeonGenerator
import dev.ambon.engine.dungeon.DungeonManager
import dev.ambon.engine.dungeon.DungeonRegistry
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.test.CommandRouterHarness
import dev.ambon.test.buildTestPlayerRegistry
import dev.ambon.test.infoMessages
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class DungeonCommandTest {
    private val portalRoom = RoomId("test:portal")

    private val template = DungeonTemplateDef(
        id = "test:crypt",
        name = "Test Crypt",
        minLevel = 3,
        roomCountMin = 5,
        roomCountMax = 5,
        roomTemplates = mapOf(
            DungeonRoomType.ENTRANCE to listOf(DungeonRoomTemplateDef("Crypt Entrance", "Enter the crypt.")),
            DungeonRoomType.CORRIDOR to listOf(DungeonRoomTemplateDef("Dark Passage", "A dark passage.")),
            DungeonRoomType.BOSS to listOf(DungeonRoomTemplateDef("Boss Chamber", "The boss lurks here.")),
        ),
        mobPools = DungeonMobPoolDef(
            common = listOf("test_skeleton"),
            boss = listOf("test_boss"),
        ),
    )

    private val skeletonSpawn = MobSpawn(
        id = MobId("test:test_skeleton"),
        name = "a skeleton",
        roomId = portalRoom,
        maxHp = 20,
        damage = DamageRange(2, 4),
        xpReward = 30L,
    )

    private val bossSpawn = MobSpawn(
        id = MobId("test:test_boss"),
        name = "the Boss",
        roomId = portalRoom,
        maxHp = 100,
        damage = DamageRange(8, 15),
        xpReward = 200L,
    )

    private lateinit var world: World
    private lateinit var mobs: MobRegistry
    private lateinit var registry: DungeonRegistry
    private lateinit var manager: DungeonManager

    @BeforeEach
    fun setUp() {
        world = World(
            rooms = mapOf(portalRoom to Room(portalRoom, "Portal Room", "A room with a dungeon portal.", emptyMap())),
            startRoom = portalRoom,
            mobSpawns = listOf(skeletonSpawn, bossSpawn),
        )
        mobs = MobRegistry()
        registry = DungeonRegistry()
        registry.register(template)
        manager = DungeonManager(
            world = world,
            mobs = mobs,
            generator = DungeonGenerator(Random(42)),
            dungeonRegistry = registry,
        )
    }

    private fun harness(): CommandRouterHarness {
        val players = buildTestPlayerRegistry(world.startRoom)
        return CommandRouterHarness.create(
            world = world,
            players = players,
            mobs = mobs,
            dungeonManager = manager,
            dungeonRegistry = registry,
        )
    }

    @Test
    fun `enter dungeon teleports player and shows dungeon message`() = runTest {
        val h = harness()
        val sid = SessionId(1)
        h.loginPlayer(sid, "Alice")
        h.players.get(sid)!!.level = 5
        h.drain()

        h.router.handle(sid, Command.DungeonEnter("crypt", null))

        val events = h.drain()
        val infos = events.infoMessages(sid)

        assertTrue(infos.any { it.contains("Test Crypt") }, "Should see dungeon name. got=$infos")
        assertTrue(manager.isInDungeon(sid), "Player should be in dungeon")

        // Player should have been moved to a dungeon room
        val ps = h.players.get(sid)!!
        assertTrue(ps.roomId.value.startsWith("dg_"), "Room should be a dungeon room. got=${ps.roomId}")
    }

    @Test
    fun `enter dungeon with insufficient level sends error`() = runTest {
        val h = harness()
        val sid = SessionId(1)
        h.loginPlayer(sid, "Alice")
        h.players.get(sid)!!.level = 1 // Below minLevel 3
        h.drain()

        h.router.handle(sid, Command.DungeonEnter("crypt", null))

        val events = h.drain()
        val texts = events.filterIsInstance<OutboundEvent.SendError>()
            .filter { it.sessionId == sid }
            .map { it.text }

        assertTrue(texts.any { it.contains("level") }, "Should mention level requirement. got=$texts")
        assertFalse(manager.isInDungeon(sid), "Player should NOT be in dungeon")
    }

    @Test
    fun `enter with unknown template sends error`() = runTest {
        val h = harness()
        val sid = SessionId(1)
        h.loginPlayer(sid, "Alice")
        h.players.get(sid)!!.level = 5
        h.drain()

        h.router.handle(sid, Command.DungeonEnter("nonexistent", null))

        val events = h.drain()
        val texts = events.filterIsInstance<OutboundEvent.SendError>()
            .filter { it.sessionId == sid }
            .map { it.text }

        assertTrue(texts.any { it.contains("Unknown") }, "Should say unknown dungeon. got=$texts")
    }

    @Test
    fun `leave dungeon returns player to original room`() = runTest {
        val h = harness()
        val sid = SessionId(1)
        h.loginPlayer(sid, "Alice")
        h.players.get(sid)!!.level = 5
        h.drain()

        // Enter dungeon
        h.router.handle(sid, Command.DungeonEnter("crypt", null))
        h.drain()
        assertTrue(manager.isInDungeon(sid))

        // Leave dungeon
        h.router.handle(sid, Command.DungeonLeave)

        val events = h.drain()
        val infos = events.infoMessages(sid)

        assertTrue(infos.any { it.contains("leave") }, "Should see leave message. got=$infos")
        assertFalse(manager.isInDungeon(sid), "Player should not be in dungeon")

        val ps = h.players.get(sid)!!
        assertTrue(ps.roomId == portalRoom, "Player should be back in portal room. got=${ps.roomId}")
    }

    @Test
    fun `leave when not in dungeon sends error`() = runTest {
        val h = harness()
        val sid = SessionId(1)
        h.loginPlayer(sid, "Alice")
        h.drain()

        h.router.handle(sid, Command.DungeonLeave)

        val events = h.drain()
        val texts = events.filterIsInstance<OutboundEvent.SendError>()
            .filter { it.sessionId == sid }
            .map { it.text }

        assertTrue(texts.any { it.contains("not in a dungeon") }, "Should say not in dungeon. got=$texts")
    }

    @Test
    fun `re-enter dungeon teleports back to existing instance`() = runTest {
        val h = harness()
        val sid = SessionId(1)
        h.loginPlayer(sid, "Alice")
        h.players.get(sid)!!.level = 5
        h.drain()

        // Enter dungeon
        h.router.handle(sid, Command.DungeonEnter("crypt", null))
        h.drain()
        val instanceRoom = h.players.get(sid)!!.roomId

        // Manually move player out (simulating something like death teleport)
        h.players.moveTo(sid, portalRoom)

        // Re-enter should go back to the existing instance
        h.router.handle(sid, Command.DungeonEnter("crypt", null))

        val events = h.drain()
        val infos = events.infoMessages(sid)

        assertTrue(infos.any { it.contains("re-enter") }, "Should see re-entry message. got=$infos")
        assertTrue(manager.isInDungeon(sid), "Player should be in dungeon again")
    }

    @Test
    fun `enter with difficulty creates instance at that difficulty`() = runTest {
        val h = harness()
        val sid = SessionId(1)
        h.loginPlayer(sid, "Alice")
        h.players.get(sid)!!.level = 5
        h.drain()

        h.router.handle(sid, Command.DungeonEnter("crypt", "hard"))

        val events = h.drain()
        val infos = events.infoMessages(sid)

        assertTrue(infos.any { it.contains("Hard") }, "Should see difficulty name. got=$infos")
    }

    @Test
    fun `enter with invalid difficulty sends error`() = runTest {
        val h = harness()
        val sid = SessionId(1)
        h.loginPlayer(sid, "Alice")
        h.players.get(sid)!!.level = 5
        h.drain()

        h.router.handle(sid, Command.DungeonEnter("crypt", "impossible"))

        val events = h.drain()
        val texts = events.filterIsInstance<OutboundEvent.SendError>()
            .filter { it.sessionId == sid }
            .map { it.text }

        assertTrue(texts.any { it.contains("Unknown difficulty") }, "Should reject invalid difficulty. got=$texts")
    }

    @Test
    fun `dungeon unavailable when manager is null`() = runTest {
        val players = buildTestPlayerRegistry(portalRoom)
        val h = CommandRouterHarness.create(
            world = world,
            players = players,
        )
        val sid = SessionId(1)
        h.loginPlayer(sid, "Alice")
        h.drain()

        h.router.handle(sid, Command.DungeonEnter("crypt", null))

        val events = h.drain()
        val texts = events.filterIsInstance<OutboundEvent.SendError>()
            .filter { it.sessionId == sid }
            .map { it.text }

        assertTrue(texts.any { it.contains("not available") }, "got=$texts")
    }
}
