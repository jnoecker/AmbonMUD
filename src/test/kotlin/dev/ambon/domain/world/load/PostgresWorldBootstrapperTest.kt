package dev.ambon.domain.world.load

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.ambon.config.MobTiersConfig
import dev.ambon.config.WorldStorageBackend
import dev.ambon.config.WorldStorageConfig
import dev.ambon.persistence.PostgresWorldContentRepository
import dev.ambon.persistence.WorldContentTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.name

class PostgresWorldBootstrapperTest {
    companion object {
        private lateinit var hikari: HikariDataSource
        private lateinit var database: Database

        @BeforeAll
        @JvmStatic
        fun setup() {
            val config =
                HikariConfig().apply {
                    jdbcUrl = "jdbc:h2:mem:world_bootstrap;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
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

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun reset() {
        transaction(database) {
            WorldContentTable.deleteAll()
        }
    }

    @Test
    fun `imports staged world files into postgres and archives them`() {
        val importDir = Files.createDirectories(tempDir.resolve("import"))
        val archiveDir = tempDir.resolve("archive")
        val sourceText = javaClass.getResource("/world/test_world.yaml")!!.readText()
        Files.writeString(importDir.resolve("test_world.yaml"), sourceText)

        val bootstrapper =
            PostgresWorldBootstrapper(
                repository = PostgresWorldContentRepository(database),
                storage =
                    WorldStorageConfig(
                        backend = WorldStorageBackend.POSTGRES,
                        importDirectory = importDir.toString(),
                        archiveDirectory = archiveDir.toString(),
                    ),
                tiers = MobTiersConfig(),
                clock = Clock.fixed(Instant.ofEpochMilli(123456L), ZoneOffset.UTC),
            )

        val world = bootstrapper.loadWorld()

        assertEquals("test_zone:hub", world.startRoom.value)
        assertFalse(Files.exists(importDir.resolve("test_world.yaml")))

        val archivedFiles =
            Files.walk(archiveDir).use { stream ->
                stream.filter { Files.isRegularFile(it) }.map { it.name }.toList()
            }
        assertTrue("test_world.yaml" in archivedFiles)

        val storedDocuments = PostgresWorldContentRepository(database).loadAll()
        assertEquals(1, storedDocuments.size)
        assertEquals("test_world.yaml", storedDocuments.single().sourceName)
    }

    @Test
    fun `invalid staged files do not replace database content or archive input`() {
        val importDir = Files.createDirectories(tempDir.resolve("import"))
        val archiveDir = tempDir.resolve("archive")
        val invalidText = javaClass.getResource("/world/bad_start_missing.yaml")!!.readText()
        Files.writeString(importDir.resolve("bad.yaml"), invalidText)

        val bootstrapper =
            PostgresWorldBootstrapper(
                repository = PostgresWorldContentRepository(database),
                storage =
                    WorldStorageConfig(
                        backend = WorldStorageBackend.POSTGRES,
                        importDirectory = importDir.toString(),
                        archiveDirectory = archiveDir.toString(),
                    ),
                tiers = MobTiersConfig(),
                clock = Clock.fixed(Instant.ofEpochMilli(123456L), ZoneOffset.UTC),
            )

        assertThrows(WorldLoadException::class.java) {
            bootstrapper.loadWorld()
        }

        assertTrue(Files.exists(importDir.resolve("bad.yaml")))
        assertFalse(Files.exists(archiveDir))
        assertTrue(PostgresWorldContentRepository(database).loadAll().isEmpty())
    }

    @Test
    fun `importPendingFiles returns zero when no staged files are present`() {
        val importDir = Files.createDirectories(tempDir.resolve("import"))
        val archiveDir = tempDir.resolve("archive")

        val bootstrapper =
            PostgresWorldBootstrapper(
                repository = PostgresWorldContentRepository(database),
                storage =
                    WorldStorageConfig(
                        backend = WorldStorageBackend.POSTGRES,
                        importDirectory = importDir.toString(),
                        archiveDirectory = archiveDir.toString(),
                    ),
                tiers = MobTiersConfig(),
            )

        assertEquals(0, bootstrapper.importPendingFiles())
        assertFalse(Files.exists(archiveDir))
    }

    @Test
    fun `empty database and no staged files fails fast`() {
        val importDir = Files.createDirectories(tempDir.resolve("import"))
        val archiveDir = tempDir.resolve("archive")

        val bootstrapper =
            PostgresWorldBootstrapper(
                repository = PostgresWorldContentRepository(database),
                storage =
                    WorldStorageConfig(
                        backend = WorldStorageBackend.POSTGRES,
                        importDirectory = importDir.toString(),
                        archiveDirectory = archiveDir.toString(),
                    ),
                tiers = MobTiersConfig(),
            )

        val error =
            assertThrows(IllegalStateException::class.java) {
                bootstrapper.loadWorld()
            }

        assertTrue(error.message!!.contains("No static world content found"))
    }
}
