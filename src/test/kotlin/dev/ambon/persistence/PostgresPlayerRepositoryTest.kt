package dev.ambon.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.ambon.domain.ids.RoomId
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PostgresPlayerRepositoryTest {
    companion object {
        private lateinit var hikari: HikariDataSource
        private lateinit var database: Database

        @BeforeAll
        @JvmStatic
        fun setup() {
            val config =
                HikariConfig().apply {
                    jdbcUrl = "jdbc:h2:mem:test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
                    username = "sa"
                    password = ""
                }
            hikari = HikariDataSource(config)
            database = Database.connect(hikari)
            transaction(database) {
                SchemaUtils.create(PlayersTable)
                exec("CREATE UNIQUE INDEX IF NOT EXISTS idx_players_name_lower ON players (name_lower)")
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
            PlayersTable.deleteAll()
        }
    }

    @Test
    fun `create then findById and findByName`() =
        runTest {
            val repo = PostgresPlayerRepository(database)

            val created = repo.create(PlayerCreationRequest("Alice", RoomId("test:a"), 1234L, "hash123", ansiEnabled = false))

            assertEquals("Alice", created.name)
            assertEquals(RoomId("test:a"), created.roomId)
            assertEquals(1234L, created.createdAtEpochMs)

            val byId = repo.findById(created.id)
            assertNotNull(byId)
            assertEquals("Alice", byId!!.name)
            assertEquals(RoomId("test:a"), byId.roomId)

            val byName = repo.findByName("alice")
            assertNotNull(byName)
            assertEquals(created.id, byName!!.id)
        }

    @Test
    fun `save persists changes`() =
        runTest {
            val repo = PostgresPlayerRepository(database)

            val created = repo.create(PlayerCreationRequest("Bob", RoomId("test:a"), 1000L, "hash456", ansiEnabled = false))
            val updated =
                created.copy(
                    roomId = RoomId("test:b"),
                    lastSeenEpochMs = 2000L,
                    ansiEnabled = true,
                    level = 3,
                    xpTotal = 400L,
                    isStaff = true,
                )

            repo.save(updated)

            val loaded = repo.findById(created.id)!!
            assertEquals(RoomId("test:b"), loaded.roomId)
            assertEquals(2000L, loaded.lastSeenEpochMs)
            assertTrue(loaded.ansiEnabled)
            assertEquals(3, loaded.level)
            assertEquals(400L, loaded.xpTotal)
            assertTrue(loaded.isStaff)
        }

    @Test
    fun `create enforces unique name case-insensitive`() =
        runTest {
            val repo = PostgresPlayerRepository(database)

            repo.create(PlayerCreationRequest("Carol", RoomId("test:a"), 1L, "hash789", ansiEnabled = false))

            val ex =
                try {
                    repo.create(PlayerCreationRequest("carol", RoomId("test:a"), 1L, "hash789", ansiEnabled = false))
                    fail("Expected PlayerPersistenceException")
                } catch (e: PlayerPersistenceException) {
                    e
                }

            assertTrue(ex.message!!.contains("taken", ignoreCase = true))
        }

    @Test
    fun `findByName returns null for unknown`() =
        runTest {
            val repo = PostgresPlayerRepository(database)
            assertNull(repo.findByName("nobody"))
        }

    @Test
    fun `findById returns null for unknown`() =
        runTest {
            val repo = PostgresPlayerRepository(database)
            assertNull(repo.findById(PlayerId(999)))
        }
}
