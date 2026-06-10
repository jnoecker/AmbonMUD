package dev.ambon.engine.commands

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ScreenReaderCommandParserTest {
    @Test
    fun `parses screenreader on`() {
        assertEquals(Command.ScreenReaderOn, CommandParser.parse("screenreader on"))
    }

    @Test
    fun `parses screenreader off`() {
        assertEquals(Command.ScreenReaderOff, CommandParser.parse("screenreader off"))
    }

    @Test
    fun `parses bare screenreader as toggle`() {
        assertEquals(Command.ScreenReaderToggle, CommandParser.parse("screenreader"))
    }

    @Test
    fun `screenreader is case insensitive`() {
        assertEquals(Command.ScreenReaderOn, CommandParser.parse("SCREENREADER ON"))
        assertEquals(Command.ScreenReaderOff, CommandParser.parse("SCREENREADER OFF"))
        assertEquals(Command.ScreenReaderToggle, CommandParser.parse("Screenreader"))
    }
}
