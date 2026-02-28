package dev.ambon.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PostgresWorldContentRepositoryTest {
    companion object {
        private lateinit var hikari: HikariDataSource
        private lateinit var database: Database

        @BeforeAll
        @JvmStatic
        fun setup() {
            val config =
                HikariConfig().apply {
                    jdbcUrl = "jdbc:h2:mem:world_content_repo;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
                    username = "sa"
                    password = ""
                }
            hikari = HikariDataSource(config)
            database = Database.connect(hikari)
            transaction(database) {
                SchemaUtils.create(WorldContentTable)
            }
        }

        @AfterAll
        @JvmStatic
        fun teardown() {
            hikari.close()
        }
    }

    @BeforeEach
    fun reset() {
        transaction(database) {
            WorldContentTable.deleteAll()
        }
    }

    @Test
    fun `replaceAll overwrites existing content and preserves load order`() {
        val repo = PostgresWorldContentRepository(database)

        repo.replaceAll(
            listOf(
                WorldContentDocument(
                    sourceName = "b.yaml",
                    zone = "zone_b",
                    content = "zone: zone_b",
                    loadOrder = 1,
                    importedAtEpochMs = 100L,
                ),
                WorldContentDocument(
                    sourceName = "a.yaml",
                    zone = "zone_a",
                    content = "zone: zone_a",
                    loadOrder = 0,
                    importedAtEpochMs = 100L,
                ),
            ),
        )

        val loaded = repo.loadAll()
        assertEquals(listOf("a.yaml", "b.yaml"), loaded.map { it.sourceName })

        repo.replaceAll(
            listOf(
                WorldContentDocument(
                    sourceName = "only.yaml",
                    zone = "zone_only",
                    content = "zone: zone_only",
                    loadOrder = 0,
                    importedAtEpochMs = 200L,
                ),
            ),
        )

        val replaced = repo.loadAll()
        assertEquals(1, replaced.size)
        assertEquals("only.yaml", replaced.single().sourceName)
        assertEquals("zone_only", replaced.single().zone)
        assertEquals(200L, replaced.single().importedAtEpochMs)
    }
}
