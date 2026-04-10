package dev.ambon.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.ambon.domain.housing.HouseRecord
import dev.ambon.domain.housing.HouseRoomRecord
import dev.ambon.domain.world.Direction
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("integration")
class PostgresHouseRepositoryTest {
    companion object {
        private lateinit var hikari: HikariDataSource
        private lateinit var database: Database

        @BeforeAll
        @JvmStatic
        fun setup() {
            val config =
                HikariConfig().apply {
                    jdbcUrl = "jdbc:h2:mem:houses_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
                    username = "sa"
                    password = ""
                }
            hikari = HikariDataSource(config)
            database = Database.connect(hikari)
            transaction(database) {
                SchemaUtils.create(HousesTable)
                exec("CREATE UNIQUE INDEX IF NOT EXISTS idx_houses_owner_name_lower ON houses (owner_name_lower)")
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
            HousesTable.deleteAll()
        }
    }

    private val ownerId = PlayerId(42L)

    private fun makeRecord(
        ownerName: String = "Gandalf",
    ) = HouseRecord(
        ownerId = ownerId,
        ownerName = ownerName,
        rooms = listOf(
            HouseRoomRecord(
                templateId = "cottage_entry",
                exits = mapOf(Direction.NORTH to 1),
            ),
            HouseRoomRecord(
                templateId = "vault",
                exits = mapOf(Direction.SOUTH to 0),
            ),
        ),
        createdAtEpochMs = 1000L,
    )

    @Test
    fun `save then findByOwnerId round-trips house`() =
        runTest {
            val repo = PostgresHouseRepository(database)
            repo.save(makeRecord())

            val found = repo.findByOwnerId(ownerId)
            assertNotNull(found)
            assertEquals("Gandalf", found!!.ownerName)
            assertEquals(2, found.rooms.size)
            assertEquals("cottage_entry", found.rooms[0].templateId)
            assertEquals(mapOf(Direction.NORTH to 1), found.rooms[0].exits)
            assertEquals("vault", found.rooms[1].templateId)
        }

    @Test
    fun `save overwrites existing record`() =
        runTest {
            val repo = PostgresHouseRepository(database)
            repo.save(makeRecord())

            val updated = makeRecord().copy(
                rooms = listOf(
                    HouseRoomRecord(templateId = "cottage_entry"),
                ),
            )
            repo.save(updated)

            val loaded = repo.findByOwnerId(ownerId)!!
            assertEquals(1, loaded.rooms.size)
        }

    @Test
    fun `findByOwnerName is case-insensitive`() =
        runTest {
            val repo = PostgresHouseRepository(database)
            repo.save(makeRecord())

            assertNotNull(repo.findByOwnerName("Gandalf"))
            assertNotNull(repo.findByOwnerName("gandalf"))
            assertNotNull(repo.findByOwnerName("GANDALF"))
            assertNull(repo.findByOwnerName("Frodo"))
        }

    @Test
    fun `delete removes house`() =
        runTest {
            val repo = PostgresHouseRepository(database)
            repo.save(makeRecord())
            assertNotNull(repo.findByOwnerId(ownerId))

            repo.delete(ownerId)
            assertNull(repo.findByOwnerId(ownerId))
        }

    @Test
    fun `findByOwnerId returns null for unknown`() =
        runTest {
            val repo = PostgresHouseRepository(database)
            assertNull(repo.findByOwnerId(PlayerId(999L)))
        }

    @Test
    fun `custom title and description round-trip`() =
        runTest {
            val repo = PostgresHouseRepository(database)
            val record = HouseRecord(
                ownerId = ownerId,
                ownerName = "Gandalf",
                rooms = listOf(
                    HouseRoomRecord(
                        templateId = "cottage_entry",
                        customTitle = "My Cozy Cabin",
                        customDescription = "A well-loved retreat.",
                    ),
                ),
                createdAtEpochMs = 2000L,
            )
            repo.save(record)

            val loaded = repo.findByOwnerId(ownerId)!!
            assertEquals("My Cozy Cabin", loaded.rooms[0].customTitle)
            assertEquals("A well-loved retreat.", loaded.rooms[0].customDescription)
        }
}
