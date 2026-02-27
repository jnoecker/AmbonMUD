package dev.ambon.persistence

import dev.ambon.config.PersistenceBackend
import dev.ambon.config.PersistenceConfig
import dev.ambon.config.RedisConfig
import dev.ambon.metrics.GameMetrics
import dev.ambon.redis.RedisConnectionManager
import org.jetbrains.exposed.sql.Database
import java.nio.file.Paths

data class PlayerRepositoryChain(
    val repository: PlayerRepository,
    val coalescingRepository: WriteCoalescingPlayerRepository?,
)

object PlayerRepositoryFactory {
    fun buildChain(
        persistence: PersistenceConfig,
        redis: RedisConfig,
        redisManager: RedisConnectionManager?,
        database: Database?,
        metrics: GameMetrics,
    ): PlayerRepositoryChain {
        val backendRepository: PlayerRepository =
            when (persistence.backend) {
                PersistenceBackend.YAML ->
                    YamlPlayerRepository(
                        rootDir = Paths.get(persistence.rootDir),
                        metrics = metrics,
                    )
                PersistenceBackend.POSTGRES ->
                    PostgresPlayerRepository(
                        database =
                            requireNotNull(database) {
                                "Database must be configured when persistence.backend=POSTGRES"
                            },
                        metrics = metrics,
                    )
            }

        val cachedRepository: PlayerRepository =
            if (redis.enabled && redisManager != null) {
                RedisCachingPlayerRepository(
                    delegate = backendRepository,
                    cache = redisManager,
                    cacheTtlSeconds = redis.cacheTtlSeconds,
                )
            } else {
                backendRepository
            }

        val coalescingRepository: WriteCoalescingPlayerRepository? =
            if (persistence.worker.enabled) {
                WriteCoalescingPlayerRepository(cachedRepository)
            } else {
                null
            }

        return PlayerRepositoryChain(
            repository = coalescingRepository ?: cachedRepository,
            coalescingRepository = coalescingRepository,
        )
    }
}
