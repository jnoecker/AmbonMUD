package dev.ambon.persistence

import dev.ambon.domain.ids.RoomId
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WriteCoalescingPlayerRepositoryTest {
    private val startRoom = RoomId("zone:start")

    @Test
    fun `save marks dirty without writing to delegate`() =
        runTest {
            val delegate = InMemoryPlayerRepository()
            val repo = WriteCoalescingPlayerRepository(delegate)

            val record = delegate.create("Alice", startRoom, 1000L, "hash", false)
            val updated = record.copy(roomId = RoomId("zone:room2"), lastSeenEpochMs = 2000L)

            // Clear the delegate so we can verify save doesn't write through
            delegate.save(record) // baseline
            repo.save(updated)

            // Delegate still has old record
            val fromDelegate = delegate.findById(record.id)
            assertNotNull(fromDelegate)
            assertEquals(startRoom, fromDelegate!!.roomId)

            // But repo cache returns updated
            val fromRepo = repo.findById(record.id)
            assertNotNull(fromRepo)
            assertEquals(RoomId("zone:room2"), fromRepo!!.roomId)

            assertEquals(1, repo.dirtyCount())
        }

    @Test
    fun `flushDirty writes dirty records to delegate`() =
        runTest {
            val delegate = InMemoryPlayerRepository()
            val repo = WriteCoalescingPlayerRepository(delegate)

            val record = delegate.create("Alice", startRoom, 1000L, "hash", false)
            val updated = record.copy(roomId = RoomId("zone:room2"))
            repo.save(updated)

            val flushed = repo.flushDirty()
            assertEquals(1, flushed)
            assertEquals(0, repo.dirtyCount())

            val fromDelegate = delegate.findById(record.id)
            assertEquals(RoomId("zone:room2"), fromDelegate!!.roomId)
        }

    @Test
    fun `multiple saves coalesce into single flush write`() =
        runTest {
            val counter = SaveCountingRepository(InMemoryPlayerRepository())
            val repo = WriteCoalescingPlayerRepository(counter)

            val record = counter.create("Alice", startRoom, 1000L, "hash", false)

            repo.save(record.copy(roomId = RoomId("zone:r1")))
            repo.save(record.copy(roomId = RoomId("zone:r2")))
            repo.save(record.copy(roomId = RoomId("zone:r3")))
            repo.save(record.copy(roomId = RoomId("zone:r4")))
            repo.save(record.copy(roomId = RoomId("zone:r5")))

            assertEquals(1, repo.dirtyCount())
            assertEquals(0, counter.saveCount)

            repo.flushDirty()

            assertEquals(1, counter.saveCount)
            val fromDelegate = counter.findById(record.id)
            assertEquals(RoomId("zone:r5"), fromDelegate!!.roomId)
        }

    @Test
    fun `findByName returns cached record`() =
        runTest {
            val delegate = InMemoryPlayerRepository()
            val repo = WriteCoalescingPlayerRepository(delegate)

            val record = delegate.create("Alice", startRoom, 1000L, "hash", false)
            val updated = record.copy(roomId = RoomId("zone:cached"))
            repo.save(updated)

            val found = repo.findByName("Alice")
            assertNotNull(found)
            assertEquals(RoomId("zone:cached"), found!!.roomId)
        }

    @Test
    fun `findByName falls through to delegate on cache miss`() =
        runTest {
            val delegate = InMemoryPlayerRepository()
            val repo = WriteCoalescingPlayerRepository(delegate)

            delegate.create("Bob", startRoom, 1000L, "hash", false)

            val found = repo.findByName("Bob")
            assertNotNull(found)
            assertEquals("Bob", found!!.name)
        }

    @Test
    fun `findById falls through to delegate on cache miss`() =
        runTest {
            val delegate = InMemoryPlayerRepository()
            val repo = WriteCoalescingPlayerRepository(delegate)

            val record = delegate.create("Charlie", startRoom, 1000L, "hash", false)

            val found = repo.findById(record.id)
            assertNotNull(found)
            assertEquals("Charlie", found!!.name)
        }

    @Test
    fun `findByName returns null for unknown player`() =
        runTest {
            val delegate = InMemoryPlayerRepository()
            val repo = WriteCoalescingPlayerRepository(delegate)

            assertNull(repo.findByName("Nobody"))
        }

    @Test
    fun `create delegates through and caches result`() =
        runTest {
            val delegate = InMemoryPlayerRepository()
            val repo = WriteCoalescingPlayerRepository(delegate)

            val record = repo.create("Dave", startRoom, 1000L, "hash", false)
            assertNotNull(record)

            val cached = repo.findById(record.id)
            assertEquals(record, cached)

            val fromDelegate = delegate.findById(record.id)
            assertEquals(record, fromDelegate)
        }

    @Test
    fun `flushDirty with no dirty records returns zero`() =
        runTest {
            val delegate = InMemoryPlayerRepository()
            val repo = WriteCoalescingPlayerRepository(delegate)

            assertEquals(0, repo.flushDirty())
        }

    @Test
    fun `flushAll is equivalent to flushDirty`() =
        runTest {
            val delegate = InMemoryPlayerRepository()
            val repo = WriteCoalescingPlayerRepository(delegate)

            val record = delegate.create("Eve", startRoom, 1000L, "hash", false)
            repo.save(record.copy(roomId = RoomId("zone:new")))

            val flushed = repo.flushAll()
            assertEquals(1, flushed)
            assertEquals(0, repo.dirtyCount())
        }
}

private class SaveCountingRepository(
    private val delegate: InMemoryPlayerRepository,
) : PlayerRepository by delegate {
    var saveCount = 0

    override suspend fun save(record: PlayerRecord) {
        saveCount++
        delegate.save(record)
    }
}
