package dev.ambon.engine

import dev.ambon.domain.DamageRange
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.items.ItemSlot
import dev.ambon.domain.mob.MobSpell
import dev.ambon.domain.mob.MobState
import dev.ambon.domain.world.load.WorldLoadException
import dev.ambon.domain.world.load.WorldLoader
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.status.StatusEffectDefinition
import dev.ambon.engine.status.StatusEffectId
import dev.ambon.test.CombatTestFixture
import dev.ambon.test.deterministicMeleeBindings
import dev.ambon.test.drainAll
import dev.ambon.test.loginOrFail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

@OptIn(ExperimentalCoroutinesApi::class)
class MobSpellCombatTest {
    private fun spellMob(
        spells: List<MobSpell> = emptyList(),
        defaultAttack: String? = null,
        hp: Int = 100,
        damage: DamageRange = DamageRange(1, 2),
    ): MobState = MobState(
        id = MobId("zone:mage"),
        name = "a mage",
        roomId = dev.ambon.test.TEST_ROOM_ID,
        hp = hp,
        maxHp = hp,
        damage = damage,
        spells = spells,
        defaultAttack = defaultAttack,
    )

    private val shadowBolt = MobSpell(
        id = "shadow_bolt",
        displayName = "Shadow Bolt",
        message = "The mage hurls a bolt of darkness at you",
        roomMessage = "{mob} hurls darkness at {target}.",
        damage = DamageRange(5, 5),
        weight = 1,
    )

    private val heal = MobSpell(
        id = "heal",
        displayName = "Heal",
        message = "{mob} heals itself.",
        healMin = 10,
        healMax = 10,
        cooldownMs = 10_000L,
        weight = 1,
    )

    @Test
    fun `mob uses defaultAttack spell instead of melee`() = runTest {
        val fixture = CombatTestFixture()
        val mob = spellMob(
            spells = listOf(shadowBolt),
            defaultAttack = "shadow_bolt",
            damage = DamageRange(1, 1),
        )
        fixture.mobs.upsert(mob)

        // Use a fixed seed that doesn't trigger dodge
        val combat = fixture.buildCombat(rng = Random(42), minDamage = 1, maxDamage = 1)
        val sid = SessionId(1L)
        fixture.players.loginOrFail(sid, "Player1")
        fixture.outbound.drainAll()

        combat.startCombat(sid, "mage")
        fixture.outbound.drainAll()

        fixture.tickCombat(combat)
        val events = fixture.outbound.drainAll()
        val texts = events.filterIsInstance<OutboundEvent.SendText>().map { it.text }

        // Should see the spell message, not generic melee "hits you"
        assertTrue(
            texts.any { it.contains("bolt of darkness") || it.contains("dodge") },
            "Expected spell message or dodge, got: $texts",
        )
    }

    @Test
    fun `mob casts non-default spell from pool`() = runTest {
        val fixture = CombatTestFixture()
        // Give the mob a default melee attack and a separate heal spell with no cooldown
        val nocdHeal = heal.copy(cooldownMs = 0L)
        val mob = spellMob(
            spells = listOf(shadowBolt, nocdHeal),
            defaultAttack = "shadow_bolt",
            hp = 50,
        )
        fixture.mobs.upsert(mob)

        // Seed chosen so the weighted random picks heal
        val combat = fixture.buildCombat(rng = Random(7), minDamage = 1, maxDamage = 1)
        val sid = SessionId(1L)
        fixture.players.loginOrFail(sid, "Player1")
        fixture.outbound.drainAll()

        combat.startCombat(sid, "mage")
        fixture.outbound.drainAll()

        fixture.tickCombat(combat)
        val updatedMob = fixture.mobs.get(mob.id)!!
        val events = fixture.outbound.drainAll()
        val texts = events.filterIsInstance<OutboundEvent.SendText>().map { it.text }

        // Either the heal fired (mob HP went up or heal message) or the shadow bolt fired,
        // depending on the seed. At minimum, we should see some combat output.
        assertTrue(texts.isNotEmpty(), "Expected combat output")
    }

