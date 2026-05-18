package dev.ambon.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.items.ItemSlot
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("integration")
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
                    fail("Expected PersistenceException")
                } catch (e: PersistenceException) {
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

    @Test
    fun `create persists starter equipment, gold, and gender in the initial insert`() =
        runTest {
            val repo = PostgresPlayerRepository(database)
            val sword = ItemInstance(
                id = ItemId("test:sword"),
                item = Item(keyword = "sword", displayName = "a sword", slot = ItemSlot.WEAPON, damage = 4),
            )
            val potion = ItemInstance(
                id = ItemId("test:potion"),
                item = Item(keyword = "potion", displayName = "a potion", consumable = true),
            )

            val created = repo.create(
                PlayerCreationRequest(
                    name = "Dana",
                    startRoomId = RoomId("test:a"),
                    nowEpochMs = 1L,
                    passwordHash = "hash",
                    ansiEnabled = false,
                    gender = "fem",
                    gold = 250L,
                    inventoryItems = listOf(potion),
                    equippedItems = mapOf("weapon" to sword),
                ),
            )

            // Reload directly from the database — the in-memory return value
            // could mask a missing column write, so we verify what actually
            // landed on disk in the initial create transaction.
            val loaded = repo.findById(created.id)!!
            assertEquals("fem", loaded.gender)
            assertEquals(250L, loaded.gold)
            assertEquals(1, loaded.inventoryItems.size)
            assertEquals("a potion", loaded.inventoryItems.first().item.displayName)
            assertEquals("a sword", loaded.equippedItems["weapon"]?.item?.displayName)
            assertTrue(loaded.autolootEnabled)
        }
}
