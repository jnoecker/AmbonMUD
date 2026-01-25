package dev.ambon.engine.commands

import dev.ambon.domain.world.Direction
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommandParserTest {
    @Test
    fun `parses movement aliases`() {
        Assertions.assertEquals(Command.Move(Direction.NORTH), CommandParser.parse("n"))
        Assertions.assertEquals(Command.Move(Direction.NORTH), CommandParser.parse("north"))
        Assertions.assertEquals(Command.Move(Direction.SOUTH), CommandParser.parse("s"))
        Assertions.assertEquals(Command.Move(Direction.EAST), CommandParser.parse("e"))
        Assertions.assertEquals(Command.Move(Direction.WEST), CommandParser.parse("west"))
    }

    @Test
    fun `parses core commands and ignores whitespace`() {
        Assertions.assertEquals(Command.Help, CommandParser.parse("help"))
        Assertions.assertEquals(Command.Help, CommandParser.parse("  ?  "))
        Assertions.assertEquals(Command.Look, CommandParser.parse("l"))
        Assertions.assertEquals(Command.Look, CommandParser.parse(" look "))
        Assertions.assertEquals(Command.Quit, CommandParser.parse("quit"))
        Assertions.assertEquals(Command.Quit, CommandParser.parse(" exit "))
        Assertions.assertEquals(Command.Noop, CommandParser.parse("   "))
    }

    @Test
    fun `parser parses tell and t aliases`() {
        assertEquals(Command.Tell("Bob", "hi there"), CommandParser.parse("tell Bob hi there"))
        assertEquals(Command.Tell("Bob", "hi there"), CommandParser.parse("t Bob hi there"))
    }

    @Test
    fun `parser parses gossip and gs aliases`() {
        assertEquals(Command.Gossip("hello"), CommandParser.parse("gossip hello"))
        assertEquals(Command.Gossip("hello"), CommandParser.parse("gs hello"))
    }

    @Test
    fun `parser parses say and apostrophe shorthand`() {
        assertEquals(Command.Say("hello"), CommandParser.parse("say hello"))
        assertEquals(Command.Say("hello"), CommandParser.parse("'hello"))
        assertEquals(Command.Say("hello world"), CommandParser.parse("'  hello world   "))
    }

    @Test
    fun `parser returns Invalid for incomplete tell and gossip`() {
        assertTrue(CommandParser.parse("tell Bob") is Command.Invalid)
        assertTrue(CommandParser.parse("t Bob") is Command.Invalid)
        assertTrue(CommandParser.parse("gossip") is Command.Invalid)
        assertTrue(CommandParser.parse("gossip   ") is Command.Invalid)
        assertTrue(CommandParser.parse("gs") is Command.Invalid)
        assertTrue(CommandParser.parse("gs   ") is Command.Invalid)
    }

    @Test
    fun `parser returns Invalid for empty say payload`() {
        assertTrue(CommandParser.parse("say   ") is Command.Invalid)
        assertTrue(CommandParser.parse("'   ") is Command.Invalid)
    }

    @Test
    fun `unknown lines become Unknown`() {
        val cmd = CommandParser.parse("dance wildly")
        Assertions.assertEquals(Command.Unknown("dance wildly"), cmd)
    }
}
