package dev.ambon.grpc

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeliveryOutcomeTest {
    @Test
    fun `fast-path success returns Delivered`() =
        runBlocking {
            val ch = Channel<Int>(1)
            val outcome =
                deliverWithBackpressure(
                    trySend = { ch.trySend(1) },
                    suspendSend = { ch.send(1) },
                    isControlPlane = false,
                    controlPlaneSendTimeoutMs = 50L,
                )
            assertEquals(DeliveryOutcome.Delivered, outcome)
            ch.close()
        }

    @Test
    fun `data-plane drop on full channel returns DroppedDataPlane with channelClosed false`() =
        runBlocking {
            val ch = Channel<Int>(Channel.RENDEZVOUS)
            val outcome =
                deliverWithBackpressure(
                    trySend = { ch.trySend(1) },
                    suspendSend = { ch.send(1) },
                    isControlPlane = false,
                    controlPlaneSendTimeoutMs = 50L,
                )
            assertTrue(outcome is DeliveryOutcome.DroppedDataPlane)
            assertFalse((outcome as DeliveryOutcome.DroppedDataPlane).channelClosed)
            ch.close()
        }

    @Test
    fun `data-plane drop on closed channel returns DroppedDataPlane with channelClosed true`() =
        runBlocking {
            val ch = Channel<Int>(1)
            ch.close()
            val outcome =
                deliverWithBackpressure(
                    trySend = { ch.trySend(1) },
                    suspendSend = { ch.send(1) },
                    isControlPlane = false,
                    controlPlaneSendTimeoutMs = 50L,
                )
            assertTrue(outcome is DeliveryOutcome.DroppedDataPlane)
            assertTrue((outcome as DeliveryOutcome.DroppedDataPlane).channelClosed)
        }

    @Test
    fun `control-plane on closed channel returns ControlPlaneFailure with stream_closed`() =
        runBlocking {
            val ch = Channel<Int>(1)
            ch.close()
            val outcome =
                deliverWithBackpressure(
                    trySend = { ch.trySend(1) },
                    suspendSend = { ch.send(1) },
                    isControlPlane = true,
                    controlPlaneSendTimeoutMs = 50L,
                )
            assertEquals(DeliveryOutcome.ControlPlaneFailure("stream_closed"), outcome)
        }

    @Test
    fun `control-plane fallback send succeeds returns DeliveredWithFallback`() =
        runBlocking {
            val ch = Channel<Int>(1)
            // Fill the channel so trySend fails, but a suspending send will succeed once we drain.
            ch.trySend(0)
            var trySendCalled = false
            val outcome =
                deliverWithBackpressure(
                    trySend = {
                        if (!trySendCalled) {
                            trySendCalled = true
                            ch.trySend(1) // fails — channel full
                        } else {
                            ch.trySend(1)
                        }
                    },
                    suspendSend = {
                        ch.receive() // drain the pre-filled element
                        ch.send(1) // now succeeds
                    },
                    isControlPlane = true,
                    controlPlaneSendTimeoutMs = 1_000L,
                )
            assertEquals(DeliveryOutcome.DeliveredWithFallback, outcome)
            ch.close()
        }

    @Test
    fun `control-plane timeout returns ControlPlaneFailure with stream_full_timeout`() =
        runBlocking {
            val ch = Channel<Int>(Channel.RENDEZVOUS)
            val outcome =
                deliverWithBackpressure(
                    trySend = { ch.trySend(1) },
                    suspendSend = { ch.send(1) }, // will suspend forever — timeout kicks in
                    isControlPlane = true,
                    controlPlaneSendTimeoutMs = 25L,
                )
            assertEquals(DeliveryOutcome.ControlPlaneFailure("stream_full_timeout"), outcome)
            ch.close()
        }
}
