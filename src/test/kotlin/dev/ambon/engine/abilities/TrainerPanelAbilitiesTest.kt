package dev.ambon.engine.abilities

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.DamageRange
import dev.ambon.test.AbilityTestFixture
import dev.ambon.test.MutableClock
import dev.ambon.test.TEST_ROOM_ID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies that [AbilitySystem.trainerPanelAbilities] returns abilities with correct lock
 * status so the Trainer.List GMCP payload can render level-gated skills visibly instead of
 * hiding them. See https://github.com/jnoecker/AmbonMUD/issues/1036.
 */
class TrainerPanelAbilitiesTest {
    private fun registry(): AbilityRegistry {
        val r = AbilityRegistry()
        r.register(
            AbilityDefinition(
                id = AbilityId("slash"),
                displayName = "Slash",
                description = "A basic slash.",
                manaCostPct = 0,
                cooldownMs = 0,
                levelRequired = 1,
                targetType = "enemy",
                effect = AbilityEffect.DirectDamage(DamageRange(1, 2)),
                requiredClass = "WARRIOR",
                skillPointCost = 1,
            ),
        )
        r.register(
            AbilityDefinition(
                id = AbilityId("cleave"),
                displayName = "Cleave",
                description = "Hit all foes.",
                manaCostPct = 25,
                cooldownMs = 0,
                levelRequired = 5,
                targetType = "enemy",
                effect = AbilityEffect.DirectDamage(DamageRange(2, 4)),
                requiredClass = "WARRIOR",
                skillPointCost = 1,
            ),
        )
        r.register(
            AbilityDefinition(
                id = AbilityId("whirlwind"),
                displayName = "Whirlwind",
                description = "Spin attack.",
                manaCostPct = 50,
                cooldownMs = 0,
                levelRequired = 10,
                targetType = "enemy",
                effect = AbilityEffect.DirectDamage(DamageRange(5, 10)),
                requiredClass = "WARRIOR",
                prerequisites = setOf(AbilityId("cleave")),
                skillPointCost = 2,
            ),
        )
        return r
    }

    private fun buildSystem(registry: AbilityRegistry): AbilitySystem {
        val fixture = AbilityTestFixture(
            roomId = TEST_ROOM_ID,
            clock = MutableClock(0L),
            rng = java.util.Random(0),
            outbound = LocalOutboundBus(),
        )
        return fixture.buildAbilitySystem(registry = registry)
    }

    @Test
    fun `trainerPanelAbilities includes level-locked abilities with reason`() {
        val registry = registry()
        val system = buildSystem(registry)

        val entries = system.trainerPanelAbilities(
            className = "WARRIOR",
            level = 3,
            knownIds = emptySet(),
        )

        val ids = entries.map { it.ability.id.value }
        assertTrue(ids.contains("slash"), "slash (level 1) should be listed")
        assertTrue(ids.contains("cleave"), "cleave (level 5) should be listed even though player is level 3")
        assertTrue(ids.contains("whirlwind"), "whirlwind (level 10) should be listed")

        val slash = entries.first { it.ability.id.value == "slash" }
        assertFalse(slash.locked, "slash should be unlocked at level 3")
        assertNull(slash.lockReason)

        val cleave = entries.first { it.ability.id.value == "cleave" }
        assertTrue(cleave.locked, "cleave should be locked at level 3")
        assertEquals("Requires level 5", cleave.lockReason)
    }

    @Test
    fun `trainerPanelAbilities flags prerequisite-locked abilities`() {
        val registry = registry()
        val system = buildSystem(registry)

        // Player at level 15 has the level for everything but hasn't learned cleave yet
        val entries = system.trainerPanelAbilities(
            className = "WARRIOR",
            level = 15,
            knownIds = emptySet(),
        )

        val whirlwind = entries.first { it.ability.id.value == "whirlwind" }
        assertTrue(whirlwind.locked, "whirlwind should be locked without its prerequisite")
        assertNotNull(whirlwind.lockReason)
        assertTrue(
            whirlwind.lockReason!!.contains("Cleave"),
            "lock reason should reference prerequisite display name, got: ${whirlwind.lockReason}",
        )
    }

    @Test
    fun `trainerPanelAbilities hides already-known abilities`() {
        val registry = registry()
        val system = buildSystem(registry)

        val entries = system.trainerPanelAbilities(
            className = "WARRIOR",
            level = 15,
            knownIds = setOf("slash"),
        )

        assertTrue(entries.none { it.ability.id.value == "slash" }, "known abilities should not appear")
    }

    @Test
    fun `trainerPanelAbilities sorts unlocked before locked within the same tier`() {
        val registry = registry()
        val system = buildSystem(registry)

        val entries = system.trainerPanelAbilities(
            className = "WARRIOR",
            level = 15,
            knownIds = emptySet(),
        )

        val firstLocked = entries.indexOfFirst { it.locked }
        val lastUnlocked = entries.indexOfLast { !it.locked }
        if (firstLocked >= 0 && lastUnlocked >= 0) {
            val summary = entries.map { e -> "${e.ability.id.value}=locked:${e.locked}" }
            assertTrue(
                lastUnlocked < firstLocked,
                "all unlocked entries should sort before the first locked entry, got: $summary",
            )
        }
    }
}
