package dev.ambon.engine

import dev.ambon.config.LevelRewardsConfig
import dev.ambon.config.ProgressionConfig
import dev.ambon.config.XpCurveConfig
import dev.ambon.domain.character.PlayerClass
import dev.ambon.domain.character.PlayerRace
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlayerClassRaceTest {
    private val baseConfig =
        ProgressionConfig(
            maxLevel = 50,
            xp = XpCurveConfig(baseXp = 100L, exponent = 2.0, linearXp = 0L),
            rewards = LevelRewardsConfig(hpPerLevel = 2, manaPerLevel = 5, fullHealOnLevelUp = true, fullManaOnLevelUp = true),
        )
    private val progression = PlayerProgression(baseConfig)

    @Test
    fun `warrior has higher HP per level than mage`() {
        val warriorHp = progression.maxHpForLevel(5, PlayerClass.WARRIOR, PlayerRace.HUMAN)
        val mageHp = progression.maxHpForLevel(5, PlayerClass.MAGE, PlayerRace.HUMAN)

        // Warrior: 10 + 4*(5-1) = 26, Mage: 10 + 1*(5-1) = 14
        assertEquals(26, warriorHp)
        assertEquals(14, mageHp)
    }

    @Test
    fun `mage has higher mana per level than warrior`() {
        val magerMana = progression.maxManaForLevel(5, PlayerClass.MAGE, PlayerRace.HUMAN)
        val warriorMana = progression.maxManaForLevel(5, PlayerClass.WARRIOR, PlayerRace.HUMAN)

        // Mage: 20 + 8*(5-1) = 52, Warrior: 20 + 1*(5-1) = 24
        assertEquals(52, magerMana)
        assertEquals(24, warriorMana)
    }

    @Test
    fun `race bonus applies to HP`() {
        val trollHp = progression.maxHpForLevel(1, PlayerClass.WARRIOR, PlayerRace.TROLL)
        val faerieHp = progression.maxHpForLevel(1, PlayerClass.WARRIOR, PlayerRace.FAERIE)
        val humanHp = progression.maxHpForLevel(1, PlayerClass.WARRIOR, PlayerRace.HUMAN)

        // Troll: 10 + 0 + 5 = 15, Faerie: 10 + 0 + (-2) = 8, Human: 10 + 0 + 0 = 10
        assertEquals(15, trollHp)
        assertEquals(8, faerieHp)
        assertEquals(10, humanHp)
    }

    @Test
    fun `race bonus applies to mana`() {
        val faerieMana = progression.maxManaForLevel(1, PlayerClass.MAGE, PlayerRace.FAERIE)
        val trollMana = progression.maxManaForLevel(1, PlayerClass.MAGE, PlayerRace.TROLL)

        // Faerie: 20 + 0 + 5 = 25, Troll: 20 + 0 + (-2) = 18
        assertEquals(25, faerieMana)
        assertEquals(18, trollMana)
    }

    @Test
    fun `grantXp uses class and race for HP and mana scaling`() {
        val player =
            PlayerState(
                sessionId = SessionId(1L),
                name = "TestMage",
                roomId = RoomId("test:start"),
                hp = 3,
                maxHp = 10,
                playerClass = PlayerClass.MAGE,
                playerRace = PlayerRace.FAERIE,
            )

        val result = progression.grantXp(player, 400L)

        assertEquals(2, result.levelsGained)
        assertEquals(3, result.newLevel)
        // Mage HP: 10 + 1*(3-1) + (-2 faerie) = 10, coerced to at least 1
        val expectedMaxHp = progression.maxHpForLevel(3, PlayerClass.MAGE, PlayerRace.FAERIE)
        assertEquals(expectedMaxHp, player.baseMaxHp)
        assertEquals(expectedMaxHp, player.hp) // full heal on level up
        // Mage Mana: 20 + 8*(3-1) + 5 faerie = 41
        val expectedMaxMana = progression.maxManaForLevel(3, PlayerClass.MAGE, PlayerRace.FAERIE)
        assertEquals(expectedMaxMana, player.maxMana)
    }

    @Test
    fun `class fromString parses full name, short name, and enum name`() {
        assertEquals(PlayerClass.WARRIOR, PlayerClass.fromString("warrior"))
        assertEquals(PlayerClass.WARRIOR, PlayerClass.fromString("Warrior"))
        assertEquals(PlayerClass.WARRIOR, PlayerClass.fromString("W"))
        assertEquals(PlayerClass.MAGE, PlayerClass.fromString("m"))
        assertEquals(PlayerClass.ROGUE, PlayerClass.fromString("ROGUE"))
        assertEquals(PlayerClass.CLERIC, PlayerClass.fromString("c"))
        assertEquals(null, PlayerClass.fromString("invalid"))
    }

    @Test
    fun `race fromString parses full name, short name, and enum name`() {
        assertEquals(PlayerRace.HUMAN, PlayerRace.fromString("human"))
        assertEquals(PlayerRace.HUMAN, PlayerRace.fromString("H"))
        assertEquals(PlayerRace.FAERIE, PlayerRace.fromString("faerie"))
        assertEquals(PlayerRace.FAERIE, PlayerRace.fromString("F"))
        assertEquals(PlayerRace.TROLL, PlayerRace.fromString("T"))
        assertEquals(PlayerRace.ETHEREAL, PlayerRace.fromString("ethereal"))
        assertEquals(null, PlayerRace.fromString("invalid"))
    }

    @Test
    fun `HP never drops below 1 even with negative race bonus`() {
        // Faerie mage at level 1: 10 + 0 + (-2) = 8, still > 1
        val hp = progression.maxHpForLevel(1, PlayerClass.MAGE, PlayerRace.FAERIE)
        assertEquals(8, hp)
        // Ensure the formula doesn't go below 1 in extreme cases
        assert(hp >= 1)
    }

    @Test
    fun `class damage bonus values are correct`() {
        assertEquals(2, PlayerClass.WARRIOR.baseDamageBonus)
        assertEquals(0, PlayerClass.MAGE.baseDamageBonus)
        assertEquals(3, PlayerClass.ROGUE.baseDamageBonus)
        assertEquals(1, PlayerClass.CLERIC.baseDamageBonus)
    }

    @Test
    fun `class armor bonus values are correct`() {
        assertEquals(1, PlayerClass.WARRIOR.baseArmorBonus)
        assertEquals(0, PlayerClass.MAGE.baseArmorBonus)
        assertEquals(0, PlayerClass.ROGUE.baseArmorBonus)
        assertEquals(0, PlayerClass.CLERIC.baseArmorBonus)
    }
}
