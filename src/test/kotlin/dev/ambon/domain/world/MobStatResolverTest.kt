package dev.ambon.domain.world

import dev.ambon.config.MobTierConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MobStatResolverTest {
    private val standardTier = MobTierConfig(
        baseHp = 20,
        hpPerLevel = 5,
        baseMinDamage = 2,
        baseMaxDamage = 4,
        damagePerLevel = 2,
        baseArmor = 1,
        baseXpReward = 30,
        xpRewardPerLevel = 10,
        baseGoldMin = 3,
        baseGoldMax = 8,
        goldPerLevel = 2,
    )

    @Test
    fun `resolves tier stats at level 1`() {
        val stats = resolveMobStats(standardTier, level = 1)
        assertEquals(20, stats.hp)
        assertEquals(2, stats.damage.min)
        assertEquals(4, stats.damage.max)
        assertEquals(1, stats.armor)
        assertEquals(30L, stats.xpReward)
        assertEquals(3L, stats.goldMin)
        assertEquals(8L, stats.goldMax)
    }

    @Test
    fun `scales with level using (level - 1) steps`() {
        val stats = resolveMobStats(standardTier, level = 5)
        // 4 steps of growth
        assertEquals(20 + 4 * 5, stats.hp)
        assertEquals(2 + 4 * 2, stats.damage.min)
        assertEquals(4 + 4 * 2, stats.damage.max)
        assertEquals(30L + 4L * 10, stats.xpReward)
    }

    @Test
    fun `overrides win over tier math`() {
        val overrides = MobStatOverrides(hp = 999, xpReward = 5L)
        val stats = resolveMobStats(standardTier, level = 5, overrides)
        assertEquals(999, stats.hp)
        assertEquals(5L, stats.xpReward)
        // Non-overridden fields still scale with level
        assertEquals(2 + 4 * 2, stats.damage.min)
    }

    @Test
    fun `level is clamped to 1 floor`() {
        val stats = resolveMobStats(standardTier, level = 0)
        assertEquals(20, stats.hp)
    }
}
