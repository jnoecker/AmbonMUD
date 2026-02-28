package dev.ambon.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.ambon.domain.guild.GuildRank
import dev.ambon.domain.guild.GuildRecord
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
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("integration")
class PostgresGuildRepositoryTest {
    companion object {
        private lateinit var hikari: HikariDataSource
        private lateinit var database: Database
        private val mapper = ObjectMapper()

        @BeforeAll
        @JvmStatic
        fun setup() {
            val config =
                HikariConfig().apply {
                    jdbcUrl = "jdbc:h2:mem:guilds_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
                    username = "sa"
                    password = ""
                }
            hikari = HikariDataSource(config)
            database = Database.connect(hikari)
            transaction(database) {
                SchemaUtils.create(GuildsTable)
                exec("CREATE UNIQUE INDEX IF NOT EXISTS idx_guilds_name_lower ON guilds (name_lower)")
                exec("CREATE UNIQUE INDEX IF NOT EXISTS idx_guilds_tag_lower ON guilds (tag_lower)")
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
            GuildsTable.deleteAll()
        }
    }

    private val leaderId = PlayerId(1L)

    private fun makeRecord(
        id: String = "shadowblade",
        name: String = "Shadowblade",
        tag: String = "SB",
    ) = GuildRecord(
        id = id,
        name = name,
        tag = tag,
        leaderId = leaderId,
        createdAtEpochMs = 1000L,
    )

    @Test
    fun `create then findById round-trips guild`() =
        runTest {
            val repo = PostgresGuildRepository(database, mapper)
            val returned = repo.create(makeRecord())

            assertEquals("shadowblade", returned.id)
            assertEquals("Shadowblade", returned.name)

            val found = repo.findById("shadowblade")
            assertNotNull(found)
            assertEquals("Shadowblade", found!!.name)
            assertEquals("SB", found.tag)
            assertEquals(leaderId, found.leaderId)
            assertNull(found.motd)
        }

    @Test
    fun `save persists member and motd changes`() =
        runTest {
            val repo = PostgresGuildRepository(database, mapper)
            val member = PlayerId(2L)
            val record = repo.create(makeRecord())
            val updated =
                record.copy(
                    members = mapOf(leaderId to GuildRank.LEADER, member to GuildRank.MEMBER),
                    motd = "Welcome!",
                )

            repo.save(updated)

            val loaded = repo.findById("shadowblade")!!
            assertEquals(2, loaded.members.size)
            assertEquals(GuildRank.LEADER, loaded.members[leaderId])
            assertEquals(GuildRank.MEMBER, loaded.members[member])
            assertEquals("Welcome!", loaded.motd)
        }

    @Test
    fun `delete removes guild`() =
        runTest {
            val repo = PostgresGuildRepository(database, mapper)
            repo.create(makeRecord())
            assertNotNull(repo.findById("shadowblade"))

            repo.delete("shadowblade")
            assertNull(repo.findById("shadowblade"))
        }

    @Test
    fun `findByName is case-insensitive`() =
        runTest {
            val repo = PostgresGuildRepository(database, mapper)
            repo.create(makeRecord())

            assertNotNull(repo.findByName("Shadowblade"))
            assertNotNull(repo.findByName("shadowblade"))
            assertNotNull(repo.findByName("SHADOWBLADE"))
            assertNull(repo.findByName("nonexistent"))
        }

    @Test
    fun `findAll returns all guilds`() =
        runTest {
            val repo = PostgresGuildRepository(database, mapper)
            repo.create(makeRecord("guild1", "GuildOne", "G1"))
            repo.create(makeRecord("guild2", "GuildTwo", "G2"))

            val all = repo.findAll()
            assertEquals(2, all.size)
            assertTrue(all.any { it.id == "guild1" })
            assertTrue(all.any { it.id == "guild2" })
        }

    @Test
    fun `findById returns null for unknown`() =
        runTest {
            val repo = PostgresGuildRepository(database, mapper)
            assertNull(repo.findById("unknown"))
        }

    @Test
    fun `findByName returns null for unknown`() =
        runTest {
            val repo = PostgresGuildRepository(database, mapper)
            assertNull(repo.findByName("nobody"))
        }
}
