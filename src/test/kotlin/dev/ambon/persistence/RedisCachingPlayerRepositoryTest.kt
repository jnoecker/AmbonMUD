package dev.ambon.persistence

import dev.ambon.domain.ids.RoomId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RedisCachingPlayerRepositoryTest {
    private lateinit var cache: InMemoryStringCache
    private lateinit var delegate: InMemoryPlayerRepository
    private lateinit var repo: RedisCachingPlayerRepository

    @BeforeEach
    fun setUp() {
        cache = InMemoryStringCache()
        delegate = InMemoryPlayerRepository()
        repo =
            RedisCachingPlayerRepository(
                delegate = delegate,
                cache = cache,
                cacheTtlSeconds = 60L,
            )
    }

    @Test
    fun `cache hit on findByName after save`() =
        runBlocking {
            val record =
                repo.create(
                    PlayerCreationRequest(
                        name = "Aragorn",
                        startRoomId = RoomId("demo:start"),
                        nowEpochMs = 1000L,
                        passwordHash = "hash1",
                        ansiEnabled = false,
                    ),
                )
            delegate.clear()

            val found = repo.findByName("Aragorn")
            assertNotNull(found)
            assertEquals(record.id, found!!.id)
            assertEquals("Aragorn", found.name)
        }

    @Test
    fun `cache miss falls through to delegate`() =
        runBlocking {
            delegate.create(
                PlayerCreationRequest(
                    name = "Legolas",
                    startRoomId = RoomId("demo:start"),
                    nowEpochMs = 2000L,
                    passwordHash = "hash2",
                    ansiEnabled = false,
                ),
            )

            val found = repo.findByName("Legolas")
            assertNotNull(found)
            assertEquals("Legolas", found!!.name)
        }

    @Test
    fun `findById cache hit`() =
        runBlocking {
            val record =
                repo.create(
                    PlayerCreationRequest(
                        name = "Gimli",
                        startRoomId = RoomId("demo:start"),
                        nowEpochMs = 3000L,
                        passwordHash = "hash3",
                        ansiEnabled = true,
                    ),
                )
            delegate.clear()

            val found = repo.findById(record.id)
            assertNotNull(found)
            assertEquals(record.id, found!!.id)
            assertEquals("Gimli", found.name)
        }

    @Test
    fun `create caches result`() =
        runBlocking {
            val record =
                repo.create(
                    PlayerCreationRequest(
                        name = "Frodo",
                        startRoomId = RoomId("demo:shire"),
                        nowEpochMs = 4000L,
                        passwordHash = "hash4",
                        ansiEnabled = false,
                    ),
                )
            delegate.clear()

            val byName = repo.findByName("Frodo")
            assertNotNull(byName)
            assertEquals(record.id, byName!!.id)

            val byId = repo.findById(record.id)
            assertNotNull(byId)
            assertEquals("Frodo", byId!!.name)
        }

    @Test
    fun `save writes through to delegate and caches`() =
        runBlocking {
            val record =
                repo.create(
                    PlayerCreationRequest(
                        name = "Samwise",
                        startRoomId = RoomId("demo:shire"),
                        nowEpochMs = 5000L,
                        passwordHash = "hash5",
                        ansiEnabled = false,
                    ),
                )
            val updated = record.copy(level = 5, xpTotal = 500L)
            repo.save(updated)

            val fromDelegate = delegate.findById(record.id)
            assertNotNull(fromDelegate)
            assertEquals(5, fromDelegate!!.level)

            delegate.clear()

            val fromCache = repo.findById(record.id)
            assertNotNull(fromCache)
            assertEquals(5, fromCache!!.level)
        }

    @Test
    fun `cache failure degrades gracefully`() =
        runBlocking {
            delegate.create(
                PlayerCreationRequest(
                    name = "Gandalf",
                    startRoomId = RoomId("demo:start"),
                    nowEpochMs = 6000L,
                    passwordHash = "hash6",
                    ansiEnabled = false,
                ),
            )
            cache.failAllRequests = true

            val found = repo.findByName("Gandalf")
            assertNotNull(found)
            assertEquals("Gandalf", found!!.name)
        }

    @Test
    fun `null returned for unknown player`() =
        runBlocking {
            val byName = repo.findByName("Unknown")
            assertNull(byName)

            val byId = repo.findById(PlayerId(99999L))
            assertNull(byId)
        }

    @Test
    fun `all fields survive round-trip including isStaff`() =
        runBlocking {
            val created =
                repo.create(
                    PlayerCreationRequest(
                        name = "Sauron",
                        startRoomId = RoomId("demo:mordor"),
                        nowEpochMs = 7000L,
                        passwordHash = "darkHash",
                        ansiEnabled = true,
                    ),
                )
            val staffRecord =
                created.copy(
                    constitution = 10,
                    level = 50,
                    xpTotal = 999_999L,
                    lastSeenEpochMs = 8000L,
                    isStaff = true,
                )
            repo.save(staffRecord)
            delegate.clear()

            val found = repo.findById(staffRecord.id)
            assertNotNull(found)
            assertEquals(staffRecord.id, found!!.id)
            assertEquals("Sauron", found.name)
            assertEquals(RoomId("demo:mordor"), found.roomId)
            assertEquals(10, found.constitution)
            assertEquals(50, found.level)
            assertEquals(999_999L, found.xpTotal)
            assertEquals(7000L, found.createdAtEpochMs)
            assertEquals(8000L, found.lastSeenEpochMs)
            assertEquals("darkHash", found.passwordHash)
            assertEquals(true, found.ansiEnabled)
            assertEquals(true, found.isStaff)
        }

    private class InMemoryStringCache : StringCache {
        private val values = mutableMapOf<String, String>()
        var failAllRequests: Boolean = false

        override fun get(key: String): String? {
            failIfConfigured()
            return values[key]
        }

        override fun setEx(
            key: String,
            ttlSeconds: Long,
            value: String,
        ) {
            failIfConfigured()
            values[key] = value
        }

        private fun failIfConfigured() {
            if (failAllRequests) error("Cache unavailable")
        }
    }
}
