package dev.ambon.engine.crafting

import dev.ambon.config.CraftingConfig
import dev.ambon.domain.crafting.CraftingSkill
import dev.ambon.domain.crafting.CraftingSkillState
import dev.ambon.domain.crafting.CraftingStationType
import dev.ambon.domain.crafting.GatheringNodeDef
import dev.ambon.domain.crafting.GatheringYield
import dev.ambon.domain.crafting.MaterialRequirement
import dev.ambon.domain.crafting.RecipeDef
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.world.ItemSpawn
import dev.ambon.engine.PlayerState
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.test.MutableClock
import dev.ambon.test.TEST_SESSION_ID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.random.Random

class CraftingSystemTest {
    private val clock = MutableClock(1000L)
    private val config = CraftingConfig(
        maxSkillLevel = 100,
        baseXpPerLevel = 50L,
        xpExponent = 1.5,
        gatherCooldownMs = 3000L,
        stationBonusQuantity = 1,
    )
    private val gatheringRegistry = GatheringRegistry()
    private val craftingRegistry = CraftingRegistry()
    private val system = CraftingSystem(
        gatheringRegistry = gatheringRegistry,
        craftingRegistry = craftingRegistry,
        config = config,
        clock = clock,
        random = Random(42),
    )
    private val items = ItemRegistry()
    private val sid = TEST_SESSION_ID
    private val roomId = RoomId("test:mine")

    private val copperOreId = ItemId("test:copper_ore")
    private val ironOreId = ItemId("test:iron_ore")
    private val copperSwordId = ItemId("test:copper_sword")

    private fun makePlayer(
        skills: Map<CraftingSkill, CraftingSkillState> = emptyMap(),
        level: Int = 1,
    ): PlayerState {
        val p = PlayerState(
            sessionId = sid,
            name = "Tester",
            roomId = roomId,
            level = level,
            craftingSkills = skills.toMutableMap(),
        )
        items.ensurePlayer(sid)
        return p
    }

    @BeforeEach
    fun setUp() {
        gatheringRegistry.clear()
        craftingRegistry.clear()
        system.clear()
        // Register item templates via loadSpawns
        items.loadSpawns(
            listOf(
                ItemSpawn(
                    instance = ItemInstance(
                        id = copperOreId,
                        item = Item(keyword = "copper", displayName = "copper ore"),
                    ),
                    roomId = null,
                ),
                ItemSpawn(
                    instance = ItemInstance(
                        id = ironOreId,
                        item = Item(keyword = "iron", displayName = "iron ore"),
                    ),
                    roomId = null,
                ),
                ItemSpawn(
                    instance = ItemInstance(
                        id = copperSwordId,
                        item = Item(keyword = "copper sword", displayName = "a copper sword", damage = 4),
                    ),
                    roomId = null,
                ),
            ),
        )
    }

