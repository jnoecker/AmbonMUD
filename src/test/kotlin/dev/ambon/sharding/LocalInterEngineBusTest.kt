package dev.ambon.sharding

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalInterEngineBusTest {
    @Test
    fun `broadcast delivers message to incoming channel`() = runBlocking {
        val bus = LocalInterEngineBus()
        bus.start()

        val msg = InterEngineMessage.GlobalBroadcast(BroadcastType.GOSSIP, "Alice", "Hello world")
        bus.broadcast(msg)

        val received = bus.incoming().tryReceive().getOrNull()
        assertEquals(msg, received)
        bus.close()
    }

    @Test
    fun `sendTo delivers message to incoming channel`() = runBlocking {
        val bus = LocalInterEngineBus()
        bus.start()

        val msg = InterEngineMessage.TellMessage("Alice", "Bob", "Hi!")
        bus.sendTo("any-engine", msg)

        val received = bus.incoming().tryReceive().getOrNull()
        assertEquals(msg, received)
        bus.close()
    }

    @Test
    fun `close causes incoming to be closed`() = runBlocking {
        val bus = LocalInterEngineBus()
        bus.start()
        bus.close()

        val result = bus.incoming().tryReceive()
        assertTrue(result.isClosed)
    }

    @Test
    fun `multiple messages are queued in order`() = runBlocking {
        val bus = LocalInterEngineBus()
        bus.start()

        val msg1 = InterEngineMessage.GlobalBroadcast(BroadcastType.GOSSIP, "A", "first")
        val msg2 = InterEngineMessage.GlobalBroadcast(BroadcastType.GOSSIP, "B", "second")
        bus.broadcast(msg1)
        bus.broadcast(msg2)

        val r1 = bus.incoming().tryReceive().getOrNull()
        val r2 = bus.incoming().tryReceive().getOrNull()
        assertEquals(msg1, r1)
        assertEquals(msg2, r2)
        bus.close()
    }
}
