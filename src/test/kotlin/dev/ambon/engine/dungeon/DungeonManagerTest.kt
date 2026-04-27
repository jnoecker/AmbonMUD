package dev.ambon.engine.dungeon

import dev.ambon.domain.DamageRange
import dev.ambon.domain.dungeon.DungeonDifficulty
import dev.ambon.domain.dungeon.DungeonLootTableDef
import dev.ambon.domain.dungeon.DungeonMobPoolDef
import dev.ambon.domain.dungeon.DungeonRoomTemplateDef
import dev.ambon.domain.dungeon.DungeonRoomType
import dev.ambon.domain.dungeon.DungeonTemplateDef
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.MobSpawn
import dev.ambon.domain.world.MobTemplateDef
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.World
import dev.ambon.engine.MobRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.random.Random

class DungeonManagerTest {
    private val portalRoomId = RoomId("test:portal")
    private val sid1 = SessionId(1L)
    private val sid2 = SessionId(2L)

    private val template = DungeonTemplateDef(
        id = "test:crypt",
        name = "Test Crypt",
        roomCountMin = 5,
        roomCountMax = 5,
        roomTemplates = mapOf(
            DungeonRoomType.ENTRANCE to listOf(DungeonRoomTemplateDef("Entrance", "Enter.")),
            DungeonRoomType.CORRIDOR to listOf(DungeonRoomTemplateDef("Corridor", "A corridor.")),
            DungeonRoomType.BOSS to listOf(DungeonRoomTemplateDef("Boss Room", "The boss awaits.")),
        ),
        mobPools = DungeonMobPoolDef(
            common = listOf("crypt_skeleton"),
            boss = listOf("crypt_lord"),
        ),
        lootTables = mapOf(
            DungeonDifficulty.NORMAL to DungeonLootTableDef(
                completionRewards = listOf(ItemId("test:relic")),
            ),
            DungeonDifficulty.LORE to DungeonLootTableDef(
                completionRewards = listOf(ItemId("test:title")),
            ),
        ),
    )

    private val skeletonId = MobId("test:crypt_skeleton")
    private val bossId = MobId("test:crypt_lord")

    private val skeletonTemplate = MobTemplateDef(
        id = skeletonId,
        name = "a skeleton",
        maxHp = 20,
        damage = DamageRange(2, 4),
        xpReward = 30L,
    )

    private val bossTemplate = MobTemplateDef(
        id = bossId,
        name = "the Crypt Lord",
        maxHp = 100,
        damage = DamageRange(8, 15),
        xpReward = 200L,
    )

    private val skeletonSpawn = MobSpawn(skeletonId, skeletonId, portalRoomId)
    private val bossSpawn = MobSpawn(bossId, bossId, portalRoomId)

    private lateinit var world: World
    private lateinit var mobs: MobRegistry
    private lateinit var registry: DungeonRegistry
    private lateinit var manager: DungeonManager