    @Nested
    inner class Gathering {
        private val node = GatheringNodeDef(
            id = "test:copper_vein",
            displayName = "a copper ore vein",
            keyword = "copper",
            skill = CraftingSkill.MINING,
            skillRequired = 1,
            yields = listOf(GatheringYield(itemId = copperOreId, minQuantity = 1, maxQuantity = 1)),
            respawnSeconds = 60,
            xpReward = 10,
            roomId = roomId,
        )

        @BeforeEach
        fun setUpNodes() {
            gatheringRegistry.register(listOf(node))
        }

        @Test
        fun `gather succeeds with sufficient skill`() {
            val player = makePlayer(
                skills = mapOf(CraftingSkill.MINING to CraftingSkillState(level = 1, xp = 0L)),
            )
            val result = system.gather(player, "copper", roomId, items)
            assertTrue(result is Either.Right)
            val r = (result as Either.Right).value
            assertEquals(1, r.itemsGathered[copperOreId])
            assertEquals(10, r.xpAwarded)
            // Player should have 1 copper ore in inventory
            assertEquals(1, items.inventory(sid).count { it.id == copperOreId })
        }

        @Test
        fun `gather fails with insufficient skill`() {
            val highNode = node.copy(skillRequired = 50)
            gatheringRegistry.clear()
            gatheringRegistry.register(listOf(highNode))

            val player = makePlayer(
                skills = mapOf(CraftingSkill.MINING to CraftingSkillState(level = 1, xp = 0L)),
            )
            val result = system.gather(player, "copper", roomId, items)
            assertTrue(result is Either.Left)
            val err = (result as Either.Left).value
            assertTrue(err is GatherError.SkillTooLow)
            assertEquals(50, (err as GatherError.SkillTooLow).required)
        }

        @Test
        fun `gather fails when node is depleted`() {
            val player = makePlayer(
                skills = mapOf(CraftingSkill.MINING to CraftingSkillState(level = 1, xp = 0L)),
            )
            // First gather succeeds
            val first = system.gather(player, "copper", roomId, items)
            assertTrue(first is Either.Right)

            // Advance past cooldown but not past respawn
            clock.advance(config.gatherCooldownMs + 1)

            // Second gather fails - node depleted
            val second = system.gather(player, "copper", roomId, items)
            assertTrue(second is Either.Left)
            assertTrue((second as Either.Left).value is GatherError.NodeDepleted)
        }

        @Test
        fun `gather fails on cooldown`() {
            val player = makePlayer(
                skills = mapOf(CraftingSkill.MINING to CraftingSkillState(level = 1, xp = 0L)),
            )
            system.gather(player, "copper", roomId, items)

            // Try again immediately - on cooldown
            val result = system.gather(player, "copper", roomId, items)
            assertTrue(result is Either.Left)
            assertTrue((result as Either.Left).value is GatherError.OnCooldown)
        }

        @Test
        fun `gather fails with no matching node`() {
            val player = makePlayer()
            val result = system.gather(player, "gold", roomId, items)
            assertTrue(result is Either.Left)
            assertTrue((result as Either.Left).value is GatherError.NoNodeFound)
        }

        @Test
        fun `node respawns after timer`() {
            val player = makePlayer(
                skills = mapOf(CraftingSkill.MINING to CraftingSkillState(level = 1, xp = 0L)),
            )
            system.gather(player, "copper", roomId, items)

            // Advance past both cooldown and respawn
            clock.advance(61_000L)
            system.tickNodeRespawns()

            val result = system.gather(player, "copper", roomId, items)
            assertTrue(result is Either.Right)
        }

        @Test
        fun `gathering awards XP and can level up`() {
            val player = makePlayer(
                skills = mapOf(CraftingSkill.MINING to CraftingSkillState(level = 1, xp = 0L)),
            )
            // XP for level 1 = 50 * 1^1.5 = 50
            // We need 50 XP, each gather gives 10, so 5 gathers to level up
            repeat(4) {
                system.gather(player, "copper", roomId, items)
                clock.advance(61_000L) // past cooldown + respawn
                system.tickNodeRespawns()
            }
            assertEquals(1, player.craftingSkills[CraftingSkill.MINING]?.level)

            // 5th gather should level up
            val result = system.gather(player, "copper", roomId, items)
            assertTrue(result is Either.Right)
            val r = (result as Either.Right).value
            assertTrue(r.leveledUp)
            assertEquals(2, r.newLevel)
            assertEquals(2, player.craftingSkills[CraftingSkill.MINING]?.level)
        }
    }

