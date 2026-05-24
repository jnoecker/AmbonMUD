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

    @Test
    fun `composite parses children and derives visual from first child`() {
        val a = load(
            "fire_bolt_dot",
            AbilityDefinitionConfig(
                targetType = "ENEMY",
                requiredClass = "MAGE",
                effect = AbilityEffectConfig(
                    type = "COMPOSITE",
                    effects = listOf(
                        AbilityEffectConfig(type = "DIRECT_DAMAGE", minDamage = 4, maxDamage = 8),
                        AbilityEffectConfig(type = "APPLY_STATUS", statusEffectId = "ignite"),
                    ),
                ),
            ),
        )
        val composite = a.effect as AbilityEffect.Composite
        assertEquals(2, composite.effects.size)
        assertEquals(AbilityEffect.DirectDamage::class, composite.effects[0]::class)
        assertEquals(AbilityEffect.ApplyStatus::class, composite.effects[1]::class)
        // First child is DirectDamage on mage → RANGED_PROJECTILE.
        assertEquals(AbilityVisualArchetype.RANGED_PROJECTILE, a.visual.archetype)
        assertEquals("DIRECT_DAMAGE", a.effect.primaryEffectType())
    }

    @Test
    fun `composite flattens nested children`() {
        val a = load(
            "nested",
            AbilityDefinitionConfig(
                targetType = "ENEMY",
                requiredClass = "MAGE",
                effect = AbilityEffectConfig(
                    type = "COMPOSITE",
                    effects = listOf(
                        AbilityEffectConfig(
                            type = "COMPOSITE",
                            effects = listOf(
                                AbilityEffectConfig(type = "DIRECT_DAMAGE", minDamage = 1, maxDamage = 1),
                                AbilityEffectConfig(type = "APPLY_STATUS", statusEffectId = "ignite"),
                            ),
                        ),
                        AbilityEffectConfig(type = "AREA_DAMAGE", minDamage = 2, maxDamage = 2),
                    ),
                ),
            ),
        )
        val flat = a.effect.flatten()
        assertEquals(3, flat.size)
        assertEquals(AbilityEffect.DirectDamage::class, flat[0]::class)
        assertEquals(AbilityEffect.ApplyStatus::class, flat[1]::class)
        assertEquals(AbilityEffect.AreaDamage::class, flat[2]::class)
    }

    @Test
    fun `composite with only unknown child types is skipped`() {
        val registry = AbilityRegistry()
        AbilityRegistryLoader.load(
            AbilityEngineConfig(
                definitions = mapOf(
                    "junk" to AbilityDefinitionConfig(
                        targetType = "ENEMY",
                        effect = AbilityEffectConfig(
                            type = "COMPOSITE",
                            effects = listOf(
                                AbilityEffectConfig(type = "NOT_A_REAL_TYPE"),
                            ),
                        ),
                    ),
                ),
            ),
            registry,
            imagesBaseUrl = "/img/",
        )
        assertEquals(0, registry.all().size)
    }

    @Test
    fun `composite with any unparseable child rejects whole ability`() {
        // Mixing one valid child with one typo'd child must NOT load a partial
        // composite — the typo would silently change combat behavior.
        val registry = AbilityRegistry()
        AbilityRegistryLoader.load(
            AbilityEngineConfig(
                definitions = mapOf(
                    "typo_dot" to AbilityDefinitionConfig(
                        targetType = "ENEMY",
                        effect = AbilityEffectConfig(
                            type = "COMPOSITE",
                            effects = listOf(
                                AbilityEffectConfig(type = "DIRECT_DAMAGE", minDamage = 5, maxDamage = 5),
                                AbilityEffectConfig(type = "APPLY_STTUS", statusEffectId = "ignite"),
                            ),
                        ),
                    ),
                ),
            ),
            registry,
            imagesBaseUrl = "/img/",
        )
        assertEquals(0, registry.all().size)
    }

    @Test
    fun `composite with empty effects list is skipped`() {
        val registry = AbilityRegistry()
        AbilityRegistryLoader.load(
            AbilityEngineConfig(
                definitions = mapOf(
                    "empty" to AbilityDefinitionConfig(
                        targetType = "ENEMY",
                        effect = AbilityEffectConfig(type = "COMPOSITE", effects = emptyList()),
                    ),
                ),
            ),
            registry,
            imagesBaseUrl = "/img/",
        )
        assertEquals(0, registry.all().size)
    }
}
