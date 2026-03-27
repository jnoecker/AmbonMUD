package dev.ambon.engine

import dev.ambon.domain.sprite.SpriteCategory
import dev.ambon.domain.sprite.SpriteUnlockCondition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SpriteLoaderTest {
    private lateinit var registry: SpriteRegistry

    @BeforeEach
    fun setup() {
        registry = SpriteRegistry()
    }

    // ── Tier generation ──────────────────────────────────────────────────

    @Test
    fun `generateTierSprites creates definitions for each tier`() {
        SpriteLoader.generateTierSprites(
            registry = registry,
            tierNames = mapOf(1 to "Novice", 10 to "Apprentice"),
            raceIds = listOf("HUMAN", "ELF"),
            classIds = listOf("WARRIOR", "MAGE"),
        )

        val all = registry.all().toList()
        assertEquals(2, all.size)

        val novice = registry.get("tier_novice")
        assertNotNull(novice)
        assertEquals("Novice", novice!!.displayName)
        assertEquals(SpriteCategory.TIER, novice.category)
        assertEquals(SpriteUnlockCondition.Level(1), novice.unlockCondition)
        assertEquals(1, novice.sortOrder)
        // 2 races x 2 classes = 4 variants
        assertEquals(4, novice.variants.size)
    }

    @Test
    fun `tier sprite variants follow naming convention`() {
        SpriteLoader.generateTierSprites(
            registry = registry,
            tierNames = mapOf(1 to "Novice"),
            raceIds = listOf("ELF"),
            classIds = listOf("MAGE"),
        )

        val novice = registry.get("tier_novice")!!
        assertEquals(1, novice.variants.size)
        val v = novice.variants[0]
        assertEquals("elf_mage_t1", v.imageId)
        assertEquals("Novice (Elf Mage)", v.displayName)
        assertEquals("ELF", v.race)
        assertEquals("MAGE", v.playerClass)
        assertEquals("player_sprites/elf_mage_t1.png", v.imagePath)
    }

    @Test
    fun `tier sprites are indexed in variant lookup`() {
        SpriteLoader.generateTierSprites(
            registry = registry,
            tierNames = mapOf(10 to "Apprentice"),
            raceIds = listOf("HUMAN"),
            classIds = listOf("WARRIOR"),
        )

        val result = registry.findVariant("human_warrior_t10")
        assertNotNull(result)
        assertEquals("tier_apprentice", result!!.first.id)
    }

    // ── Staff generation ─────────────────────────────────────────────────

    @Test
    fun `generateStaffSprites creates a staff definition`() {
        SpriteLoader.generateStaffSprites(
            registry = registry,
            raceIds = listOf("HUMAN", "ELF", "DWARF"),
        )

        val staff = registry.get("staff")
        assertNotNull(staff)
        assertEquals("Staff", staff!!.displayName)
        assertEquals(SpriteCategory.STAFF, staff.category)
        assertEquals(SpriteUnlockCondition.Staff, staff.unlockCondition)
        assertEquals(3, staff.variants.size)
    }

    @Test
    fun `staff variants follow naming convention`() {
        SpriteLoader.generateStaffSprites(
            registry = registry,
            raceIds = listOf("ELF"),
        )

        val staff = registry.get("staff")!!
        val v = staff.variants[0]
        assertEquals("elf_base_tstaff", v.imageId)
        assertEquals("Staff (Elf)", v.displayName)
        assertEquals("ELF", v.race)
        assertEquals("player_sprites/elf_base_tstaff.png", v.imagePath)
    }

    // ── YAML loading ─────────────────────────────────────────────────────

    @Test
    fun `loadFromResource loads sprites from YAML`() {
        SpriteLoader.loadFromResource("world/sprites.yaml", registry)

        val beetle = registry.get("beetle_slayer")
        assertNotNull(beetle)
        assertEquals("Beetle Slayer", beetle!!.displayName)
        assertEquals(SpriteCategory.ACHIEVEMENT, beetle.category)
        assertEquals(SpriteUnlockCondition.Achievement("combat/beetle_exterminator"), beetle.unlockCondition)
        assertEquals(100, beetle.sortOrder)
        assertEquals(3, beetle.variants.size)

        val spider = registry.get("spider_hunter")
        assertNotNull(spider)
        assertEquals(2, spider!!.variants.size)
    }

    @Test
    fun `loaded variants have correct qualifiers`() {
        SpriteLoader.loadFromResource("world/sprites.yaml", registry)

        val beetle = registry.get("beetle_slayer")!!
        val generic = beetle.variants.find { it.imageId == "beetle_slayer" }
        assertNotNull(generic)
        assertEquals(null, generic!!.race)
        assertEquals(null, generic.playerClass)

        val elfVariant = beetle.variants.find { it.imageId == "elf_beetle_slayer" }
        assertNotNull(elfVariant)
        assertEquals("ELF", elfVariant!!.race)
        assertEquals(null, elfVariant.playerClass)
    }

    @Test
    fun `loadFromResource skips missing resource gracefully`() {
        SpriteLoader.loadFromResource("world/nonexistent_sprites.yaml", registry)
        assertTrue(registry.all().isEmpty())
    }

    // ── Combined loading ─────────────────────────────────────────────────

    @Test
    fun `tier and achievement sprites coexist in registry`() {
        SpriteLoader.generateTierSprites(
            registry = registry,
            tierNames = mapOf(1 to "Novice"),
            raceIds = listOf("ELF"),
            classIds = listOf("MAGE"),
        )
        SpriteLoader.generateStaffSprites(registry, listOf("ELF"))
        SpriteLoader.loadFromResource("world/sprites.yaml", registry)

        // Should have tier + staff + 2 achievement sprites
        assertTrue(registry.all().size >= 4)
        assertNotNull(registry.get("tier_novice"))
        assertNotNull(registry.get("staff"))
        assertNotNull(registry.get("beetle_slayer"))
        assertNotNull(registry.get("spider_hunter"))
    }
}
