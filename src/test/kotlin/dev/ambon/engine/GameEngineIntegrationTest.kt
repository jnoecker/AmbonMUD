package dev.ambon.engine

import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.WorldFactory
import dev.ambon.engine.events.InboundEvent
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.scheduler.Scheduler
import dev.ambon.persistence.InMemoryPlayerRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
            val inbound = Channel<InboundEvent>(capacity = Channel.UNLIMITED)
            val outbound = Channel<OutboundEvent>(capacity = Channel.UNLIMITED)

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

    @Test
    fun `connect and disconnect broadcast room presence`() =
        runTest {
            val inbound = Channel<InboundEvent>(capacity = Channel.UNLIMITED)
            val outbound = Channel<OutboundEvent>(capacity = Channel.UNLIMITED)

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

            runCurrent()
            advanceTimeBy(5)
            runCurrent()

            val got = mutableListOf<OutboundEvent>()
            withTimeout(500) {
                while (got.none { it is OutboundEvent.SendText && it.sessionId == sid1 && it.text == "Player2 enters." }) {
                    got += outbound.receive()
                }
            }

            inbound.send(InboundEvent.Disconnected(sid2, "test"))
            runCurrent()
            advanceTimeBy(5)
            runCurrent()

            withTimeout(500) {
                while (got.none { it is OutboundEvent.SendText && it.sessionId == sid1 && it.text == "Player2 leaves." }) {
                    got += outbound.receive()
                }
            }

            engineJob.cancel()
            inbound.close()
            outbound.close()
        }
}
