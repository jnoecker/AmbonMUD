package dev.ambon.engine.commands

import dev.ambon.domain.world.Direction
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommandParserTest {
    @Test
    fun `parses movement aliases`() {
        assertEquals(Command.Move(Direction.NORTH), CommandParser.parse("n"))
        assertEquals(Command.Move(Direction.NORTH), CommandParser.parse("north"))
        assertEquals(Command.Move(Direction.SOUTH), CommandParser.parse("s"))
        assertEquals(Command.Move(Direction.EAST), CommandParser.parse("e"))
        assertEquals(Command.Move(Direction.WEST), CommandParser.parse("west"))
    }

    @Test
    fun `parses core commands and ignores whitespace`() {
        assertEquals(Command.Help, CommandParser.parse("help"))
        assertEquals(Command.Help, CommandParser.parse("  ?  "))
        assertEquals(Command.Look, CommandParser.parse("l"))
        assertEquals(Command.Look, CommandParser.parse(" look "))
        assertEquals(Command.Quit, CommandParser.parse("quit"))
        assertEquals(Command.Quit, CommandParser.parse(" exit "))
        assertEquals(Command.Noop, CommandParser.parse("   "))
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
        assertEquals(Command.Unknown("dance wildly"), cmd)
    }

    @Test
    fun `parses exits`() {
        assertTrue(CommandParser.parse("exits") is Command.Exits)
        assertTrue(CommandParser.parse("ex") is Command.Exits)
    }

    @Test
    fun `parses look direction`() {
        val c1 = CommandParser.parse("look north")
        assertEquals(Command.LookDir(Direction.NORTH), c1)

        val c2 = CommandParser.parse("l e")
        assertEquals(Command.LookDir(Direction.EAST), c2)
    }

    @Test
    fun `look direction invalid usage`() {
        val c = CommandParser.parse("look sideways")
        assertTrue(c is Command.Invalid)
    }
}
