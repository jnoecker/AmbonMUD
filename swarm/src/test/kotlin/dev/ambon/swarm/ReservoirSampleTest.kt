package dev.ambon.swarm

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReservoirSampleTest {
    @Test
    fun `snapshot returns all values when below capacity`() {
        val sample = ReservoirSample(maxSize = 100)
        repeat(10) { sample.add(it.toLong()) }
        val result = sample.snapshot()
        assertEquals(10, result.size)
        assertEquals((0L..9L).toList(), result)
    }

    @Test
    fun `snapshot returns exactly maxSize values when overfilled`() {
        val sample = ReservoirSample(maxSize = 50)
        repeat(1000) { sample.add(it.toLong()) }
        val result = sample.snapshot()
        assertEquals(50, result.size)
    }

    @Test
    fun `empty reservoir returns empty snapshot`() {
        val sample = ReservoirSample()
        assertTrue(sample.snapshot().isEmpty())
    }

    @Test
    fun `single element is retained`() {
        val sample = ReservoirSample()
        sample.add(42L)
        assertEquals(listOf(42L), sample.snapshot())
    }

    @Test
    fun `reservoir samples from full range`() {
        val sample = ReservoirSample(maxSize = 100)
        repeat(10_000) { sample.add(it.toLong()) }
        val result = sample.snapshot()
        assertEquals(100, result.size)
        // With 10k values sampled into 100 slots, we expect values spread
        // across the range, not just the first 100
        val maxVal = result.max()
        assertTrue(maxVal > 100, "Expected reservoir to contain values beyond first 100, got max=$maxVal")
    }
}
