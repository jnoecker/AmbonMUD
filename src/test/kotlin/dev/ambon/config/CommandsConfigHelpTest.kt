package dev.ambon.config

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommandsConfigHelpTest {
    @Test
    fun `help groups by category and shows usage with description`() {
        val config = CommandsConfig(
            entries = linkedMapOf(
                "look" to CommandMetadata("look/l", "Look around", "navigation"),
                "mystery" to CommandMetadata("mystery <arg>", "From an unknown category", "experimental"),
                "smite" to CommandMetadata("smite <target>", "Staff only", "admin", staff = true),
            ),
        )

        val help = config.generateHelp(isStaff = false)
        assertTrue(help.contains("[Navigation]"), "expected category header, got:\n$help")
        assertTrue(help.contains("look/l — Look around"), "expected usage — description, got:\n$help")
        assertTrue(help.contains("[Experimental]"), "unknown categories must not be dropped, got:\n$help")
        assertTrue(help.contains("mystery <arg> — From an unknown category"))
        assertFalse(help.contains("smite"), "staff commands must be hidden from players")

        val staffHelp = config.generateHelp(isStaff = true)
        assertTrue(staffHelp.contains("[Staff]"), "expected staff section, got:\n$staffHelp")
        assertTrue(staffHelp.contains("smite <target> — Staff only"))
    }

    @Test
    fun `every default manifest entry has a usage and a description`() {
        val blank = CommandsConfig.defaultCommandEntries()
            .filterValues { it.usage.isBlank() || it.description.isBlank() }
            .keys
        assertTrue(blank.isEmpty()) {
            "Manifest entries must explain their command — blank usage/description for: ${blank.sorted()}"
        }
    }
}
