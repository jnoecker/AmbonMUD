package dev.ambon.transport

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.OutboundEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

private fun Channel<OutboundFrame>.tryReceiveText(): String? = (tryReceive().getOrNull() as? OutboundFrame.Text)?.content

@OptIn(ExperimentalCoroutinesApi::class)
class OutboundRouterPromptCoalescingTest {
    @Test
    fun `coalesces consecutive prompts`() =
        runTest {
            val engineOutbound = LocalOutboundBus()
            val router = OutboundRouter(engineOutbound, this)
            val job = router.start()

            val sid = SessionId(1)
            val q = Channel<OutboundFrame>(capacity = 10)
            router.register(sid, q) { fail("should not close") }

            engineOutbound.send(OutboundEvent.SendPrompt(sid))
            engineOutbound.send(OutboundEvent.SendPrompt(sid))
            engineOutbound.send(OutboundEvent.SendPrompt(sid))
            runCurrent()

            assertEquals("> ", q.tryReceiveText())
            assertNull(q.tryReceiveText(), "Only one prompt should be enqueued")

            job.cancel()
            q.close()
            engineOutbound.close()
        }

    @Test
    fun `text breaks prompt coalescing`() =
        runTest {
            val engineOutbound = LocalOutboundBus()
            val router = OutboundRouter(engineOutbound, this)
            val job = router.start()

            val sid = SessionId(2)
            val q = Channel<OutboundFrame>(capacity = 10)
            router.register(sid, q) { fail("should not close") }

            engineOutbound.send(OutboundEvent.SendPrompt(sid))
            engineOutbound.send(OutboundEvent.SendText(sid, "hello"))
            engineOutbound.send(OutboundEvent.SendPrompt(sid))
            runCurrent()

            assertEquals("> ", q.tryReceiveText())
            assertEquals("hello\r\n", q.tryReceiveText())
            assertEquals("> ", q.tryReceiveText())

            job.cancel()
            q.close()
            engineOutbound.close()
        }
}
