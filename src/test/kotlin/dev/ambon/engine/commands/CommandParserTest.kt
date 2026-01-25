package dev.ambon.engine.commands

import dev.ambon.domain.world.Direction
import org.junit.jupiter.api.Assertions
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
    fun `unknown lines become Unknown`() {
        val cmd = CommandParser.parse("dance wildly")
        Assertions.assertEquals(Command.Unknown("dance wildly"), cmd)
    }
}
