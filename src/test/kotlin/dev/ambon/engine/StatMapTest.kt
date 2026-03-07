package dev.ambon.engine

import dev.ambon.domain.StatBlock
import dev.ambon.domain.StatMap
import dev.ambon.domain.toStatBlock
import dev.ambon.domain.toStatMap
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
    fun `StatBlock round-trips through StatMap`() {
        val block = StatBlock(str = 11, dex = 10, con = 12, int = 8, wis = 9, cha = 13)
        val map = block.toStatMap()
        val roundTripped = map.toStatBlock()
        assertEquals(block, roundTripped)
    }

    @Test
    fun `toStatMap omits zero-value fields`() {
        val block = StatBlock(str = 2, cha = -1)
        val map = block.toStatMap()
        assertEquals(setOf("STR", "CHA"), map.values.keys)
    }

    @Test
    fun `toStatBlock defaults to 0 for absent keys`() {
        val map = StatMap.of("STR" to 5)
        val block = map.toStatBlock()
        assertEquals(5, block.str)
        assertEquals(0, block.dex)
        assertEquals(0, block.con)
        assertEquals(0, block.int)
        assertEquals(0, block.wis)
        assertEquals(0, block.cha)
    }
}
