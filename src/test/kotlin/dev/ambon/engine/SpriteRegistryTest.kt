package dev.ambon.engine

import dev.ambon.domain.sprite.SpriteCategory
import dev.ambon.domain.sprite.SpriteDefinition
import dev.ambon.domain.sprite.SpriteUnlockCondition
import dev.ambon.domain.sprite.SpriteVariant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SpriteRegistryTest {
    private lateinit var registry: SpriteRegistry

    @BeforeEach
    fun setup() {
        registry = SpriteRegistry()
    }

    private val tierNovice = SpriteDefinition(
        id = "tier_novice",
        displayName = "Novice",
        category = SpriteCategory.TIER,
        unlockCondition = SpriteUnlockCondition.Level(1),
        sortOrder = 1,
        variants = listOf(
            SpriteVariant(
                "elf_mage_t1",
                "Novice (Elf Mage)",
                race = "ELF",
                playerClass = "MAGE",
                imagePath = "player_sprites/elf_mage_t1.png",
            ),
            SpriteVariant(
                "human_warrior_t1",
                "Novice (Human Warrior)",
                race = "HUMAN",
                playerClass = "WARRIOR",
                imagePath = "player_sprites/human_warrior_t1.png",
            ),
            SpriteVariant("t1", "Novice", imagePath = "player_sprites/t1.png"),
        ),
    )

    private val tierApprentice = SpriteDefinition(
        id = "tier_apprentice",
        displayName = "Apprentice",
        category = SpriteCategory.TIER,
        unlockCondition = SpriteUnlockCondition.Level(10),
        sortOrder = 10,
        variants = listOf(
            SpriteVariant(
                "elf_mage_t10",
                "Apprentice (Elf Mage)",
                race = "ELF",
                playerClass = "MAGE",
                imagePath = "player_sprites/elf_mage_t10.png",
            ),
            SpriteVariant(
                "human_warrior_t10",
                "Apprentice (Human Warrior)",
                race = "HUMAN",
                playerClass = "WARRIOR",
                imagePath = "player_sprites/human_warrior_t10.png",
            ),
        ),
    )

    private val achievementSprite = SpriteDefinition(
        id = "beetle_slayer",
        displayName = "Beetle Slayer",
        category = SpriteCategory.ACHIEVEMENT,
        unlockCondition = SpriteUnlockCondition.Achievement("combat/beetle_exterminator"),
        sortOrder = 100,
        variants = listOf(
            SpriteVariant("beetle_slayer", "Beetle Slayer", imagePath = "player_sprites/beetle_slayer.png"),
            SpriteVariant("elf_beetle_slayer", "Beetle Slayer (Elf)", race = "ELF", imagePath = "player_sprites/elf_beetle_slayer.png"),
        ),
    )

    private val staffSprite = SpriteDefinition(
        id = "staff",
        displayName = "Staff",
        category = SpriteCategory.STAFF,
        unlockCondition = SpriteUnlockCondition.Staff,
        sortOrder = 1000,
        variants = listOf(
            SpriteVariant("elf_base_tstaff", "Staff (Elf)", race = "ELF", imagePath = "player_sprites/elf_base_tstaff.png"),
            SpriteVariant("human_base_tstaff", "Staff (Human)", race = "HUMAN", imagePath = "player_sprites/human_base_tstaff.png"),
        ),
    )

    // ── Registration & lookup ─────────────────────────────────────────────

    @Test
    fun `register and retrieve definitions`() {
        registry.register(tierNovice)
        registry.register(achievementSprite)
        assertEquals(tierNovice, registry.get("tier_novice"))
        assertEquals(achievementSprite, registry.get("beetle_slayer"))
        assertNull(registry.get("nonexistent"))
    }

    @Test
    fun `findVariant returns definition and variant`() {
        registry.register(tierNovice)
        val result = registry.findVariant("elf_mage_t1")
        assertNotNull(result)
        assertEquals(tierNovice, result!!.first)
        assertEquals("elf_mage_t1", result.second.imageId)
    }

    @Test
    fun `findVariant returns null for unknown imageId`() {
        registry.register(tierNovice)
        assertNull(registry.findVariant("nonexistent_sprite"))
    }

    // ── Unlock conditions ─────────────────────────────────────────────────

    @Test
    fun `level unlock - meets threshold`() {
        registry.register(tierApprentice)
        val unlocked = registry.unlockedDefinitions(level = 10, unlockedAchievementIds = emptySet(), isStaff = false)
        assertEquals(1, unlocked.size)
        assertEquals("tier_apprentice", unlocked[0].id)
    }

    @Test
    fun `level unlock - below threshold`() {
        registry.register(tierApprentice)
        val unlocked = registry.unlockedDefinitions(level = 9, unlockedAchievementIds = emptySet(), isStaff = false)
        assertTrue(unlocked.isEmpty())
    }

    @Test
    fun `achievement unlock - has achievement`() {
        registry.register(achievementSprite)
        val unlocked = registry.unlockedDefinitions(
            level = 1,
            unlockedAchievementIds = setOf("combat/beetle_exterminator"),
            isStaff = false,
        )
        assertEquals(1, unlocked.size)
        assertEquals("beetle_slayer", unlocked[0].id)
    }

    @Test
    fun `achievement unlock - missing achievement`() {
        registry.register(achievementSprite)
        val unlocked = registry.unlockedDefinitions(level = 1, unlockedAchievementIds = emptySet(), isStaff = false)
        assertTrue(unlocked.isEmpty())
    }

    @Test
    fun `staff unlock - is staff`() {
        registry.register(staffSprite)
        val unlocked = registry.unlockedDefinitions(level = 1, unlockedAchievementIds = emptySet(), isStaff = true)
        assertEquals(1, unlocked.size)
    }

    @Test
    fun `staff unlock - not staff`() {
        registry.register(staffSprite)
        val unlocked = registry.unlockedDefinitions(level = 1, unlockedAchievementIds = emptySet(), isStaff = false)
        assertTrue(unlocked.isEmpty())
    }

    // ── Available variants (unlock + player match) ───────────────────────

    @Test
    fun `availableVariants filters by race and class`() {
        registry.register(tierNovice)
        val available = registry.availableVariants(
            level = 1,
            unlockedAchievementIds = emptySet(),
            isStaff = false,
            playerRace = "ELF",
            playerClass = "MAGE",
            playerGender = "neutral",
        )
        val imageIds = available.map { it.second.imageId }.toSet()
        // Elf mage should see: elf_mage_t1 (race+class match) and t1 (generic)
        assertTrue("elf_mage_t1" in imageIds)
        assertTrue("t1" in imageIds)
        // Should NOT see human_warrior_t1
        assertTrue("human_warrior_t1" !in imageIds)
    }

    @Test
    fun `availableVariants includes generic achievement variants`() {
        registry.register(achievementSprite)
        val available = registry.availableVariants(
            level = 1,
            unlockedAchievementIds = setOf("combat/beetle_exterminator"),
            isStaff = false,
            playerRace = "HUMAN",
            playerClass = "WARRIOR",
            playerGender = "neutral",
        )
        val imageIds = available.map { it.second.imageId }.toSet()
        // Human warrior should see generic beetle_slayer but NOT elf_beetle_slayer
        assertTrue("beetle_slayer" in imageIds)
        assertTrue("elf_beetle_slayer" !in imageIds)
    }

    @Test
    fun `staff sees all staff variants regardless of race`() {
        registry.register(staffSprite)
        val available = registry.availableVariants(
            level = 1,
            unlockedAchievementIds = emptySet(),
            isStaff = true,
            playerRace = "ELF",
            playerClass = "MAGE",
            playerGender = "neutral",
        )
        val imageIds = available.map { it.second.imageId }.toSet()
        // Staff can see all staff variants, even those for other races
        assertTrue("elf_base_tstaff" in imageIds)
        assertTrue("human_base_tstaff" in imageIds)
    }

    // ── Validate selection ─────────────────────────────────────────────

    @Test
    fun `validateSelection accepts valid unlocked matching variant`() {
        registry.register(tierNovice)
        val result = registry.validateSelection(
            imageId = "elf_mage_t1",
            level = 1,
            unlockedAchievementIds = emptySet(),
            isStaff = false,
            playerRace = "ELF",
            playerClass = "MAGE",
            playerGender = "neutral",
        )
        assertNotNull(result)
        assertEquals("elf_mage_t1", result!!.imageId)
    }

    @Test
    fun `validateSelection rejects non-matching race`() {
        registry.register(tierNovice)
        val result = registry.validateSelection(
            imageId = "elf_mage_t1",
            level = 1,
            unlockedAchievementIds = emptySet(),
            isStaff = false,
            playerRace = "HUMAN",
            playerClass = "MAGE",
            playerGender = "neutral",
        )
        assertNull(result)
    }

    @Test
    fun `validateSelection rejects locked sprite`() {
        registry.register(tierApprentice)
        val result = registry.validateSelection(
            imageId = "elf_mage_t10",
            level = 5,
            unlockedAchievementIds = emptySet(),
            isStaff = false,
            playerRace = "ELF",
            playerClass = "MAGE",
            playerGender = "neutral",
        )
        assertNull(result)
    }

    @Test
    fun `validateSelection accepts generic variant for any player`() {
        registry.register(tierNovice)
        val result = registry.validateSelection(
            imageId = "t1",
            level = 1,
            unlockedAchievementIds = emptySet(),
            isStaff = false,
            playerRace = "DWARF",
            playerClass = "CLERIC",
            playerGender = "neutral",
        )
        assertNotNull(result)
        assertEquals("t1", result!!.imageId)
    }

    // ── Auto-resolve ─────────────────────────────────────────────────────

    @Test
    fun `autoResolve picks highest tier with best variant match`() {
        registry.register(tierNovice)
        registry.register(tierApprentice)
        val result = registry.autoResolve(
            level = 15,
            isStaff = false,
            playerRace = "ELF",
            playerClass = "MAGE",
            playerGender = "neutral",
        )
        assertNotNull(result)
        // Should pick apprentice (higher tier, level 15 >= 10)
        assertEquals("elf_mage_t10", result!!.imageId)
    }

    @Test
    fun `autoResolve falls back to lower tier if higher not unlocked`() {
        registry.register(tierNovice)
        registry.register(tierApprentice)
        val result = registry.autoResolve(
            level = 5,
            isStaff = false,
            playerRace = "ELF",
            playerClass = "MAGE",
            playerGender = "neutral",
        )
        assertNotNull(result)
        assertEquals("elf_mage_t1", result!!.imageId)
    }

    @Test
    fun `autoResolve prefers race+class variant over generic`() {
        registry.register(tierNovice)
        val result = registry.autoResolve(
            level = 1,
            isStaff = false,
            playerRace = "ELF",
            playerClass = "MAGE",
            playerGender = "neutral",
        )
        assertNotNull(result)
        assertEquals("elf_mage_t1", result!!.imageId)
    }

    @Test
    fun `autoResolve prefers staff sprite for staff member`() {
        registry.register(tierNovice)
        registry.register(staffSprite)
        val result = registry.autoResolve(
            level = 1,
            isStaff = true,
            playerRace = "ELF",
            playerClass = "MAGE",
            playerGender = "neutral",
        )
        assertNotNull(result)
        // Staff sprite has higher sortOrder (1000)
        assertEquals("elf_base_tstaff", result!!.imageId)
    }

    // ── Best variant (specificity) ───────────────────────────────────────

    @Test
    fun `bestVariant returns most specific match`() {
        val def = SpriteDefinition(
            id = "test",
            displayName = "Test",
            category = SpriteCategory.ACHIEVEMENT,
            unlockCondition = SpriteUnlockCondition.Level(1),
            variants = listOf(
                SpriteVariant("generic", "Generic", imagePath = "g.png"),
                SpriteVariant("elf", "Elf", race = "ELF", imagePath = "e.png"),
                SpriteVariant("elf_mage", "Elf Mage", race = "ELF", playerClass = "MAGE", imagePath = "em.png"),
            ),
        )
        val result = registry.bestVariant(def, "ELF", "MAGE", "neutral")
        assertEquals("elf_mage", result?.imageId)
    }

    @Test
    fun `bestVariant falls back to race match when no race+class match`() {
        val def = SpriteDefinition(
            id = "test",
            displayName = "Test",
            category = SpriteCategory.ACHIEVEMENT,
            unlockCondition = SpriteUnlockCondition.Level(1),
            variants = listOf(
                SpriteVariant("generic", "Generic", imagePath = "g.png"),
                SpriteVariant("elf", "Elf", race = "ELF", imagePath = "e.png"),
            ),
        )
        val result = registry.bestVariant(def, "ELF", "WARRIOR", "neutral")
        assertEquals("elf", result?.imageId)
    }

    @Test
    fun `bestVariant falls back to generic when no qualifier match`() {
        val def = SpriteDefinition(
            id = "test",
            displayName = "Test",
            category = SpriteCategory.ACHIEVEMENT,
            unlockCondition = SpriteUnlockCondition.Level(1),
            variants = listOf(
                SpriteVariant("generic", "Generic", imagePath = "g.png"),
                SpriteVariant("elf", "Elf", race = "ELF", imagePath = "e.png"),
            ),
        )
        val result = registry.bestVariant(def, "HUMAN", "WARRIOR", "neutral")
        assertEquals("generic", result?.imageId)
    }

    // ── Clear ────────────────────────────────────────────────────────────

    @Test
    fun `clear removes all definitions and variants`() {
        registry.register(tierNovice)
        registry.register(achievementSprite)
        registry.clear()
        assertTrue(registry.all().isEmpty())
        assertNull(registry.findVariant("elf_mage_t1"))
    }

    // ── Requirements-based unlock ────────────────────────────────────────

    private val requirementsSprite = SpriteDefinition(
        id = "elven_arcanist",
        displayName = "Elven Arcanist",
        description = "An elf who has mastered ancient magic.",
        category = SpriteCategory.GENERAL,
        requirements = listOf(
            dev.ambon.domain.sprite.SpriteRequirement.Race("ELF"),
            dev.ambon.domain.sprite.SpriteRequirement.PlayerClass("MAGE"),
            dev.ambon.domain.sprite.SpriteRequirement.MinLevel(30),
        ),
        sortOrder = 200,
        variants = listOf(
            SpriteVariant("elven_arcanist", "Elven Arcanist", imagePath = "player_sprites/elven_arcanist.png"),
        ),
    )

    private val levelOnlyRequirement = SpriteDefinition(
        id = "twilight_wanderer",
        displayName = "Twilight Wanderer",
        category = SpriteCategory.GENERAL,
        requirements = listOf(
            dev.ambon.domain.sprite.SpriteRequirement.MinLevel(40),
        ),
        sortOrder = 250,
        variants = listOf(
            SpriteVariant("twilight_wanderer", "Twilight Wanderer", imagePath = "player_sprites/twilight_wanderer.png"),
        ),
    )

    @Test
    fun `requirements - all met unlocks sprite`() {
        registry.register(requirementsSprite)
        val unlocked = registry.unlockedDefinitions(
            level = 30,
            unlockedAchievementIds = emptySet(),
            isStaff = false,
            playerRace = "ELF",
            playerClass = "MAGE",
        )
        assertEquals(1, unlocked.size)
        assertEquals("elven_arcanist", unlocked[0].id)
    }

    @Test
    fun `requirements - wrong race fails`() {
        registry.register(requirementsSprite)
        val unlocked = registry.unlockedDefinitions(
            level = 30,
            unlockedAchievementIds = emptySet(),
            isStaff = false,
            playerRace = "HUMAN",
            playerClass = "MAGE",
        )
        assertTrue(unlocked.isEmpty())
    }

    @Test
    fun `requirements - wrong class fails`() {
        registry.register(requirementsSprite)
        val unlocked = registry.unlockedDefinitions(
            level = 30,
            unlockedAchievementIds = emptySet(),
            isStaff = false,
            playerRace = "ELF",
            playerClass = "WARRIOR",
        )
        assertTrue(unlocked.isEmpty())
    }

    @Test
    fun `requirements - level too low fails`() {
        registry.register(requirementsSprite)
        val unlocked = registry.unlockedDefinitions(
            level = 29,
            unlockedAchievementIds = emptySet(),
            isStaff = false,
            playerRace = "ELF",
            playerClass = "MAGE",
        )
        assertTrue(unlocked.isEmpty())
    }

    @Test
    fun `requirements - level-only sprite works for any race and class`() {
        registry.register(levelOnlyRequirement)
        val unlocked = registry.unlockedDefinitions(
            level = 40,
            unlockedAchievementIds = emptySet(),
            isStaff = false,
            playerRace = "HUMAN",
            playerClass = "WARRIOR",
        )
        assertEquals(1, unlocked.size)
    }

    @Test
    fun `legacy and requirements sprites coexist`() {
        registry.register(tierNovice) // legacy
        registry.register(requirementsSprite) // requirements
        registry.register(levelOnlyRequirement) // requirements

        val unlocked = registry.unlockedDefinitions(
            level = 40,
            unlockedAchievementIds = emptySet(),
            isStaff = false,
            playerRace = "ELF",
            playerClass = "MAGE",
        )
        // Should include: novice tier (level 1), elven_arcanist (elf+mage+30), twilight_wanderer (40)
        assertEquals(3, unlocked.size)
    }

    @Test
    fun `validateSelection works with requirements sprite`() {
        registry.register(requirementsSprite)
        val result = registry.validateSelection(
            imageId = "elven_arcanist",
            level = 30,
            unlockedAchievementIds = emptySet(),
            isStaff = false,
            playerRace = "ELF",
            playerClass = "MAGE",
            playerGender = "female",
        )
        assertNotNull(result)
        assertEquals("elven_arcanist", result!!.imageId)
    }

    @Test
    fun `validateSelection rejects requirements sprite when requirements not met`() {
        registry.register(requirementsSprite)
        val result = registry.validateSelection(
            imageId = "elven_arcanist",
            level = 30,
            unlockedAchievementIds = emptySet(),
            isStaff = false,
            playerRace = "HUMAN",
            playerClass = "MAGE",
            playerGender = "male",
        )
        assertNull(result)
    }
}
