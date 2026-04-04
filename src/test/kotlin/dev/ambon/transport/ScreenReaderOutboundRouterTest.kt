package dev.ambon.transport

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.OutboundEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.fail as jfail

private fun Channel<OutboundFrame>.tryReceiveText(): String? =
    (tryReceive().getOrNull() as? OutboundFrame.Text)?.content

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenReaderOutboundRouterTest {
    @Test
    fun `box-drawing characters stripped when screen reader enabled`() =
        runTest {
            val engineOutbound = LocalOutboundBus()
            val router = OutboundRouter(engineOutbound, this)
            val job = router.start()

            val sid = SessionId(1)
            val q = Channel<OutboundFrame>(10)
            router.register(sid, q) { jfail("should not close") }

            // Enable screen reader
            engineOutbound.send(OutboundEvent.SetScreenReader(sid, true))

            // Send text with box-drawing characters
            engineOutbound.send(OutboundEvent.SendText(sid, "\u2554\u2550\u2550\u2550\u2557 Title \u255A\u2550\u2550\u2550\u255D"))
            runCurrent()

            val text = q.tryReceiveText()!!
            // Should contain only plain ASCII replacements
            assertFalse(text.contains('\u2554'), "Should not contain box-drawing: $text")
            assertTrue(text.contains("+---+"), "Should contain plain replacement: $text")

            job.cancel()
            q.close()
            engineOutbound.close()
        }

    @Test
    fun `normal output when screen reader disabled`() =
        runTest {
            val engineOutbound = LocalOutboundBus()
            val router = OutboundRouter(engineOutbound, this)
            val job = router.start()

            val sid = SessionId(2)
            val q = Channel<OutboundFrame>(10)
            router.register(sid, q) { jfail("should not close") }

            // Screen reader NOT enabled (default)
            engineOutbound.send(OutboundEvent.SendText(sid, "\u2554\u2550\u2550\u2550\u2557"))
            runCurrent()

            val text = q.tryReceiveText()!!
            // Should pass through unchanged
            assertTrue(text.contains('\u2554'), "Box-drawing should be present: $text")

            job.cancel()
            q.close()
            engineOutbound.close()
        }

    @Test
    fun `SetScreenReader toggles filter on and off`() =
        runTest {
            val engineOutbound = LocalOutboundBus()
            val router = OutboundRouter(engineOutbound, this)
            val job = router.start()

            val sid = SessionId(3)
            val q = Channel<OutboundFrame>(10)
            router.register(sid, q) { jfail("should not close") }

            // Enable then disable
            engineOutbound.send(OutboundEvent.SetScreenReader(sid, true))
            engineOutbound.send(OutboundEvent.SendText(sid, "\u2550\u2550\u2550\u2550"))
            runCurrent()
            val filtered = q.tryReceiveText()!!
            assertTrue(filtered.contains("---"), "Should be filtered: $filtered")

            engineOutbound.send(OutboundEvent.SetScreenReader(sid, false))
            engineOutbound.send(OutboundEvent.SendText(sid, "\u2550\u2550\u2550\u2550"))
            runCurrent()
            val unfiltered = q.tryReceiveText()!!
            assertTrue(unfiltered.contains('\u2550'), "Should not be filtered: $unfiltered")

            job.cancel()
            q.close()
            engineOutbound.close()
        }
}
