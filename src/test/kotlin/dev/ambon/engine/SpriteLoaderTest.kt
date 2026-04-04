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
        SpriteLoader.loadFromResource("world/test_sprites.yaml", registry)

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
        SpriteLoader.loadFromResource("world/test_sprites.yaml", registry)

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
    fun `staff and achievement sprites coexist in registry`() {
        SpriteLoader.generateStaffSprites(registry, listOf("ELF"))
        SpriteLoader.loadFromResource("world/test_sprites.yaml", registry)

        // Should have staff + legacy achievements + new requirements sprites
        assertTrue(registry.all().size >= 3)
        assertNotNull(registry.get("staff"))
        assertNotNull(registry.get("beetle_slayer"))
        assertNotNull(registry.get("spider_hunter"))
    }

    // ── Requirements-based sprites ──────────────────────────────────────

    @Test
    fun `loadFromResource loads requirements-based sprites`() {
        SpriteLoader.loadFromResource("world/test_sprites.yaml", registry)

        val arcanist = registry.get("elven_arcanist")
        assertNotNull(arcanist)
        assertEquals("Elven Arcanist", arcanist!!.displayName)
        assertEquals(SpriteCategory.GENERAL, arcanist.category)
        assertEquals(3, arcanist.requirements.size)
        assertEquals(200, arcanist.sortOrder)
        assertEquals("An elf who has mastered ancient magic.", arcanist.description)
    }

    @Test
    fun `single-image shorthand creates one variant with sprite id`() {
        SpriteLoader.loadFromResource("world/test_sprites.yaml", registry)

        val heritage = registry.get("elven_heritage")
        assertNotNull(heritage)
        assertEquals(1, heritage!!.variants.size)
        assertEquals("elven_heritage", heritage.variants[0].imageId)
        assertEquals("player_sprites/elven_heritage.png", heritage.variants[0].imagePath)
    }

    @Test
    fun `requirements sprite has correct requirement types`() {
        SpriteLoader.loadFromResource("world/test_sprites.yaml", registry)

        val arcanist = registry.get("elven_arcanist")!!
        assertTrue(arcanist.requirements.any { it is dev.ambon.domain.sprite.SpriteRequirement.Race })
        assertTrue(arcanist.requirements.any { it is dev.ambon.domain.sprite.SpriteRequirement.PlayerClass })
        assertTrue(arcanist.requirements.any { it is dev.ambon.domain.sprite.SpriteRequirement.MinLevel })
    }

    @Test
    fun `legacy and requirements sprites coexist from same YAML`() {
        SpriteLoader.loadFromResource("world/test_sprites.yaml", registry)

        // Legacy achievement sprites
        assertNotNull(registry.get("beetle_slayer"))
        // Requirements-based sprites
        assertNotNull(registry.get("elven_heritage"))
        assertNotNull(registry.get("sword_saint"))
        assertNotNull(registry.get("dragon_slayer"))
    }
}
