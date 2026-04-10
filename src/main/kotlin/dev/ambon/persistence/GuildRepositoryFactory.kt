package dev.ambon.persistence

import dev.ambon.config.PersistenceConfig
import org.jetbrains.exposed.v1.jdbc.Database

object GuildRepositoryFactory {
    fun create(
        persistence: PersistenceConfig,
        database: Database?,
    ): GuildRepository =
        createRepository(
            persistence,
            database,
            yamlFactory = { rootDir -> YamlGuildRepository(rootDir = rootDir) },
            postgresFactory = { db -> PostgresGuildRepository(database = db) },
        )
}
