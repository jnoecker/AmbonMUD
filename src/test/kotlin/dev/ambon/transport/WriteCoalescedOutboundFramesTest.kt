package dev.ambon.transport

import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Deterministic tests for [writeCoalescedOutboundFrames]. Pre-filling the queue before the
 * writer runs avoids the router/writer scheduling race that makes the end-to-end integration
 * test flaky for small burst sizes.
 */
class WriteCoalescedOutboundFramesTest {
    @Test
    fun `consecutive text frames already queued are coalesced into one frame`() =
        runBlocking {
            val queue = Channel<OutboundFrame>(capacity = 64)
            val sent = mutableListOf<Frame>()
            val consumed = mutableListOf<OutboundFrame>()

            queue.trySend(OutboundFrame.Text("alpha"))
            queue.trySend(OutboundFrame.Text("beta"))
            queue.trySend(OutboundFrame.Text("gamma"))
            queue.close()

            writeCoalescedOutboundFrames(
                outboundQueue = queue,
                send = { sent += it },
                onFrameConsumed = { consumed += it },
            )

            assertEquals(1, sent.size, "expected one coalesced text frame, got ${sent.map { (it as Frame.Text).readText() }}")
            assertEquals("alphabetagamma", (sent[0] as Frame.Text).readText())
            assertEquals(3, consumed.size)
        }

    @Test
    fun `gmcp frame flushes pending text and stays isolated`() =
        runBlocking {
            val queue = Channel<OutboundFrame>(capacity = 64)
            val sent = mutableListOf<String>()

            queue.trySend(OutboundFrame.Text("before"))
            queue.trySend(OutboundFrame.Gmcp("Room.Info", """{"id":"x"}"""))
            queue.trySend(OutboundFrame.Text("after"))
            queue.close()

            writeCoalescedOutboundFrames(
                outboundQueue = queue,
                send = { sent += (it as Frame.Text).readText() },
                onFrameConsumed = { },
            )

            assertEquals(3, sent.size, "expected text/gmcp/text as three distinct frames, got $sent")
            assertEquals("before", sent[0])
            assertEquals("""{"gmcp":"Room.Info","data":{"id":"x"}}""", sent[1])
            assertEquals("after", sent[2])
        }

    @Test
    fun `drain bound caps coalesced frame size`() =
        runBlocking {
            val queue = Channel<OutboundFrame>(capacity = 100)
            val sent = mutableListOf<String>()
            val maxBatch = 4

            // Queue up more frames than a single wake-up batch can drain. With maxBatch=4,
            // the writer consumes 1 + 4 = 5 frames per iteration, so 11 frames produces
            // three batches of sizes 5, 5, 1.
            repeat(11) { queue.trySend(OutboundFrame.Text("x$it")) }
            queue.close()

            writeCoalescedOutboundFrames(
                outboundQueue = queue,
                send = { sent += (it as Frame.Text).readText() },
                onFrameConsumed = { },
                maxBatch = maxBatch,
            )

            assertEquals(3, sent.size, "expected 3 batched frames, got $sent")
            assertEquals("x0x1x2x3x4", sent[0])
            assertEquals("x5x6x7x8x9", sent[1])
            assertEquals("x10", sent[2])
        }

    @Test
    fun `empty text string does not produce a frame`() =
        runBlocking {
            val queue = Channel<OutboundFrame>(capacity = 4)
            val sent = mutableListOf<Frame>()

            queue.trySend(OutboundFrame.Text(""))
            queue.close()

            writeCoalescedOutboundFrames(
                outboundQueue = queue,
                send = { sent += it },
                onFrameConsumed = { },
            )

            assertTrue(sent.isEmpty(), "empty text should not emit a frame, got $sent")
        }

    @Test
    fun `writer exits when queue closes even if send raises`() =
        runBlocking {
            val queue = Channel<OutboundFrame>(capacity = 4)
            queue.trySend(OutboundFrame.Text("boom"))
            queue.close()

            // Send throws; writer should swallow and return cleanly.
            writeCoalescedOutboundFrames(
                outboundQueue = queue,
                send = { error("simulated transport failure") },
                onFrameConsumed = { },
            )
            // Reaching here without a thrown exception proves the try/catch kept us alive.
        }

    @Test
    fun `late arrivals to queue are picked up on next wake`() =
        runTest {
            val queue = Channel<OutboundFrame>(capacity = 64)
            val sent = mutableListOf<String>()

            val writer: Job =
                launch {
                    writeCoalescedOutboundFrames(
                        outboundQueue = queue,
                        send = { sent += (it as Frame.Text).readText() },
                        onFrameConsumed = { },
                    )
                }

            // First batch
            queue.send(OutboundFrame.Text("first"))
            yield()
            // Second batch (after the writer has parked waiting on receive again)
            queue.send(OutboundFrame.Text("second"))
            queue.close()
            writer.join()

            assertEquals(listOf("first", "second"), sent)
        }
}
