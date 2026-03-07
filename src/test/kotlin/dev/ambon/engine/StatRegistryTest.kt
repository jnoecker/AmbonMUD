package dev.ambon.engine

import dev.ambon.config.StatsEngineConfig
import dev.ambon.domain.StatDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StatRegistryTest {
    private fun defaultRegistry(): StatRegistry =
        StatRegistry().also { reg ->
            StatRegistryLoader.load(StatsEngineConfig(), reg)
        }

    @Test
    fun `default config loads six stats`() {
        val registry = defaultRegistry()
        assertEquals(6, registry.all().size)
        val ids = registry.ids()
        assertEquals(listOf("STR", "DEX", "CON", "INT", "WIS", "CHA"), ids)
    }

    @Test
    fun `get is case-insensitive`() {
        val registry = defaultRegistry()
        assertEquals("Strength", registry.get("STR")?.displayName)
        assertEquals("Strength", registry.get("str")?.displayName)
    }

    @Test
    fun `get returns null for unknown stat`() {
        assertNull(defaultRegistry().get("LCK"))
    }

    @Test
    fun `contains checks by id`() {
        val registry = defaultRegistry()
        assertTrue(registry.contains("STR"))
        assertFalse(registry.contains("LCK"))
    }

    @Test
    fun `baseStat returns default for unknown id`() {
        assertEquals(0, defaultRegistry().baseStat("LCK"))
    }

    @Test
    fun `baseStat returns configured value`() {
        assertEquals(10, defaultRegistry().baseStat("STR"))
    }

    @Test
    fun `ordering is preserved from config`() {
        val registry = defaultRegistry()
        val ids = registry.ids()
        // STR comes before DEX, DEX before CON, etc.
        assertTrue(ids.indexOf("STR") < ids.indexOf("DEX"))
        assertTrue(ids.indexOf("DEX") < ids.indexOf("CON"))
        assertTrue(ids.indexOf("CON") < ids.indexOf("INT"))
        assertTrue(ids.indexOf("INT") < ids.indexOf("WIS"))
        assertTrue(ids.indexOf("WIS") < ids.indexOf("CHA"))
    }

    @Test
    fun `custom stat can be registered`() {
        val registry = StatRegistry()
        registry.register(StatDefinition(id = "LCK", displayName = "Luck", abbreviation = "LCK", baseStat = 5))
        assertEquals("Luck", registry.get("LCK")?.displayName)
        assertEquals(5, registry.baseStat("LCK"))
    }

    @Test
    fun `all stats have expected fields from default config`() {
        val str = defaultRegistry().get("STR")!!
        assertEquals("STR", str.id)
        assertEquals("Strength", str.displayName)
        assertEquals("STR", str.abbreviation)
        assertEquals(10, str.baseStat)
        assertTrue(str.description.isNotBlank())
    }
}
