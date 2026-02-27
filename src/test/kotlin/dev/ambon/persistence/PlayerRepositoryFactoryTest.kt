package dev.ambon.persistence

import dev.ambon.config.PersistenceBackend
import dev.ambon.config.PersistenceConfig
import dev.ambon.config.PersistenceWorkerConfig
import dev.ambon.config.RedisConfig
import dev.ambon.metrics.GameMetrics
import dev.ambon.redis.RedisConnectionManager
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PlayerRepositoryFactoryTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `yaml backend with redis and worker disabled returns bare repository`() {
        val persistence =
            PersistenceConfig(
                backend = PersistenceBackend.YAML,
                rootDir = tempDir.toString(),
                worker = PersistenceWorkerConfig(enabled = false),
            )

        val chain =
            PlayerRepositoryFactory.buildChain(
                persistence = persistence,
                redis = RedisConfig(enabled = false),
                redisManager = null,
                database = null,
                metrics = GameMetrics.noop(),
            )

        assertTrue(chain.repository is YamlPlayerRepository)
        assertNull(chain.coalescingRepository)
    }

    @Test
    fun `redis enabled wraps backend repository with cache layer`() {
        val persistence =
            PersistenceConfig(
                backend = PersistenceBackend.YAML,
                rootDir = tempDir.toString(),
                worker = PersistenceWorkerConfig(enabled = false),
            )
        val redisConfig = RedisConfig(enabled = true, cacheTtlSeconds = 90L)
        val redisManager = RedisConnectionManager(redisConfig)

        try {
            val chain =
                PlayerRepositoryFactory.buildChain(
                    persistence = persistence,
                    redis = redisConfig,
                    redisManager = redisManager,
                    database = null,
                    metrics = GameMetrics.noop(),
                )

            assertTrue(chain.repository is RedisCachingPlayerRepository)
            assertNull(chain.coalescingRepository)
        } finally {
            redisManager.close()
        }
    }

    @Test
    fun `worker enabled adds write coalescing as outermost layer`() {
        val persistence =
            PersistenceConfig(
                backend = PersistenceBackend.YAML,
                rootDir = tempDir.toString(),
                worker = PersistenceWorkerConfig(enabled = true),
            )
        val redisConfig = RedisConfig(enabled = true, cacheTtlSeconds = 90L)
        val redisManager = RedisConnectionManager(redisConfig)

        try {
            val chain =
                PlayerRepositoryFactory.buildChain(
                    persistence = persistence,
                    redis = redisConfig,
                    redisManager = redisManager,
                    database = null,
                    metrics = GameMetrics.noop(),
                )

            val coalescing = chain.coalescingRepository
            assertTrue(coalescing is WriteCoalescingPlayerRepository)
            assertSame(coalescing, chain.repository)

            val delegate = delegatedRepositoryOf(coalescing!!)
            assertTrue(delegate is RedisCachingPlayerRepository)
        } finally {
            redisManager.close()
        }
    }

    @Test
    fun `postgres backend requires a configured database`() {
        val persistence =
            PersistenceConfig(
                backend = PersistenceBackend.POSTGRES,
                rootDir = tempDir.toString(),
                worker = PersistenceWorkerConfig(enabled = false),
            )

        assertThrows(IllegalArgumentException::class.java) {
            PlayerRepositoryFactory.buildChain(
                persistence = persistence,
                redis = RedisConfig(enabled = false),
                redisManager = null,
                database = null,
                metrics = GameMetrics.noop(),
            )
        }
    }

    private fun delegatedRepositoryOf(repo: Any): PlayerRepository {
        val field = DelegatingPlayerRepository::class.java.getDeclaredField("delegate")
        field.isAccessible = true
        return field.get(repo) as PlayerRepository
    }
}
