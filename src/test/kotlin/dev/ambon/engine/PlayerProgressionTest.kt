package dev.ambon.engine

import dev.ambon.config.LevelRewardsConfig
import dev.ambon.config.ProgressionConfig
import dev.ambon.config.XpCurveConfig
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PlayerProgressionTest {
    @Test
    fun `xp curve follows predictable monotonic thresholds`() {
        val progression =
            PlayerProgression(
                ProgressionConfig(
                    maxLevel = 50,
                    xp = XpCurveConfig(baseXp = 100L, exponent = 2.0, linearXp = 0L),
                ),
            )

        assertEquals(0L, progression.totalXpForLevel(1))
        assertEquals(100L, progression.totalXpForLevel(2))
        assertEquals(400L, progression.totalXpForLevel(3))
        assertEquals(900L, progression.totalXpForLevel(4))
        assertEquals(1600L, progression.totalXpForLevel(5))
    }

    @Test
    fun `computeLevel is capped and xp breakdown matches current level`() {
        val progression =
            PlayerProgression(
                ProgressionConfig(
                    maxLevel = 5,
                    xp = XpCurveConfig(baseXp = 100L, exponent = 2.0, linearXp = 0L),
                ),
            )

        assertEquals(1, progression.computeLevel(0L))
        assertEquals(1, progression.computeLevel(99L))
        assertEquals(2, progression.computeLevel(100L))
        assertEquals(2, progression.computeLevel(399L))
        assertEquals(3, progression.computeLevel(400L))
        assertEquals(5, progression.computeLevel(100_000L))
        assertEquals(23L, progression.xpIntoLevel(423L))
        assertEquals(500L, progression.xpToNextLevel(423L))
        assertNull(progression.xpToNextLevel(100_000L))
    }

    @Test
    fun `grantXp applies level-up rewards and full-heal setting`() {
        val progression =
            PlayerProgression(
                ProgressionConfig(
                    maxLevel = 50,
                    xp = XpCurveConfig(baseXp = 100L, exponent = 2.0, linearXp = 0L),
                    rewards = LevelRewardsConfig(hpPerLevel = 2, fullHealOnLevelUp = true),
                ),
            )

        val player =
            PlayerState(
                sessionId = SessionId(1L),
                name = "Alice",
                roomId = RoomId("test:start"),
                hp = 3,
                maxHp = 10,
            )

        val result = progression.grantXp(player, 400L)

        assertEquals(2, result.levelsGained)
        assertEquals(3, result.newLevel)
        assertEquals(400L, result.xpTotal)
        assertEquals(3, player.level)
        assertEquals(400L, player.xpTotal)
        // Default Warrior class: hpPerLevel=4 → 10 + 4*2 = 18
        assertEquals(18, player.maxHp)
        assertEquals(18, player.hp)
    }

    @Test
    fun `grantXp does not heal when full-heal is disabled`() {
        val progression =
            PlayerProgression(
                ProgressionConfig(
                    maxLevel = 50,
                    xp = XpCurveConfig(baseXp = 100L, exponent = 2.0, linearXp = 0L),
                    rewards = LevelRewardsConfig(hpPerLevel = 2, fullHealOnLevelUp = false),
                ),
            )

        val player =
            PlayerState(
                sessionId = SessionId(2L),
                name = "Bob",
                roomId = RoomId("test:start"),
                hp = 3,
                maxHp = 10,
            )

        progression.grantXp(player, 100L)

        assertEquals(2, player.level)
        assertEquals(100L, player.xpTotal)
        // Default Warrior class: hpPerLevel=4 → 10 + 4*1 = 14
        assertEquals(14, player.maxHp)
        assertEquals(3, player.hp)
    }

    @Test
    fun `grantXp uses level formula as max hp source of truth`() {
        val progression =
            PlayerProgression(
                ProgressionConfig(
                    maxLevel = 50,
                    xp = XpCurveConfig(baseXp = 100L, exponent = 2.0, linearXp = 0L),
                    rewards = LevelRewardsConfig(hpPerLevel = 2, fullHealOnLevelUp = true),
                ),
            )

        val player =
            PlayerState(
                sessionId = SessionId(3L),
                name = "Cara",
                roomId = RoomId("test:start"),
                baseMaxHp = 99,
                hp = 50,
                maxHp = 99,
            )

        progression.grantXp(player, 100L)

        assertEquals(2, player.level)
        // Default Warrior class: hpPerLevel=4 → 10 + 4*1 = 14
        assertEquals(14, player.baseMaxHp)
        assertEquals(14, player.maxHp)
        assertEquals(14, player.hp)
    }
}
