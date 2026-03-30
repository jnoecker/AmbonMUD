package dev.ambon.persistence

import dev.ambon.config.PersistenceBackend
import dev.ambon.config.PersistenceConfig
import org.jetbrains.exposed.sql.Database
import java.nio.file.Paths

object HouseRepositoryFactory {
    fun create(
        persistence: PersistenceConfig,
        database: Database?,
    ): HouseRepository =
        when (persistence.backend) {
            PersistenceBackend.YAML ->
                YamlHouseRepository(
                    rootDir = Paths.get(persistence.rootDir),
                )
            PersistenceBackend.POSTGRES ->
                PostgresHouseRepository(
                    database = requireNotNull(database) {
                        "Database must be configured when persistence.backend=POSTGRES"
                    },
                )
        }
}