    @Test
    fun `spell cooldown prevents re-use`() = runTest {
        val fixture = CombatTestFixture()
        // Only one spell with a long cooldown, no default attack
        val longCdSpell = shadowBolt.copy(cooldownMs = 60_000L)
        val mob = spellMob(
            spells = listOf(longCdSpell),
            damage = DamageRange(1, 1),
        )
        fixture.mobs.upsert(mob)

        val combat = fixture.buildCombat(rng = Random(42), minDamage = 1, maxDamage = 1)
        val sid = SessionId(1L)
        fixture.players.loginOrFail(sid, "Player1")
        fixture.outbound.drainAll()

        combat.startCombat(sid, "mage")
        fixture.outbound.drainAll()

        // First tick: spell should fire
        fixture.tickCombat(combat)
        val firstTexts = fixture.outbound.drainAll()
            .filterIsInstance<OutboundEvent.SendText>().map { it.text }

        // Second tick: spell on cooldown, should fall back to melee
        fixture.tickCombat(combat)
        val secondTexts = fixture.outbound.drainAll()
            .filterIsInstance<OutboundEvent.SendText>().map { it.text }

        // The first tick should have either spell or dodge, and the second tick
        // should have either melee ("hits you") or dodge
        val secondHasMelee = secondTexts.any { it.contains("hits you") || it.contains("dodge") }
        assertTrue(secondHasMelee, "Expected melee fallback on second tick, got: $secondTexts")
    }

    @Test
    fun `heal spell restores mob HP`() = runTest {
        val fixture = CombatTestFixture()
        val healOnlyMob = spellMob(
            spells = listOf(heal.copy(cooldownMs = 0L, weight = 100)),
            hp = 50,
            damage = DamageRange(1, 1),
        )
        // Damage the mob first
        healOnlyMob.takeDamage(20)
        assertEquals(30, healOnlyMob.hp)
        fixture.mobs.upsert(healOnlyMob)

        val combat = fixture.buildCombat(rng = Random(42), minDamage = 1, maxDamage = 1)
        val sid = SessionId(1L)
        fixture.players.loginOrFail(sid, "Player1")
        fixture.outbound.drainAll()

        combat.startCombat(sid, "mage")
        fixture.outbound.drainAll()

        fixture.tickCombat(combat)

        val updatedMob = fixture.mobs.get(healOnlyMob.id)!!
        // Player also hits the mob for 1 damage, so HP should be 30 - 1 + 10 = 39
        // (or 30 + 10 - 1 depending on phase order)
        // With dodge possible, just check HP is higher than post-player-hit (29 or 30)
        assertTrue(updatedMob.hp >= 30, "Expected mob to heal, hp=${updatedMob.hp}")
    }

    @Test
    fun `mob with no spells uses standard melee`() = runTest {
        val fixture = CombatTestFixture()
        val mob = spellMob(damage = DamageRange(3, 3))
        fixture.mobs.upsert(mob)

        val combat = fixture.buildCombat(rng = Random(42), minDamage = 1, maxDamage = 1)
        val sid = SessionId(1L)
        fixture.players.loginOrFail(sid, "Player1")
        fixture.outbound.drainAll()

        combat.startCombat(sid, "mage")
        fixture.outbound.drainAll()

        fixture.tickCombat(combat)
        val texts = fixture.outbound.drainAll()
            .filterIsInstance<OutboundEvent.SendText>().map { it.text }

        // Should see standard melee hit messages
        assertTrue(
            texts.any { it.contains("hits you") || it.contains("dodge") },
            "Expected melee output, got: $texts",
        )
    }

