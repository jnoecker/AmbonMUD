package dev.ambon.engine.abilities

import dev.ambon.config.PetConfig
import dev.ambon.config.PetTemplateConfig
import dev.ambon.domain.ids.RoomId
import dev.ambon.engine.DirtyNotifier
import dev.ambon.engine.PetSystem
import dev.ambon.engine.PlayerState
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.status.StatusEffectDefinition
import dev.ambon.engine.status.StatusEffectId
import dev.ambon.engine.status.StatusEffectRegistry
import dev.ambon.engine.status.StatusEffectSystem
import dev.ambon.test.AbilityTestFixture
import dev.ambon.test.MutableClock
import dev.ambon.test.TEST_ROOM_ID
import dev.ambon.test.TEST_SESSION_ID
import dev.ambon.test.drainAll
import dev.ambon.test.loginOrFail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * Tests for the `pet` ability target type — heal/buff abilities that route to
 * the caster's currently-active pet. Group-mate pets are intentionally not addressable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PetTargetSpellTest {
    private val roomId = TEST_ROOM_ID
    private val sid = TEST_SESSION_ID

    private val petConfig = PetConfig(
        definitions = mapOf(
            "tank_pet" to PetTemplateConfig(
                name = "a tank pet",
                description = "A sturdy companion.",
                baseHp = 100,
                baseMinDamage = 1,
                baseMaxDamage = 2,
                baseArmor = 0,
                threatMultiplier = 5.0,
            ),
        ),
    )

    private fun ownerStatsFor(player: PlayerState): PetSystem.OwnerStats =
        PetSystem.OwnerStats(maxHp = player.maxHp, damageMin = 1, damageMax = 4, armor = 0)

    private data class Harness(
        val fixture: AbilityTestFixture,
        val abilitySystem: AbilitySystem,
        val petSystem: PetSystem,
        val statusEffects: StatusEffectSystem,
    )

    private fun buildHarness(): Harness {
        val clock = MutableClock(0L)
        val rng = Random(42)
        val fixture = AbilityTestFixture(roomId = roomId, clock = clock, rng = rng)
        val petSystem = PetSystem(config = petConfig, mobs = fixture.mobs, clock = clock)

        val statusRegistry = StatusEffectRegistry()
        statusRegistry.register(
            StatusEffectDefinition(
                id = StatusEffectId("might"),
                displayName = "Might",
                effectType = "buff",
                durationMs = 30_000,
                stackBehavior = "none",
            ),
        )
        val statusEffects = StatusEffectSystem(
            registry = statusRegistry,
            players = fixture.players,
            mobs = fixture.mobs,
            outbound = fixture.outbound,
            clock = clock,
            rng = rng,
            dirtyNotifier = DirtyNotifier.NO_OP,
        )

        val registry = AbilityRegistry()
        registry.register(
            AbilityDefinition(
                id = AbilityId("mend_pet"),
                displayName = "Mend Pet",
                description = "Restore HP to your pet.",
                manaCostPct = 25.0,
                cooldownMs = 0,
                levelRequired = 1,
                targetType = "pet",
                effect = AbilityEffect.DirectHeal(minHeal = 20, maxHeal = 20),
            ),
        )
        registry.register(
            AbilityDefinition(
                id = AbilityId("empower_pet"),
                displayName = "Empower Pet",
                description = "Buff your pet.",
                manaCostPct = 25.0,
                cooldownMs = 0,
                levelRequired = 1,
                targetType = "pet",
                effect = AbilityEffect.ApplyStatus(StatusEffectId("might")),
            ),
        )

        val abilitySystem = fixture.buildAbilitySystem(
            registry = registry,
            statusEffects = statusEffects,
            petSystem = petSystem,
        )
        return Harness(fixture, abilitySystem, petSystem, statusEffects)
    }

    @Test
    fun `pet-target heal restores pet HP and deducts mana`() = runTest {
        val h = buildHarness()
        h.fixture.players.loginOrFail(sid, "Tamer")
        h.abilitySystem.syncAbilities(sid, 1)
        val player = h.fixture.players.get(sid)!!
        player.mana = 50

        val pet = h.petSystem.summon(sid, "tank_pet", player.roomId, ownerStatsFor(player))!!
        pet.hp = 10 // wounded
        h.fixture.outbound.drainAll()

        val err = h.abilitySystem.cast(sid, "mend_pet", null)
        assertNull(err)

        assertEquals(30, pet.hp, "Pet should be healed for 20 HP (capped at maxHp=100)")
        assertTrue(player.mana < 50, "Mana should be deducted")

        val messages = h.fixture.outbound.drainAll()
            .filterIsInstance<OutboundEvent.SendText>()
            .map { it.text }
        assertTrue(
            messages.any { it.contains("Mend Pet heals a tank pet for 20 HP") },
            "Expected pet-heal message; got: $messages",
        )
    }

    @Test
    fun `pet-target buff applies status to pet not caster`() = runTest {
        val h = buildHarness()
        h.fixture.players.loginOrFail(sid, "Tamer")
        h.abilitySystem.syncAbilities(sid, 1)
        val player = h.fixture.players.get(sid)!!
        player.mana = 50

        val pet = h.petSystem.summon(sid, "tank_pet", player.roomId, ownerStatsFor(player))!!
        h.fixture.outbound.drainAll()

        val err = h.abilitySystem.cast(sid, "empower_pet", null)
        assertNull(err)

        assertTrue(h.statusEffects.hasMobEffect(pet.id, "buff"), "Buff should be applied to the pet")
        assertTrue(!h.statusEffects.hasPlayerEffect(sid, "buff"), "Caster should not get the buff")
    }

    @Test
    fun `pet-target cast fails when caster has no active pet`() = runTest {
        val h = buildHarness()
        h.fixture.players.loginOrFail(sid, "Lonely")
        h.abilitySystem.syncAbilities(sid, 1)
        val player = h.fixture.players.get(sid)!!
        player.mana = 50

        val err = h.abilitySystem.cast(sid, "mend_pet", null)
        assertNotNull(err)
        assertTrue(err!!.contains("no active pet"), "Expected no-pet error; got: $err")
        assertEquals(50, player.mana, "Mana should not be consumed on failed pet cast")
    }

    @Test
    fun `pet-target cast fails when pet is in a different room`() = runTest {
        val h = buildHarness()
        h.fixture.players.loginOrFail(sid, "Wanderer")
        h.abilitySystem.syncAbilities(sid, 1)
        val player = h.fixture.players.get(sid)!!
        player.mana = 50

        val pet = h.petSystem.summon(sid, "tank_pet", player.roomId, ownerStatsFor(player))!!
        // Move pet away without the owner — simulates pet getting stranded.
        h.fixture.mobs.moveTo(pet.id, RoomId("zone:elsewhere"))
        h.fixture.outbound.drainAll()

        val err = h.abilitySystem.cast(sid, "mend_pet", null)
        assertNotNull(err)
        assertEquals(50, player.mana, "Mana should not be consumed when pet is unreachable")
    }
}
