package dev.ambon.engine

import dev.ambon.config.ClassDefinitionConfig
import dev.ambon.config.ClassEngineConfig
import dev.ambon.config.DiminishingXpConfig
import dev.ambon.config.DiminishingXpThreshold
import dev.ambon.config.LevelRewardsConfig
import dev.ambon.config.ProgressionConfig
import dev.ambon.config.QuestBaselineConfig
import dev.ambon.config.QuestDifficulty
import dev.ambon.config.QuestXpConfig
import dev.ambon.config.XpCurveConfig
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PlayerProgressionTest {
    private fun defaultClassRegistry(): PlayerClassRegistry =
        PlayerClassRegistry().also { reg ->
            PlayerClassRegistryLoader.load(
                ClassEngineConfig(
                    definitions = mapOf(
                        "WARRIOR" to ClassDefinitionConfig(
                            displayName = "Warrior",
                            hpPerLevel = 8,
                            manaPerLevel = 4,
                            primaryStat = "STR",
                            threatMultiplier = 1.5,
                        ),
                        "MAGE" to ClassDefinitionConfig(
                            displayName = "Mage",
                            hpPerLevel = 4,
                            manaPerLevel = 16,
                            primaryStat = "INT",
                        ),
                    ),
                ),
                reg,
            )
        }

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
                playerClass = "",
            )

        val result = progression.grantXp(player, 400L)

        assertEquals(2, result.levelsGained)
        assertEquals(3, result.newLevel)
        assertEquals(400L, result.xpTotal)
        assertEquals(3, player.level)
        assertEquals(400L, player.xpTotal)
        assertEquals(14, player.maxHp)
        assertEquals(14, player.hp)
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
                playerClass = "",
            )

        progression.grantXp(player, 100L)

        assertEquals(2, player.level)
        assertEquals(100L, player.xpTotal)
        assertEquals(12, player.maxHp)
        assertEquals(3, player.hp)
    }

    @Test
    fun `warrior gains more hp per level than mage`() {
        val progression =
            PlayerProgression(
                ProgressionConfig(
                    maxLevel = 50,
                    xp = XpCurveConfig(baseXp = 100L, exponent = 2.0, linearXp = 0L),
                    rewards = LevelRewardsConfig(hpPerLevel = 3, manaPerLevel = 5, fullHealOnLevelUp = true),
                ),
                classRegistry = defaultClassRegistry(),
            )

        val warrior =
            PlayerState(
                sessionId = SessionId(10L),
                name = "War",
                roomId = RoomId("test:start"),
                hp = 10,
                maxHp = 10,
                playerClass = "WARRIOR",
            )

        val mage =
            PlayerState(
                sessionId = SessionId(11L),
                name = "Mag",
                roomId = RoomId("test:start"),
                hp = 10,
                maxHp = 10,
                playerClass = "MAGE",
            )

        progression.grantXp(warrior, 100L) // level 1 → 2
        progression.grantXp(mage, 100L) // level 1 → 2

        // Warrior: hpPerLevel=8 → 10 + 8 = 18
        assertEquals(18, warrior.maxHp, "Warrior HP should use class hpPerLevel=8")
        // Mage: hpPerLevel=4 → 10 + 4 = 14
        assertEquals(14, mage.maxHp, "Mage HP should use class hpPerLevel=4")
    }

    @Test
    fun `mage gains more mana per level than warrior`() {
        val progression =
            PlayerProgression(
                ProgressionConfig(
                    maxLevel = 50,
                    xp = XpCurveConfig(baseXp = 100L, exponent = 2.0, linearXp = 0L),
                    rewards =
                        LevelRewardsConfig(
                            hpPerLevel = 3,
                            manaPerLevel = 5,
                            fullHealOnLevelUp = true,
                            fullManaOnLevelUp = true,
                        ),
                ),
                classRegistry = defaultClassRegistry(),
            )

        val warrior =
            PlayerState(
                sessionId = SessionId(12L),
                name = "War",
                roomId = RoomId("test:start"),
                hp = 10,
                maxHp = 10,
                playerClass = "WARRIOR",
            )

        val mage =
            PlayerState(
                sessionId = SessionId(13L),
                name = "Mag",
                roomId = RoomId("test:start"),
                hp = 10,
                maxHp = 10,
                playerClass = "MAGE",
            )

        progression.grantXp(warrior, 100L) // level 1 → 2
        progression.grantXp(mage, 100L) // level 1 → 2

        // Warrior: manaPerLevel=4 → 20 + 4 = 24
        assertEquals(24, warrior.maxMana, "Warrior mana should use class manaPerLevel=4")
        // Mage: manaPerLevel=16 → 20 + 16 = 36
        assertEquals(36, mage.maxMana, "Mage mana should use class manaPerLevel=16")
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
                playerClass = "",
            )

        progression.grantXp(player, 100L)

        assertEquals(2, player.level)
        assertEquals(12, player.baseMaxHp)
        assertEquals(12, player.maxHp)
        assertEquals(12, player.hp)
    }

    @Test
    fun `diminishing returns applies largest matching threshold`() {
        val progression =
            PlayerProgression(
                ProgressionConfig(
                    xp = XpCurveConfig(
                        diminishing = DiminishingXpConfig(
                            enabled = true,
                            thresholds = listOf(
                                DiminishingXpThreshold(levelsBelow = 5, multiplier = 0.5),
                                DiminishingXpThreshold(levelsBelow = 10, multiplier = 0.1),
                            ),
                        ),
                    ),
                ),
            )

        assertEquals(1.0, progression.diminishingKillXpMultiplier(playerLevel = 3, mobLevel = 3))
        assertEquals(1.0, progression.diminishingKillXpMultiplier(playerLevel = 5, mobLevel = 1))
        assertEquals(0.5, progression.diminishingKillXpMultiplier(playerLevel = 6, mobLevel = 1))
        assertEquals(0.5, progression.diminishingKillXpMultiplier(playerLevel = 10, mobLevel = 1))
        assertEquals(0.1, progression.diminishingKillXpMultiplier(playerLevel = 11, mobLevel = 1))
        assertEquals(0.1, progression.diminishingKillXpMultiplier(playerLevel = 50, mobLevel = 1))
        assertEquals(1.0, progression.diminishingKillXpMultiplier(playerLevel = 1, mobLevel = 10))
    }

    @Test
    fun `diminishing returns disabled yields full xp`() {
        val progression =
            PlayerProgression(
                ProgressionConfig(
                    xp = XpCurveConfig(
                        diminishing = DiminishingXpConfig(
                            enabled = false,
                            thresholds = listOf(
                                DiminishingXpThreshold(levelsBelow = 1, multiplier = 0.0),
                            ),
                        ),
                    ),
                ),
            )

        assertEquals(1.0, progression.diminishingKillXpMultiplier(playerLevel = 50, mobLevel = 1))
    }

    @Test
    fun `computeQuestXp returns zero when no difficulty is declared`() {
        val progression = PlayerProgression()
        assertEquals(0L, progression.computeQuestXp(null, level = 5))
    }

    @Test
    fun `computeQuestXp scales baseline by tier multiplier at the quest's level`() {
        val progression =
            PlayerProgression(
                ProgressionConfig(
                    quests = QuestXpConfig(
                        baseline = QuestBaselineConfig(baseXp = 50L, xpPerLevel = 20L),
                        tiers = mapOf(
                            QuestDifficulty.TRIVIAL to 0.25,
                            QuestDifficulty.EASY to 0.5,
                            QuestDifficulty.STANDARD to 1.0,
                            QuestDifficulty.HARD to 1.75,
                            QuestDifficulty.EPIC to 3.0,
                        ),
                    ),
                ),
            )

        // Level 1: baseline = 50
        assertEquals(13L, progression.computeQuestXp(QuestDifficulty.TRIVIAL, level = 1))
        assertEquals(50L, progression.computeQuestXp(QuestDifficulty.STANDARD, level = 1))
        assertEquals(150L, progression.computeQuestXp(QuestDifficulty.EPIC, level = 1))

        // Level 5: baseline = 50 + 20*4 = 130
        assertEquals(33L, progression.computeQuestXp(QuestDifficulty.TRIVIAL, level = 5))
        assertEquals(130L, progression.computeQuestXp(QuestDifficulty.STANDARD, level = 5))
        assertEquals(228L, progression.computeQuestXp(QuestDifficulty.HARD, level = 5))

        // Level 10: baseline = 50 + 20*9 = 230
        assertEquals(690L, progression.computeQuestXp(QuestDifficulty.EPIC, level = 10))
    }

    @Test
    fun `computeQuestXp clamps level 0 or below to level 1 baseline`() {
        val progression = PlayerProgression()
        val atOne = progression.computeQuestXp(QuestDifficulty.STANDARD, level = 1)
        val atZero = progression.computeQuestXp(QuestDifficulty.STANDARD, level = 0)
        val atNegative = progression.computeQuestXp(QuestDifficulty.STANDARD, level = -5)
        assertEquals(atOne, atZero)
        assertEquals(atOne, atNegative)
    }

    @Test
    fun `computeQuestXp returns zero when the tier lacks a multiplier`() {
        val progression =
            PlayerProgression(
                ProgressionConfig(
                    quests = QuestXpConfig(
                        tiers = mapOf(QuestDifficulty.STANDARD to 1.0),
                    ),
                ),
            )
        assertEquals(0L, progression.computeQuestXp(QuestDifficulty.EPIC, level = 5))
    }
}
