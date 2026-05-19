package dev.ambon.engine

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class DemoNameGeneratorTest {
    @Test
    fun `generated name starts with a curated fantasy first name`() = runTest {
        val gen = DemoNameGenerator(Random(0))
        val name = gen.generate(isTaken = { false })
        // Strip trailing digits — what remains must be a curated entry.
        val stem = name.trimEnd { it.isDigit() }
        assertTrue(
            stem in DemoNameGenerator.FANTASY_NAMES,
            "stem '$stem' (from '$name') should be one of the curated fantasy names",
        )
    }

    @Test
    fun `generated name fits within the 16-char player name limit`() = runTest {
        val gen = DemoNameGenerator(Random(0))
        repeat(50) {
            val name = gen.generate(isTaken = { false })
            assertTrue(name.length <= 16, "name '$name' must be <= 16 chars")
        }
    }

    @Test
    fun `generator retries when a candidate is taken`() = runTest {
        val taken = mutableSetOf<String>()
        val gen = DemoNameGenerator(Random(42))
        val first = gen.generate(isTaken = { it in taken })
        taken.add(first)
        val second = gen.generate(isTaken = { it in taken })
        assertNotEquals(first, second, "generator should not return a taken name")
    }

    @Test
    fun `generator falls back when all short candidates are taken`() = runTest {
        // Reject every 2-digit suffix; the fallback to 4-digit suffix should succeed.
        // The fallback path is verified by observing that the returned name has a
        // suffix longer than 2 digits — which only happens after the first pass
        // is exhausted and the longer-suffix fallback engages.
        val gen = DemoNameGenerator(Random(0))
        val name = gen.generate(
            isTaken = { candidate ->
                val trailingDigits = candidate.takeLastWhile { it.isDigit() }.length
                trailingDigits <= 2
            },
            maxAttempts = 5,
        )
        assertTrue(
            name.takeLastWhile { it.isDigit() }.length >= 3,
            "fallback should use a longer suffix, got '$name'",
        )
    }

    @Test
    fun `generator runs without throwing across many invocations`() = runTest {
        val gen = DemoNameGenerator(Random(123))
        val produced = (1..200).map { gen.generate(isTaken = { false }) }
        // Just exercising the loop — we expect some variety with random seeds.
        assertTrue(produced.distinct().size > 1)
        assertEquals(200, produced.size)
    }
}
