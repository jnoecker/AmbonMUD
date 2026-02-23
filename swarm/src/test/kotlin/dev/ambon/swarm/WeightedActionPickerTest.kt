package dev.ambon.swarm

import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeightedActionPickerTest {
    @Test
    fun `single non-zero weight always selected`() {
        val weights = BehaviorWeights(idle = 0, loginChurn = 0, movement = 100, chat = 0, autoCombat = 0)
        val random = Random(1)
        repeat(100) {
            assertEquals(BotAction.MOVEMENT, WeightedActionPicker.pick(weights, random))
        }
    }

    @Test
    fun `deterministic sequence with fixed seed`() {
        val weights = BehaviorWeights()
        val first = generate(weights, 10, seed = 123)
        val second = generate(weights, 10, seed = 123)
        assertEquals(first, second)
        assertTrue(first.isNotEmpty())
    }

    private fun generate(
        weights: BehaviorWeights,
        count: Int,
        seed: Int,
    ): List<BotAction> {
        val random = Random(seed)
        return (1..count).map { WeightedActionPicker.pick(weights, random) }
    }
}
