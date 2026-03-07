package dev.ambon.engine

import dev.ambon.config.ClassDefinitionConfig
import dev.ambon.config.ClassEngineConfig
import dev.ambon.domain.PlayerClassDef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PlayerClassRegistryTest {
    @Test
    fun `register and retrieve by id`() {
        val registry = PlayerClassRegistry()
        val def = PlayerClassDef(
            id = "WARRIOR",
            displayName = "Warrior",
            hpPerLevel = 8,
            manaPerLevel = 4,
        )
        registry.register(def)
        assertEquals(def, registry.get("WARRIOR"))
        assertEquals(def, registry.get("warrior"))
    }

    @Test
    fun `get returns null for unknown id`() {
        val registry = PlayerClassRegistry()
        assertNull(registry.get("NONEXISTENT"))
    }

    @Test
    fun `selectable filters out non-selectable classes`() {
        val registry = PlayerClassRegistry()
        registry.register(PlayerClassDef("WARRIOR", "Warrior", 8, 4, selectable = true))
        registry.register(PlayerClassDef("SWARM", "Swarm", 2, 3, selectable = false))
        val selectable = registry.selectable()
        assertEquals(1, selectable.size)
        assertEquals("WARRIOR", selectable[0].id)
    }

    @Test
    fun `all returns every registered class`() {
        val registry = PlayerClassRegistry()
        registry.register(PlayerClassDef("WARRIOR", "Warrior", 8, 4))
        registry.register(PlayerClassDef("MAGE", "Mage", 4, 16))
        assertEquals(2, registry.all().size)
    }

    @Test
    fun `loader populates registry from config`() {
        val config = ClassEngineConfig(
            definitions = mapOf(
                "WARRIOR" to ClassDefinitionConfig(
                    displayName = "Warrior",
                    hpPerLevel = 8,
                    manaPerLevel = 4,
                    description = "Melee fighter.",
                    primaryStat = "STR",
                ),
                "SWARM" to ClassDefinitionConfig(
                    displayName = "Swarm",
                    hpPerLevel = 2,
                    manaPerLevel = 3,
                    selectable = false,
                ),
            ),
        )
        val registry = PlayerClassRegistry()
        PlayerClassRegistryLoader.load(config, registry)
        assertEquals("Warrior", registry.get("WARRIOR")?.displayName)
        assertEquals(8, registry.get("WARRIOR")?.hpPerLevel)
        assertEquals("STR", registry.get("WARRIOR")?.primaryStat)
        assertEquals(false, registry.get("SWARM")?.selectable)
        assertNull(registry.get("SWARM")?.primaryStat)
    }
}
