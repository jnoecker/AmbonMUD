package dev.ambon.engine.abilities

import dev.ambon.config.AbilityDefinitionConfig
import dev.ambon.config.AbilityEffectConfig
import dev.ambon.config.AbilityEngineConfig
import dev.ambon.config.AbilityVisualConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AbilityVisualLoaderTest {
    private fun load(key: String, def: AbilityDefinitionConfig): AbilityDefinition {
        val registry = AbilityRegistry()
        AbilityRegistryLoader.load(
            AbilityEngineConfig(definitions = mapOf(key to def)),
            registry,
            imagesBaseUrl = "/img/",
        )
        return registry.all().first()
    }

    @Test
    fun `direct damage warrior derives MELEE_STRIKE`() {
        val a = load(
            "power_strike",
            AbilityDefinitionConfig(
                displayName = "Power Strike",
                targetType = "ENEMY",
                requiredClass = "WARRIOR",
                effect = AbilityEffectConfig(type = "DIRECT_DAMAGE", minDamage = 5, maxDamage = 10),
            ),
        )
        assertEquals(AbilityVisualArchetype.MELEE_STRIKE, a.visual.archetype)
    }

    @Test
    fun `direct damage mage derives RANGED_PROJECTILE`() {
        val a = load(
            "arcane_bolt",
            AbilityDefinitionConfig(
                targetType = "ENEMY",
                requiredClass = "MAGE",
                effect = AbilityEffectConfig(type = "DIRECT_DAMAGE", minDamage = 5, maxDamage = 10),
            ),
        )
        assertEquals(AbilityVisualArchetype.RANGED_PROJECTILE, a.visual.archetype)
    }

    @Test
    fun `apply status on enemy derives DEBUFF_AURA`() {
        val a = load(
            "hex",
            AbilityDefinitionConfig(
                targetType = "ENEMY",
                requiredClass = "MAGE",
                effect = AbilityEffectConfig(type = "APPLY_STATUS", statusEffectId = "hex_debuff"),
            ),
        )
        assertEquals(AbilityVisualArchetype.DEBUFF_AURA, a.visual.archetype)
    }

    @Test
    fun `apply status on self derives BUFF_AURA`() {
        val a = load(
            "blessing",
            AbilityDefinitionConfig(
                targetType = "SELF",
                requiredClass = "CLERIC",
                effect = AbilityEffectConfig(type = "APPLY_STATUS", statusEffectId = "blessing_buff"),
            ),
        )
        assertEquals(AbilityVisualArchetype.BUFF_AURA, a.visual.archetype)
    }

    @Test
    fun `direct heal derives HEAL_AURA`() {
        val a = load(
            "heal",
            AbilityDefinitionConfig(
                targetType = "SELF",
                requiredClass = "CLERIC",
                effect = AbilityEffectConfig(type = "DIRECT_HEAL", minHeal = 10, maxHeal = 20),
            ),
        )
        assertEquals(AbilityVisualArchetype.HEAL_AURA, a.visual.archetype)
    }

    @Test
    fun `summon pet derives SUMMON_POOF`() {
        val a = load(
            "summon_wolf",
            AbilityDefinitionConfig(
                targetType = "SELF",
                requiredClass = "RANGER",
                effect = AbilityEffectConfig(type = "SUMMON_PET", petTemplateKey = "wolf"),
            ),
        )
        assertEquals(AbilityVisualArchetype.SUMMON_POOF, a.visual.archetype)
    }

    @Test
    fun `area damage derives AREA_BURST`() {
        val a = load(
            "cleave",
            AbilityDefinitionConfig(
                targetType = "ENEMY",
                requiredClass = "WARRIOR",
                effect = AbilityEffectConfig(type = "AREA_DAMAGE", minDamage = 5, maxDamage = 10),
            ),
        )
        assertEquals(AbilityVisualArchetype.AREA_BURST, a.visual.archetype)
    }

    @Test
    fun `explicit archetype overrides auto-derived`() {
        val a = load(
            "shadow_step",
            AbilityDefinitionConfig(
                targetType = "ENEMY",
                requiredClass = "ROGUE",
                effect = AbilityEffectConfig(type = "DIRECT_DAMAGE", minDamage = 5, maxDamage = 10),
                visual = AbilityVisualConfig(archetype = "RANGED_PROJECTILE"),
            ),
        )
        // Default for rogue+DIRECT_DAMAGE would be MELEE_STRIKE — explicit override wins.
        assertEquals(AbilityVisualArchetype.RANGED_PROJECTILE, a.visual.archetype)
    }

    @Test
    fun `projectile image is resolved against images base`() {
        val a = load(
            "ice_lance",
            AbilityDefinitionConfig(
                targetType = "ENEMY",
                requiredClass = "MAGE",
                effect = AbilityEffectConfig(type = "DIRECT_DAMAGE", minDamage = 5, maxDamage = 10),
                visual = AbilityVisualConfig(projectileImage = "ice.png", color = "#88ccff"),
            ),
        )
        assertEquals("/img/ice.png", a.visual.projectileImage)
        assertEquals("#88ccff", a.visual.color)
        assertNull(a.visual.accentColor)
    }

    @Test
    fun `unknown archetype string falls back to derived default`() {
        val a = load(
            "weird",
            AbilityDefinitionConfig(
                targetType = "ENEMY",
                requiredClass = "MAGE",
                effect = AbilityEffectConfig(type = "DIRECT_DAMAGE", minDamage = 5, maxDamage = 10),
                visual = AbilityVisualConfig(archetype = "NOT_A_REAL_ARCHETYPE"),
            ),
        )
        assertEquals(AbilityVisualArchetype.RANGED_PROJECTILE, a.visual.archetype)
    }
}
