package dev.ambon.engine

import dev.ambon.config.LevelRewardsConfig
import dev.ambon.config.ProgressionConfig
import dev.ambon.config.UnderLevelXpBonusConfig
import dev.ambon.config.XpCurveConfig
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.test.CombatTestFixture
import dev.ambon.test.drainAll
import dev.ambon.test.loginOrFail
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * D-31 group reward rules: the group XP bonus is withheld from a member more than
 * `xpBonusLevelGap` levels below the mob (the carried passenger still divides the pot),
 * and the integer kill-gold remainder rotates through the members instead of always
 * landing on the killing blow.
 */
class GroupRewardRuleTest {
    /** A curve nobody levels on, with the punch-up bonus off so the gap rule is measured alone. */
    private fun flatProgression(): PlayerProgression =
        PlayerProgression(
            ProgressionConfig(
                maxLevel = 40,
                xp =
                    XpCurveConfig(
                        baseXp = 1_000_000L,
                        exponent = 2.0,
                        linearXp = 0L,
                        multiplier = 1.0,
                        defaultKillXp = 100L,
                        underLevelBonus = UnderLevelXpBonusConfig(enabled = false),
                    ),
                rewards = LevelRewardsConfig(),
            ),
        )

    private fun spawnMob(
        fixture: CombatTestFixture,
        key: String,
        level: Int,
        gold: Long = 0L,
        name: String = "a rat",
    ): MobState {
        val mob =
            MobState(
                MobId("demo:$key"),
                name,
                fixture.roomId,
                hp = 1,
                maxHp = 1,
                xpReward = 100L,
                goldMin = gold,
                goldMax = gold,
                level = level,
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
    fun `a member more than the level gap below the mob gets the split without the bonus`() =
        runTest {
            val fixture = CombatTestFixture()
            val group = GroupSystem(fixture.players, fixture.outbound, fixture.clock)
            val combat =
                fixture.buildCombat(
                    progression = flatProgression(),
                    groupSystem = group,
                    groupXpBonusPerMember = 0.6,
                    groupXpBonusLevelGap = 4,
                )
            val peer = SessionId(1L)
            val edge = SessionId(2L)
            val passenger = SessionId(3L)
            fixture.players.loginOrFail(peer, "Alice")
            fixture.players.loginOrFail(edge, "Bob")
            fixture.players.loginOrFail(passenger, "Carol")
            fixture.players.get(peer)!!.level = 10
            fixture.players.get(edge)!!.level = 6 // exactly the gap: still a peer
            fixture.players.get(passenger)!!.level = 5 // one past it: carried
            formGroup(group, peer, listOf(edge to "Bob", passenger to "Carol"))
            fixture.outbound.drainAll()

            spawnMob(fixture, "rat", level = 10)
            assertNull(combat.startCombat(peer, "rat"))
            fixture.tickCombat(combat)

            // 100 XP over three members = 33.3 a share; the bonus at 0.6 x 2 steps is x2.2 -> 73.
            assertEquals(73L, fixture.players.get(peer)!!.xpTotal, "a peer collects the group bonus")
            assertEquals(73L, fixture.players.get(edge)!!.xpTotal, "a member exactly at the gap is a peer")
            assertEquals(33L, fixture.players.get(passenger)!!.xpTotal, "the passenger gets the plain split share")
        }

    @Test
    fun `a zero gap keeps the bonus for everyone`() =
        runTest {
            val fixture = CombatTestFixture()
            val group = GroupSystem(fixture.players, fixture.outbound, fixture.clock)
            val combat =
                fixture.buildCombat(
                    progression = flatProgression(),
                    groupSystem = group,
                    groupXpBonusPerMember = 0.6,
                    groupXpBonusLevelGap = 0,
                )
            val peer = SessionId(1L)
            val passenger = SessionId(2L)
            fixture.players.loginOrFail(peer, "Alice")
            fixture.players.loginOrFail(passenger, "Carol")
            fixture.players.get(peer)!!.level = 10
            fixture.players.get(passenger)!!.level = 1
            formGroup(group, peer, listOf(passenger to "Carol"))
            fixture.outbound.drainAll()

            spawnMob(fixture, "rat", level = 10)
            assertNull(combat.startCombat(peer, "rat"))
            fixture.tickCombat(combat)

            // (100 / 2) x 1.6 = 80 each: the pre-D-31 rule.
            assertEquals(80L, fixture.players.get(peer)!!.xpTotal)
            assertEquals(80L, fixture.players.get(passenger)!!.xpTotal)
        }

    @Test
    fun `the kill gold remainder rotates through the members`() =
        runTest {
            val fixture = CombatTestFixture()
            val group = GroupSystem(fixture.players, fixture.outbound, fixture.clock)
            val combat = fixture.buildCombat(progression = flatProgression(), groupSystem = group)
            val killer = SessionId(1L)
            val second = SessionId(2L)
            val third = SessionId(3L)
            fixture.players.loginOrFail(killer, "Alice")
            fixture.players.loginOrFail(second, "Bob")
            fixture.players.loginOrFail(third, "Carol")
            formGroup(group, killer, listOf(second to "Bob", third to "Carol"))
            fixture.outbound.drainAll()

            // A 4-gold roll among three: 1 each and a remainder of 1, to a different member each kill.
            spawnMob(fixture, "rat1", level = 1, gold = 4L)
            assertNull(combat.startCombat(killer, "rat"))
            fixture.tickCombat(combat)
            spawnMob(fixture, "bat", level = 1, gold = 4L, name = "a bat")
            assertNull(combat.startCombat(killer, "bat"))
            fixture.tickCombat(combat)

            assertEquals(3L, fixture.players.get(killer)!!.gold, "the killer took the first remainder only")
            assertEquals(3L, fixture.players.get(second)!!.gold, "the second member took the second remainder")
            assertEquals(2L, fixture.players.get(third)!!.gold, "the third member is next")
        }
}
