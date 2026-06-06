package dev.ambon.engine.commands

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AutopeekCommandParserTest {
    @Test
    fun `parses autopeek on`() {
        assertEquals(Command.AutopeekOn, CommandParser.parse("autopeek on"))
    }

    @Test
    fun `parses autopeek off`() {
        assertEquals(Command.AutopeekOff, CommandParser.parse("autopeek off"))
    }

    @Test
    fun `parses autopeek status`() {
        assertEquals(Command.AutopeekStatus, CommandParser.parse("autopeek status"))
    }

    @Test
    fun `bare autopeek shows status`() {
        assertEquals(Command.AutopeekStatus, CommandParser.parse("autopeek"))
    }

    @Test
    fun `autopeek is case insensitive`() {
        assertEquals(Command.AutopeekOn, CommandParser.parse("AUTOPEEK ON"))
        assertEquals(Command.AutopeekOff, CommandParser.parse("Autopeek Off"))
        assertEquals(Command.AutopeekStatus, CommandParser.parse("AutoPeek Status"))
    }
}