    @BeforeEach
    fun setUp() {
        world = World(
            rooms = mapOf(portalRoomId to Room(portalRoomId, "Portal", "A portal room.", emptyMap())),
            startRoom = portalRoomId,
            mobTemplates = mapOf(skeletonId to skeletonTemplate, bossId to bossTemplate),
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

    @Nested
    inner class InstanceCreation {
        @Test
        fun `creates instance with rooms in world`() {
            val inst = manager.createInstance(
                template = template,
                difficulty = DungeonDifficulty.NORMAL,
                leader = sid1,
                members = setOf(sid1),
                partyLevel = 5,
                returnRoom = portalRoomId,
            )
            // Rooms should be added to the world
            for ((_, roomId) in inst.roomIds) {
                assertNotNull(world.rooms[roomId], "Room $roomId should exist in world")
            }
            assertTrue(inst.roomIds.size >= 5)
        }

        @Test
        fun `spawns mobs in dungeon rooms`() {
            val inst = manager.createInstance(
                template = template,
                difficulty = DungeonDifficulty.NORMAL,
                leader = sid1,
                members = setOf(sid1),
                partyLevel = 5,
                returnRoom = portalRoomId,
            )
            assertTrue(inst.spawnedMobs.isNotEmpty(), "Should have spawned mobs")
            for (mobId in inst.spawnedMobs) {
                assertNotNull(mobs.get(mobId), "Mob $mobId should exist in registry")
            }
        }

        @Test
        fun `tracks player in dungeon`() {
            manager.createInstance(
                template = template,
                difficulty = DungeonDifficulty.NORMAL,
                leader = sid1,
                members = setOf(sid1),
                partyLevel = 5,
                returnRoom = portalRoomId,
            )
            assertTrue(manager.isInDungeon(sid1))
            assertFalse(manager.isInDungeon(sid2))
        }
    }

    @Nested
    inner class DifficultyScaling {
        @Test
        fun `lore mode mobs have reduced stats`() {
            val inst = manager.createInstance(
                template = template,
                difficulty = DungeonDifficulty.LORE,
                leader = sid1,
                members = setOf(sid1),
                partyLevel = 1,
                returnRoom = portalRoomId,
            )
            // Find any spawned mob and check its HP is scaled down
            val anyMob = inst.spawnedMobs.mapNotNull { mobs.get(it) }.firstOrNull()
            assertNotNull(anyMob)
            // LORE = 0.5x HP multiplier, level 1 = 1.0x scale
            // Skeleton base HP = 20, so LORE should be ~10
            if (anyMob!!.templateKey.contains("skeleton")) {
                assertTrue(anyMob.maxHp <= 10, "Lore skeleton HP should be ~10, was ${anyMob.maxHp}")
            }
        }

        @Test
        fun `heroic mode mobs have increased stats`() {
            val inst = manager.createInstance(
                template = template,
                difficulty = DungeonDifficulty.HEROIC,
                leader = sid1,
                members = setOf(sid1),
                partyLevel = 1,
                returnRoom = portalRoomId,
            )
            val anyMob = inst.spawnedMobs.mapNotNull { mobs.get(it) }.firstOrNull()
            assertNotNull(anyMob)
            if (anyMob!!.templateKey.contains("skeleton")) {
                assertTrue(anyMob.maxHp >= 40, "Heroic skeleton HP should be ~40, was ${anyMob.maxHp}")
            }
        }
    }

    @Nested
    inner class Lifecycle {
        @Test
        fun `removing last player destroys instance`() {
            val inst = manager.createInstance(
                template = template,
                difficulty = DungeonDifficulty.NORMAL,
                leader = sid1,
                members = setOf(sid1),
                partyLevel = 5,
                returnRoom = portalRoomId,
            )
            val mobCount = inst.spawnedMobs.size
            assertTrue(mobCount > 0)

            val returnRoom = manager.removePlayer(sid1)
            assertEquals(portalRoomId, returnRoom)
            assertFalse(manager.isInDungeon(sid1))

            // Mobs should be cleaned up
            for (mobId in inst.spawnedMobs) {
                assertNull(mobs.get(mobId))
            }
        }

        @Test
        fun `instance survives when one member leaves`() {
            val inst = manager.createInstance(
                template = template,
                difficulty = DungeonDifficulty.NORMAL,
                leader = sid1,
                members = setOf(sid1, sid2),
                partyLevel = 5,
                returnRoom = portalRoomId,
            )

            manager.removePlayer(sid1)
            assertFalse(manager.isInDungeon(sid1))
            assertTrue(manager.isInDungeon(sid2))
            // Instance still exists — mobs are alive
            assertTrue(inst.spawnedMobs.any { mobs.get(it) != null })
        }

        @Test
        fun `boss detection finds correct instance`() {
            val inst = manager.createInstance(
                template = template,
                difficulty = DungeonDifficulty.NORMAL,
                leader = sid1,
                members = setOf(sid1),
                partyLevel = 5,
                returnRoom = portalRoomId,
            )
            // Find a boss mob
            val bossRoomId = inst.roomIds[inst.layout.bossIndex]!!
            val bossMob = mobs.mobsInRoom(bossRoomId).firstOrNull()
            assertNotNull(bossMob)

            val found = manager.findInstanceByBossMob(bossMob!!.id)
            assertEquals(inst.id, found?.id)
        }

        @Test
        fun `markComplete sets completed flag`() {
            val inst = manager.createInstance(
                template = template,
                difficulty = DungeonDifficulty.NORMAL,
                leader = sid1,
                members = setOf(sid1),
                partyLevel = 5,
                returnRoom = portalRoomId,
            )
            assertFalse(inst.completed)
            manager.markComplete(inst)
            assertTrue(inst.completed)
        }
    }
}
