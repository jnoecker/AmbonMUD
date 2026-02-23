package dev.ambon.engine

import dev.ambon.bus.LocalInboundBus
import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.load.WorldLoader
import dev.ambon.engine.events.InboundEvent
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.scheduler.Scheduler
import dev.ambon.persistence.InMemoryPlayerRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class GameEngineAnsiBehaviorTest {
    @Test
    fun `ansi on then clear emits ClearScreen (not dashed line)`() =
        runTest {
            val inbound = LocalInboundBus()
            val outbound = LocalOutboundBus()

            val world = WorldLoader.loadFromResource("world/test_world.yaml")
            val repo = InMemoryPlayerRepository()
            val players =
                PlayerRegistry(
                    world.startRoom,
                    repo,
                    ItemRegistry(),
                )

            val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
            val mobs = MobRegistry()
            val items = ItemRegistry()
            val scheduler = Scheduler(clock)
            val tickMillis = 10L
            val engine =
                GameEngine(
                    inbound = inbound,
                    outbound = outbound,
                    players = players,
                    world = world,
                    clock = clock,
                    tickMillis = tickMillis,
                    scheduler = scheduler,
                    mobs = mobs,
                    items = items,
                )

            val job: Job = launch { engine.run() }

            val sid = SessionId(1)

            // Start engine and let it reach its first delay().
            runCurrent()

            // Connect
            inbound.send(InboundEvent.Connected(sid))
            advanceTimeBy(tickMillis)
            runCurrent()

            // Log in
            inbound.send(InboundEvent.LineReceived(sid, "Alice"))
            inbound.send(InboundEvent.LineReceived(sid, "yes"))
            inbound.send(InboundEvent.LineReceived(sid, "password"))
            advanceTimeBy(tickMillis)
            runCurrent()

            // Enable ANSI
            inbound.send(InboundEvent.LineReceived(sid, "ansi on"))
            advanceTimeBy(tickMillis)
            runCurrent()

            // Clear should now be ClearScreen (because ansiEnabled must be true)
            inbound.send(InboundEvent.LineReceived(sid, "clear"))
            advanceTimeBy(tickMillis)
            runCurrent()

            val outs = drainOutbound(outbound)

            // Sanity: we should have asked transport to enable ANSI at some point
            assertTrue(
                outs.any { it is OutboundEvent.SetAnsi && it.sessionId == sid && it.enabled },
                "Expected SetAnsi(true) in outbound events; got=$outs",
            )

            // The bug we want to prevent: clear falling back to dashed line
            assertTrue(
                outs.any { it is OutboundEvent.ClearScreen && it.sessionId == sid },
                "Expected ClearScreen after 'ansi on' then 'clear'; got=$outs",
            )

            assertFalse(
                outs.any { it is OutboundEvent.SendText && it.sessionId == sid && it.text.contains("---") },
                "Did not expect dashed fallback after 'ansi on'; got=$outs",
            )

            job.cancel()
            inbound.close()
            outbound.close()
        }

    @Test
    fun `ansi off then clear still emits ClearScreen (fallback handled by transport)`() =
        runTest {
            val inbound = LocalInboundBus()
            val outbound = LocalOutboundBus()

            val world = WorldLoader.loadFromResource("world/test_world.yaml")
            val repo = InMemoryPlayerRepository()
            val players = PlayerRegistry(world.startRoom, repo, ItemRegistry())

            val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
            val mobs = MobRegistry()
            val items = ItemRegistry()
            val scheduler = Scheduler(clock)
            val tickMillis = 10L
            val engine =
                GameEngine(
                    inbound = inbound,
                    outbound = outbound,
                    players = players,
                    world = world,
                    clock = clock,
                    tickMillis = tickMillis,
                    scheduler = scheduler,
                    mobs = mobs,
                    items = items,
                )

            val job: Job = launch { engine.run() }

            val sid = SessionId(2)

            runCurrent()

            inbound.send(InboundEvent.Connected(sid))
            advanceTimeBy(tickMillis)
            runCurrent()

            inbound.send(InboundEvent.LineReceived(sid, "Alice"))
            inbound.send(InboundEvent.LineReceived(sid, "yes"))
            inbound.send(InboundEvent.LineReceived(sid, "password"))
            advanceTimeBy(tickMillis)
            runCurrent()

            inbound.send(InboundEvent.LineReceived(sid, "ansi on"))
            advanceTimeBy(tickMillis)
            runCurrent()

            inbound.send(InboundEvent.LineReceived(sid, "ansi off"))
            advanceTimeBy(tickMillis)
            runCurrent()

            inbound.send(InboundEvent.LineReceived(sid, "clear"))
            advanceTimeBy(tickMillis)
            runCurrent()

            val outs = drainOutbound(outbound)

            assertTrue(
                outs.any { it is OutboundEvent.SetAnsi && it.sessionId == sid && !it.enabled },
                "Expected SetAnsi(false) in outbound events; got=$outs",
            )

            assertTrue(
                outs.any { it is OutboundEvent.ClearScreen && it.sessionId == sid },
                "Expected ClearScreen after 'clear' regardless of ANSI state; got=$outs",
            )

            assertFalse(
                outs.any { it is OutboundEvent.SendText && it.sessionId == sid && it.text == "----------------" },
                "Engine should not send dashed fallback; that belongs in transport. got=$outs",
            )

            job.cancel()
            inbound.close()
            outbound.close()
        }

    private fun drainOutbound(outbound: LocalOutboundBus): List<OutboundEvent> {
        val out = mutableListOf<OutboundEvent>()
        while (true) {
            val ev = outbound.tryReceive().getOrNull() ?: break
            out += ev
        }
        return out
    }
}
