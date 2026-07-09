package dev.ambon.engine

import dev.ambon.config.LevelRewardsConfig
import dev.ambon.config.ProgressionConfig
import dev.ambon.config.XpCurveConfig
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.test.CombatTestFixture
import dev.ambon.test.drainAll
import dev.ambon.test.loginOrFail
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Regression tests for the Akathavae pledge loophole (#1387): pledged players
 * must never receive kill XP from group members' kills, and the fighters'
 * split must pay out as if the pledged member were not there at all.
 */
class AkathavaeGroupKillXpTest {
    private val pledgeMessage = "Your pledge holds — the kill earns you nothing. Record the world instead."

    /** XP curve big enough that no test kill ever levels anyone up. */
    private fun flatProgression(): PlayerProgression =
        PlayerProgression(
            ProgressionConfig(
                maxLevel = 20,
                xp =
                    XpCurveConfig(
                        baseXp = 1_000_000L,
                        exponent = 2.0,
                        linearXp = 0L,
                        multiplier = 1.0,
                        defaultKillXp = 100L,
                    ),
                rewards = LevelRewardsConfig(),
            ),
        )

    private fun spawnRat(
        fixture: CombatTestFixture,
        key: String,
    ): MobState {
        val mob =
            MobState(
                MobId("demo:$key"),
                "a rat",
                fixture.roomId,
                hp = 1,
                maxHp = 1,
                xpReward = 100L,
            )
        fixture.mobs.upsert(mob)
        return mob
    }

    private suspend fun formGroup(
        group: GroupSystem,
        leader: SessionId,
        members: List<Pair<SessionId, String>>,
    ) {
        for ((sid, name) in members) {
            assertNull(group.invite(leader, name))
            assertNull(group.accept(sid))
        }
    }

    @Test
    fun `pledged member earns nothing and fighters split as a two-member group`() =
        runTest {
            val fixture = CombatTestFixture()
            val group = GroupSystem(fixture.players, fixture.outbound, fixture.clock)
            val combat =
                fixture.buildCombat(
                    progression = flatProgression(),
                    groupSystem = group,
                    groupXpBonusPerMember = 0.10,
                )

            val fighter1 = SessionId(1L)
            val fighter2 = SessionId(2L)
            val pledged = SessionId(3L)
            fixture.players.loginOrFail(fighter1, "Alice")
            fixture.players.loginOrFail(fighter2, "Bob")
            fixture.players.loginOrFail(pledged, "Chronicler")
            fixture.players.get(pledged)!!.isAkathavae = true

            formGroup(group, fighter1, listOf(fighter2 to "Bob", pledged to "Chronicler"))
            fixture.outbound.drainAll()

            spawnRat(fixture, "rat1")
            assertNull(combat.startCombat(fighter1, "rat"))
            fixture.tickCombat(combat)

            // 100 XP mob split across the 2 eligible fighters with one group-bonus
            // step: (100 / 2) * 1.10 = 55 each — exactly the 2-member payout.
            assertEquals(55L, fixture.players.get(fighter1)!!.xpTotal, "killer should get the 2-member split")
            assertEquals(55L, fixture.players.get(fighter2)!!.xpTotal, "fighter should get the 2-member split")
            assertEquals(0L, fixture.players.get(pledged)!!.xpTotal, "pledged member must earn no kill XP")
        }

    @Test
    fun `fighter payout with a pledged member matches a group without them`() =
        runTest {
            // Control: two fighters, no Akathavae.
            val control = CombatTestFixture()
            val controlGroup = GroupSystem(control.players, control.outbound, control.clock)
            val controlCombat =
                control.buildCombat(
                    progression = flatProgression(),
                    groupSystem = controlGroup,
                    groupXpBonusPerMember = 0.10,
                )
            val c1 = SessionId(1L)
            val c2 = SessionId(2L)
            control.players.loginOrFail(c1, "Alice")
            control.players.loginOrFail(c2, "Bob")
            formGroup(controlGroup, c1, listOf(c2 to "Bob"))
            spawnRat(control, "rat1")
            assertNull(controlCombat.startCombat(c1, "rat"))
            control.tickCombat(controlCombat)

            // Same two fighters plus a pledged Akathavae along for the kill.
            val fixture = CombatTestFixture()
            val group = GroupSystem(fixture.players, fixture.outbound, fixture.clock)
            val combat =
                fixture.buildCombat(
                    progression = flatProgression(),
                    groupSystem = group,
                    groupXpBonusPerMember = 0.10,
                )
            val f1 = SessionId(1L)
            val f2 = SessionId(2L)
            val pledged = SessionId(3L)
            fixture.players.loginOrFail(f1, "Alice")
            fixture.players.loginOrFail(f2, "Bob")
            fixture.players.loginOrFail(pledged, "Chronicler")
            fixture.players.get(pledged)!!.isAkathavae = true
            formGroup(group, f1, listOf(f2 to "Bob", pledged to "Chronicler"))
            spawnRat(fixture, "rat1")
            assertNull(combat.startCombat(f1, "rat"))
            fixture.tickCombat(combat)

            assertEquals(control.players.get(c1)!!.xpTotal, fixture.players.get(f1)!!.xpTotal)
            assertEquals(control.players.get(c2)!!.xpTotal, fixture.players.get(f2)!!.xpTotal)
        }

    @Test
    fun `non-Akathavae three-member group split is unchanged`() =
        runTest {
            val fixture = CombatTestFixture()
            val group = GroupSystem(fixture.players, fixture.outbound, fixture.clock)
            val combat =
                fixture.buildCombat(
                    progression = flatProgression(),
                    groupSystem = group,
                    groupXpBonusPerMember = 0.10,
                )

            val f1 = SessionId(1L)
            val f2 = SessionId(2L)
            val f3 = SessionId(3L)
            fixture.players.loginOrFail(f1, "Alice")
            fixture.players.loginOrFail(f2, "Bob")
            fixture.players.loginOrFail(f3, "Cara")
            formGroup(group, f1, listOf(f2 to "Bob", f3 to "Cara"))

            spawnRat(fixture, "rat1")
            assertNull(combat.startCombat(f1, "rat"))
            fixture.tickCombat(combat)

            // (100 / 3) * 1.20 = 40 each (toLong truncation of 33.33 * 1.2).
            for (sid in listOf(f1, f2, f3)) {
                assertEquals(40L, fixture.players.get(sid)!!.xpTotal, "3-member split should be unchanged")
            }
        }

    @Test
    fun `pledge flavor message is sent once per login not on every kill`() =
        runTest {
            val fixture = CombatTestFixture()
            val group = GroupSystem(fixture.players, fixture.outbound, fixture.clock)
            val combat =
                fixture.buildCombat(
                    progression = flatProgression(),
                    groupSystem = group,
                    groupXpBonusPerMember = 0.10,
                )

            val fighter = SessionId(1L)
            val pledged = SessionId(2L)
            fixture.players.loginOrFail(fighter, "Alice")
            fixture.players.loginOrFail(pledged, "Chronicler")
            fixture.players.get(pledged)!!.isAkathavae = true
            formGroup(group, fighter, listOf(pledged to "Chronicler"))
            fixture.outbound.drainAll()

            spawnRat(fixture, "rat1")
            assertNull(combat.startCombat(fighter, "rat"))
            fixture.tickCombat(combat)

            spawnRat(fixture, "rat2")
            assertNull(combat.startCombat(fighter, "rat"))
            fixture.tickCombat(combat)

            val pledgeMessages =
                fixture.outbound
                    .drainAll()
                    .filterIsInstance<OutboundEvent.SendText>()
                    .filter { it.sessionId == pledged && it.text == pledgeMessage }
            assertEquals(1, pledgeMessages.size, "pledge reminder should fire exactly once per login")
            assertEquals(0L, fixture.players.get(pledged)!!.xpTotal)
        }
}
