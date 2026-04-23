package dev.ambon.domain.world

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ZoneScalingTest {
    @Test
    fun `parse accepts lowercase and defaults to static`() {
        assertEquals(ScalingMode.STATIC, ScalingMode.parse(null))
        assertEquals(ScalingMode.STATIC, ScalingMode.parse(""))
        assertEquals(ScalingMode.STATIC, ScalingMode.parse("static"))
        assertEquals(ScalingMode.BOUNDED, ScalingMode.parse("BOUNDED"))
        assertEquals(ScalingMode.PLAYER, ScalingMode.parse("Player"))
    }

    @Test
    fun `parse rejects unknown modes`() {
        assertThrows(IllegalArgumentException::class.java) { ScalingMode.parse("dynamic") }
    }

    @Test
    fun `static returns authored level when set`() {
        val s = ZoneScaling(ScalingMode.STATIC)
        assertEquals(5, s.resolveLevel(referencePlayerLevel = 30, authoredLevel = 5))
    }

    @Test
    fun `static falls back to reference level when author omitted`() {
        val s = ZoneScaling(ScalingMode.STATIC)
        assertEquals(12, s.resolveLevel(referencePlayerLevel = 12, authoredLevel = null))
    }

    @Test
    fun `bounded clamps reference level to levelRange`() {
        val s = ZoneScaling(ScalingMode.BOUNDED, levelRange = 3..8)
        assertEquals(3, s.resolveLevel(referencePlayerLevel = 1, authoredLevel = null))
        assertEquals(5, s.resolveLevel(referencePlayerLevel = 5, authoredLevel = null))
        assertEquals(8, s.resolveLevel(referencePlayerLevel = 30, authoredLevel = null))
    }

    @Test
    fun `bounded falls back to range min when no reference`() {
        val s = ZoneScaling(ScalingMode.BOUNDED, levelRange = 3..8)
        assertEquals(3, s.resolveLevel(referencePlayerLevel = null, authoredLevel = null))
    }

    @Test
    fun `bounded clamps authored fallback to range too`() {
        val s = ZoneScaling(ScalingMode.BOUNDED, levelRange = 3..8)
        assertEquals(8, s.resolveLevel(referencePlayerLevel = null, authoredLevel = 50))
        assertEquals(3, s.resolveLevel(referencePlayerLevel = null, authoredLevel = 1))
    }

    @Test
    fun `player mode ignores authored level in favour of reference`() {
        val s = ZoneScaling(ScalingMode.PLAYER)
        assertEquals(20, s.resolveLevel(referencePlayerLevel = 20, authoredLevel = 3))
    }

    @Test
    fun `player mode falls back to authored then 1 when no reference`() {
        val s = ZoneScaling(ScalingMode.PLAYER)
        assertEquals(3, s.resolveLevel(referencePlayerLevel = null, authoredLevel = 3))
        assertEquals(1, s.resolveLevel(referencePlayerLevel = null, authoredLevel = null))
    }

    @Test
    fun `resolveLevel never returns below 1`() {
        val s = ZoneScaling(ScalingMode.PLAYER)
        assertEquals(1, s.resolveLevel(referencePlayerLevel = -5, authoredLevel = null))
    }
}
