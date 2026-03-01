package dev.ambon.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppConfigLoaderTest {
    private val testResourcePath = "/test-application.yaml"

    @Test
    fun `system property overrides default config`() {
        val key = "config.override.ambonMUD.server.telnetPort"
        val previous = System.getProperty(key)
        System.setProperty(key, "4444")

        try {
            val config = AppConfigLoader.load(resourcePath = testResourcePath)
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
    fun `default application config uses yaml persistence with redis disabled`() {
        val config = AppConfigLoader.load()

        assertEquals(PersistenceBackend.YAML, config.persistence.backend)
        assertTrue(!config.redis.enabled)
        assertTrue(!config.engine.debug.enableSwarmClass)
        assertEquals("labyrinth:cell_00_00", config.engine.classStartRooms["SWARM"])
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

    @Test
    fun `validated rejects combat room feedback when feedback is disabled`() {
        val invalid =
            AppConfig(
                engine =
                    EngineConfig(
                        combat =
                            CombatEngineConfig(
                                feedback = CombatFeedbackConfig(enabled = false, roomBroadcastEnabled = true),
                            ),
                    ),
            )
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `validation rejects inboundBudgetMs of zero`() {
        val invalid = AppConfig(server = ServerConfig(inboundBudgetMs = 0L))
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `validation rejects inboundBudgetMs equal to tickMillis`() {
        val invalid = AppConfig(server = ServerConfig(tickMillis = 100L, inboundBudgetMs = 100L))
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `validation rejects inboundBudgetMs greater than tickMillis`() {
        val invalid = AppConfig(server = ServerConfig(tickMillis = 100L, inboundBudgetMs = 101L))
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `validation accepts valid inboundBudgetMs`() {
        val valid = AppConfig(server = ServerConfig(tickMillis = 100L, inboundBudgetMs = 30L))
        valid.validated() // should not throw
    }

    @Test
    fun `redis validation skipped when disabled`() {
        val config = AppConfig(redis = RedisConfig(enabled = false, uri = ""))
        config.validated()
    }

    @Test
    fun `redis validation rejects blank uri when enabled`() {
        val invalid = AppConfig(redis = RedisConfig(enabled = true, uri = ""))
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `redis validation rejects non-positive cacheTtlSeconds`() {
        val invalid = AppConfig(redis = RedisConfig(enabled = true, cacheTtlSeconds = 0L))
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `redis bus validation rejects blank shared secret when enabled`() {
        val invalid =
            AppConfig(
                redis =
                    RedisConfig(
                        enabled = true,
                        bus = RedisBusConfig(enabled = true, sharedSecret = ""),
                    ),
            )
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `redis bus validation accepts non-blank shared secret`() {
        val config =
            AppConfig(
                redis =
                    RedisConfig(
                        enabled = true,
                        bus = RedisBusConfig(enabled = true, sharedSecret = "secret"),
                    ),
            )
        config.validated()
    }
}
