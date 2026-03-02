package dev.ambon.persistence

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.LoginResult
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.test.TestPasswordHasher
import dev.ambon.test.buildTestPlayerRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.readText
import kotlin.io.path.writeText

class YamlPlayerRepositoryTest {
    @TempDir
    lateinit var tmp: Path

    private val testHash = TestPasswordHasher.hash("password")

    @Test
    fun `create then findById and findByName`() =
        runTest {
            val repo = YamlPlayerRepository(tmp)
            val now = 1234L
            val hash = testHash

            val r = repo.create(PlayerCreationRequest("Alice", RoomId("test:a"), now, hash, ansiEnabled = false))

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
            val hash = testHash

            val r = repo.create(PlayerCreationRequest("Bob", RoomId("test:a"), now, hash, ansiEnabled = false))
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
            val hash = testHash

            repo.create(PlayerCreationRequest("Carol", RoomId("test:a"), now, hash, ansiEnabled = false))

            val ex =
                try {
                    repo.create(PlayerCreationRequest("carol", RoomId("test:a"), now, hash, ansiEnabled = false))
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
                    PlayerCreationRequest(
                        name = "Alice",
                        startRoomId = RoomId("test:b"),
                        nowEpochMs = 1000,
                        passwordHash = testHash,
                        ansiEnabled = false,
                    ),
                )

            val players = buildTestPlayerRegistry(start, repo, ItemRegistry(), clock)
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

            val players = buildTestPlayerRegistry(start, repo, ItemRegistry(), clock)
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
            val hash = testHash

            val r = repo.create(PlayerCreationRequest("GoldTest", RoomId("test:a"), now, hash, ansiEnabled = false))
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

    @Test
    fun `concurrent create assigns unique ids and persists next id`() =
        runTest {
            val repo = YamlPlayerRepository(tmp)
            val now = 1000L
            val names = (1..32).map { "Player$it" }

            val created =
                withContext(Dispatchers.Default) {
                    coroutineScope {
                        val start = CompletableDeferred<Unit>()
                        val jobs =
                            names.map { name ->
                                async {
                                    start.await()
                                    repo.create(PlayerCreationRequest(name, RoomId("test:a"), now, testHash, ansiEnabled = false))
                                }
                            }
                        start.complete(Unit)
                        jobs.awaitAll()
                    }
                }

            assertEquals(names.size, created.size)
            assertEquals((1L..names.size.toLong()).toSet(), created.map { it.id.value }.toSet())
            assertEquals((names.size + 1).toString(), tmp.resolve("next_player_id.txt").readText().trim())
        }

    @Test
    fun `concurrent create allows only one matching name`() =
        runTest {
            val repo = YamlPlayerRepository(tmp)
            val attempts = 16
            val now = 1000L

            val results =
                withContext(Dispatchers.Default) {
                    coroutineScope {
                        val start = CompletableDeferred<Unit>()
                        val jobs =
                            (1..attempts).map {
                                async {
                                    start.await()
                                    runCatching {
                                        repo.create(
                                            PlayerCreationRequest(
                                                "Carol",
                                                RoomId("test:a"),
                                                now,
                                                testHash,
                                                ansiEnabled = false,
                                            ),
                                        )
                                    }
                                }
                            }
                        start.complete(Unit)
                        jobs.awaitAll()
                    }
                }

            assertEquals(1, results.count { it.isSuccess })
            assertEquals(attempts - 1, results.count { it.isFailure })
            results.filter { it.isFailure }.forEach { result ->
                val error = result.exceptionOrNull()
                assertTrue(error is PlayerPersistenceException)
                assertTrue(error!!.message!!.contains("taken", ignoreCase = true))
            }
        }

    @Test
    fun `findByName returns lowest-id record when duplicate names exist on disk`() =
        runTest {
            // Simulate the on-disk state left by a previous data-loss incident: two files both
            // named "Ambuoroko" with IDs 1 and 5.  The repository must always return ID 1
            // (lowest / earliest created) regardless of filesystem scan order.
            val playersDir = tmp.resolve("players")
            playersDir.toFile().mkdirs()

            val hash1 = TestPasswordHasher.hash("password1")
            val hash5 = TestPasswordHasher.hash("password5")

            fun yaml(id: Long, hash: String) =
                """
                id: $id
                name: "Ambuoroko"
                roomId: "test:start"
                createdAtEpochMs: 1000
                lastSeenEpochMs: 2000
                passwordHash: "$hash"
                ansiEnabled: false
                """.trimIndent()

            playersDir.resolve("00000000000000000001.yaml").writeText(yaml(1, hash1))
            playersDir.resolve("00000000000000000005.yaml").writeText(yaml(5, hash5))
            tmp.resolve("next_player_id.txt").writeText("6")

            val repo = YamlPlayerRepository(tmp)
            val found = repo.findByName("Ambuoroko")

            assertNotNull(found)
            assertEquals(1L, found!!.id.value)
        }

    @Test
    fun `nextId is corrected upward when next_player_id_txt is stale`() =
        runTest {
            // Simulate a stale next_player_id.txt (e.g. file lost and recreated from scratch)
            // that falls behind the actual max file ID.  Creating a new player must not
            // overwrite the existing file with the highest ID.
            val playersDir = tmp.resolve("players")
            playersDir.toFile().mkdirs()

            val existingHash = TestPasswordHasher.hash("secret")
            playersDir.resolve("00000000000000000010.yaml").writeText(
                """
                id: 10
                name: "HighId"
                roomId: "test:start"
                createdAtEpochMs: 1000
                lastSeenEpochMs: 2000
                passwordHash: "$existingHash"
                ansiEnabled: false
                """.trimIndent(),
            )
            // Stale counter — would collide with IDs 1..10 if not corrected
            tmp.resolve("next_player_id.txt").writeText("1")

            val repo = YamlPlayerRepository(tmp)
            // Force the index scan (and nextId correction) by doing a lookup
            repo.findByName("HighId")

            // Now create a new player; it must receive ID 11, not 1..10
            val newRecord = repo.create(
                PlayerCreationRequest(
                    "NewPlayer",
                    RoomId("test:start"),
                    3000L,
                    TestPasswordHasher.hash("pw"),
                    ansiEnabled = false,
                ),
            )
            assertEquals(11L, newRecord.id.value)

            // The existing file must be untouched
            val existing = repo.findByName("HighId")
            assertNotNull(existing)
            assertEquals(10L, existing!!.id.value)
        }

    @Test
    fun `save and reload preserves mail inbox`() =
        runTest {
            val repo = YamlPlayerRepository(tmp)
            val r = repo.create(PlayerCreationRequest("Alice", RoomId("test:a"), 0L, testHash, ansiEnabled = false))
            val mail =
                dev.ambon.domain.mail.MailMessage(
                    id = "abc123",
                    fromName = "Bob",
                    body = "Hello Alice!",
                    sentAtEpochMs = 9000L,
                    read = false,
                )
            repo.save(r.copy(inbox = listOf(mail)))

            val loaded = repo.findById(r.id)!!
            assertEquals(1, loaded.inbox.size)
            assertEquals("abc123", loaded.inbox[0].id)
            assertEquals("Bob", loaded.inbox[0].fromName)
            assertEquals("Hello Alice!", loaded.inbox[0].body)
            assertFalse(loaded.inbox[0].read)
        }
}
