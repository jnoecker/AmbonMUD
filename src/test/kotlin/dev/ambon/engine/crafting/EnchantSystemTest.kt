package dev.ambon.engine.crafting

import dev.ambon.config.CraftingConfig
import dev.ambon.config.EnchantingConfig
import dev.ambon.config.EnchantmentDefinition
import dev.ambon.config.MaterialConfigEntry
import dev.ambon.domain.crafting.CraftingSkillState
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.items.ItemSlot
import dev.ambon.engine.items.ItemRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class EnchantSystemTest {
    private val sid = SessionId(1L)

    private val keenEdge = EnchantmentDefinition(
        displayName = "Keen Edge",
        skill = "enchanting",
        skillRequired = 1,
        materials = listOf(MaterialConfigEntry(itemId = "essence", quantity = 1)),
        damageBonus = 2,
        targetSlots = listOf("hand"),
        xpReward = 30,
    )

    private val fortify = EnchantmentDefinition(
        displayName = "Fortify",
        skill = "enchanting",
        skillRequired = 1,
        materials = listOf(MaterialConfigEntry(itemId = "essence", quantity = 1)),
        armorBonus = 2,
        targetSlots = listOf("head", "body"),
        xpReward = 30,
    )

    private val might = EnchantmentDefinition(
        displayName = "Might",
        skill = "enchanting",
        skillRequired = 10,
        materials = listOf(
            MaterialConfigEntry(itemId = "essence", quantity = 2),
            MaterialConfigEntry(itemId = "gem", quantity = 1),
        ),
        statBonuses = mapOf("STR" to 2),
        targetSlots = listOf("hand"),
        xpReward = 50,
    )

    private val config = EnchantingConfig(
        maxEnchantmentsPerItem = 1,
        definitions = mapOf(
            "keen_edge" to keenEdge,
            "fortify" to fortify,
            "might" to might,
        ),
    )

    private lateinit var items: ItemRegistry
    private lateinit var craftingSystem: CraftingSystem
    private lateinit var enchantSystem: EnchantSystem

    private fun sword() = ItemInstance(
        id = ItemId("sword_1"),
        item = Item(
            keyword = "sword",
            displayName = "a copper sword",
            slot = ItemSlot.HAND,
            damage = 4,
        ),
    )

    private fun helm() = ItemInstance(
        id = ItemId("helm_1"),
        item = Item(
            keyword = "helm",
            displayName = "a copper helm",
            slot = ItemSlot.HEAD,
            armor = 2,
        ),
    )

    private fun essence() = ItemInstance(
        id = ItemId("essence"),
        item = Item(keyword = "essence", displayName = "a wisp of arcane essence"),
    )

    private fun gem() = ItemInstance(
        id = ItemId("gem"),
        item = Item(keyword = "gem", displayName = "a rough gem"),
    )

    @BeforeEach
    fun setUp() {
        items = ItemRegistry()
        items.ensurePlayer(sid)
        craftingSystem = CraftingSystem(
            gatheringRegistry = GatheringRegistry(),
            craftingRegistry = CraftingRegistry(),
            config = CraftingConfig(),
            clock = java.time.Clock.systemUTC(),
        )
        enchantSystem = EnchantSystem(
            config = config,
            items = items,
            craftingSystem = craftingSystem,
        )
    }

    private fun skills(level: Int = 1): MutableMap<String, CraftingSkillState> =
        mutableMapOf("enchanting" to CraftingSkillState(level = level))

    @Nested
    inner class SuccessfulEnchant {
        @Test
        fun `enchant adds damage bonus and updates display name`() {
            items.addToInventory(sid, sword())
            items.addToInventory(sid, essence())

            val result = enchantSystem.enchant(sid, "sword", "keen_edge", skills())

            assertTrue(result is EnchantResult.Success)
            val success = result as EnchantResult.Success
            assertEquals(6, success.enchantedItem.item.damage) // 4 + 2
            assertTrue(success.enchantedItem.item.displayName.contains("+2 dmg"))
            assertEquals(listOf("keen_edge"), success.enchantedItem.enchantments)
            // Material consumed
            assertTrue(items.inventory(sid).none { it.item.keyword == "essence" })
        }

        @Test
        fun `enchant adds armor bonus`() {
            items.addToInventory(sid, helm())
            items.addToInventory(sid, essence())

            val result = enchantSystem.enchant(sid, "helm", "fortify", skills())

            assertTrue(result is EnchantResult.Success)
            val success = result as EnchantResult.Success
            assertEquals(4, success.enchantedItem.item.armor) // 2 + 2
        }

        @Test
        fun `enchant adds stat bonuses`() {
            items.addToInventory(sid, sword())
            items.addToInventory(sid, essence())
            items.addToInventory(sid, essence())
            items.addToInventory(sid, gem())

            val result = enchantSystem.enchant(sid, "sword", "might", skills(level = 10))

            assertTrue(result is EnchantResult.Success)
            val success = result as EnchantResult.Success
            assertEquals(2, success.enchantedItem.item.stats["STR"])
        }

        @Test
        fun `enchant awards xp`() {
            items.addToInventory(sid, sword())
            items.addToInventory(sid, essence())

            val result = enchantSystem.enchant(sid, "sword", "keen_edge", skills())

            assertTrue(result is EnchantResult.Success)
            assertEquals(30, (result as EnchantResult.Success).xpAwarded)
        }

        @Test
        fun `auto-selects best available enchantment`() {
            items.addToInventory(sid, sword())
            items.addToInventory(sid, essence())

            val result = enchantSystem.enchant(sid, "sword", null, skills())

            assertTrue(result is EnchantResult.Success)
            assertEquals("Keen Edge", (result as EnchantResult.Success).enchantmentName)
        }
    }

    @Nested
    inner class Failures {
        @Test
        fun `item not found`() {
            val result = enchantSystem.enchant(sid, "sword", "keen_edge", skills())
            assertTrue(result is EnchantResult.ItemNotFound)
        }

        @Test
        fun `non-equippable item`() {
            items.addToInventory(sid, essence()) // no slot
            val result = enchantSystem.enchant(sid, "essence", "keen_edge", skills())
            assertTrue(result is EnchantResult.NotEquippable)
        }

        @Test
        fun `already enchanted`() {
            val enchanted = sword().copy(enchantments = listOf("keen_edge"))
            items.addToInventory(sid, enchanted)
            items.addToInventory(sid, essence())

            val result = enchantSystem.enchant(sid, "sword", "fortify", skills())
            assertTrue(result is EnchantResult.AlreadyEnchanted)
        }

        @Test
        fun `same enchantment twice`() {
            val config2 = config.copy(maxEnchantmentsPerItem = 2)
            val sys2 = EnchantSystem(config = config2, items = items, craftingSystem = craftingSystem)
            val enchanted = sword().copy(enchantments = listOf("keen_edge"))
            items.addToInventory(sid, enchanted)
            items.addToInventory(sid, essence())

            val result = sys2.enchant(sid, "sword", "keen_edge", skills())
            assertTrue(result is EnchantResult.AlreadyHasEnchantment)
        }

        @Test
        fun `wrong slot`() {
            items.addToInventory(sid, helm())
            items.addToInventory(sid, essence())

            val result = enchantSystem.enchant(sid, "helm", "keen_edge", skills())
            assertTrue(result is EnchantResult.WrongSlot)
        }

        @Test
        fun `skill too low`() {
            items.addToInventory(sid, sword())
            items.addToInventory(sid, essence())
            items.addToInventory(sid, essence())
            items.addToInventory(sid, gem())

            val result = enchantSystem.enchant(sid, "sword", "might", skills(level = 5))
            assertTrue(result is EnchantResult.SkillTooLow)
        }

        @Test
        fun `missing materials`() {
            items.addToInventory(sid, sword())
            // No essence

            val result = enchantSystem.enchant(sid, "sword", "keen_edge", skills())
            assertTrue(result is EnchantResult.MissingMaterials)
        }

        @Test
        fun `unknown enchantment`() {
            items.addToInventory(sid, sword())

            val result = enchantSystem.enchant(sid, "sword", "nonexistent", skills())
            assertTrue(result is EnchantResult.EnchantmentNotFound)
        }

        @Test
        fun `no enchantment available when none match`() {
            items.addToInventory(sid, sword())
            // No materials at all

            val result = enchantSystem.enchant(sid, "sword", null, skills())
            assertTrue(result is EnchantResult.NoEnchantmentAvailable)
        }
    }

    @Nested
    inner class DisplayName {
        @Test
        fun `suffix shows damage bonus`() {
            val suffix = EnchantSystem.formatEnchantmentSuffix(keenEdge)
            assertEquals("(+2 dmg)", suffix)
        }

        @Test
        fun `suffix shows armor bonus`() {
            val suffix = EnchantSystem.formatEnchantmentSuffix(fortify)
            assertEquals("(+2 armor)", suffix)
        }

        @Test
        fun `suffix shows stat bonuses`() {
            val suffix = EnchantSystem.formatEnchantmentSuffix(might)
            assertEquals("(+2 STR)", suffix)
        }
    }
}
