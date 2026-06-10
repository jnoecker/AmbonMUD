package dev.ambon.engine

import dev.ambon.config.MobVariantDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpawnToMobStateVariantTest {
    private val world = dev.ambon.test.TestWorlds.okSmall
    private val spawn = world.mobSpawns.first { world.mobTemplate(it.templateId)?.role?.isCombatant == true }

    @Test
    fun `no variant leaves the mob unchanged`() {
        val plain = spawnToMobState(spawn, world)
        assertNull(plain.variantId)
        assertNull(plain.tint)
        assertEquals(world.mobTemplate(spawn.templateId)!!.name, plain.name)
    }

    @Test
    fun `variant applies name prefix, tint, overlay and stat bumps`() {
        val base = spawnToMobState(spawn, world)
        val def = MobVariantDefinition(
            displayName = "Shadow-touched",
            namePrefix = "Shadow-touched ",
            tint = "#6a4aa0",
            overlay = "swirl",
            hpMultiplier = 2.0,
            xpMultiplier = 2.0,
        )
        val variant = spawnToMobState(spawn, world, variant = RolledMobVariant("shadow", def))

        assertEquals("shadow", variant.variantId)
        assertEquals("Shadow-touched", variant.variantName)
        assertEquals("#6a4aa0", variant.tint)
        assertEquals("swirl", variant.overlay)
        assertTrue(variant.name.startsWith("Shadow-touched "))
        assertEquals(base.maxHp * 2, variant.maxHp)
        assertEquals(variant.maxHp, variant.hp)
        assertEquals(base.xpReward * 2, variant.xpReward)
    }
}
