package dev.ambon.engine

import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.items.ItemSlot
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.persistence.PersistenceWorker
import dev.ambon.persistence.PlayerCreationRequest
import dev.ambon.persistence.WriteCoalescingPlayerRepository
import dev.ambon.test.MutableClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests shutdown-related cleanup: persistence flush, trade cleanup,
 * and auction listing persistence.
 *
 * These tests ensure that server shutdown procedures properly persist
 * all dirty state and clean up active game sessions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShutdownCleanupTest {
    private val startRoom = RoomId("zone:start")

    // ── Persistence flush on shutdown ─────────────────────────────────────

    @Test
    fun `shutdown flushes all dirty player records`() = runTest {
        val delegate = InMemoryPlayerRepository()
        val repo = WriteCoalescingPlayerRepository(delegate)
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testScheduler)
        val worker = PersistenceWorker(
            repo,
            flushIntervalMs = 60_000L, // Very long so it won't auto-flush
            scope = testScope,
            dispatcher = testDispatcher,
        )

        // Create multiple players with dirty records
        val record1 = delegate.create(PlayerCreationRequest("Alice", startRoom, 1000L, "hash", false))
        val record2 = delegate.create(PlayerCreationRequest("Bob", startRoom, 1000L, "hash", false))
        val record3 = delegate.create(PlayerCreationRequest("Charlie", startRoom, 1000L, "hash", false))

        repo.save(record1.copy(roomId = RoomId("zone:room1")))
        repo.save(record2.copy(roomId = RoomId("zone:room2")))
        repo.save(record3.copy(roomId = RoomId("zone:room3")))

        assertEquals(3, repo.dirtyCount(), "All 3 records should be dirty")

        worker.start()
        runCurrent()

        // No time advanced — records should still be dirty
        assertEquals(3, repo.dirtyCount(), "Records should still be dirty before shutdown")

        // Trigger shutdown
        worker.shutdown()

        // All records should have been flushed
        assertEquals(0, repo.dirtyCount(), "All records should be flushed after shutdown")
        assertEquals(RoomId("zone:room1"), delegate.findById(record1.id)!!.roomId)
        assertEquals(RoomId("zone:room2"), delegate.findById(record2.id)!!.roomId)
        assertEquals(RoomId("zone:room3"), delegate.findById(record3.id)!!.roomId)
    }

    @Test
    fun `shutdown flushes records that were modified just before shutdown`() = runTest {
        val delegate = InMemoryPlayerRepository()
        val repo = WriteCoalescingPlayerRepository(delegate)
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testScheduler)
        val worker = PersistenceWorker(
            repo,
            flushIntervalMs = 60_000L,
            scope = testScope,
            dispatcher = testDispatcher,
        )

        val record = delegate.create(PlayerCreationRequest("Alice", startRoom, 1000L, "hash", false))

        worker.start()
        runCurrent()

        // Simulate rapid state changes right before shutdown
        repo.save(record.copy(roomId = RoomId("zone:r1"), gold = 100))
        repo.save(record.copy(roomId = RoomId("zone:r2"), gold = 200))
        repo.save(record.copy(roomId = RoomId("zone:final"), gold = 500))

        // The coalescing should mean only 1 dirty entry
        assertEquals(1, repo.dirtyCount())

        worker.shutdown()

        // The final state should be persisted
        val persisted = delegate.findById(record.id)!!
        assertEquals(RoomId("zone:final"), persisted.roomId)
        assertEquals(500, persisted.gold)
    }

    // ── Trade cleanup on shutdown ─────────────────────────────────────────

    @Test
    fun `active trades are canceled on shutdown returning escrowed items`() = runTest {
        val items = ItemRegistry()
        val tradeSystem = TradeSystem(items)

        val alice = SessionId(1)
        val bob = SessionId(2)

        val sword = ItemInstance(
            id = ItemId("sword_1"),
            item = Item(keyword = "sword", displayName = "a copper sword", slot = ItemSlot.HAND, damage = 4),
        )
        val shield = ItemInstance(
            id = ItemId("shield_1"),
            item = Item(keyword = "shield", displayName = "a wooden shield", slot = ItemSlot.HAND, armor = 2),
        )

        items.addToInventory(alice, sword)
        items.addToInventory(bob, shield)

        // Start a trade and offer items
        tradeSystem.createSession(alice, bob)
        tradeSystem.offerItem(alice, "sword")
        tradeSystem.offerItem(bob, "shield")

        // Verify items are in escrow
        assertTrue(items.inventory(alice).isEmpty(), "Sword in escrow")
        assertTrue(items.inventory(bob).isEmpty(), "Shield in escrow")

        // Simulate shutdown: cancel all active trades
        tradeSystem.cancelForPlayer(alice)

        // Items should be returned
        assertTrue(items.inventory(alice).any { it.item.keyword == "sword" }, "Sword returned to Alice")
        assertTrue(items.inventory(bob).any { it.item.keyword == "shield" }, "Shield returned to Bob")
        assertFalse(tradeSystem.isInTrade(alice))
        assertFalse(tradeSystem.isInTrade(bob))
    }

    @Test
    fun `multiple concurrent trades are all cleaned up on shutdown`() = runTest {
        val items = ItemRegistry()
        val tradeSystem = TradeSystem(items)

        val alice = SessionId(1)
        val bob = SessionId(2)
        val charlie = SessionId(3)
        val dave = SessionId(4)

        val sword = ItemInstance(
            id = ItemId("sword_1"),
            item = Item(keyword = "sword", displayName = "sword", slot = ItemSlot.HAND, damage = 4),
        )
        val shield = ItemInstance(
            id = ItemId("shield_1"),
            item = Item(keyword = "shield", displayName = "shield", slot = ItemSlot.HAND, armor = 2),
        )

        items.addToInventory(alice, sword)
        items.addToInventory(charlie, shield)

        // Two separate trades
        tradeSystem.createSession(alice, bob)
        tradeSystem.offerItem(alice, "sword")

        tradeSystem.createSession(charlie, dave)
        tradeSystem.offerItem(charlie, "shield")

        // Shutdown: cancel all
        tradeSystem.cancelForPlayer(alice)
        tradeSystem.cancelForPlayer(charlie)

        assertTrue(items.inventory(alice).any { it.item.keyword == "sword" })
        assertTrue(items.inventory(charlie).any { it.item.keyword == "shield" })
        assertFalse(tradeSystem.isInTrade(alice))
        assertFalse(tradeSystem.isInTrade(bob))
        assertFalse(tradeSystem.isInTrade(charlie))
        assertFalse(tradeSystem.isInTrade(dave))
    }

    // ── Duel cleanup on shutdown ──────────────────────────────────────────

    @Test
    fun `active duels are ended on shutdown`() = runTest {
        val clock = MutableClock(0L)
        val duelSystem = DuelSystem(clock)

        val alice = SessionId(1)
        val bob = SessionId(2)
        val charlie = SessionId(3)
        val dave = SessionId(4)

        // Two active duels
        duelSystem.challenge(alice, bob)
        duelSystem.accept(bob)
        duelSystem.challenge(charlie, dave)
        duelSystem.accept(dave)

        assertTrue(duelSystem.isInDuel(alice))
        assertTrue(duelSystem.isInDuel(charlie))

        // Shutdown: end all
        duelSystem.onPlayerDisconnect(alice)
        duelSystem.onPlayerDisconnect(charlie)

        assertFalse(duelSystem.isInDuel(alice))
        assertFalse(duelSystem.isInDuel(bob))
        assertFalse(duelSystem.isInDuel(charlie))
        assertFalse(duelSystem.isInDuel(dave))
    }

    // ── Write coalescing preserves most recent state ──────────────────────

    @Test
    fun `coalescing repository preserves last written state through shutdown`() = runTest {
        val delegate = InMemoryPlayerRepository()
        val repo = WriteCoalescingPlayerRepository(delegate)

        val record = delegate.create(PlayerCreationRequest("Alice", startRoom, 1000L, "hash", false))

        // Simulate a player gaining XP and gold across many ticks
        for (i in 1..50) {
            repo.save(
                record.copy(
                    xpTotal = i.toLong() * 100,
                    gold = i.toLong() * 10,
                    roomId = RoomId("zone:room_$i"),
                ),
            )
        }

        // Should only be 1 dirty entry despite 50 saves
        assertEquals(1, repo.dirtyCount())

        // Flush all (as shutdown does)
        val result = repo.flushAll()
        assertEquals(1, result.flushed)
        assertEquals(0, result.failed)

        // The final state should be the one persisted
        val persisted = delegate.findById(record.id)!!
        assertEquals(5000L, persisted.xpTotal)
        assertEquals(500L, persisted.gold)
        assertEquals(RoomId("zone:room_50"), persisted.roomId)
    }
}
