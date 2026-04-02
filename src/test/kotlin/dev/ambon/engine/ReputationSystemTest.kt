package dev.ambon.engine

import dev.ambon.config.FactionConfig
import dev.ambon.config.FactionDefinition
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ReputationSystemTest {
    private val sid = SessionId(1L)

    private val config = FactionConfig(
        definitions = mapOf(
            "guild_a" to FactionDefinition(
                name = "Guild Alpha",
                description = "The first guild",
                enemies = listOf("guild_b"),
            ),
            "guild_b" to FactionDefinition(
                name = "Guild Beta",
                description = "The second guild",
                enemies = listOf("guild_a"),
            ),
        ),
        defaultReputation = 0,
        killPenalty = 5,
        killBonus = 3,
        questRewards = mapOf(
            "quest_alpha" to mapOf("guild_a" to 100),
            "quest_both" to mapOf("guild_a" to 50, "guild_b" to -25),
        ),
    )

    private lateinit var rep: ReputationSystem
    private lateinit var player: PlayerState

    @BeforeEach
    fun setUp() {
        rep = ReputationSystem(config = config)
        player = PlayerState(
            sessionId = sid,
            name = "Tester",
            roomId = RoomId("test:room"),
        )
    }

    @Nested
    inner class Standings {
        @Test
        fun `default standing is 0`() {
            assertEquals(0, rep.getStanding(player, "guild_a"))
        }

        @Test
        fun `adjust standing changes value`() {
            val newVal = rep.adjustStanding(player, "guild_a", 100)
            assertEquals(100, newVal)
            assertEquals(100, rep.getStanding(player, "guild_a"))
        }

        @Test
        fun `standing clamped to -1000 to 1000`() {
            rep.adjustStanding(player, "guild_a", 2000)
            assertEquals(1000, rep.getStanding(player, "guild_a"))

            rep.adjustStanding(player, "guild_b", -3000)
            assertEquals(-1000, rep.getStanding(player, "guild_b"))
        }

        @Test
        fun `adjust unknown faction returns null`() {
            assertNull(rep.adjustStanding(player, "nonexistent", 50))
        }

        @Test
        fun `allStandings returns all factions`() {
            rep.adjustStanding(player, "guild_a", 100)
            val standings = rep.allStandings(player)
            assertEquals(2, standings.size)
            assertEquals(100, standings["guild_a"])
            assertEquals(0, standings["guild_b"])
        }
    }

    @Nested
    inner class StandingTiers {
        @Test
        fun `tier for various reputation values`() {
            assertEquals(StandingTier.NEUTRAL, StandingTier.forReputation(0))
            assertEquals(StandingTier.NEUTRAL, StandingTier.forReputation(-100))
            assertEquals(StandingTier.FRIENDLY, StandingTier.forReputation(100))
            assertEquals(StandingTier.HONORED, StandingTier.forReputation(500))
            assertEquals(StandingTier.REVERED, StandingTier.forReputation(1000))
            assertEquals(StandingTier.UNFRIENDLY, StandingTier.forReputation(-500))
            assertEquals(StandingTier.HOSTILE, StandingTier.forReputation(-1000))
            assertEquals(StandingTier.HATED, StandingTier.forReputation(-1001))
        }
    }

    @Nested
    inner class MobKills {
        @Test
        fun `killing mob of faction A loses rep with A`() {
            val changes = rep.onMobKilled(player, "guild_a", 1)
            assertTrue(changes.any { it.factionId == "guild_a" && it.amount < 0 })
        }

        @Test
        fun `killing mob of faction A gains rep with enemy B`() {
            val changes = rep.onMobKilled(player, "guild_a", 1)
            assertTrue(changes.any { it.factionId == "guild_b" && it.amount > 0 })
        }

        @Test
        fun `killing mob with no faction returns empty`() {
            val changes = rep.onMobKilled(player, null, 1)
            assertTrue(changes.isEmpty())
        }
    }

    @Nested
    inner class QuestRewards {
        @Test
        fun `quest completion awards faction rep`() {
            val changes = rep.onQuestCompleted(player, "quest_alpha")
            assertEquals(1, changes.size)
            assertEquals("guild_a", changes[0].factionId)
            assertEquals(100, changes[0].amount)
            assertEquals(100, rep.getStanding(player, "guild_a"))
        }

        @Test
        fun `quest with multi-faction rewards`() {
            val changes = rep.onQuestCompleted(player, "quest_both")
            assertEquals(2, changes.size)
            assertEquals(50, rep.getStanding(player, "guild_a"))
            assertEquals(-25, rep.getStanding(player, "guild_b"))
        }

        @Test
        fun `quest with no faction rewards returns empty`() {
            val changes = rep.onQuestCompleted(player, "unknown_quest")
            assertTrue(changes.isEmpty())
        }
    }

    @Nested
    inner class Requirements {
        @Test
        fun `meets requirement with sufficient rep`() {
            rep.adjustStanding(player, "guild_a", 500)
            assertTrue(rep.meetsRequirement(player, "guild_a", 100))
        }

        @Test
        fun `fails requirement with insufficient rep`() {
            assertFalse(rep.meetsRequirement(player, "guild_a", 100))
        }
    }
}
