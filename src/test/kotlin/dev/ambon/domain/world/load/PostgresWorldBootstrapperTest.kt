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
    fun `importPendingFiles merges new staged files into existing world content`() {
        val importDir = Files.createDirectories(tempDir.resolve("import"))
        val archiveDir = tempDir.resolve("archive")
        val repo = PostgresWorldContentRepository(database)
        val existingText = javaClass.getResource("/world/test_world.yaml")!!.readText()
        val additionalText = javaClass.getResource("/world/ok_small.yaml")!!.readText()

        repo.replaceAll(
            listOf(
                dev.ambon.persistence.WorldContentDocument(
                    sourceName = "test_world.yaml",
                    zone = "test_zone",
                    content = existingText,
                    loadOrder = 0,
                    importedAtEpochMs = 1L,
                ),
            ),
        )
        Files.writeString(importDir.resolve("addon_zone.yaml"), additionalText)

        val bootstrapper =
            PostgresWorldBootstrapper(
                repository = repo,
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

        assertTrue(world.rooms.containsKey(dev.ambon.domain.ids.RoomId("test_zone:hub")))
        assertTrue(world.rooms.containsKey(dev.ambon.domain.ids.RoomId("ok_small:a")))

        val storedDocuments = repo.loadAll()
        assertEquals(listOf("test_world.yaml", "addon_zone.yaml"), storedDocuments.map { it.sourceName })
    }

    @Test
    fun `importPendingFiles replaces existing document when staged source name matches`() {
        val importDir = Files.createDirectories(tempDir.resolve("import"))
        val archiveDir = tempDir.resolve("archive")
        val repo = PostgresWorldContentRepository(database)
        val originalText = javaClass.getResource("/world/ok_small.yaml")!!.readText()
        val replacementText = javaClass.getResource("/world/test_world.yaml")!!.readText()

        repo.replaceAll(
            listOf(
                dev.ambon.persistence.WorldContentDocument(
                    sourceName = "replace_zone.yaml",
                    zone = "replace_zone",
                    content = originalText,
                    loadOrder = 0,
                    importedAtEpochMs = 1L,
                ),
            ),
        )
        Files.writeString(importDir.resolve("replace_zone.yaml"), replacementText)

        val bootstrapper =
            PostgresWorldBootstrapper(
                repository = repo,
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

        assertTrue(world.rooms.containsKey(dev.ambon.domain.ids.RoomId("test_zone:hub")))
        assertFalse(world.rooms.containsKey(dev.ambon.domain.ids.RoomId("ok_small:a")))

        val storedDocuments = repo.loadAll()
        assertEquals(1, storedDocuments.size)
        assertEquals("replace_zone.yaml", storedDocuments.single().sourceName)
        assertEquals(replacementText, storedDocuments.single().content)
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
    fun `empty database and no staged files fails fast when no resources configured`() {
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

    @Test
    fun `bootstraps from classpath resources when database is empty`() {
        val importDir = Files.createDirectories(tempDir.resolve("import"))
        val archiveDir = tempDir.resolve("archive")
        val repo = PostgresWorldContentRepository(database)

        val bootstrapper =
            PostgresWorldBootstrapper(
                repository = repo,
                storage =
                    WorldStorageConfig(
                        backend = WorldStorageBackend.POSTGRES,
                        importDirectory = importDir.toString(),
                        archiveDirectory = archiveDir.toString(),
                    ),
                tiers = MobTiersConfig(),
                resources = listOf("world/test_world.yaml"),
                clock = Clock.fixed(Instant.ofEpochMilli(999L), ZoneOffset.UTC),
            )

        val world = bootstrapper.loadWorld()

        // World loaded correctly from the bundled resource.
        assertEquals("test_zone:hub", world.startRoom.value)

        // Documents were persisted so the next startup reads from DB without re-bootstrapping.
        val storedDocuments = repo.loadAll()
        assertEquals(1, storedDocuments.size)
        assertEquals("test_world.yaml", storedDocuments.single().sourceName)
        assertEquals(999L, storedDocuments.single().importedAtEpochMs)

        // Import directory untouched (no staged files to archive).
        assertFalse(Files.exists(archiveDir))
    }

    @Test
    fun `skips resource bootstrap when database already has content`() {
        val importDir = Files.createDirectories(tempDir.resolve("import"))
        val archiveDir = tempDir.resolve("archive")
        val repo = PostgresWorldContentRepository(database)
        val existingText = javaClass.getResource("/world/test_world.yaml")!!.readText()

        repo.replaceAll(
            listOf(
                dev.ambon.persistence.WorldContentDocument(
                    sourceName = "test_world.yaml",
                    zone = "test_zone",
                    content = existingText,
                    loadOrder = 0,
                    importedAtEpochMs = 1L,
                ),
            ),
        )

        // ok_small.yaml would change the world if bootstrap ran — it must not.
        val bootstrapper =
            PostgresWorldBootstrapper(
                repository = repo,
                storage =
                    WorldStorageConfig(
                        backend = WorldStorageBackend.POSTGRES,
                        importDirectory = importDir.toString(),
                        archiveDirectory = archiveDir.toString(),
                    ),
                tiers = MobTiersConfig(),
                resources = listOf("world/ok_small.yaml"),
            )

        val world = bootstrapper.loadWorld()

        // Still loaded from the existing DB content, not the resource fallback.
        assertTrue(world.rooms.containsKey(dev.ambon.domain.ids.RoomId("test_zone:hub")))
        assertFalse(world.rooms.containsKey(dev.ambon.domain.ids.RoomId("ok_small:a")))

        val storedDocuments = repo.loadAll()
        assertEquals(1, storedDocuments.size)
        assertEquals("test_world.yaml", storedDocuments.single().sourceName)
    }
}
