package dev.ambon.engine.commands

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpriteCommandTest {
    // ── Parser tests ─────────────────────────────────────────────────────

    @Test
    fun `sprite alone parses to SpriteList`() {
        assertEquals(Command.SpriteList, CommandParser.parse("sprite"))
    }

    @Test
    fun `sprites alone parses to SpriteList`() {
        assertEquals(Command.SpriteList, CommandParser.parse("sprites"))
    }

    @Test
    fun `sprite list parses to SpriteList`() {
        assertEquals(Command.SpriteList, CommandParser.parse("sprite list"))
    }

    @Test
    fun `sprite set with id parses to SpriteSet`() {
        val result = CommandParser.parse("sprite set elf_mage_t1")
        assertTrue(result is Command.SpriteSet)
        assertEquals("elf_mage_t1", (result as Command.SpriteSet).imageId)
    }

    @Test
    fun `sprite set without id returns Invalid`() {
        val result = CommandParser.parse("sprite set")
        assertTrue(result is Command.Invalid)
    }

    @Test
    fun `sprite default parses to SpriteDefault`() {
        assertEquals(Command.SpriteDefault, CommandParser.parse("sprite default"))
    }

    @Test
    fun `sprite clear parses to SpriteDefault`() {
        assertEquals(Command.SpriteDefault, CommandParser.parse("sprite clear"))
    }

    @Test
    fun `sprite auto parses to SpriteDefault`() {
        assertEquals(Command.SpriteDefault, CommandParser.parse("sprite auto"))
    }

    @Test
    fun `sprite with bare id parses to SpriteSet`() {
        val result = CommandParser.parse("sprite beetle_slayer")
        assertTrue(result is Command.SpriteSet)
        assertEquals("beetle_slayer", (result as Command.SpriteSet).imageId)
    }

    @Test
    fun `sprite command is case insensitive`() {
        assertEquals(Command.SpriteList, CommandParser.parse("Sprite List"))
    }
}
