package dev.ambon.persistence

import dev.ambon.domain.ids.RoomId
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class YamlPlayerRepositoryTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `create then findById and findByName`() =
        runTest {
            val repo = YamlPlayerRepository(tmp)
            val now = 1234L

            val r = repo.create("Alice", RoomId("test:a"), now)

            val byId = repo.findById(r.id)
            assertNotNull(byId)
            assertEquals("Alice", byId!!.name)
            assertEquals(RoomId("test:a"), byId.roomId)

            val byName = repo.findByName("alice")
            assertNotNull(byName)
            assertEquals(r.id, byName!!.id)
        }

    @Test
    fun `save persists changes`() =
        runTest {
            val repo = YamlPlayerRepository(tmp)
            val now = 1000L

            val r = repo.create("Bob", RoomId("test:a"), now)
            val updated = r.copy(roomId = RoomId("test:b"), lastSeenEpochMs = 2000L, ansiEnabled = true)

            repo.save(updated)

            val loaded = repo.findById(r.id)!!
            assertEquals(RoomId("test:b"), loaded.roomId)
            assertEquals(2000L, loaded.lastSeenEpochMs)
            assertTrue(loaded.ansiEnabled)
        }

    @Test
    fun `create enforces unique name case-insensitive`() =
        runTest {
            val repo = YamlPlayerRepository(tmp)
            val now = 1L

            repo.create("Carol", RoomId("test:a"), now)

            val ex =
                try {
                    repo.create("carol", RoomId("test:a"), now)
                    fail("Expected PlayerPersistenceException")
                } catch (e: PlayerPersistenceException) {
                    e
                }

            assertTrue(ex.message!!.contains("taken", ignoreCase = true))
        }
}
