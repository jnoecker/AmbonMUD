package dev.ambon.persistence

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.LoginResult
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.items.ItemRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mindrot.jbcrypt.BCrypt
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.writeText

class YamlPlayerRepositoryTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `create then findById and findByName`() =
        runTest {
            val repo = YamlPlayerRepository(tmp)
            val now = 1234L
            val hash = BCrypt.hashpw("password", BCrypt.gensalt())

            val r = repo.create("Alice", RoomId("test:a"), now, hash, ansiEnabled = false)

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
            val hash = BCrypt.hashpw("password", BCrypt.gensalt())

            val r = repo.create("Bob", RoomId("test:a"), now, hash, ansiEnabled = false)
            val updated =
                r.copy(
                    roomId = RoomId("test:b"),
                    lastSeenEpochMs = 2000L,
                    ansiEnabled = true,
                    level = 3,
                    xpTotal = 400L,
                    isStaff = true,
                )

            repo.save(updated)

            val loaded = repo.findById(r.id)!!
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
            val repo = YamlPlayerRepository(tmp)
            val now = 1L
            val hash = BCrypt.hashpw("password", BCrypt.gensalt())

            repo.create("Carol", RoomId("test:a"), now, hash, ansiEnabled = false)

            val ex =
                try {
                    repo.create("carol", RoomId("test:a"), now, hash, ansiEnabled = false)
                    fail("Expected PlayerPersistenceException")
                } catch (e: PlayerPersistenceException) {
                    e
                }

            assertTrue(ex.message!!.contains("taken", ignoreCase = true))
        }

    @Test
    fun `name loads existing player record and restores room`() =
        runTest {
            val repo = YamlPlayerRepository(tmp)
            val start = RoomId("test:a")
            val clock = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC)

            // create a record that lives in room b
            val existing =
                repo.create(
                    "Alice",
                    RoomId("test:b"),
                    1000,
                    BCrypt.hashpw("password", BCrypt.gensalt()),
                    ansiEnabled = false,
                )

            val players = PlayerRegistry(start, repo, ItemRegistry(), clock)
            val sid = SessionId(1)
            val res = players.login(sid, "Alice", "password")
            assertEquals(LoginResult.Ok, res)

            val ps = players.get(sid)!!
            assertEquals(existing.id, ps.playerId)
            assertEquals(RoomId("test:b"), ps.roomId)
        }

    @Test
    fun `move persists roomId for claimed player`() =
        runTest {
            val repo = YamlPlayerRepository(tmp)
            val start = RoomId("test:a")
            val clock = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC)

            val players = PlayerRegistry(start, repo, ItemRegistry(), clock)
            val sid = SessionId(1)
            val res = players.login(sid, "Bob", "password")
            assertEquals(LoginResult.Ok, res)
            val ps = players.get(sid)!!
            val pid = ps.playerId!!

            players.moveTo(sid, RoomId("test:c"))

            val loaded = repo.findById(pid)!!
            assertEquals(RoomId("test:c"), loaded.roomId)
        }

    @Test
    fun `legacy yaml without progression fields loads defaults`() =
        runTest {
            val repo = YamlPlayerRepository(tmp)
            val path = tmp.resolve("players").resolve("00000000000000000001.yaml")
            path.writeText(
                """
                id: 1
                name: Legacy
                roomId: test:a
                createdAtEpochMs: 100
                lastSeenEpochMs: 200
                passwordHash: legacy-hash
                ansiEnabled: false
                """.trimIndent(),
            )

            val loaded = repo.findById(PlayerId(1))
            assertNotNull(loaded)
            assertEquals(1, loaded!!.level)
            assertEquals(0L, loaded.xpTotal)
            assertFalse(loaded.isStaff)
        }

    @Test
    fun `save persists gold and reload restores it`() =
        runTest {
            val repo = YamlPlayerRepository(tmp)
            val now = 1000L
            val hash = BCrypt.hashpw("password", BCrypt.gensalt())

            val r = repo.create("GoldTest", RoomId("test:a"), now, hash, ansiEnabled = false)
            assertEquals(0L, r.gold)

            val updated = r.copy(gold = 250L)
            repo.save(updated)

            val loaded = repo.findById(r.id)!!
            assertEquals(250L, loaded.gold)
        }

    @Test
    fun `legacy yaml without gold field defaults to zero`() =
        runTest {
            val repo = YamlPlayerRepository(tmp)
            val path = tmp.resolve("players").resolve("00000000000000000002.yaml")
            path.parent.toFile().mkdirs()
            path.writeText(
                """
                id: 2
                name: LegacyNoGold
                roomId: test:a
                createdAtEpochMs: 100
                lastSeenEpochMs: 200
                passwordHash: legacy-hash
                ansiEnabled: false
                """.trimIndent(),
            )

            val loaded = repo.findById(PlayerId(2))
            assertNotNull(loaded)
            assertEquals(0L, loaded!!.gold)
        }
}
