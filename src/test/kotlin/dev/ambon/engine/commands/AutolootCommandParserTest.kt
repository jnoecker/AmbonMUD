package dev.ambon.engine.commands

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AutolootCommandParserTest {
    @Test
    fun `parses autoloot on`() {
        assertEquals(Command.AutolootOn, CommandParser.parse("autoloot on"))
    }

    @Test
    fun `parses autoloot off`() {
        assertEquals(Command.AutolootOff, CommandParser.parse("autoloot off"))
    }

    @Test
    fun `parses autoloot status`() {
        assertEquals(Command.AutolootStatus, CommandParser.parse("autoloot status"))
    }

    @Test
    fun `bare autoloot shows status`() {
        assertEquals(Command.AutolootStatus, CommandParser.parse("autoloot"))
    }

    @Test
    fun `autoloot is case insensitive`() {
        assertEquals(Command.AutolootOn, CommandParser.parse("AUTOLOOT ON"))
        assertEquals(Command.AutolootOff, CommandParser.parse("Autoloot Off"))
        assertEquals(Command.AutolootStatus, CommandParser.parse("AutoLoot Status"))
    }
}
