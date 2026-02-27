package dev.ambon.engine

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.achievement.AchievementCategory
import dev.ambon.domain.achievement.AchievementCriterion
import dev.ambon.domain.achievement.AchievementDef
import dev.ambon.domain.achievement.AchievementRewards
import dev.ambon.domain.achievement.CriterionType
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.drainAll
import dev.ambon.test.loginOrFail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AchievementSystemTest {
    private val roomId = RoomId("zone:room")

    private fun setup(vararg achievements: AchievementDef): Triple<AchievementSystem, PlayerRegistry, LocalOutboundBus> {
        val items = ItemRegistry()
        val players = dev.ambon.test.buildTestPlayerRegistry(roomId, InMemoryPlayerRepository(), items)
        val outbound = LocalOutboundBus()
        val registry = AchievementRegistry()
        achievements.forEach { registry.register(it) }
        val system =
            AchievementSystem(
                registry = registry,
                players = players,
                outbound = outbound,
            )
        return Triple(system, players, outbound)
    }

    // ── KILL criteria ─────────────────────────────────────────────────────────

    @Test
    fun `onMobKilled increments KILL criterion progress`() =
        runTest {
            val ach =
                AchievementDef(
                    id = "combat/slayer",
                    displayName = "Slayer",
                    description = "Kill 3 rats.",
                    category = AchievementCategory.COMBAT,
                    criteria =
                        listOf(
                            AchievementCriterion(
                                type = CriterionType.KILL,
                                targetId = "zone:rat",
                                count = 3,
                                description = "Kill 3 rats",
                            ),
                        ),
                )
            val (sys, players, _) = setup(ach)
            val sid = SessionId(1L)
            players.loginOrFail(sid, "Hero")

            sys.onMobKilled(sid, "zone:rat")

            val ps = players.get(sid)!!
            assertFalse(ps.unlockedAchievementIds.contains("combat/slayer"))
            val prog = ps.achievementProgress["combat/slayer"]
            assertEquals(1, prog?.progress?.get(0)?.current)
        }

    @Test
    fun `onMobKilled unlocks achievement when kill count reaches required`() =
        runTest {
            val ach =
                AchievementDef(
                    id = "combat/slayer",
                    displayName = "Slayer",
                    description = "Kill 3 rats.",
                    category = AchievementCategory.COMBAT,
                    criteria =
                        listOf(
                            AchievementCriterion(
                                type = CriterionType.KILL,
                                targetId = "zone:rat",
                                count = 3,
                                description = "Kill 3 rats",
                            ),
                        ),
                    rewards = AchievementRewards(xp = 100L, gold = 10L),
                )
            val (sys, players, outbound) = setup(ach)
            val sid = SessionId(1L)
            players.loginOrFail(sid, "Hero")

            repeat(3) { sys.onMobKilled(sid, "zone:rat") }

            val ps = players.get(sid)!!
            assertTrue(ps.unlockedAchievementIds.contains("combat/slayer"))
            assertFalse(ps.achievementProgress.containsKey("combat/slayer"))
            assertEquals(10L, ps.gold)

            val msgs = outbound.drainAll().filterIsInstance<OutboundEvent.SendInfo>()
            assertTrue(msgs.any { it.text.contains("Slayer") })
        }

    @Test
    fun `onMobKilled with blank targetId matches any mob`() =
        runTest {
            val ach =
                AchievementDef(
                    id = "combat/first_blood",
                    displayName = "First Blood",
                    description = "Kill anything.",
                    category = AchievementCategory.COMBAT,
                    criteria =
                        listOf(
                            AchievementCriterion(
                                type = CriterionType.KILL,
                                targetId = "",
                                count = 1,
                                description = "Kill any mob",
                            ),
                        ),
                )
            val (sys, players, _) = setup(ach)
            val sid = SessionId(1L)
            players.loginOrFail(sid, "Hero")

            sys.onMobKilled(sid, "zone:whatever_mob")

            val ps = players.get(sid)!!
            assertTrue(ps.unlockedAchievementIds.contains("combat/first_blood"))
        }

    @Test
    fun `already unlocked achievement is not double-awarded`() =
        runTest {
            val ach =
                AchievementDef(
                    id = "combat/first_blood",
                    displayName = "First Blood",
                    description = "Kill anything.",
                    category = AchievementCategory.COMBAT,
                    criteria =
                        listOf(
                            AchievementCriterion(
                                type = CriterionType.KILL,
                                targetId = "",
                                count = 1,
                            ),
                        ),
                    rewards = AchievementRewards(gold = 50L),
                )
            val (sys, players, _) = setup(ach)
            val sid = SessionId(1L)
            players.loginOrFail(sid, "Hero")

            sys.onMobKilled(sid, "zone:rat")
            sys.onMobKilled(sid, "zone:rat") // second kill should not re-award

            val ps = players.get(sid)!!
            assertEquals(50L, ps.gold) // only awarded once
        }

    @Test
    fun `non-matching mob template does not progress KILL criterion`() =
        runTest {
            val ach =
                AchievementDef(
                    id = "combat/wolf_hunter",
                    displayName = "Wolf Hunter",
                    description = "Kill wolves.",
                    category = AchievementCategory.COMBAT,
                    criteria =
                        listOf(
                            AchievementCriterion(
                                type = CriterionType.KILL,
                                targetId = "zone:wolf",
                                count = 1,
                            ),
                        ),
                )
            val (sys, players, _) = setup(ach)
            val sid = SessionId(1L)
            players.loginOrFail(sid, "Hero")

            sys.onMobKilled(sid, "zone:rat") // wrong mob

            val ps = players.get(sid)!!
            assertFalse(ps.unlockedAchievementIds.contains("combat/wolf_hunter"))
            assertNull(ps.achievementProgress["combat/wolf_hunter"])
        }

    // ── REACH_LEVEL criteria ──────────────────────────────────────────────────

    @Test
    fun `onLevelReached unlocks REACH_LEVEL achievement when level threshold met`() =
        runTest {
            val ach =
                AchievementDef(
                    id = "milestone/level_5",
                    displayName = "Level 5",
                    description = "Reach level 5.",
                    category = AchievementCategory.CLASS,
                    criteria =
                        listOf(
                            AchievementCriterion(
                                type = CriterionType.REACH_LEVEL,
                                count = 5,
                                description = "Reach level 5",
                            ),
                        ),
                    rewards = AchievementRewards(title = "Adventurer"),
                )
            val (sys, players, outbound) = setup(ach)
            val sid = SessionId(1L)
            players.loginOrFail(sid, "Hero")

            sys.onLevelReached(sid, 5)

            val ps = players.get(sid)!!
            assertTrue(ps.unlockedAchievementIds.contains("milestone/level_5"))

            val msgs = outbound.drainAll().filterIsInstance<OutboundEvent.SendText>()
            assertTrue(msgs.any { it.text.contains("Adventurer") })
        }

    @Test
    fun `onLevelReached does not unlock if level below threshold`() =
        runTest {
            val ach =
                AchievementDef(
                    id = "milestone/level_10",
                    displayName = "Level 10",
                    description = "Reach level 10.",
                    category = AchievementCategory.CLASS,
                    criteria =
                        listOf(
                            AchievementCriterion(
                                type = CriterionType.REACH_LEVEL,
                                count = 10,
                            ),
                        ),
                )
            val (sys, players, _) = setup(ach)
            val sid = SessionId(1L)
            players.loginOrFail(sid, "Hero")

            sys.onLevelReached(sid, 5)

            val ps = players.get(sid)!!
            assertFalse(ps.unlockedAchievementIds.contains("milestone/level_10"))
        }

    // ── QUEST_COMPLETE criteria ───────────────────────────────────────────────

    @Test
    fun `onQuestCompleted unlocks QUEST_COMPLETE achievement`() =
        runTest {
            val ach =
                AchievementDef(
                    id = "quests/first_quest",
                    displayName = "First Quest",
                    description = "Complete any quest.",
                    category = AchievementCategory.SOCIAL,
                    criteria =
                        listOf(
                            AchievementCriterion(
                                type = CriterionType.QUEST_COMPLETE,
                                targetId = "zone:tutorial",
                                count = 1,
                            ),
                        ),
                )
            val (sys, players, _) = setup(ach)
            val sid = SessionId(1L)
            players.loginOrFail(sid, "Hero")

            sys.onQuestCompleted(sid, "zone:tutorial")

            val ps = players.get(sid)!!
            assertTrue(ps.unlockedAchievementIds.contains("quests/first_quest"))
        }

    @Test
    fun `onQuestCompleted with blank targetId matches any quest`() =
        runTest {
            val ach =
                AchievementDef(
                    id = "quests/any",
                    displayName = "Quester",
                    description = "Complete a quest.",
                    category = AchievementCategory.SOCIAL,
                    criteria =
                        listOf(
                            AchievementCriterion(
                                type = CriterionType.QUEST_COMPLETE,
                                targetId = "",
                                count = 1,
                            ),
                        ),
                )
            val (sys, players, _) = setup(ach)
            val sid = SessionId(1L)
            players.loginOrFail(sid, "Hero")

            sys.onQuestCompleted(sid, "zone:whatever")

            val ps = players.get(sid)!!
            assertTrue(ps.unlockedAchievementIds.contains("quests/any"))
        }

    // ── Title system ──────────────────────────────────────────────────────────

    @Test
    fun `availableTitles returns only unlocked achievements with title rewards`() =
        runTest {
            val achWithTitle =
                AchievementDef(
                    id = "combat/slayer",
                    displayName = "Slayer",
                    description = "Kill anything.",
                    category = AchievementCategory.COMBAT,
                    criteria =
                        listOf(
                            AchievementCriterion(
                                type = CriterionType.KILL,
                                targetId = "",
                                count = 1,
                            ),
                        ),
                    rewards = AchievementRewards(title = "The Slayer"),
                )
            val achNoTitle =
                AchievementDef(
                    id = "combat/runner",
                    displayName = "Runner",
                    description = "Kill 10 things.",
                    category = AchievementCategory.COMBAT,
                    criteria =
                        listOf(
                            AchievementCriterion(
                                type = CriterionType.KILL,
                                targetId = "",
                                count = 10,
                            ),
                        ),
                )
            val (sys, players, _) = setup(achWithTitle, achNoTitle)
            val sid = SessionId(1L)
            players.loginOrFail(sid, "Hero")

            // Unlock the first achievement
            sys.onMobKilled(sid, "zone:rat")

            val titles = sys.availableTitles(sid)
            assertEquals(1, titles.size)
            assertEquals("The Slayer", titles[0].second)
        }

    @Test
    fun `formatAchievements hides hidden achievements until unlocked`() =
        runTest {
            val hidden =
                AchievementDef(
                    id = "secret/hidden",
                    displayName = "Secret",
                    description = "A secret.",
                    category = AchievementCategory.COMBAT,
                    criteria =
                        listOf(
                            AchievementCriterion(
                                type = CriterionType.KILL,
                                targetId = "zone:boss",
                                count = 1,
                            ),
                        ),
                    hidden = true,
                )
            val (sys, players, _) = setup(hidden)
            val sid = SessionId(1L)
            players.loginOrFail(sid, "Hero")

            val listBefore = sys.formatAchievements(sid)
            assertFalse(listBefore.contains("Secret"))
            assertTrue(listBefore.contains("????"))

            sys.onMobKilled(sid, "zone:boss")

            val listAfter = sys.formatAchievements(sid)
            assertTrue(listAfter.contains("Secret"))
        }

    @Test
    fun `two achievements both unlock from the same mob kill`() =
        runTest {
            val ach1 =
                AchievementDef(
                    id = "combat/first_blood",
                    displayName = "First Blood",
                    description = "Kill anything.",
                    category = AchievementCategory.COMBAT,
                    criteria =
                        listOf(
                            AchievementCriterion(
                                type = CriterionType.KILL,
                                targetId = "",
                                count = 1,
                            ),
                        ),
                    rewards = AchievementRewards(gold = 10L),
                )
            val ach2 =
                AchievementDef(
                    id = "combat/rat_slayer",
                    displayName = "Rat Slayer",
                    description = "Kill a rat.",
                    category = AchievementCategory.COMBAT,
                    criteria =
                        listOf(
                            AchievementCriterion(
                                type = CriterionType.KILL,
                                targetId = "zone:rat",
                                count = 1,
                            ),
                        ),
                    rewards = AchievementRewards(gold = 5L),
                )
            val (sys, players, _) = setup(ach1, ach2)
            val sid = SessionId(1L)
            players.loginOrFail(sid, "Hero")

            sys.onMobKilled(sid, "zone:rat")

            val ps = players.get(sid)!!
            assertTrue(ps.unlockedAchievementIds.contains("combat/first_blood"))
            assertTrue(ps.unlockedAchievementIds.contains("combat/rat_slayer"))
            assertEquals(15L, ps.gold) // both rewards granted
        }

    @Test
    fun `achievement with multiple criteria unlocks only when all criteria are met`() =
        runTest {
            val ach =
                AchievementDef(
                    id = "elite/veteran",
                    displayName = "Veteran",
                    description = "Kill 3 rats and reach level 5.",
                    category = AchievementCategory.COMBAT,
                    criteria =
                        listOf(
                            AchievementCriterion(
                                type = CriterionType.KILL,
                                targetId = "zone:rat",
                                count = 3,
                                description = "Kill 3 rats",
                            ),
                            AchievementCriterion(
                                type = CriterionType.REACH_LEVEL,
                                count = 5,
                                description = "Reach level 5",
                            ),
                        ),
                )
            val (sys, players, _) = setup(ach)
            val sid = SessionId(1L)
            players.loginOrFail(sid, "Hero")

            // KILL criterion met but REACH_LEVEL still pending — must not unlock
            repeat(3) { sys.onMobKilled(sid, "zone:rat") }
            assertFalse(players.get(sid)!!.unlockedAchievementIds.contains("elite/veteran"))

            // Both criteria now met — must unlock
            sys.onLevelReached(sid, 5)
            assertTrue(players.get(sid)!!.unlockedAchievementIds.contains("elite/veteran"))
        }

    @Test
    fun `setDisplayTitle updates player active title`() =
        runTest {
            val items = ItemRegistry()
            val players = dev.ambon.test.buildTestPlayerRegistry(roomId, InMemoryPlayerRepository(), items)
            val sid = SessionId(1L)
            players.loginOrFail(sid, "Hero")

            players.setDisplayTitle(sid, "Warrior")
            assertEquals("Warrior", players.get(sid)?.activeTitle)

            players.setDisplayTitle(sid, null)
            assertNull(players.get(sid)?.activeTitle)
        }
}
