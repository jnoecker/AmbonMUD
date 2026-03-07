package dev.ambon.engine

import dev.ambon.domain.StatMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StatMapTest {
    @Test
    fun `get returns 0 for absent stat`() {
        val m = StatMap.EMPTY
        assertEquals(0, m["STR"])
        assertEquals(0, m["LCK"])
    }

    @Test
    fun `get is case-insensitive`() {
        val m = StatMap.of("str" to 5)
        assertEquals(5, m["STR"])
        assertEquals(5, m["str"])
        assertEquals(5, m["Str"])
    }

    @Test
    fun `plus combines values additively`() {
        val a = StatMap.of("STR" to 3, "DEX" to 1)
        val b = StatMap.of("STR" to 2, "CON" to 4)
        val result = a + b
        assertEquals(5, result["STR"])
        assertEquals(1, result["DEX"])
        assertEquals(4, result["CON"])
    }

    @Test
    fun `plus with EMPTY returns same map`() {
        val a = StatMap.of("STR" to 3)
        assertEquals(a, a + StatMap.EMPTY)
        assertEquals(a, StatMap.EMPTY + a)
    }

    @Test
    fun `isZero returns true for empty map`() {
        assertTrue(StatMap.EMPTY.isZero())
    }

    @Test
    fun `isZero returns false when any value is non-zero`() {
        assertFalse(StatMap.of("STR" to 1).isZero())
    }

    @Test
    fun `nonZero filters out zero values`() {
        val m = StatMap(mapOf("STR" to 3, "DEX" to 0, "CON" to -1))
        assertEquals(mapOf("STR" to 3, "CON" to -1), m.nonZero())
    }

    @Test
    fun `with returns updated map`() {
        val m = StatMap.of("STR" to 5)
        val updated = m.with("STR", 8)
        assertEquals(8, updated["STR"])
        // original unchanged
        assertEquals(5, m["STR"])
    }

    @Test
    fun `plus normalizes keys from other map`() {
        // Direct-constructor StatMap with lowercase keys — plus must normalize them
        val a = StatMap.of("STR" to 3)
        val b = StatMap(mapOf("str" to 2))
        val result = a + b
        // Should see STR=5, not both STR=3 and str=2
        assertEquals(5, result["STR"])
        assertEquals(1, result.values.size)
    }
}
