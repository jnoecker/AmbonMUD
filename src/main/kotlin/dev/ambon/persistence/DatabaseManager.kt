package dev.ambon.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.ambon.config.DatabaseConfig
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database

class DatabaseManager(
    private val config: DatabaseConfig,
) {
    private val hikariDataSource: HikariDataSource

    val database: Database

    init {
        val hikariConfig =
            HikariConfig().apply {
                jdbcUrl = config.jdbcUrl
                username = config.username
                password = config.password
                maximumPoolSize = config.maxPoolSize
                minimumIdle = config.minimumIdle
            }
        hikariDataSource = HikariDataSource(hikariConfig)
        database = Database.connect(hikariDataSource)
    }

    fun migrate() {
        Flyway
            .configure()
            .dataSource(hikariDataSource)
            .load()
            .migrate()
    }

    fun close() {
        hikariDataSource.close()
    }
}
