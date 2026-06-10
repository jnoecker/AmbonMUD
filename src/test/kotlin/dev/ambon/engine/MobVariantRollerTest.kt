package dev.ambon.engine

import dev.ambon.config.MobVariantDefinition
import dev.ambon.config.MobVariantsConfig
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.mob.MobRole
import dev.ambon.domain.world.MobTemplateDef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

class MobVariantRollerTest {
    private fun template(
        role: MobRole = MobRole.COMBAT,
        eligible: Boolean = true,
    ): MobTemplateDef =
        MobTemplateDef(
            id = MobId("rat"),
            name = "giant rat",
            role = role,
            variantEligible = eligible,
        )

    private val oneVariant = mapOf(
        "shadow" to MobVariantDefinition(displayName = "Shadow-touched", namePrefix = "Shadow-touched ", weight = 1.0),
    )

    @Test
    fun `disabled config never rolls`() {
        val roller = MobVariantRoller(MobVariantsConfig(enabled = false, chance = 1.0, variants = oneVariant))
        assertNull(roller.roll(template()))
    }

    @Test
    fun `non-combat mobs are never variants`() {
        val roller = MobVariantRoller(MobVariantsConfig(chance = 1.0, variants = oneVariant))
        assertNull(roller.roll(template(role = MobRole.VENDOR)))
    }

    @Test
    fun `author opt-out is honored`() {
        val roller = MobVariantRoller(MobVariantsConfig(chance = 1.0, variants = oneVariant))
        assertNull(roller.roll(template(eligible = false)))
    }

    @Test
    fun `chance 0 never rolls and chance 1 always rolls`() {
        assertNull(MobVariantRoller(MobVariantsConfig(chance = 0.0, variants = oneVariant)).roll(template()))
        val rolled = MobVariantRoller(MobVariantsConfig(chance = 1.0, variants = oneVariant)).roll(template())
        assertNotNull(rolled)
        assertEquals("shadow", rolled!!.id)
    }

    @Test
    fun `weighted pick stays within the configured set`() {
        val roller = MobVariantRoller(MobVariantsConfig(chance = 1.0), rng = Random(42L))
        repeat(50) {
            val rolled = roller.roll(template())
            assertNotNull(rolled)
            assertTrue(MobVariantsConfig.DEFAULT_VARIANTS.containsKey(rolled!!.id))
        }
    }
}
