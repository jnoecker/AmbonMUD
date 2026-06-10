package dev.ambon.engine.commands

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AudioLinksCommandParserTest {
    @Test
    fun `parses audio on`() {
        assertEquals(Command.AudioLinksOn, CommandParser.parse("audio on"))
        assertEquals(Command.AudioLinksOn, CommandParser.parse("audiolinks on"))
    }

    @Test
    fun `parses audio off`() {
        assertEquals(Command.AudioLinksOff, CommandParser.parse("audio off"))
        assertEquals(Command.AudioLinksOff, CommandParser.parse("audiolinks off"))
    }

    @Test
    fun `parses bare audio as toggle`() {
        assertEquals(Command.AudioLinksToggle, CommandParser.parse("audio"))
        assertEquals(Command.AudioLinksToggle, CommandParser.parse("audiolinks"))
    }

    @Test
    fun `audio is case insensitive`() {
        assertEquals(Command.AudioLinksOn, CommandParser.parse("AUDIO ON"))
        assertEquals(Command.AudioLinksOff, CommandParser.parse("Audio Off"))
        assertEquals(Command.AudioLinksToggle, CommandParser.parse("AUDIO"))
    }
}
