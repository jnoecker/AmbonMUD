package dev.ambon.engine

import dev.ambon.config.RaceDefinitionConfig
import dev.ambon.config.RaceEngineConfig
import dev.ambon.config.RaceStatModsConfig
import dev.ambon.domain.RaceDef
import dev.ambon.domain.StatMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RaceRegistryTest {
    @Test
    fun `register and retrieve by id`() {
        val registry = RaceRegistry()
        val def = RaceDef(
            id = "HUMAN",
            displayName = "Human",
            statMods = StatMap.of("STR" to 1, "CHA" to 1),
        )
        registry.register(def)
        assertEquals(def, registry.get("HUMAN"))
        assertEquals(def, registry.get("human"))
    }

    @Test
    fun `get returns null for unknown id`() {
        val registry = RaceRegistry()
        assertNull(registry.get("NONEXISTENT"))
    }

    @Test
    fun `all returns every registered race`() {
        val registry = RaceRegistry()
        registry.register(RaceDef("HUMAN", "Human"))
        registry.register(RaceDef("ELF", "Elf"))
        assertEquals(2, registry.all().size)
    }

    @Test
    fun `loader populates registry from config`() {
        val config = RaceEngineConfig(
            definitions = mapOf(
                "HUMAN" to RaceDefinitionConfig(
                    displayName = "Human",
                    description = "Versatile.",
                    statMods = RaceStatModsConfig(str = 1, cha = 1),
                ),
                "ELF" to RaceDefinitionConfig(
                    displayName = "Elf",
                    statMods = RaceStatModsConfig(str = -1, dex = 2, con = -2, int = 1),
                ),
            ),
        )
        val registry = RaceRegistry()
        RaceRegistryLoader.load(config, registry)
        assertEquals("Human", registry.get("HUMAN")?.displayName)
        assertEquals(StatMap.of("STR" to 1, "CHA" to 1), registry.get("HUMAN")?.statMods)
        assertEquals(StatMap.of("STR" to -1, "DEX" to 2, "CON" to -2, "INT" to 1), registry.get("ELF")?.statMods)
    }
}
