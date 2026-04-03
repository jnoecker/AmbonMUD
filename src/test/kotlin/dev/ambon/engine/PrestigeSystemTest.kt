package dev.ambon.engine

import dev.ambon.config.PrestigeConfig
import dev.ambon.config.PrestigePerkConfig
import dev.ambon.config.ProgressionConfig
import dev.ambon.domain.StatMap
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrestigeSystemTest {
    private val testPerks = mapOf(
        1 to PrestigePerkConfig(type = "STAT_BONUS", stat = "STR", amount = 1, description = "+1 STR"),
        2 to PrestigePerkConfig(type = "SKILL_POINT", amount = 2, description = "+2 Skill Points"),
        3 to PrestigePerkConfig(type = "TITLE", title = "the Reborn", description = "Unlock title"),
        4 to PrestigePerkConfig(type = "MAX_HP", amount = 10, description = "+10 HP"),
        5 to PrestigePerkConfig(type = "MAX_MANA", amount = 15, description = "+15 Mana"),
        6 to PrestigePerkConfig(type = "STAT_BONUS", stat = "ALL", amount = 1, description = "+1 All Stats"),
    )

    private val config = PrestigeConfig(
        enabled = true,
        xpCostBase = 1000,
        xpCostMultiplier = 2.0,
        maxRank = 6,
        perks = testPerks,
    )

    private val progression = PlayerProgression(
        config = ProgressionConfig(maxLevel = 10),
    )

    private fun system() = PrestigeSystem(config, progression)

    private fun maxLevelPlayer(xpTotal: Long = 100_000L): PlayerState =
        PlayerState(
            sessionId = SessionId(1),
            name = "TestPlayer",
            roomId = RoomId("test:room"),
            level = 10,
            xpTotal = xpTotal,
            baseMaxHp = 50,
            hp = 50,
            maxHp = 50,
            baseMana = 30,
            mana = 30,
            maxMana = 30,
            stats = StatMap.of(
                "STR" to 10,
                "DEX" to 10,
                "CON" to 10,
                "INT" to 10,
                "WIS" to 10,
                "CHA" to 10,
            ),
        )

    // ---------- XP cost calculation ----------

    @Test
    fun `xpCostForNextRank uses base times multiplier to the power of current rank`() {
        val sys = system()
        assertEquals(1000L, sys.xpCostForNextRank(0)) // 1000 * 2^0 = 1000
        assertEquals(2000L, sys.xpCostForNextRank(1)) // 1000 * 2^1 = 2000
        assertEquals(4000L, sys.xpCostForNextRank(2)) // 1000 * 2^2 = 4000
        assertEquals(8000L, sys.xpCostForNextRank(3)) // 1000 * 2^3 = 8000
    }

    // ---------- canPrestige checks ----------

    @Test
    fun `canPrestige returns false when not max level`() {
        val sys = system()
        val player = maxLevelPlayer().copy(level = 5)
        assertFalse(sys.canPrestige(player, 10))
    }

    @Test
    fun `canPrestige returns false when not enough XP`() {
        val sys = system()
        // Player at max level but with just enough XP to be level 10 (no surplus)
        val levelXp = progression.totalXpForLevel(10)
        val player = maxLevelPlayer(xpTotal = levelXp)
        assertFalse(sys.canPrestige(player, 10))
    }

    @Test
    fun `canPrestige returns false when already at max rank`() {
        val sys = system()
        val player = maxLevelPlayer(xpTotal = 10_000_000L)
        player.prestigeLevel = 6 // max rank
        assertFalse(sys.canPrestige(player, 10))
    }

    @Test
    fun `canPrestige returns false when system is disabled`() {
        val disabledConfig = config.copy(enabled = false)
        val sys = PrestigeSystem(disabledConfig, progression)
        val player = maxLevelPlayer(xpTotal = 10_000_000L)
        assertFalse(sys.canPrestige(player, 10))
    }

    @Test
    fun `canPrestige returns true with enough surplus XP at max level`() {
        val sys = system()
        val levelXp = progression.totalXpForLevel(10)
        val player = maxLevelPlayer(xpTotal = levelXp + 1000) // Exactly enough for rank 1
        assertTrue(sys.canPrestige(player, 10))
    }

    // ---------- prestige() behavior ----------

    @Test
    fun `prestige deducts XP increments rank and returns correct perk`() {
        val sys = system()
        val levelXp = progression.totalXpForLevel(10)
        val player = maxLevelPlayer(xpTotal = levelXp + 5000)

        val result = sys.prestige(player, 10)

        assertNotNull(result)
        assertEquals(1, result!!.newRank)
        assertEquals(1000L, result.xpSpent)
        assertEquals("STAT_BONUS", result.perk?.type)
        assertEquals(1, player.prestigeLevel)
        assertEquals(1000L, player.prestigeXpSpent)
    }

    @Test
    fun `prestige returns null when requirements not met`() {
        val sys = system()
        val player = maxLevelPlayer().copy(level = 5)
        assertNull(sys.prestige(player, 10))
    }

    @Test
    fun `prestige applies STAT_BONUS perk`() {
        val sys = system()
        val levelXp = progression.totalXpForLevel(10)
        val player = maxLevelPlayer(xpTotal = levelXp + 5000)
        val strBefore = player.stats["STR"]

        sys.prestige(player, 10)

        assertEquals(strBefore + 1, player.stats["STR"])
    }

    @Test
    fun `prestige applies MAX_HP perk`() {
        val sys = system()
        val levelXp = progression.totalXpForLevel(10)
        // Need enough XP for ranks 1-4
        val totalCost = (0..3).sumOf { sys.xpCostForNextRank(it) }
        val player = maxLevelPlayer(xpTotal = levelXp + totalCost + 1000)

        // Prestige 3 times to get to rank 4 (MAX_HP perk)
        sys.prestige(player, 10) // rank 1: STAT_BONUS
        sys.prestige(player, 10) // rank 2: SKILL_POINT
        sys.prestige(player, 10) // rank 3: TITLE

        val hpBefore = player.maxHp
        sys.prestige(player, 10) // rank 4: MAX_HP +10

        assertEquals(hpBefore + 10, player.maxHp)
        assertEquals(4, player.prestigeLevel)
    }

    @Test
    fun `prestige applies MAX_MANA perk`() {
        val sys = system()
        val levelXp = progression.totalXpForLevel(10)
        val totalCost = (0..4).sumOf { sys.xpCostForNextRank(it) }
        val player = maxLevelPlayer(xpTotal = levelXp + totalCost + 1000)

        repeat(4) { sys.prestige(player, 10) }
        val manaBefore = player.maxMana
        sys.prestige(player, 10) // rank 5: MAX_MANA +15

        assertEquals(manaBefore + 15, player.maxMana)
    }

    @Test
    fun `prestige applies ALL stat bonus`() {
        val sys = system()
        val levelXp = progression.totalXpForLevel(10)
        val totalCost = (0..5).sumOf { sys.xpCostForNextRank(it) }
        val player = maxLevelPlayer(xpTotal = levelXp + totalCost + 1000)

        repeat(5) { sys.prestige(player, 10) }
        val strBefore = player.stats["STR"]
        val dexBefore = player.stats["DEX"]
        sys.prestige(player, 10) // rank 6: +1 ALL

        // STR already got +1 at rank 1, so total is base + 1 (rank1) + 1 (rank6)
        assertEquals(strBefore + 1, player.stats["STR"])
        assertEquals(dexBefore + 1, player.stats["DEX"])
    }

    @Test
    fun `consecutive prestiges deduct escalating XP`() {
        val sys = system()
        val levelXp = progression.totalXpForLevel(10)
        val player = maxLevelPlayer(xpTotal = levelXp + 100_000)

        val r1 = sys.prestige(player, 10)!!
        val r2 = sys.prestige(player, 10)!!

        assertEquals(1000L, r1.xpSpent)
        assertEquals(2000L, r2.xpSpent)
        assertEquals(3000L, player.prestigeXpSpent)
    }

    // ---------- Accumulated bonuses ----------

    @Test
    fun `accumulatedStatBonuses sums correctly across multiple ranks`() {
        val sys = system()
        val bonuses = sys.accumulatedStatBonuses(6)

        // Rank 1: STR +1, Rank 6: ALL +1 → STR total = 2, DEX/CON/INT/WIS/CHA = 1
        assertEquals(2, bonuses["STR"])
        assertEquals(1, bonuses["DEX"])
        assertEquals(1, bonuses["CON"])
        assertEquals(1, bonuses["INT"])
        assertEquals(1, bonuses["WIS"])
        assertEquals(1, bonuses["CHA"])
    }

    @Test
    fun `accumulatedMaxHpBonus returns sum of MAX_HP perks`() {
        val sys = system()
        assertEquals(0, sys.accumulatedMaxHpBonus(3)) // Ranks 1-3 have no MAX_HP
        assertEquals(10, sys.accumulatedMaxHpBonus(4)) // Rank 4 has +10 HP
    }

    @Test
    fun `accumulatedMaxManaBonus returns sum of MAX_MANA perks`() {
        val sys = system()
        assertEquals(0, sys.accumulatedMaxManaBonus(4))
        assertEquals(15, sys.accumulatedMaxManaBonus(5)) // Rank 5 has +15 Mana
    }

    @Test
    fun `accumulatedSkillPointBonus returns sum of SKILL_POINT perks`() {
        val sys = system()
        assertEquals(0, sys.accumulatedSkillPointBonus(1))
        assertEquals(2, sys.accumulatedSkillPointBonus(2)) // Rank 2 has +2
    }

    @Test
    fun `unlockedTitles returns titles earned up to rank`() {
        val sys = system()
        assertEquals(emptyList<String>(), sys.unlockedTitles(2))
        assertEquals(listOf("the Reborn"), sys.unlockedTitles(3))
    }

    // ---------- Available XP ----------

    @Test
    fun `availableXp subtracts level cost and prestige spent`() {
        val sys = system()
        val levelXp = progression.totalXpForLevel(10)
        val player = maxLevelPlayer(xpTotal = levelXp + 5000)
        player.prestigeXpSpent = 1000

        assertEquals(4000L, sys.availableXp(player))
    }
}