    @Nested
    inner class Crafting {
        private val recipe = RecipeDef(
            id = "test:copper_sword",
            displayName = "Copper Sword",
            skill = CraftingSkill.SMITHING,
            skillRequired = 1,
            levelRequired = 1,
            materials = listOf(MaterialRequirement(itemId = copperOreId, quantity = 3)),
            outputItemId = copperSwordId,
            outputQuantity = 1,
            stationType = CraftingStationType.FORGE,
            stationBonus = 0,
            xpReward = 25,
        )

        @BeforeEach
        fun setUpRecipes() {
            craftingRegistry.register(listOf(recipe))
        }

        private fun giveCopper(count: Int) {
            repeat(count) {
                val inst = items.createFromTemplate(copperOreId)!!
                items.addToInventory(sid, inst)
            }
        }

        @Test
        fun `craft succeeds with sufficient materials and skill`() {
            val player = makePlayer(
                skills = mapOf(CraftingSkill.SMITHING to CraftingSkillState(level = 1, xp = 0L)),
            )
            giveCopper(3)

            val result = system.craft(player, "copper sword", roomId, items, null)
            assertTrue(result is Either.Right)
            val r = (result as Either.Right).value
            assertEquals(1, r.quantityProduced)
            assertEquals(25, r.xpAwarded)
            assertFalse(r.stationBonusApplied)

            // Materials consumed
            assertEquals(0, items.inventory(sid).count { it.id == copperOreId })
            // Output created
            assertEquals(1, items.inventory(sid).count { it.id == copperSwordId })
        }

        @Test
        fun `craft fails with missing materials`() {
            val player = makePlayer(
                skills = mapOf(CraftingSkill.SMITHING to CraftingSkillState(level = 1, xp = 0L)),
            )
            giveCopper(1) // need 3

            val result = system.craft(player, "copper sword", roomId, items, null)
            assertTrue(result is Either.Left)
            val err = (result as Either.Left).value
            assertTrue(err is CraftError.MissingMaterials)
            val missing = (err as CraftError.MissingMaterials).missing
            assertEquals(1, missing.size)
            assertEquals(copperOreId, missing[0].first)
            assertEquals(2, missing[0].second) // need 2 more
        }

        @Test
        fun `craft fails with insufficient skill`() {
            val player = makePlayer(
                skills = mapOf(CraftingSkill.SMITHING to CraftingSkillState(level = 1, xp = 0L)),
            )
            giveCopper(3)

            val highRecipe = recipe.copy(skillRequired = 50)
            craftingRegistry.clear()
            craftingRegistry.register(listOf(highRecipe))

            val result = system.craft(player, "copper sword", roomId, items, null)
            assertTrue(result is Either.Left)
            assertTrue((result as Either.Left).value is CraftError.SkillTooLow)
        }

        @Test
        fun `craft fails with insufficient player level`() {
            val player = makePlayer(
                skills = mapOf(CraftingSkill.SMITHING to CraftingSkillState(level = 1, xp = 0L)),
                level = 1,
            )
            giveCopper(3)

            val levelRecipe = recipe.copy(levelRequired = 10)
            craftingRegistry.clear()
            craftingRegistry.register(listOf(levelRecipe))

            val result = system.craft(player, "copper sword", roomId, items, null)
            assertTrue(result is Either.Left)
            assertTrue((result as Either.Left).value is CraftError.LevelTooLow)
        }

        @Test
        fun `craft with station bonus gives extra output`() {
            val player = makePlayer(
                skills = mapOf(CraftingSkill.SMITHING to CraftingSkillState(level = 1, xp = 0L)),
            )
            giveCopper(3)

            // config.stationBonusQuantity = 1, recipe.stationBonus = 0
            // So bonus should be config default (1) since recipe has 0
            val result = system.craft(player, "copper sword", roomId, items, CraftingStationType.FORGE)
            assertTrue(result is Either.Right)
            val r = (result as Either.Right).value
            assertTrue(r.stationBonusApplied)
            assertEquals(2, r.quantityProduced) // 1 base + 1 bonus
            assertEquals(2, items.inventory(sid).count { it.id == copperSwordId })
        }

        @Test
        fun `craft with wrong station type gives no bonus`() {
            val player = makePlayer(
                skills = mapOf(CraftingSkill.SMITHING to CraftingSkillState(level = 1, xp = 0L)),
            )
            giveCopper(3)

            val result = system.craft(player, "copper sword", roomId, items, CraftingStationType.ALCHEMY_TABLE)
            assertTrue(result is Either.Right)
            val r = (result as Either.Right).value
            assertFalse(r.stationBonusApplied)
            assertEquals(1, r.quantityProduced)
        }

        @Test
        fun `craft unknown recipe returns error`() {
            val player = makePlayer()
            val result = system.craft(player, "mithril armor", roomId, items, null)
            assertTrue(result is Either.Left)
            assertTrue((result as Either.Left).value is CraftError.RecipeNotFound)
        }
    }

