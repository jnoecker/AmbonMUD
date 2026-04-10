package dev.ambon.persistence

import dev.ambon.config.PersistenceConfig
import org.jetbrains.exposed.v1.jdbc.Database

object HouseRepositoryFactory {
    fun create(
        persistence: PersistenceConfig,
        database: Database?,
    ): HouseRepository =
        createRepository(
            persistence,
            database,
            yamlFactory = { rootDir -> YamlHouseRepository(rootDir = rootDir) },
            postgresFactory = { db -> PostgresHouseRepository(database = db) },
        )
}
