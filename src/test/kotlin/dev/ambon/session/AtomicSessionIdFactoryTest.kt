package dev.ambon.session

import dev.ambon.domain.ids.SessionId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AtomicSessionIdFactoryTest {
    @Test
    fun `allocates sequential ids starting from 1`() {
        val factory = AtomicSessionIdFactory()
        assertEquals(SessionId(1), factory.allocate())
        assertEquals(SessionId(2), factory.allocate())
        assertEquals(SessionId(3), factory.allocate())
    }

    @Test
    fun `custom start id`() {
        val factory = AtomicSessionIdFactory(startId = 100)
        assertEquals(SessionId(100), factory.allocate())
        assertEquals(SessionId(101), factory.allocate())
    }

    @Test
    fun `ids are unique across many allocations`() {
        val factory = AtomicSessionIdFactory()
        val ids = (1..1000).map { factory.allocate() }.toSet()
        assertEquals(1000, ids.size)
    }
}
