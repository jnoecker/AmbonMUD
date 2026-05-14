package dev.ambon.domain.world

import dev.ambon.config.ItemBudgetConfig
import dev.ambon.domain.world.data.ItemFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ItemBudgetTest {
    private val config = ItemBudgetConfig()

    @Test
    fun `item without level or rarity is skipped`() {
        val item = ItemFile(
            displayName = "a sword",
            slot = "weapon",
            damage = 4,
        )
        assertNull(evaluateItemBudget("sword", item, config))
    }

    @Test
    fun `item with disabled budget config is skipped`() {
        val item = ItemFile(
            displayName = "a sword",
            slot = "weapon",
            damage = 4,
            level = 1,
        )
        val disabled = config.copy(enabled = false)
        assertNull(evaluateItemBudget("sword", item, disabled))
    }

    @Test
    fun `level-1 common weapon with damage 4 fits within slot budget`() {
        val item = ItemFile(
            displayName = "iron sword",
            slot = "weapon",
            damage = 4,
            level = 1,
            rarity = "common",
        )
        val eval = evaluateItemBudget("iron_sword", item, config)!!
        // Budget: (6 + 1*2) * 1.0 = 8. Spent: 4*5 = 20. Over budget.
        assertEquals(20.0, eval.spent, 0.001)
        assertEquals(8.0, eval.budget, 0.001)
        assertTrue(eval.overBudget)
    }

    @Test
    fun `level-5 rare weapon with damage 4 is within budget`() {
        val item = ItemFile(
            displayName = "iron sword",
            slot = "weapon",
            damage = 4,
            level = 5,
            rarity = "rare",
        )
        val eval = evaluateItemBudget("iron_sword", item, config)!!
        // Budget: (6 + 5*2) * 1.5 = 24. Spent: 4*5 = 20.
        assertEquals(20.0, eval.spent, 0.001)
        assertEquals(24.0, eval.budget, 0.001)
        assertFalse(eval.overBudget)
    }

    @Test
    fun `breakdown lists all non-zero contributions`() {
        val item = ItemFile(
            displayName = "blessed pendant",
            slot = "neck",
            armor = 2,
            stats = mapOf("CON" to 3, "WIS" to 1),
            level = 10,
            rarity = "epic",
        )
        val eval = evaluateItemBudget("blessed", item, config)!!
        // armor 2*2 = 4, stats 4*1 = 4, total 8
        assertEquals(8.0, eval.spent, 0.001)
        // breakdown should mention both armor and stats
        assertTrue(eval.breakdown.any { it.contains("armor") })
        assertTrue(eval.breakdown.any { it.contains("stats") })
    }

    @Test
    fun `unknown rarity raises an error`() {
        val item = ItemFile(
            displayName = "weird sword",
            slot = "weapon",
            damage = 4,
            level = 1,
            rarity = "mythic",
        )
        try {
            evaluateItemBudget("weird", item, config)
            error("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("mythic"))
        }
    }

    @Test
    fun `tolerance prevents borderline overage from being flagged`() {
        val item = ItemFile(
            displayName = "borderline weapon",
            slot = "weapon",
            damage = 1,
            stats = mapOf("STR" to 4),
            level = 1,
            rarity = "common",
        )
        // Budget: (6 + 2) * 1.0 = 8. Spent: 1*5 + 4*1 = 9. 9/8 = 1.125, default tolerance 5%.
        val eval = evaluateItemBudget("borderline", item, config)!!
        assertTrue(eval.overBudget) // 12.5% over, exceeds 5% tolerance

        val lenient = config.copy(tolerance = 0.20)
        val eval2 = evaluateItemBudget("borderline", item, lenient)!!
        assertFalse(eval2.overBudget) // 12.5% < 20%
    }
}
