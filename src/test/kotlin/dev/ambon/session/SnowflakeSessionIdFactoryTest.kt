package dev.ambon.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class SnowflakeSessionIdFactoryTest {
    @Test
    fun `IDs are unique within a single factory`() {
        val factory = SnowflakeSessionIdFactory(gatewayId = 1)
        val ids = (1..1000).map { factory.allocate() }.toSet()
        assertEquals(1000, ids.size, "All 1000 IDs should be unique")
    }

    @Test
    fun `IDs from different gateways never collide`() {
        val a = SnowflakeSessionIdFactory(gatewayId = 1)
        val b = SnowflakeSessionIdFactory(gatewayId = 2)
        val idsA = (1..500).map { a.allocate() }.toSet()
        val idsB = (1..500).map { b.allocate() }.toSet()
        assertTrue(idsA.intersect(idsB).isEmpty(), "Gateways with different IDs must not share session IDs")
    }

    @Test
    fun `gatewayId bits are in the top 16 bits`() {
        val factory = SnowflakeSessionIdFactory(gatewayId = 0xABCD)
        val id = factory.allocate().value
        val extractedGateway = (id ushr 48) and 0xFFFFL
        assertEquals(0xABCDL, extractedGateway)
    }

    @Test
    fun `sequence increments within the same second`() {
        val factory = SnowflakeSessionIdFactory(gatewayId = 0)
        val id1 = factory.allocate().value
        val id2 = factory.allocate().value
        val seq1 = id1 and 0xFFFFL
        val seq2 = id2 and 0xFFFFL
        // Within the same second, seq2 = seq1 + 1 (or seq1 is 0 and seq2 is 1)
        assertTrue(seq2 > seq1, "Sequence should increment: seq1=$seq1 seq2=$seq2")
    }

    @Test
    fun `gatewayId 0 is valid`() {
        val factory = SnowflakeSessionIdFactory(gatewayId = 0)
        val id = factory.allocate().value
        assertEquals(0L, (id ushr 48) and 0xFFFFL)
    }

    @Test
    fun `gatewayId 65535 is valid`() {
        val factory = SnowflakeSessionIdFactory(gatewayId = 0xFFFF)
        val id = factory.allocate().value
        assertEquals(0xFFFFL, (id ushr 48) and 0xFFFFL)
    }

    @Test
    fun `gatewayId out of range throws`() {
        assertThrows<IllegalArgumentException> { SnowflakeSessionIdFactory(-1) }
        assertThrows<IllegalArgumentException> { SnowflakeSessionIdFactory(0x10000) }
    }

    @Test
    fun `IDs from gateway 1 and gateway 2 differ in top 16 bits`() {
        val a = SnowflakeSessionIdFactory(gatewayId = 1)
        val b = SnowflakeSessionIdFactory(gatewayId = 2)
        val idA = a.allocate().value
        val idB = b.allocate().value
        assertNotEquals((idA ushr 48) and 0xFFFFL, (idB ushr 48) and 0xFFFFL)
    }

    @Test
    fun `implements SessionIdFactory interface`() {
        val factory: SessionIdFactory = SnowflakeSessionIdFactory(gatewayId = 5)
        val id = factory.allocate()
        assertTrue(id.value != 0L)
    }

    // -------------------------------------------------------------------------
    // New hardening tests
    // -------------------------------------------------------------------------

    @Test
    fun `clock rollback applies monotonic floor`() {
        val clock = AtomicLong(100L)
        val factory = SnowflakeSessionIdFactory(gatewayId = 1, clockSeconds = { clock.get() })

        val idBefore = factory.allocate().value
        val tsBefore = (idBefore ushr 16) and 0xFFFFFFFFL

        // Roll the clock back by 5 seconds
        clock.set(95L)
        val idAfter = factory.allocate().value
        val tsAfter = (idAfter ushr 16) and 0xFFFFFFFFL

        assertEquals(tsBefore, tsAfter, "Timestamp bits must not decrease on clock rollback")
        assertNotEquals(idBefore, idAfter, "IDs must still be unique after clock rollback")
    }

    @Test
    fun `clock rollback fires callback exactly once per rollback event`() {
        val clock = AtomicLong(100L)
        val rollbackCount = AtomicInteger(0)
        val factory =
            SnowflakeSessionIdFactory(
                gatewayId = 1,
                clockSeconds = { clock.get() },
                onClockRollback = { rollbackCount.incrementAndGet() },
            )

        factory.allocate() // second=100, seq=0
        clock.set(99L) // rollback
        factory.allocate() // should fire callback once
        clock.set(98L) // another rollback
        factory.allocate() // should fire callback again

        assertEquals(2, rollbackCount.get(), "Callback must fire once per rollback-detected allocate()")
    }

    @Test
    fun `sequence overflow waits then resumes with new timestamp`() {
        val clock = AtomicLong(0L)
        val factory = SnowflakeSessionIdFactory(gatewayId = 1, clockSeconds = { clock.get() })

        // Fill the sequence for second 0 (seq 0..65535 = 65536 allocations)
        repeat(65536) { factory.allocate() }

        // 65537th call will overflow. Advance the clock from a background thread after a short delay.
        val future =
            CompletableFuture.supplyAsync {
                factory.allocate()
            }
        Thread.sleep(15) // give the factory time to enter the wait loop
        clock.set(1L) // advance the clock to unblock the wait

        val id = future.get(2, TimeUnit.SECONDS)
        val ts = (id.value ushr 16) and 0xFFFFFFFFL
        val seq = id.value and 0xFFFFL

        assertEquals(1L, ts, "ID after overflow must carry the new timestamp second")
        assertEquals(0L, seq, "Sequence must reset to 0 after clock advances")
    }

    @Test
    fun `sequence overflow fires callback`() {
        val clock = AtomicLong(0L)
        val overflowCount = AtomicInteger(0)
        val factory =
            SnowflakeSessionIdFactory(
                gatewayId = 1,
                clockSeconds = { clock.get() },
                onSequenceOverflow = { overflowCount.incrementAndGet() },
            )

        // Fill the sequence
        repeat(65536) { factory.allocate() }

        // Trigger overflow in a background thread
        val future = CompletableFuture.supplyAsync { factory.allocate() }
        Thread.sleep(15)
        clock.set(1L)
        future.get(2, TimeUnit.SECONDS)

        assertEquals(1, overflowCount.get(), "Overflow callback must fire exactly once")
    }

    @Test
    fun `sustained high allocation produces no collisions across clock boundaries`() {
        val clock = AtomicLong(1_000L)
        val factory = SnowflakeSessionIdFactory(gatewayId = 7, clockSeconds = { clock.get() })

        val ids = mutableSetOf<Long>()
        repeat(200_000) { i ->
            // Advance clock every 65536 allocations to keep sequence from exhausting
            if (i > 0 && i % 65536 == 0) clock.incrementAndGet()
            val id = factory.allocate().value
            assertTrue(ids.add(id), "Duplicate ID detected at allocation $i: $id")
        }
    }
}