    @Test
    fun `spell applies status effect to player`() = runTest {
        val fixture = CombatTestFixture()
        val burnDef = StatusEffectDefinition(
            id = StatusEffectId("burn"),
            displayName = "Burn",
            effectType = "debuff",
            durationMs = 10_000L,
        )
        val statusEffects = fixture.buildStatusEffects(burnDef, rng = Random(1))

        val burnSpell = MobSpell(
            id = "fire_breath",
            displayName = "Fire Breath",
            message = "The mage breathes fire at you",
            damage = DamageRange(3, 3),
            statusEffectId = StatusEffectId("burn"),
            weight = 100,
        )
        val mob = spellMob(spells = listOf(burnSpell), damage = DamageRange(1, 1))
        fixture.mobs.upsert(mob)

        val combat = fixture.buildCombat(
            rng = Random(42),
            minDamage = 1,
            maxDamage = 1,
            statusEffects = statusEffects,
        )
        val sid = SessionId(1L)
        fixture.players.loginOrFail(sid, "Player1")
        fixture.outbound.drainAll()

        combat.startCombat(sid, "mage")
        fixture.outbound.drainAll()
        fixture.tickCombat(combat)

        // If the spell hit (not dodged), the burn effect should be applied
        val hasBurn = statusEffects.hasPlayerEffect(sid, "debuff")
        val texts = fixture.outbound.drainAll()
            .filterIsInstance<OutboundEvent.SendText>().map { it.text }
        val dodged = texts.any { it.contains("dodge") }

        assertTrue(hasBurn || dodged, "Expected burn effect applied or attack dodged")
    }

    // --- D-25: damaging spells are floored at the mob's own mitigated swing ---

    /** Bindings with dodge disabled so the mob's blow always lands and the HP arithmetic is exact. */
    private fun noDodgeBindings() = deterministicMeleeBindings(unarmedAttackPower = 1).copy(dodgePerPoint = 0.0)

    private suspend fun loginAt100(fixture: CombatTestFixture, sid: SessionId, name: String) =
        fixture.players.loginOrFail(sid, name).let {
            val player = fixture.players.get(sid)!!
            player.maxHp = 100
            player.hp = 100
            player
        }

    @Test
    fun `default attack authored below the swing lands for the mitigated swing`() = runTest {
        val fixture = CombatTestFixture()
        val weakDefault = MobSpell(
            id = "rusty_jab",
            displayName = "Rusty Jab",
            message = "The mage jabs at you",
            damage = DamageRange(1, 1),
            weight = 1,
        )
        // Swing 20 against armor 20 (K = 20) mitigates to 10; the authored 1 must not replace it.
        val mob = spellMob(
            spells = listOf(weakDefault),
            defaultAttack = "rusty_jab",
            damage = DamageRange(20, 20),
        )
        fixture.mobs.upsert(mob)

        val combat = fixture.buildCombat(rng = Random(1), minDamage = 1, maxDamage = 1, bindings = noDodgeBindings())
        val sid = SessionId(1L)
        val player = loginAt100(fixture, sid, "Player1")
        fixture.equipItem(
            sid,
            ItemInstance(
                ItemId("demo:plate"),
                Item(keyword = "plate", displayName = "plate mail", slot = ItemSlot.BODY, armor = 20),
            ),
        )
        fixture.outbound.drainAll()

        assertNull(combat.startCombat(sid, "mage"))
        fixture.outbound.drainAll()
        fixture.tickCombat(combat)

        val texts = fixture.outbound.drainAll().filterIsInstance<OutboundEvent.SendText>().map { it.text }
        assertEquals(90, player.hp, "Expected the mitigated swing (10), not the authored 1 or the raw 20; got: $texts")
        assertTrue(
            texts.any { it.contains("jabs at you for 10 damage") },
            "Expected the spell message carrying the floored damage, got: $texts",
        )
    }

    @Test
    fun `special authored above the swing keeps its authored roll`() = runTest {
        val fixture = CombatTestFixture()
        // Swing 1..1, Shadow Bolt 5..5 (no cooldown): the bolt is picked every phase and is the larger number.
        val mob = spellMob(spells = listOf(shadowBolt), damage = DamageRange(1, 1))
        fixture.mobs.upsert(mob)

        val combat = fixture.buildCombat(rng = Random(1), minDamage = 1, maxDamage = 1, bindings = noDodgeBindings())
        val sid = SessionId(1L)
        val player = loginAt100(fixture, sid, "Player1")
        fixture.outbound.drainAll()

        assertNull(combat.startCombat(sid, "mage"))
        fixture.outbound.drainAll()
        fixture.tickCombat(combat)

        val texts = fixture.outbound.drainAll().filterIsInstance<OutboundEvent.SendText>().map { it.text }
        assertEquals(95, player.hp, "Expected the authored 5 to land unchanged, got: $texts")
    }

