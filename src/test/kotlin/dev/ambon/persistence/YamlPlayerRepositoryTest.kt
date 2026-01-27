package dev.ambon.persistence

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.RenameResult
import dev.ambon.engine.items.ItemRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

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

    @Test
    fun `name does not attach existing record for different player`() =
        runTest {
            val repo = YamlPlayerRepository(tmp)
            val start = RoomId("test:a")
            val clock = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC)

            // create a record that lives in room b
            repo.create("Alice", RoomId("test:b"), 1000)
            val owner = repo.create("Bob", RoomId("test:a"), 1000)

            val players = PlayerRegistry(start, repo, ItemRegistry(), clock)
            val sid = SessionId(1)
            players.connect(sid)
            players.attachExisting(sid, owner)
            players.setAccountBound(sid, true)

            val res = players.rename(sid, "Alice")
            assertEquals(RenameResult.Taken, res)

            val ps = players.get(sid)!!
            assertEquals(owner.id, ps.playerId)
            assertEquals(owner.roomId, ps.roomId)
        }

    @Test
    fun `move persists roomId for claimed player`() =
        runTest {
            val repo = YamlPlayerRepository(tmp)
            val start = RoomId("test:a")
            val clock = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC)

            val players = PlayerRegistry(start, repo, ItemRegistry(), clock)
            val sid = SessionId(1)
            players.connect(sid)

            players.rename(sid, "Bob")
            val ps = players.get(sid)!!
            val pid = ps.playerId!!

            players.moveTo(sid, RoomId("test:c"))

            val loaded = repo.findById(pid)!!
            assertEquals(RoomId("test:c"), loaded.roomId)
        }

    @Test
    fun `name does not attach existing record without account-bound session`() =
        runTest {
            val repo = YamlPlayerRepository(tmp)
            val start = RoomId("test:a")
            val clock = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC)

            repo.create("Alice", RoomId("test:b"), 1000)

            val players = PlayerRegistry(start, repo, ItemRegistry(), clock)
            val sid = SessionId(1)
            players.connect(sid)

            val res = players.rename(sid, "Alice")
            assertEquals(RenameResult.Taken, res)
        }
}
