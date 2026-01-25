package dev.ambon.engine

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.InboundEvent
import dev.ambon.engine.events.OutboundEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GameEngineIntegrationTest {
    @Test
    fun `connect then say hello then quit`() =
        runTest {
            val inbound = Channel<InboundEvent>(capacity = Channel.UNLIMITED)
            val outbound = Channel<OutboundEvent>(capacity = Channel.UNLIMITED)

            // Make ticks fast for tests
            val engine = GameEngine(inbound = inbound, outbound = outbound, tickMillis = 1L)
            val engineJob = launch { engine.run() }

            val sid = SessionId(1L)

            inbound.send(InboundEvent.Connected(sid))
            inbound.send(InboundEvent.LineReceived(sid, "Hello"))
            inbound.send(InboundEvent.LineReceived(sid, "quit"))

            // Let the engine start + process any immediate work
            runCurrent()
            // Step time forward a few ticks so the engine loop definitely runs
            advanceTimeBy(5)
            runCurrent()

            // Collect events deterministically
            val got = mutableListOf<OutboundEvent>()

            withTimeout(500) {
                while (got.none { it is OutboundEvent.Close && it.sessionId == sid }) {
                    got += outbound.receive()
                }
            }

            assertTrue(got.any { it is OutboundEvent.SendText && it.sessionId == sid }, "Expected SendText; got=$got")
            assertTrue(
                got.any { it is OutboundEvent.SendPrompt && it.sessionId == sid },
                "Expected SendPrompt; got=$got",
            )
            assertTrue(got.any { it is OutboundEvent.Close && it.sessionId == sid }, "Expected Close; got=$got")

            engineJob.cancel()
            inbound.close()
            outbound.close()
        }
}
