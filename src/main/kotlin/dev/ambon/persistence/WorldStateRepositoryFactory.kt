package dev.ambon.persistence

import dev.ambon.config.PersistenceBackend
import dev.ambon.config.PersistenceConfig
import org.jetbrains.exposed.sql.Database
import java.nio.file.Paths

object WorldStateRepositoryFactory {
    fun create(
        persistence: PersistenceConfig,
        database: Database?,
    ): WorldStateRepository =
        when (persistence.backend) {
            PersistenceBackend.YAML ->
                YamlWorldStateRepository(
                    rootDir = Paths.get(persistence.rootDir),
                )
            PersistenceBackend.POSTGRES ->
                PostgresWorldStateRepository(
                    database = requireNotNull(database) {
                        "Database must be configured when persistence.backend=POSTGRES"
                    },
                )
        }
}
