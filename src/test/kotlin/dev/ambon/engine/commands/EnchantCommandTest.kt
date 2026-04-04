package dev.ambon.engine.commands

import dev.ambon.config.CraftingConfig
import dev.ambon.config.EnchantingConfig
import dev.ambon.config.EnchantmentDefinition
import dev.ambon.config.MaterialConfigEntry
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.items.ItemSlot
import dev.ambon.engine.crafting.CraftingRegistry
import dev.ambon.engine.crafting.CraftingSystem
import dev.ambon.engine.crafting.EnchantSystem
import dev.ambon.engine.crafting.GatheringRegistry
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.test.CommandRouterHarness
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EnchantCommandTest {
    // ── Parser tests ─────────────────────────────────────────────────────

    @Nested
    inner class Parser {
        @Test
        fun `enchant with item parses to Enchant`() {
            val result = CommandParser.parse("enchant sword")
            assertTrue(result is Command.Enchant)
            assertEquals("sword", (result as Command.Enchant).itemKeyword)
            assertEquals(null, result.enchantmentId)
        }

        @Test
        fun `enchant with item and enchantment parses to Enchant`() {
            val result = CommandParser.parse("enchant sword keen_edge")
            assertTrue(result is Command.Enchant)
            assertEquals("sword", (result as Command.Enchant).itemKeyword)
            assertEquals("keen_edge", result.enchantmentId)
        }

        @Test
        fun `enchant alone returns Invalid`() {
            val result = CommandParser.parse("enchant")
            assertTrue(result is Command.Invalid)
        }

        @Test
        fun `enchantments parses to Enchantments`() {
            assertEquals(Command.Enchantments, CommandParser.parse("enchantments"))
        }
    }

    // ── Router tests ─────────────────────────────────────────────────────

    private val enchantConfig = EnchantingConfig(
        maxEnchantmentsPerItem = 1,
        definitions = mapOf(
            "keen_edge" to EnchantmentDefinition(
                displayName = "Keen Edge",
                skill = "enchanting",
                skillRequired = 1,
                materials = listOf(MaterialConfigEntry(itemId = "essence", quantity = 1)),
                damageBonus = 2,
                targetSlots = listOf("weapon"),
                xpReward = 30,
            ),
        ),
    )

    private fun harness(): Triple<CommandRouterHarness, EnchantSystem, ItemRegistry> {
        val items = ItemRegistry()
        val craftingSystem = CraftingSystem(
            gatheringRegistry = GatheringRegistry(),
            craftingRegistry = CraftingRegistry(),
            config = CraftingConfig(),
            clock = java.time.Clock.systemUTC(),
        )
        val enchantSystem = EnchantSystem(
            config = enchantConfig,
            items = items,
            craftingSystem = craftingSystem,
        )
        val h = CommandRouterHarness.create(
            items = items,
            enchantSystem = enchantSystem,
        )
        return Triple(h, enchantSystem, items)
    }

    @Nested
    inner class Router {
        @Test
        fun `enchant succeeds with materials and skill`() = runTest {
            val (h, _, items) = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            val player = h.players.get(sid)!!
            player.craftingSkills["enchanting"] =
                dev.ambon.domain.crafting.CraftingSkillState(level = 1)

            items.addToInventory(
                sid,
                ItemInstance(
                    id = ItemId("sword_1"),
                    item = Item(keyword = "sword", displayName = "a copper sword", slot = ItemSlot.WEAPON, damage = 4),
                ),
            )
            items.addToInventory(
                sid,
                ItemInstance(id = ItemId("essence"), item = Item(keyword = "essence", displayName = "a wisp of arcane essence")),
            )
            h.drain()

            h.router.handle(sid, Command.Enchant("sword", "keen_edge"))

            val infos = h.drain().filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
            assertTrue(infos.any { it.contains("enchant", ignoreCase = true) && it.contains("Keen Edge") }, "got=$infos")
        }

        @Test
        fun `enchant with missing item shows error`() = runTest {
            val (h, _, _) = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            h.drain()

            h.router.handle(sid, Command.Enchant("sword", "keen_edge"))

            val errors = h.drain().filterIsInstance<OutboundEvent.SendError>().map { it.text }
            assertTrue(errors.any { it.contains("don't have", ignoreCase = true) }, "got=$errors")
        }

        @Test
        fun `enchantments lists available enchantments`() = runTest {
            val (h, _, _) = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            h.drain()

            h.router.handle(sid, Command.Enchantments)

            val infos = h.drain().filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
            assertTrue(infos.any { it.contains("Available Enchantments") }, "got=$infos")
            assertTrue(infos.any { it.contains("keen_edge") }, "got=$infos")
        }
    }
}
