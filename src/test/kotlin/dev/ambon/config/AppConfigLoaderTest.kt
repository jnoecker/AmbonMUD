package dev.ambon.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AppConfigLoaderTest {
    @Test
    fun `loads defaults from application yaml`() {
        val config = AppConfigLoader.load()

        assertEquals(4000, config.server.telnetPort)
        assertEquals(8080, config.server.webPort)
        assertEquals(listOf("world/demo_ruins.yaml"), config.world.resources)
        assertEquals("data/players", config.persistence.rootDir)
        assertEquals(false, config.demo.autoLaunchBrowser)
    }

    @Test
    fun `system property overrides default config`() {
        val key = "config.override.quickmud.server.telnetPort"
        val previous = System.getProperty(key)
        System.setProperty(key, "4444")

        try {
            val config = AppConfigLoader.load()
            assertEquals(4444, config.server.telnetPort)
        } finally {
            if (previous == null) {
                System.clearProperty(key)
            } else {
                System.setProperty(key, previous)
            }
        }
    }

    @Test
    fun `validation rejects invalid values`() {
        val invalid = AppConfig(server = ServerConfig(telnetPort = 0))
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }
}
