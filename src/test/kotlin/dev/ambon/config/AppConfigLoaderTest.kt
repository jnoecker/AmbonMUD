package dev.ambon.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AppConfigLoaderTest {
    @Test
    fun `system property overrides default config`() {
        val key = "config.override.ambonMUD.server.telnetPort"
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

    @Test
    fun `validation rejects invalid progression values`() {
        val invalid = AppConfig(progression = ProgressionConfig(maxLevel = 0))
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `validated rejects tier with baseHp 0`() {
        val badTier = MobTierConfig(baseHp = 0)
        val invalid =
            AppConfig(
                engine = EngineConfig(mob = MobEngineConfig(tiers = MobTiersConfig(standard = badTier))),
            )
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }
}
