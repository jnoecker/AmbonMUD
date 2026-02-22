package dev.ambon.gateway

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.InboundEvent
import dev.ambon.grpc.proto.InboundEventProto
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SessionRouterTest {
    private fun makeRouter(vararg engineIds: String): Pair<SessionRouter, Map<String, Channel<InboundEventProto>>> {
        val router = SessionRouter(engineIds.toList())
        val channels = mutableMapOf<String, Channel<InboundEventProto>>()
        for (id in engineIds) {
            val ch = Channel<InboundEventProto>(100)
            channels[id] = ch
            router.registerEngine(id, ch)
        }
        return router to channels
    }

    @Test
    fun `requires at least one engine`() {
        assertThrows<IllegalArgumentException> {
            SessionRouter(emptyList())
        }
    }

    @Test
    fun `Connected event assigns session round-robin`() =
        runBlocking {
            val (router, channels) = makeRouter("e1", "e2")

            val sid1 = SessionId(1)
            val sid2 = SessionId(2)
            val sid3 = SessionId(3)

            router.send(InboundEvent.Connected(sid1))
            router.send(InboundEvent.Connected(sid2))
            router.send(InboundEvent.Connected(sid3))

            assertEquals("e1", router.engineFor(sid1))
            assertEquals("e2", router.engineFor(sid2))
            assertEquals("e1", router.engineFor(sid3))

            // Verify events reached the right channels
            assertNotNull(channels["e1"]!!.tryReceive().getOrNull())
            assertNotNull(channels["e2"]!!.tryReceive().getOrNull())
            assertNotNull(channels["e1"]!!.tryReceive().getOrNull())
        }

    @Test
    fun `LineReceived routes to assigned engine`() =
        runBlocking {
            val (router, channels) = makeRouter("e1", "e2")

            val sid = SessionId(1)
            router.send(InboundEvent.Connected(sid))
            // sid is assigned to e1

            router.send(InboundEvent.LineReceived(sid, "look"))

            // Connected + LineReceived should both be on e1
            val proto1 = channels["e1"]!!.tryReceive().getOrNull()
            assertNotNull(proto1)
            val proto2 = channels["e1"]!!.tryReceive().getOrNull()
            assertNotNull(proto2)
            assertEquals("look", proto2!!.lineReceived.line)

            // e2 should have nothing
            assertNull(channels["e2"]!!.tryReceive().getOrNull())
        }

    @Test
    fun `Disconnected cleans up session mapping`() =
        runBlocking {
            val (router, _) = makeRouter("e1")

            val sid = SessionId(1)
            router.send(InboundEvent.Connected(sid))
            assertEquals("e1", router.engineFor(sid))

            router.send(InboundEvent.Disconnected(sid, "client closed"))
            assertNull(router.engineFor(sid))
        }

    @Test
    fun `remapSession updates routing`() =
        runBlocking {
            val (router, channels) = makeRouter("e1", "e2")

            val sid = SessionId(1)
            router.send(InboundEvent.Connected(sid))
            assertEquals("e1", router.engineFor(sid))

            // Remap to e2
            assertTrue(router.remapSession(sid, "e2"))
            assertEquals("e2", router.engineFor(sid))

            // Subsequent events should go to e2
            router.send(InboundEvent.LineReceived(sid, "look"))
            assertNotNull(channels["e2"]!!.tryReceive().getOrNull())
        }

    @Test
    fun `remapSession fails for unknown engine`() {
        val (router, _) = makeRouter("e1")

        val sid = SessionId(1)
        assertFalse(router.remapSession(sid, "unknown"))
    }

    @Test
    fun `trySend routes correctly`() {
        val (router, channels) = makeRouter("e1", "e2")

        val sid = SessionId(1)
        val result = router.trySend(InboundEvent.Connected(sid))
        assertTrue(result.isSuccess)

        val lineResult = router.trySend(InboundEvent.LineReceived(sid, "hello"))
        assertTrue(lineResult.isSuccess)

        // Both events should be on e1
        assertNotNull(channels["e1"]!!.tryReceive().getOrNull())
        assertNotNull(channels["e1"]!!.tryReceive().getOrNull())
    }

    @Test
    fun `trySend returns failure for unmapped session`() {
        val (router, _) = makeRouter("e1")

        // LineReceived for a session that was never connected
        val result = router.trySend(InboundEvent.LineReceived(SessionId(999), "hello"))
        assertTrue(result.isFailure)
    }

    @Test
    fun `close closes all engine channels`() {
        val (router, channels) = makeRouter("e1", "e2")

        router.close()

        assertTrue(channels["e1"]!!.isClosedForSend)
        assertTrue(channels["e2"]!!.isClosedForSend)
    }

    @Test
    fun `reattachEngine swaps channel`() =
        runBlocking {
            val (router, channels) = makeRouter("e1")

            val sid = SessionId(1)
            router.send(InboundEvent.Connected(sid))
            assertNotNull(channels["e1"]!!.tryReceive().getOrNull())

            // Swap to new channel
            val newChannel = Channel<InboundEventProto>(100)
            router.reattachEngine("e1", newChannel)

            router.send(InboundEvent.LineReceived(sid, "after reattach"))

            // Old channel should not have new events
            assertNull(channels["e1"]!!.tryReceive().getOrNull())
            // New channel should have the event
            val proto = newChannel.tryReceive().getOrNull()
            assertNotNull(proto)
            assertEquals("after reattach", proto!!.lineReceived.line)
        }
}
