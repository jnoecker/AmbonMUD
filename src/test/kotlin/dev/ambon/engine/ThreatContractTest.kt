package dev.ambon.engine

import dev.ambon.config.StatBindingsConfig
import dev.ambon.domain.PlayerClassDef
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.engine.abilities.scaledTauntThreat
import dev.ambon.engine.status.StatusEffectDefinition
import dev.ambon.engine.status.StatusEffectId
import dev.ambon.test.CombatTestFixture
import dev.ambon.test.drainAll
import dev.ambon.test.loginOrFail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.pow

/**
 * D-23 group and threat contract: ability and periodic damage build threat through the class
 * multiplier, taunt flat threat rides the ability level curve, and kill gold splits like kill XP.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreatContractTest {
    private fun tankRegistry(): PlayerClassRegistry =
        PlayerClassRegistry().also {
            it.register(
                PlayerClassDef(
                    id = "TANK",
                    displayName = "Tank",
                    hpScalingRate = 1.1,
                    manaScalingRate = 1.05,
                    threatMultiplier = 2.0,
                ),
            )
        }

    private fun goblin(
        fixture: CombatTestFixture,
        hp: Int = 200,
        gold: Long = 0L,
    ): MobState =
        MobState(
            MobId("zone:goblin"),
            "a goblin",
            fixture.roomId,
            hp = hp,
            maxHp = hp,
            xpReward = 100L,
            goldMin = gold,
            goldMax = gold,
        ).also { fixture.mobs.upsert(it) }

    @Test
    fun `ability damage threat carries the class multiplier`() =
        runTest {
            val fixture = CombatTestFixture()
            val combat = fixture.buildCombat(classRegistry = tankRegistry())
            val sid = SessionId(1L)
            fixture.players.loginOrFail(sid, "Hero")
            fixture.players.get(sid)!!.playerClass = "TANK"
            val mob = goblin(fixture)
            assertNull(combat.startCombat(sid, "goblin"))

            combat.addDamageThreat(mob.id, sid, 100.0)

            // engagement seeds the multiplier itself, then 100 damage x 2.0
            assertEquals(2.0 + 200.0, combat.threatTable.getThreat(mob.id, sid), 1e-9)
        }

    @Test
    fun `periodic damage builds threat for its source through the same multiplier`() =
        runTest {
            val fixture = CombatTestFixture()
            val bleed =
                StatusEffectDefinition(
                    id = StatusEffectId("bleed"),
                    displayName = "Bleeding",
                    effectType = "dot",
                    durationMs = 8_000L,
                    tickIntervalMs = 2_000L,
                    tickMinValue = 4,
                    tickMaxValue = 4,
                )
            val statusEffects = fixture.buildStatusEffects(bleed)
            val combat = fixture.buildCombat(classRegistry = tankRegistry(), statusEffects = statusEffects)
            statusEffects.onMobPeriodicDamage = { mobId, source, damage -> combat.addDamageThreat(mobId, source, damage.toDouble()) }
            val sid = SessionId(1L)
            fixture.players.loginOrFail(sid, "Hero")
            fixture.players.get(sid)!!.playerClass = "TANK"
            val mob = goblin(fixture)
            assertNull(combat.startCombat(sid, "goblin"))
            fixture.outbound.drainAll()
            val before = combat.threatTable.getThreat(mob.id, sid)

            assertTrue(statusEffects.applyToMob(mob.id, StatusEffectId("bleed"), sourceSessionId = sid, casterLevel = 1))
            fixture.clock.advance(2_000L)
            statusEffects.tick(fixture.clock.millis())

            val dealt = mob.maxHp - mob.hp
            assertTrue(dealt > 0, "the bleed should have ticked")
            assertEquals(before + dealt * 2.0, combat.threatTable.getThreat(mob.id, sid), 1e-9)
        }

    @Test
    fun `taunt flat threat rides the ability level curve`() {
        val bindings = StatBindingsConfig(spellLevelScalingRate = 1.1)
        assertEquals(300.0, scaledTauntThreat(300.0, 1, bindings), 1e-9)
        assertEquals(300.0 * 1.1.pow(10), scaledTauntThreat(300.0, 11, bindings), 1e-9)
        assertEquals(300.0, scaledTauntThreat(300.0, 0, bindings), 1e-9)
    }

    @Test
    fun `kill gold is split among group members in the room with the remainder to the killer`() =
        runTest {
            val fixture = CombatTestFixture()
            val group = GroupSystem(fixture.players, fixture.outbound, fixture.clock)
            val combat = fixture.buildCombat(groupSystem = group)
            val killer = SessionId(1L)
            val ally = SessionId(2L)
            fixture.players.loginOrFail(killer, "Alice")
            fixture.players.loginOrFail(ally, "Bob")
            assertNull(group.invite(killer, "Bob"))
            assertNull(group.accept(ally))
            val killerGold = fixture.players.get(killer)!!.gold
            val allyGold = fixture.players.get(ally)!!.gold
            goblin(fixture, hp = 1, gold = 101L)
            assertNull(combat.startCombat(killer, "goblin"))
            fixture.outbound.drainAll()

            fixture.tickCombat(combat)

            assertEquals(killerGold + 51L, fixture.players.get(killer)!!.gold, "killer takes the share plus the remainder")
            assertEquals(allyGold + 50L, fixture.players.get(ally)!!.gold, "the ally in the room takes an equal share")
        }

    @Test
    fun `solo kill gold is unchanged`() =
        runTest {
            val fixture = CombatTestFixture()
            val combat = fixture.buildCombat()
            val sid = SessionId(1L)
            fixture.players.loginOrFail(sid, "Hero")
            val start = fixture.players.get(sid)!!.gold
            goblin(fixture, hp = 1, gold = 40L)
            assertNull(combat.startCombat(sid, "goblin"))

            fixture.tickCombat(combat)

            assertEquals(start + 40L, fixture.players.get(sid)!!.gold)
        }
}
