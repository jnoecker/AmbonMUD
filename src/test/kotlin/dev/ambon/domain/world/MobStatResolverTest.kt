package dev.ambon.domain.world

import dev.ambon.config.MobTierConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.math.floor
import kotlin.math.pow

class MobStatResolverTest {
    private val standardTier = MobTierConfig(
        baseHp = 20,
        hpScalingRate = 1.10,
        baseMinDamage = 2,
        baseMaxDamage = 4,
        damageScalingRate = 1.05,
        baseArmor = 1,
        baseXpReward = 30,
        xpScalingRate = 1.08,
        baseGoldMin = 3,
        baseGoldMax = 8,
        goldScalingRate = 1.15,
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
    fun `scales with level using floor(base × rate^(level - 1))`() {
        val stats = resolveMobStats(standardTier, level = 5)
        val steps = 4
        assertEquals(floor(20.0 * 1.10.pow(steps)).toInt(), stats.hp)
        assertEquals(floor(2.0 * 1.05.pow(steps)).toInt(), stats.damage.min)
        assertEquals(floor(4.0 * 1.05.pow(steps)).toInt(), stats.damage.max)
        assertEquals(floor(30.0 * 1.08.pow(steps)).toLong(), stats.xpReward)
        assertEquals(floor(3.0 * 1.15.pow(steps)).toLong(), stats.goldMin)
        assertEquals(floor(8.0 * 1.15.pow(steps)).toLong(), stats.goldMax)
    }

    @Test
    fun `L30 standard mob HP matches floor(base × rate^29) reference math`() {
        val tier = MobTierConfig(
            baseHp = 150,
            hpScalingRate = 1.10,
            baseMinDamage = 1,
            baseMaxDamage = 1,
            damageScalingRate = 1.0,
            baseArmor = 0,
            baseXpReward = 0,
            xpScalingRate = 1.0,
            baseGoldMin = 0,
            baseGoldMax = 0,
            goldScalingRate = 1.0,
        )
        val stats = resolveMobStats(tier, level = 30)
        assertEquals(floor(150.0 * 1.10.pow(29)).toInt(), stats.hp)
    }

    @Test
    fun `overrides win over tier math`() {
        val overrides = MobStatOverrides(hp = 999, xpReward = 5L)
        val stats = resolveMobStats(standardTier, level = 5, overrides)
        assertEquals(999, stats.hp)
        assertEquals(5L, stats.xpReward)
        assertEquals(floor(2.0 * 1.05.pow(4)).toInt(), stats.damage.min)
    }

    @Test
    fun `level is clamped to 1 floor`() {
        val stats = resolveMobStats(standardTier, level = 0)
        assertEquals(20, stats.hp)
    }
}
