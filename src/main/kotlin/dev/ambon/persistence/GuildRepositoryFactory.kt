package dev.ambon.persistence

import dev.ambon.config.PersistenceBackend
import dev.ambon.config.PersistenceConfig
import org.jetbrains.exposed.sql.Database
import java.nio.file.Paths

object GuildRepositoryFactory {
    fun create(
        persistence: PersistenceConfig,
        database: Database?,
    ): GuildRepository =
        when (persistence.backend) {
            PersistenceBackend.YAML ->
                YamlGuildRepository(
                    rootDir = Paths.get(persistence.rootDir),
                )
            PersistenceBackend.POSTGRES ->
                PostgresGuildRepository(
                    database = requireNotNull(database) {
                        "Database must be configured when persistence.backend=POSTGRES"
                    },
                )
        }
}