    @Nested
    inner class SkillProgression {
        @Test
        fun `xpForLevel calculation`() {
            // level 1: 50 * 1^1.5 = 50
            assertEquals(50L, system.xpForLevel(1))
            // level 2: 50 * 2^1.5 ≈ 141
            assertEquals(141L, system.xpForLevel(2))
            // level 10: 50 * 10^1.5 ≈ 1581
            assertEquals(1581L, system.xpForLevel(10))
        }

        @Test
        fun `skill level does not exceed maxSkillLevel`() {
            val player = makePlayer(
                skills = mapOf(CraftingSkill.MINING to CraftingSkillState(level = 100, xp = 0L)),
            )
            val node = GatheringNodeDef(
                id = "test:copper_vein",
                displayName = "a copper ore vein",
                keyword = "copper",
                skill = CraftingSkill.MINING,
                skillRequired = 1,
                yields = listOf(GatheringYield(itemId = copperOreId, minQuantity = 1, maxQuantity = 1)),
                respawnSeconds = 60,
                xpReward = 999999,
                roomId = roomId,
            )
            gatheringRegistry.register(listOf(node))

            val result = system.gather(player, "copper", roomId, items)
            assertTrue(result is Either.Right)
            val r = (result as Either.Right).value
            assertFalse(r.leveledUp) // Already at max
            assertEquals(100, player.craftingSkills[CraftingSkill.MINING]?.level)
        }
    }

    @Nested
    inner class Registry {
        @Test
        fun `gathering registry finds nodes in room`() {
            val node1 = GatheringNodeDef(
                id = "test:copper_vein",
                displayName = "a copper ore vein",
                keyword = "copper",
                skill = CraftingSkill.MINING,
                skillRequired = 1,
                yields = listOf(GatheringYield(itemId = copperOreId)),
                roomId = roomId,
            )
            val node2 = GatheringNodeDef(
                id = "test:iron_vein",
                displayName = "an iron ore vein",
                keyword = "iron",
                skill = CraftingSkill.MINING,
                skillRequired = 15,
                yields = listOf(GatheringYield(itemId = ironOreId)),
                roomId = roomId,
            )
            gatheringRegistry.register(listOf(node1, node2))

            assertEquals(2, gatheringRegistry.nodesInRoom(roomId).size)
            assertEquals(0, gatheringRegistry.nodesInRoom(RoomId("test:other")).size)
        }

        @Test
        fun `crafting registry finds recipe by keyword`() {
            val recipe = RecipeDef(
                id = "test:copper_sword",
                displayName = "Copper Sword",
                skill = CraftingSkill.SMITHING,
                skillRequired = 1,
                materials = listOf(MaterialRequirement(itemId = copperOreId, quantity = 3)),
                outputItemId = copperSwordId,
            )
            craftingRegistry.register(listOf(recipe))

            assertEquals(recipe, craftingRegistry.findRecipe("copper_sword"))
            assertEquals(recipe, craftingRegistry.findRecipe("Copper Sword"))
            assertEquals(recipe, craftingRegistry.findRecipe("copper"))
        }

        @Test
        fun `crafting registry filters by skill`() {
            val smithRecipe = RecipeDef(
                id = "test:copper_sword",
                displayName = "Copper Sword",
                skill = CraftingSkill.SMITHING,
                skillRequired = 1,
                materials = listOf(MaterialRequirement(itemId = copperOreId, quantity = 3)),
                outputItemId = copperSwordId,
            )
            val alchRecipe = RecipeDef(
                id = "test:potion",
                displayName = "Potion",
                skill = CraftingSkill.ALCHEMY,
                skillRequired = 1,
                materials = listOf(MaterialRequirement(itemId = copperOreId, quantity = 1)),
                outputItemId = copperSwordId,
            )
            craftingRegistry.register(listOf(smithRecipe, alchRecipe))

            assertEquals(1, craftingRegistry.recipesForSkill(CraftingSkill.SMITHING).size)
            assertEquals(1, craftingRegistry.recipesForSkill(CraftingSkill.ALCHEMY).size)
            assertEquals(0, craftingRegistry.recipesForSkill(CraftingSkill.MINING).size)
        }
    }
}
