package dev.ambon.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

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
}
