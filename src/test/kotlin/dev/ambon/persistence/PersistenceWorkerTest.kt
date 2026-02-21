package dev.ambon.persistence

import dev.ambon.domain.ids.RoomId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PersistenceWorkerTest {
    private val startRoom = RoomId("zone:start")

    @Test
    fun `worker flushes dirty records on interval`() =
        runTest {
            val delegate = InMemoryPlayerRepository()
            val repo = WriteCoalescingPlayerRepository(delegate)
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val testScope = TestScope(testScheduler)
            val worker =
                PersistenceWorker(
                    repo,
                    flushIntervalMs = 1_000L,
                    scope = testScope,
                    dispatcher = testDispatcher,
                )

            val record = delegate.create("Alice", startRoom, 1000L, "hash", false)
            repo.save(record.copy(roomId = RoomId("zone:room2")))
            assertEquals(1, repo.dirtyCount())

            worker.start()
            runCurrent()

            advanceTimeBy(1_100L)
            runCurrent()

            assertEquals(0, repo.dirtyCount())
            assertEquals(RoomId("zone:room2"), delegate.findById(record.id)!!.roomId)
        }

    @Test
    fun `shutdown flushes remaining dirty records`() =
        runTest {
            val delegate = InMemoryPlayerRepository()
            val repo = WriteCoalescingPlayerRepository(delegate)
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val testScope = TestScope(testScheduler)
            val worker =
                PersistenceWorker(
                    repo,
                    flushIntervalMs = 60_000L,
                    scope = testScope,
                    dispatcher = testDispatcher,
                )

            val record = delegate.create("Bob", startRoom, 1000L, "hash", false)
            repo.save(record.copy(roomId = RoomId("zone:final")))

            worker.start()
            runCurrent()

            assertEquals(1, repo.dirtyCount())

            worker.shutdown()

            assertEquals(0, repo.dirtyCount())
            assertEquals(RoomId("zone:final"), delegate.findById(record.id)!!.roomId)
        }

    @Test
    fun `worker handles multiple flush cycles`() =
        runTest {
            val delegate = InMemoryPlayerRepository()
            val repo = WriteCoalescingPlayerRepository(delegate)
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val testScope = TestScope(testScheduler)
            val worker =
                PersistenceWorker(
                    repo,
                    flushIntervalMs = 500L,
                    scope = testScope,
                    dispatcher = testDispatcher,
                )

            val record = delegate.create("Charlie", startRoom, 1000L, "hash", false)

            worker.start()
            runCurrent()

            // Cycle 1
            repo.save(record.copy(roomId = RoomId("zone:r1")))
            advanceTimeBy(600L)
            runCurrent()
            assertEquals(0, repo.dirtyCount())
            assertEquals(RoomId("zone:r1"), delegate.findById(record.id)!!.roomId)

            // Cycle 2
            repo.save(record.copy(roomId = RoomId("zone:r2")))
            advanceTimeBy(600L)
            runCurrent()
            assertEquals(0, repo.dirtyCount())
            assertEquals(RoomId("zone:r2"), delegate.findById(record.id)!!.roomId)
        }
}
