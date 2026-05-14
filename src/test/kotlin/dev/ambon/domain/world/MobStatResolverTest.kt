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

    @Test
    fun `hpMult scales tier-derived hp`() {
        val overrides = MobStatOverrides(hpMult = 1.5)
        val stats = resolveMobStats(standardTier, level = 5, overrides)
        // Baseline hp at level 5: 20 + 4*5 = 40. With 1.5x: 60.
        assertEquals(60, stats.hp)
    }

    @Test
    fun `dmgMult scales both min and max damage`() {
        val overrides = MobStatOverrides(dmgMult = 2.0)
        val stats = resolveMobStats(standardTier, level = 1, overrides)
        assertEquals(4, stats.damage.min) // 2 * 2
        assertEquals(8, stats.damage.max) // 4 * 2
    }

    @Test
    fun `xpMult and goldMult scale rewards`() {
        val overrides = MobStatOverrides(xpMult = 0.5, goldMult = 2.0)
        val stats = resolveMobStats(standardTier, level = 1, overrides)
        assertEquals(15L, stats.xpReward) // 30 * 0.5
        assertEquals(6L, stats.goldMin) // 3 * 2
        assertEquals(16L, stats.goldMax) // 8 * 2
    }

    @Test
    fun `absolute override beats multiplier`() {
        val overrides = MobStatOverrides(hp = 100, hpMult = 5.0)
        val stats = resolveMobStats(standardTier, level = 1, overrides)
        // hp override wins, hpMult is ignored for the hp field
        assertEquals(100, stats.hp)
    }

    @Test
    fun `fractional multipliers round half-away-from-zero rather than truncating`() {
        // baseMaxDamage=4 * 1.15 = 4.6 → should round to 5, not truncate to 4
        val overrides = MobStatOverrides(dmgMult = 1.15)
        val stats = resolveMobStats(standardTier, level = 1, overrides)
        // baseMinDamage=2 * 1.15 = 2.3 → 2
        assertEquals(2, stats.damage.min)
        assertEquals(5, stats.damage.max)
    }

    @Test
    fun `hpMult clamps to at least 1`() {
        val overrides = MobStatOverrides(hpMult = 0.01)
        val stats = resolveMobStats(standardTier, level = 1, overrides)
        // 20 * 0.01 = 0.2 → clamped to 1
        assertEquals(1, stats.hp)
    }
}
