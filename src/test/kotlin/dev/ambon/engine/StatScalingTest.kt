package dev.ambon.engine

import dev.ambon.config.StatBindingsConfig
import dev.ambon.domain.StatMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

class StatScalingTest {
    private val additive = StatBindingsConfig(meleeStatMultiplier = 0.25, spellStatMultiplier = 0.25)
    private val multiplicative =
        StatBindingsConfig(
            statScalingMode = "multiplicative",
            meleePercentPerPoint = 0.0075,
            spellPercentPerPoint = 0.0075,
            healPercentPerPoint = 0.0075,
            shieldPercentPerPoint = 0.0075,
            dodgePerPoint = 0.5,
            maxDodgePercent = 30,
            meleeVarianceMin = 1.0,
            meleeVarianceMax = 1.0,
        )

    @Test
    fun `additive mode reproduces the legacy stat bonus`() {
        // 20 points above base at 0.25 per point add 5 to the anchor
        assertEquals(45.0, additive.statAdjustedCore(40.0, PlayerState.BASE_STAT + 20, 0.25, 0.0075), 1e-9)
        assertEquals(40.0, additive.statAdjustedCore(40.0, PlayerState.BASE_STAT, 0.25, 0.0075), 1e-9)
    }

    @Test
    fun `multiplicative mode scales the anchor by percent per point and floors at zero`() {
        assertEquals(40.0 * 1.15, multiplicative.statAdjustedCore(40.0, PlayerState.BASE_STAT + 20, 0.25, 0.0075), 1e-9)
        assertEquals(40.0, multiplicative.statAdjustedCore(40.0, PlayerState.BASE_STAT, 0.25, 0.0075), 1e-9)
        assertEquals(0.0, multiplicative.statAdjustedCore(40.0, PlayerState.BASE_STAT - 200, 0.25, 0.0075), 1e-9)
    }

    @Test
    fun `melee swing honours the multiplicative mode`() {
        val stats = StatMap.of("STR" to PlayerState.BASE_STAT + 20)
        val swing = computePlayerMeleeSwing(multiplicative, level = 1, stats = stats, equipAttack = 9, enemyArmor = 0, rng = Random(1))
        // attackPower 1 + 9 = 10, x (1 + 20 x 0.0075) = 11.5 -> rounds to 12 with variance fixed at 1.0
        assertEquals(12, swing.raw)
    }

    @Test
    fun `dodge percent is fractional per point and capped`() {
        assertEquals(10.0, multiplicative.dodgePercent(StatMap.of("DEX" to PlayerState.BASE_STAT + 20)), 1e-9)
        assertEquals(30.0, multiplicative.dodgePercent(StatMap.of("DEX" to PlayerState.BASE_STAT + 90)), 1e-9)
        assertEquals(0.0, multiplicative.dodgePercent(StatMap.of("DEX" to PlayerState.BASE_STAT - 5)), 1e-9)
        // the engine default is unchanged: 2 points per DEX point, cap 30
        assertEquals(30.0, StatBindingsConfig().dodgePercent(StatMap.of("DEX" to PlayerState.BASE_STAT + 15)), 1e-9)
    }

    @Test
    fun `dodge roll never fires at zero and always fires at one hundred`() {
        repeat(20) { seed ->
            assertFalse(rollDodge(0.0, Random(seed.toLong())))
            assertTrue(rollDodge(100.0, Random(seed.toLong())))
        }
    }
}
