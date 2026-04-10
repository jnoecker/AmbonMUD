package dev.ambon.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.ambon.config.DatabaseConfig
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database

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
                maxLifetime = 1_800_000L // 30 min
                connectionTimeout = 30_000L // 30 sec
                idleTimeout = 600_000L // 10 min
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
