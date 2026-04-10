package dev.ambon.persistence

import dev.ambon.config.PersistenceConfig
import org.jetbrains.exposed.v1.jdbc.Database

object WorldStateRepositoryFactory {
    fun create(
        persistence: PersistenceConfig,
        database: Database?,
    ): WorldStateRepository =
        createRepository(
            persistence,
            database,
            yamlFactory = { rootDir -> YamlWorldStateRepository(rootDir = rootDir) },
            postgresFactory = { db -> PostgresWorldStateRepository(database = db) },
        )
}
