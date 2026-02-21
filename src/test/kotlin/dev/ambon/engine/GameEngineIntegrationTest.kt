package dev.ambon.engine

import dev.ambon.bus.LocalInboundBus
import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.WorldFactory
import dev.ambon.domain.world.load.WorldLoader
import dev.ambon.engine.events.InboundEvent
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.scheduler.Scheduler
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.MutableClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class GameEngineIntegrationTest {
    @Test
    fun `connect then say hello then quit`() =
        runTest {
            val inbound = LocalInboundBus()
            val outbound = LocalOutboundBus()

            val world = WorldFactory.demoWorld()
            val repo = InMemoryPlayerRepository()
            val players = PlayerRegistry(world.startRoom, repo, ItemRegistry())

            val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
            val mobs = MobRegistry()
            val items = ItemRegistry()
            val scheduler = Scheduler(clock)
            val tickMillis = 1L // Make ticks fast for tests
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
            val engineJob = launch { engine.run() }

            val sid = SessionId(1L)

            inbound.send(InboundEvent.Connected(sid))
            inbound.send(InboundEvent.LineReceived(sid, "Alice"))
            inbound.send(InboundEvent.LineReceived(sid, "yes"))
            inbound.send(InboundEvent.LineReceived(sid, "password"))
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
                    got += outbound.asReceiveChannel().receive()
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

    @Test
    fun `connect and disconnect broadcast room presence`() =
        runTest {
            val inbound = LocalInboundBus()
            val outbound = LocalOutboundBus()

            val world = WorldFactory.demoWorld()
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
            val engineJob = launch { engine.run() }

            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)

            inbound.send(InboundEvent.Connected(sid1))
            inbound.send(InboundEvent.Connected(sid2))
            inbound.send(InboundEvent.LineReceived(sid1, "Alice"))
            inbound.send(InboundEvent.LineReceived(sid2, "Bob"))
            inbound.send(InboundEvent.LineReceived(sid1, "yes"))
            inbound.send(InboundEvent.LineReceived(sid2, "yes"))
            inbound.send(InboundEvent.LineReceived(sid1, "password"))
            inbound.send(InboundEvent.LineReceived(sid2, "password"))

            runCurrent()
            advanceTimeBy(5)
            runCurrent()

            val got = mutableListOf<OutboundEvent>()
            withTimeout(500) {
                while (got.none { it is OutboundEvent.SendText && it.sessionId == sid1 && it.text == "Bob enters." }) {
                    got += outbound.asReceiveChannel().receive()
                }
            }

            inbound.send(InboundEvent.Disconnected(sid2, "test"))
            runCurrent()
            advanceTimeBy(5)
            runCurrent()

            withTimeout(500) {
                while (got.none { it is OutboundEvent.SendText && it.sessionId == sid1 && it.text == "Bob leaves." }) {
                    got += outbound.asReceiveChannel().receive()
                }
            }

            engineJob.cancel()
            inbound.close()
            outbound.close()
        }

    @Test
    fun `zone reset notifies players and restores spawn state`() =
        runTest {
            val inbound = LocalInboundBus()
            val outbound = LocalOutboundBus()

            val world = WorldLoader.loadFromResource("world/ok_small.yaml")
            val items = ItemRegistry()
            val repo = InMemoryPlayerRepository()
            val players = PlayerRegistry(world.startRoom, repo, items)
            val mobs = MobRegistry()
            val clock = MutableClock(0L)
            val scheduler = Scheduler(clock)
            val tickMillis = 1_000L

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
            val engineJob = launch { engine.run() }

            suspend fun step(ms: Long) {
                clock.advance(ms)
                advanceTimeBy(ms)
                runCurrent()
            }

            val sid = SessionId(1L)

            runCurrent()

            inbound.send(InboundEvent.Connected(sid))
            inbound.send(InboundEvent.LineReceived(sid, "Alice"))
            inbound.send(InboundEvent.LineReceived(sid, "yes"))
            inbound.send(InboundEvent.LineReceived(sid, "password"))
            step(tickMillis)

            inbound.send(InboundEvent.LineReceived(sid, "get coin"))
            step(tickMillis)

            assertTrue(items.inventory(sid).any { it.item.keyword == "coin" })
            assertTrue(items.itemsInRoom(world.startRoom).none { it.item.keyword == "coin" })

            drainOutbound(outbound)

            step(60_000L)
            val resetEvents = drainOutbound(outbound)

            assertTrue(
                resetEvents.any {
                    it is OutboundEvent.SendText &&
                        it.sessionId == sid &&
                        it.text == "The air shimmers as the area resets around you."
                },
                "Expected zone reset notification after zone reset; got=$resetEvents",
            )
            assertEquals(
                1,
                items.itemsInRoom(world.startRoom).count { it.item.keyword == "coin" },
            )

            engineJob.cancel()
            inbound.close()
            outbound.close()
        }

    @Test
    fun `ansi preference persists and is restored on login`() =
        runTest {
            val inbound = LocalInboundBus()
            val outbound = LocalOutboundBus()

            val world = WorldFactory.demoWorld()
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
            val engineJob = launch { engine.run() }

            fun step() {
                advanceTimeBy(tickMillis)
                runCurrent()
            }

            val sid1 = SessionId(1L)

            runCurrent()
            inbound.send(InboundEvent.Connected(sid1))
            inbound.send(InboundEvent.LineReceived(sid1, "Alice"))
            inbound.send(InboundEvent.LineReceived(sid1, "yes"))
            inbound.send(InboundEvent.LineReceived(sid1, "password"))
            step()

            inbound.send(InboundEvent.LineReceived(sid1, "ansi on"))
            step()

            inbound.send(InboundEvent.Disconnected(sid1, "test"))
            step()

            drainOutbound(outbound)

            val sid2 = SessionId(2L)
            inbound.send(InboundEvent.Connected(sid2))
            inbound.send(InboundEvent.LineReceived(sid2, "Alice"))
            inbound.send(InboundEvent.LineReceived(sid2, "password"))
            step()

            val outs = drainOutbound(outbound)
            assertTrue(
                outs.any { it is OutboundEvent.SetAnsi && it.sessionId == sid2 && it.enabled },
                "Expected ANSI preference to be restored on login; got=$outs",
            )

            engineJob.cancel()
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