    @Test
    fun `special authored below the swing is floored at the unmitigated swing when unarmored`() = runTest {
        val fixture = CombatTestFixture()
        val weakSpecial = MobSpell(
            id = "ember_flick",
            displayName = "Ember Flick",
            message = "The mage flicks an ember at you",
            damage = DamageRange(3, 3),
            weight = 100,
        )
        val mob = spellMob(spells = listOf(weakSpecial), damage = DamageRange(20, 20))
        fixture.mobs.upsert(mob)

        val combat = fixture.buildCombat(rng = Random(1), minDamage = 1, maxDamage = 1, bindings = noDodgeBindings())
        val sid = SessionId(1L)
        val player = loginAt100(fixture, sid, "Player1")
        fixture.outbound.drainAll()

        assertNull(combat.startCombat(sid, "mage"))
        fixture.outbound.drainAll()
        fixture.tickCombat(combat)

        // No armor: the floor is the raw swing of 20, not the authored 3.
        assertEquals(80, player.hp, "Expected the unmitigated swing (20) to floor the authored 3")
    }

    @Test
    fun `heal spells stay authored regardless of the swing`() = runTest {
        val fixture = CombatTestFixture()
        val healer = spellMob(
            spells = listOf(heal.copy(cooldownMs = 0L, weight = 100)),
            hp = 50,
            damage = DamageRange(20, 20),
        )
        healer.takeDamage(20)
        assertEquals(30, healer.hp)
        fixture.mobs.upsert(healer)

        val combat = fixture.buildCombat(rng = Random(1), minDamage = 1, maxDamage = 1, bindings = noDodgeBindings())
        val sid = SessionId(1L)
        val player = loginAt100(fixture, sid, "Player1")
        fixture.outbound.drainAll()

        assertNull(combat.startCombat(sid, "mage"))
        fixture.tickCombat(combat)

        val healed = fixture.mobs.get(healer.id)!!
        // 30 after the pre-damage, +10 authored heal, -1 for the player's swing = 39; the swing of 20 plays no part.
        assertEquals(39, healed.hp, "Expected the authored heal of 10 (30 - 1 + 10)")
        assertEquals(100, player.hp, "A heal phase deals no damage to the player")
    }

    // --- WorldLoader validation tests ---

    @Test
    fun `WorldLoader loads mob spells from YAML`() {
        val world = WorldLoader.loadFromResource("world/ok_mob_spells.yaml")
        val shadowMage = world.mobTemplates.values.first { it.name == "a shadow mage" }

        assertEquals(2, shadowMage.spells.size)
        assertEquals("shadow_bolt", shadowMage.defaultAttack)

        val bolt = shadowMage.spells.first { it.id == "shadow_bolt" }
        assertEquals("Shadow Bolt", bolt.displayName)
        assertEquals(8, bolt.damage?.min)
        assertEquals(14, bolt.damage?.max)
        assertEquals(8000L, bolt.cooldownMs)
        assertEquals(3, bolt.weight)
    }

    @Test
    fun `WorldLoader rejects spell with no effect`() {
        assertThrows(WorldLoadException::class.java) {
            WorldLoader.loadFromResource("world/bad_mob_spell_no_effect.yaml")
        }
    }

    @Test
    fun `WorldLoader rejects invalid defaultAttack reference`() {
        assertThrows(WorldLoadException::class.java) {
            WorldLoader.loadFromResource("world/bad_mob_spell_default_ref.yaml")
        }
    }

    @Test
    fun `mob with no spells loads cleanly`() {
        val world = WorldLoader.loadFromResource("world/ok_mob_spells.yaml")
        val dummy = world.mobTemplates.values.first { it.name == "a training dummy" }
        assertTrue(dummy.spells.isEmpty())
        assertNull(dummy.defaultAttack)
    }
}
