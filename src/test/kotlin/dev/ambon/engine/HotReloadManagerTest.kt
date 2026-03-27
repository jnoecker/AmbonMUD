package dev.ambon.engine

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.config.EngineConfig
import dev.ambon.domain.DamageRange
import dev.ambon.domain.crafting.GatheringNodeDef
import dev.ambon.domain.crafting.GatheringYield
import dev.ambon.domain.crafting.RecipeDef
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.quest.QuestDef
import dev.ambon.domain.quest.QuestObjectiveDef
import dev.ambon.domain.quest.QuestRewards
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.ItemSpawn
import dev.ambon.domain.world.MobSpawn
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.ShopDefinition
import dev.ambon.domain.world.World
import dev.ambon.engine.abilities.AbilityRegistry
import dev.ambon.engine.behavior.BehaviorTreeSystem
import dev.ambon.engine.crafting.CraftingRegistry
import dev.ambon.engine.crafting.GatheringRegistry
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.status.StatusEffectRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.MutableClock
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HotReloadManagerTest {
    private val roomA = RoomId("zone1:roomA")
    private val roomB = RoomId("zone1:roomB")
    private val roomC = RoomId("zone2:roomC")

    private fun baseWorld() = World(
        rooms = mapOf(
            roomA to Room(roomA, "Room A", "desc", mapOf(Direction.NORTH to roomB)),
            roomB to Room(roomB, "Room B", "desc", mapOf(Direction.SOUTH to roomA)),
        ),
        startRoom = roomA,
        mobSpawns = listOf(
            MobSpawn(MobId("zone1:rat"), "a rat", roomA, maxHp = 10, damage = DamageRange(1, 2)),
        ),
        itemSpawns = listOf(
            ItemSpawn(ItemInstance(ItemId("zone1:sword"), Item("sword", "a sword")), roomA),
        ),
        shopDefinitions = listOf(
            ShopDefinition(
                id = "zone1:shop",
                name = "Shop",
                roomId = roomA,
                itemIds = listOf(ItemId("zone1:sword")),
            ),
        ),
        questDefinitions = listOf(
            QuestDef(
                id = "zone1:quest1",
                name = "Test Quest",
                description = "desc",
                giverMobId = "zone1:rat",
                objectives = listOf(
                    QuestObjectiveDef("kill", "zone1:rat", 1, "Kill a rat"),
                ),
                rewards = QuestRewards(),
            ),
        ),
        gatheringNodes = listOf(
            GatheringNodeDef(
                id = "zone1:ore",
                displayName = "Iron Ore",
                keyword = "ore",
                skill = "mining",
                yields = listOf(GatheringYield(ItemId("zone1:iron_ore"))),
                roomId = roomA,
            ),
        ),
        recipes = listOf(
            RecipeDef(
                id = "zone1:iron_bar",
                displayName = "Iron Bar",
                skill = "smelting",
                materials = emptyList(),
                outputItemId = ItemId("zone1:iron_bar"),
            ),
        ),
    )

    private fun updatedWorld() = World(
        rooms = mapOf(
            roomA to Room(roomA, "Room A Updated", "new desc", mapOf(Direction.NORTH to roomB)),
            roomB to Room(roomB, "Room B Updated", "new desc", mapOf(Direction.SOUTH to roomA)),
            roomC to Room(roomC, "Room C", "zone2 room", emptyMap()),
        ),
        startRoom = roomA,
        mobSpawns = listOf(
            MobSpawn(
                MobId("zone1:rat"),
                "a big rat",
                roomA,
                maxHp = 20,
                damage = DamageRange(2, 4),
            ),
            MobSpawn(
                MobId("zone2:wolf"),
                "a wolf",
                roomC,
                maxHp = 30,
                damage = DamageRange(3, 5),
            ),
        ),
        itemSpawns = listOf(
            ItemSpawn(ItemInstance(ItemId("zone1:sword"), Item("sword", "a sword", damage = 5)), roomA),
            ItemSpawn(ItemInstance(ItemId("zone2:shield"), Item("shield", "a shield")), roomC),
        ),
        shopDefinitions = listOf(
            ShopDefinition(
                id = "zone1:shop",
                name = "Updated Shop",
                roomId = roomA,
                itemIds = listOf(ItemId("zone1:sword")),
            ),
        ),
        questDefinitions = listOf(
            QuestDef(
                id = "zone1:quest1",
                name = "Updated Quest",
                description = "new desc",
                giverMobId = "zone1:rat",
                objectives = listOf(
                    QuestObjectiveDef("kill", "zone1:rat", 2, "Kill two rats"),
                ),
                rewards = QuestRewards(),
            ),
        ),
        gatheringNodes = emptyList(),
        recipes = emptyList(),
    )

    private fun buildManager(
        world: World,
        worldLoader: () -> World,
    ): TestHarness {
        val clock = MutableClock(0L)
        val outbound = LocalOutboundBus()
        val mobs = MobRegistry()
        val items = ItemRegistry()
        val playerRepo = InMemoryPlayerRepository()
        val players = PlayerRegistry(
            startRoom = world.startRoom,
            repo = playerRepo,
            items = items,
            clock = clock,
        )
        val shopRegistry = ShopRegistry(items)
        val gatheringRegistry = GatheringRegistry()
        val craftingRegistry = CraftingRegistry()
        val questRegistry = QuestRegistry()
        val abilityRegistry = AbilityRegistry()
        val statusEffectRegistry = StatusEffectRegistry()
        val mobSystem = MobSystem()
        val behaviorTreeSystem = BehaviorTreeSystem(
            world = world,
            mobs = mobs,
            players = players,
            outbound = outbound,
            clock = clock,
        )
        val gmcpEmitter = GmcpEmitter(
            outbound = outbound,
            supportsPackage = { _, _ -> false },
        )
        val worldState = WorldStateRegistry(world)

        // Initialize registries from world data (matching GameEngine init)
        world.mobSpawns.forEach { spawn ->
            mobs.upsert(spawnToMobState(spawn, world))
        }
        items.loadSpawns(world.itemSpawns)
        shopRegistry.register(world.shopDefinitions)
        gatheringRegistry.register(world.gatheringNodes)
        craftingRegistry.register(world.recipes)
        world.questDefinitions.forEach { questRegistry.register(it) }

        val manager = HotReloadManager(
            world = world,
            mobs = mobs,
            items = items,
            players = players,
            outbound = outbound,
            shopRegistry = shopRegistry,
            gatheringRegistry = gatheringRegistry,
            craftingRegistry = craftingRegistry,
            questRegistry = questRegistry,
            abilityRegistry = abilityRegistry,
            statusEffectRegistry = statusEffectRegistry,
            equipmentSlotRegistry = EquipmentSlotRegistry(EngineConfig().equipment),
            mobSystem = mobSystem,
            behaviorTreeSystem = behaviorTreeSystem,
            gmcpEmitter = gmcpEmitter,
            worldState = worldState,
            engineConfig = EngineConfig(),
            imagesBaseUrl = "/images/",
            worldLoader = worldLoader,
        )

        return TestHarness(
            manager = manager,
            world = world,
            mobs = mobs,
            items = items,
            players = players,
            outbound = outbound,
            shopRegistry = shopRegistry,
            gatheringRegistry = gatheringRegistry,
            craftingRegistry = craftingRegistry,
            questRegistry = questRegistry,
        )
    }

    data class TestHarness(
        val manager: HotReloadManager,
        val world: World,
        val mobs: MobRegistry,
        val items: ItemRegistry,
        val players: PlayerRegistry,
        val outbound: LocalOutboundBus,
        val shopRegistry: ShopRegistry,
        val gatheringRegistry: GatheringRegistry,
        val craftingRegistry: CraftingRegistry,
        val questRegistry: QuestRegistry,
    )

    @Test
    fun `reloadWorld updates rooms from fresh world`() = runTest {
        val h = buildManager(baseWorld()) { updatedWorld() }

        val result = h.manager.reloadWorld()

        assertEquals("Room A Updated", h.world.rooms[roomA]!!.title)
        assertNotNull(h.world.rooms[roomC])
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `reloadWorld adds new mob spawns`() = runTest {
        val h = buildManager(baseWorld()) { updatedWorld() }

        assertNotNull(h.mobs.get(MobId("zone1:rat")))
        assertNull(h.mobs.get(MobId("zone2:wolf")))

        h.manager.reloadWorld()

        assertNotNull(h.mobs.get(MobId("zone1:rat")))
        assertNotNull(h.mobs.get(MobId("zone2:wolf")))
    }

    @Test
    fun `reloadWorld existing mobs keep current state`() = runTest {
        val h = buildManager(baseWorld()) { updatedWorld() }

        // Damage the rat before reload
        h.mobs.get(MobId("zone1:rat"))!!.hp = 3

        h.manager.reloadWorld()

        // Rat should still be alive with 3 HP (not reset to 20)
        assertEquals(3, h.mobs.get(MobId("zone1:rat"))!!.hp)
    }

    @Test
    fun `reloadWorld refreshes shop registry`() = runTest {
        val h = buildManager(baseWorld()) { updatedWorld() }

        h.manager.reloadWorld()

        val shop = h.shopRegistry.shopInRoom(roomA)
        assertNotNull(shop)
        assertEquals("Updated Shop", shop!!.name)
    }

    @Test
    fun `reloadWorld clears gathering and crafting when removed`() = runTest {
        val h = buildManager(baseWorld()) { updatedWorld() }

        assertTrue(h.gatheringRegistry.allNodes().isNotEmpty())
        assertTrue(h.craftingRegistry.allRecipes().isNotEmpty())

        h.manager.reloadWorld()

        assertTrue(h.gatheringRegistry.allNodes().isEmpty())
        assertTrue(h.craftingRegistry.allRecipes().isEmpty())
    }

    @Test
    fun `reloadWorld refreshes quest definitions`() = runTest {
        val h = buildManager(baseWorld()) { updatedWorld() }

        h.manager.reloadWorld()

        val quest = h.questRegistry.get("zone1:quest1")
        assertNotNull(quest)
        assertEquals("Updated Quest", quest!!.name)
    }

    @Test
    fun `reloadWorld returns error when loader fails`() = runTest {
        val h = buildManager(baseWorld()) { throw RuntimeException("YAML parse error") }

        val result = h.manager.reloadWorld()

        assertTrue(result.errors.isNotEmpty())
        assertTrue(result.errors.first().contains("YAML parse error"))
        // World should remain unchanged
        assertEquals("Room A", h.world.rooms[roomA]!!.title)
    }

    @Test
    fun `reloadAll reloads world and definitions`() = runTest {
        val h = buildManager(baseWorld()) { updatedWorld() }

        val result = h.manager.reloadAll()

        assertTrue(result.errors.isEmpty())
        assertTrue(result.zonesReloaded > 0)
        assertEquals("Room A Updated", h.world.rooms[roomA]!!.title)
    }
}
