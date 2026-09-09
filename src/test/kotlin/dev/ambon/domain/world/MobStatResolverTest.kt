package dev.ambon.domain.world

import dev.ambon.config.MobTierAnchorConfig
import dev.ambon.config.MobTierConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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

    private val anchoredTier =
        standardTier.copy(
            levelAnchors =
                mapOf(
                    "1" to MobTierAnchorConfig(hp = 20, minDamage = 2, maxDamage = 4),
                    "5" to MobTierAnchorConfig(hp = 40, minDamage = 4, maxDamage = 8),
                ),
        )

    @Test
    fun `level anchors are exact at anchor levels`() {
        assertEquals(20, resolveMobStats(anchoredTier, level = 1).hp)
        val at5 = resolveMobStats(anchoredTier, level = 5)
        assertEquals(40, at5.hp)
        assertEquals(4, at5.damage.min)
        assertEquals(8, at5.damage.max)
    }

    @Test
    fun `level anchors interpolate geometrically between anchors`() {
        // 20 -> 40 over 4 levels: rate 2^(1/4); level 3 = floor(20 * 2^(2/4)) = 28
        val at3 = resolveMobStats(anchoredTier, level = 3)
        assertEquals(floor(20.0 * 2.0.pow(0.5)).toInt(), at3.hp)
        assertEquals(28, at3.hp)
    }

    @Test
    fun `level anchors extend the last segment above the highest anchor`() {
        // segment 1->5 doubles; level 9 continues that growth: floor(40 * 2^(4/4)) = 80
        assertEquals(80, resolveMobStats(anchoredTier, level = 9).hp)
    }

    @Test
    fun `extrapolated damage bounds never cross`() {
        // min grows 4x per segment, max only 1.25x: above the top anchor min would overtake max.
        val crossing =
            standardTier.copy(
                levelAnchors =
                    mapOf(
                        "1" to MobTierAnchorConfig(hp = 20, minDamage = 2, maxDamage = 8),
                        "2" to MobTierAnchorConfig(hp = 40, minDamage = 8, maxDamage = 10),
                    ),
            )
        val far = resolveMobStats(crossing, level = 6)
        assertEquals(far.damage.min, far.damage.max)
    }

    private val xpAnchoredTier =
        standardTier.copy(
            levelAnchors =
                mapOf(
                    "1" to MobTierAnchorConfig(hp = 20, minDamage = 2, maxDamage = 4, xpReward = 100L),
                    "5" to MobTierAnchorConfig(hp = 40, minDamage = 4, maxDamage = 8, xpReward = 400L),
                ),
        )

    @Test
    fun `anchored xp is exact at anchor levels and interpolates between them`() {
        assertEquals(100L, resolveMobStats(xpAnchoredTier, level = 1).xpReward)
        assertEquals(400L, resolveMobStats(xpAnchoredTier, level = 5).xpReward)
        // 100 -> 400 over 4 levels: rate 4^(1/4); level 3 = floor(100 * 4^(2/4)) = 200
        assertEquals(200L, resolveMobStats(xpAnchoredTier, level = 3).xpReward)
    }

    @Test
    fun `anchored xp extends the last segment above the highest anchor and holds below the lowest`() {
        // 100 -> 400 doubles every two levels: level 7 = 400 * 2 = 800
        assertEquals(800L, resolveMobStats(xpAnchoredTier, level = 7).xpReward)
        assertEquals(100L, resolveMobStats(xpAnchoredTier, level = 1).xpReward)
    }

    @Test
    fun `an authored xp override still wins over the anchored curve`() {
        val overridden = resolveMobStats(xpAnchoredTier, level = 3, overrides = MobStatOverrides(xpReward = 7L))
        assertEquals(7L, overridden.xpReward)
    }

    @Test
    fun `a saturating xp curve is expressible where a geometric rate is not`() {
        // The D-28 pacing target: fourfold from 1 to 5, then flat. No base x rate^(L-1) can do both.
        val saturating =
            standardTier.copy(
                levelAnchors =
                    mapOf(
                        "1" to MobTierAnchorConfig(hp = 20, minDamage = 2, maxDamage = 4, xpReward = 86L),
                        "5" to MobTierAnchorConfig(hp = 40, minDamage = 4, maxDamage = 8, xpReward = 374L),
                        "10" to MobTierAnchorConfig(hp = 80, minDamage = 8, maxDamage = 16, xpReward = 554L),
                        "30" to MobTierAnchorConfig(hp = 300, minDamage = 30, maxDamage = 60, xpReward = 857L),
                    ),
            )
        assertEquals(86L, resolveMobStats(saturating, level = 1).xpReward)
        assertEquals(374L, resolveMobStats(saturating, level = 5).xpReward)
        assertEquals(857L, resolveMobStats(saturating, level = 30).xpReward)
        // Monotone and inside the anchors between them: level 20 sits between the 10 and 30 anchors.
        val at20 = resolveMobStats(saturating, level = 20).xpReward
        assertTrue(at20 in 554L..857L, "expected the level-20 award inside its segment, got $at20")
    }

    @Test
    fun `level anchors leave gold and armor on the formula, and xp too when no anchor declares it`() {
        val at5 = resolveMobStats(anchoredTier, level = 5)
        assertEquals(floor(30.0 * 1.08.pow(4)).toLong(), at5.xpReward)
        assertEquals(1, at5.armor)
        val overridden = resolveMobStats(anchoredTier, level = 5, overrides = MobStatOverrides(hp = 999))
        assertEquals(999, overridden.hp)
    }
}
