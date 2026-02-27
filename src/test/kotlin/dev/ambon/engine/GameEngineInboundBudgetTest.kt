package dev.ambon.engine

import dev.ambon.bus.LocalInboundBus
import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.InboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.scheduler.Scheduler
import dev.ambon.persistence.InMemoryPlayerRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * A [Clock] whose [millis] advances by [stepMs] on every call, simulating elapsed wall time
 * during event processing without requiring real wall-clock delays.
 */
private class TickingClock(
    private var nowMs: Long = 0L,
    private val stepMs: Long = 1L,
) : Clock() {
    override fun millis(): Long {
        val current = nowMs
        nowMs += stepMs
        return current
    }

    override fun instant(): Instant = Instant.ofEpochMilli(nowMs)

    override fun getZone(): ZoneId = ZoneId.of("UTC")

    override fun withZone(zone: ZoneId): Clock = TickingClock(nowMs, stepMs)
}

@OptIn(ExperimentalCoroutinesApi::class)
class GameEngineInboundBudgetTest {
    /**
     * When the inbound drain budget is exhausted mid-tick, unprocessed events remain
     * in the queue and will be handled in a subsequent tick.
     *
     * The [TickingClock] advances 1 ms on every [Clock.millis] call, so with
     * [inboundBudgetMs] = 3:
     *   - tickStart = 0  → deadline = 3
     *   - budget check 1: clock = 1 (< 3) → process event 1
     *   - budget check 2: clock = 2 (< 3) → process event 2
     *   - budget check 3: clock = 3 (>= 3) → budget exceeded, break
     * Two events are processed; 8 remain for the next tick.
     */
    @Test
    fun `inbound drain stops at time budget leaving remaining events for next tick`() =
        runTest {
            val inbound = LocalInboundBus()
            val outbound = LocalOutboundBus()
            val world = dev.ambon.test.TestWorlds.testWorld
            val repo = InMemoryPlayerRepository()
            val items = ItemRegistry()
            val players = dev.ambon.test.buildTestPlayerRegistry(world.startRoom, repo, items)
            val mobs = MobRegistry()
            val clock = TickingClock(nowMs = 0L, stepMs = 1L)
            val scheduler = Scheduler(clock)

            val engine =
                GameEngine(
                    inbound = inbound,
                    outbound = outbound,
                    players = players,
                    world = world,
                    mobs = mobs,
                    items = items,
                    clock = clock,
                    tickMillis = 100_000L,
                    scheduler = scheduler,
                    inboundBudgetMs = 3L,
                )

            // Queue 10 events for a session that is not connected; the engine handles them
            // by returning early (no session state found), so clock.millis() is not called
            // inside handle() and the advancing pattern stays predictable.
            val dummySid = SessionId(999L)
            repeat(10) { inbound.trySend(InboundEvent.LineReceived(dummySid, "noop")) }
            assertEquals(10, inbound.depth())

            val engineJob = launch { engine.run() }
            runCurrent() // run one tick up to the first delay()

            val remaining = inbound.depth()
            assertTrue(remaining > 0, "Expected events to remain in queue after budget cap, but got depth=$remaining")

            engineJob.cancel()
        }

    /**
     * When the clock does not advance (fixed time, [MutableClock]-style), the time budget
     * never fires and all events up to [maxInboundEventsPerTick] are drained in a single tick.
     */
    @Test
    fun `inbound drain processes all events when clock is fixed and budget never fires`() =
        runTest {
            val inbound = LocalInboundBus()
            val outbound = LocalOutboundBus()
            val world = dev.ambon.test.TestWorlds.testWorld
            val repo = InMemoryPlayerRepository()
            val items = ItemRegistry()
            val players = dev.ambon.test.buildTestPlayerRegistry(world.startRoom, repo, items)
            val mobs = MobRegistry()
            // Fixed clock: millis() always returns 0, so 0 < deadline always → budget never fires.
            val clock = Clock.fixed(Instant.EPOCH, ZoneId.of("UTC"))
            val scheduler = Scheduler(clock)

            val engine =
                GameEngine(
                    inbound = inbound,
                    outbound = outbound,
                    players = players,
                    world = world,
                    mobs = mobs,
                    items = items,
                    clock = clock,
                    tickMillis = 1_000L,
                    scheduler = scheduler,
                    inboundBudgetMs = 30L,
                )

            val dummySid = SessionId(999L)
            repeat(10) { inbound.trySend(InboundEvent.LineReceived(dummySid, "noop")) }

            val engineJob = launch { engine.run() }
            runCurrent()

            assertEquals(0, inbound.depth(), "Expected all events to be drained when clock is fixed")

            engineJob.cancel()
        }
}
