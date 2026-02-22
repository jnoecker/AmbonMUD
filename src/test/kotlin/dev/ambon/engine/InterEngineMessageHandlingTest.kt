package dev.ambon.engine

import dev.ambon.bus.LocalInboundBus
import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.WorldFactory
import dev.ambon.engine.events.InboundEvent
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.scheduler.Scheduler
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.sharding.BroadcastType
import dev.ambon.sharding.InterEngineBus
import dev.ambon.sharding.InterEngineMessage
import dev.ambon.sharding.LocalInterEngineBus
import dev.ambon.sharding.PlayerSummary
import dev.ambon.test.MutableClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * InterEngineBus that captures outgoing messages (sendTo/broadcast) in a list
 * while still delivering broadcast messages to the engine's incoming channel.
 * sendTo messages are NOT looped back, so the engine won't consume its own responses.
 */
private class CapturingInterEngineBus : InterEngineBus {
    private val incomingChannel = Channel<InterEngineMessage>(1_000)
    val sent = mutableListOf<Pair<String?, InterEngineMessage>>()

    override suspend fun sendTo(
        targetEngineId: String,
        message: InterEngineMessage,
    ) {
        sent.add(targetEngineId to message)
        // NOT looped back — only captured
    }

    override suspend fun broadcast(message: InterEngineMessage) {
        sent.add(null to message)
        incomingChannel.send(message) // broadcast loops back for self-filtering
    }

    override fun incoming(): ReceiveChannel<InterEngineMessage> = incomingChannel

    override suspend fun start() {}

    override fun close() {
        incomingChannel.close()
    }

    /** Inject a message into the incoming channel for the engine to process. */
    suspend fun inject(message: InterEngineMessage) {
        incomingChannel.send(message)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class InterEngineMessageHandlingTest {
    private fun buildEngine(
        engineId: String = "engine-1",
        bus: InterEngineBus = LocalInterEngineBus(),
        onShutdown: suspend () -> Unit = {},
    ): EngineTestHarness {
        val clock = MutableClock(0L)
        val world = WorldFactory.demoWorld()
        val items = ItemRegistry()
        val repo = InMemoryPlayerRepository()
        val players = PlayerRegistry(world.startRoom, repo, items, clock = clock)
        val mobs = MobRegistry()
        val inbound = LocalInboundBus()
        val outbound = LocalOutboundBus()
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
                tickMillis = 100L,
                scheduler = scheduler,
                interEngineBus = bus,
                engineId = engineId,
                onShutdown = onShutdown,
            )

        return EngineTestHarness(engine, inbound, outbound, bus, players, clock)
    }

    data class EngineTestHarness(
        val engine: GameEngine,
        val inbound: LocalInboundBus,
        val outbound: LocalOutboundBus,
        val bus: InterEngineBus,
        val players: PlayerRegistry,
        val clock: MutableClock,
    )

    private fun drain(ch: LocalOutboundBus): List<OutboundEvent> {
        val out = mutableListOf<OutboundEvent>()
        while (true) {
            val ev = ch.tryReceive().getOrNull() ?: break
            out += ev
        }
        return out
    }

    private suspend fun loginViaEngine(
        harness: EngineTestHarness,
        sid: SessionId,
        name: String,
    ) {
        harness.inbound.send(InboundEvent.Connected(sid))
        harness.inbound.send(InboundEvent.LineReceived(sid, name))
        harness.inbound.send(InboundEvent.LineReceived(sid, "yes"))
        harness.inbound.send(InboundEvent.LineReceived(sid, "password"))
    }

    @Test
    fun `GlobalBroadcast GOSSIP delivers to local players when from another engine`() =
        runTest {
            val h = buildEngine("engine-1")
            val sid = SessionId(1)

            val job = launch { h.engine.run() }

            loginViaEngine(h, sid, "Alice")
            advanceUntilIdle()
            drain(h.outbound)

            // Inject a gossip broadcast from another engine
            (h.bus as LocalInterEngineBus).broadcast(
                InterEngineMessage.GlobalBroadcast(
                    broadcastType = BroadcastType.GOSSIP,
                    senderName = "Bob",
                    text = "hello from engine-2",
                    sourceEngineId = "engine-2",
                ),
            )
            advanceUntilIdle()
            h.clock.advance(200)
            advanceUntilIdle()

            val outs = drain(h.outbound)
            assertTrue(
                outs.any { it is OutboundEvent.SendText && "[GOSSIP] Bob: hello from engine-2" in (it as OutboundEvent.SendText).text },
                "Local player should receive remote gossip. got=$outs",
            )

            job.cancel()
        }

    @Test
    fun `GlobalBroadcast from same engine is ignored`() =
        runTest {
            val h = buildEngine("engine-1")
            val sid = SessionId(1)

            val job = launch { h.engine.run() }

            loginViaEngine(h, sid, "Alice")
            advanceUntilIdle()
            drain(h.outbound)

            (h.bus as LocalInterEngineBus).broadcast(
                InterEngineMessage.GlobalBroadcast(
                    broadcastType = BroadcastType.GOSSIP,
                    senderName = "Alice",
                    text = "my own gossip",
                    sourceEngineId = "engine-1",
                ),
            )
            advanceUntilIdle()
            h.clock.advance(200)
            advanceUntilIdle()

            val outs = drain(h.outbound)
            assertTrue(
                outs.none { it is OutboundEvent.SendText && "my own gossip" in (it as OutboundEvent.SendText).text },
                "Self-broadcast should be ignored. got=$outs",
            )

            job.cancel()
        }

    @Test
    fun `TellMessage delivers to local player`() =
        runTest {
            val h = buildEngine("engine-1")
            val sid = SessionId(1)

            val job = launch { h.engine.run() }

            loginViaEngine(h, sid, "Alice")
            advanceUntilIdle()
            drain(h.outbound)

            (h.bus as LocalInterEngineBus).broadcast(
                InterEngineMessage.TellMessage(
                    fromName = "Bob",
                    toName = "Alice",
                    text = "hi alice",
                ),
            )
            advanceUntilIdle()
            h.clock.advance(200)
            advanceUntilIdle()

            val outs = drain(h.outbound)
            assertTrue(
                outs.any { it is OutboundEvent.SendText && "Bob tells you: hi alice" in (it as OutboundEvent.SendText).text },
                "Local player should receive remote tell. got=$outs",
            )

            job.cancel()
        }

    @Test
    fun `WhoRequest from other engine triggers WhoResponse`() =
        runTest {
            val capturingBus = CapturingInterEngineBus()
            val h = buildEngine("engine-1", bus = capturingBus)
            val sid = SessionId(1)

            val job = launch { h.engine.run() }

            loginViaEngine(h, sid, "Alice")
            advanceUntilIdle()
            drain(h.outbound)
            capturingBus.sent.clear()

            // Inject a WhoRequest from another engine
            capturingBus.inject(
                InterEngineMessage.WhoRequest(
                    requestId = "req-123",
                    replyToEngineId = "engine-2",
                ),
            )
            advanceUntilIdle()
            h.clock.advance(200)
            advanceUntilIdle()

            // The capturing bus should have recorded the WhoResponse via sendTo
            val whoResponses =
                capturingBus.sent
                    .filter { (_, msg) -> msg is InterEngineMessage.WhoResponse }
            assertEquals(1, whoResponses.size, "Expected exactly one WhoResponse. sent=${capturingBus.sent}")
            val (targetEngine, msg) = whoResponses[0]
            assertEquals("engine-2", targetEngine)
            val resp = msg as InterEngineMessage.WhoResponse
            assertEquals("req-123", resp.requestId)
            assertEquals(1, resp.players.size)
            assertEquals("Alice", resp.players[0].name)

            job.cancel()
        }

    @Test
    fun `WhoRequest from self is ignored`() =
        runTest {
            val capturingBus = CapturingInterEngineBus()
            val h = buildEngine("engine-1", bus = capturingBus)
            val sid = SessionId(1)

            val job = launch { h.engine.run() }

            loginViaEngine(h, sid, "Alice")
            advanceUntilIdle()
            drain(h.outbound)
            capturingBus.sent.clear()

            // Inject a WhoRequest from self (should be ignored)
            capturingBus.inject(
                InterEngineMessage.WhoRequest(
                    requestId = "req-self",
                    replyToEngineId = "engine-1",
                ),
            )
            advanceUntilIdle()
            h.clock.advance(200)
            advanceUntilIdle()

            val whoResponses = capturingBus.sent.filter { (_, msg) -> msg is InterEngineMessage.WhoResponse }
            assertTrue(whoResponses.isEmpty(), "Self-WhoRequest should not generate a response. sent=${capturingBus.sent}")

            job.cancel()
        }

    @Test
    fun `WhoResponse with unknown requestId is silently dropped`() =
        runTest {
            val h = buildEngine("engine-1")
            val sid = SessionId(1)

            val job = launch { h.engine.run() }

            loginViaEngine(h, sid, "Alice")
            advanceUntilIdle()
            drain(h.outbound)

            // Inject a WhoResponse with an unknown requestId
            (h.bus as LocalInterEngineBus).broadcast(
                InterEngineMessage.WhoResponse(
                    requestId = "unknown-request",
                    players = listOf(PlayerSummary("Bob", "swamp:edge", 3)),
                ),
            )
            advanceUntilIdle()
            h.clock.advance(200)
            advanceUntilIdle()

            val outs = drain(h.outbound)
            assertTrue(
                outs.none { it is OutboundEvent.SendInfo && "Bob" in (it as OutboundEvent.SendInfo).text },
                "Unknown requestId WhoResponse should be dropped",
            )

            job.cancel()
        }

    @Test
    fun `KickRequest closes local player session`() =
        runTest {
            val h = buildEngine("engine-1")
            val sid = SessionId(1)

            val job = launch { h.engine.run() }

            loginViaEngine(h, sid, "Alice")
            advanceUntilIdle()
            drain(h.outbound)

            (h.bus as LocalInterEngineBus).broadcast(
                InterEngineMessage.KickRequest(targetPlayerName = "Alice"),
            )
            advanceUntilIdle()
            h.clock.advance(200)
            advanceUntilIdle()

            val outs = drain(h.outbound)
            assertTrue(
                outs.any { it is OutboundEvent.Close && it.sessionId == sid },
                "KickRequest should close the local session. got=$outs",
            )

            job.cancel()
        }

    @Test
    fun `ShutdownRequest triggers local shutdown`() =
        runTest {
            var shutdownCalled = false
            val h = buildEngine("engine-1", onShutdown = { shutdownCalled = true })
            val sid = SessionId(1)

            val job = launch { h.engine.run() }

            loginViaEngine(h, sid, "Alice")
            advanceUntilIdle()
            drain(h.outbound)

            (h.bus as LocalInterEngineBus).broadcast(
                InterEngineMessage.ShutdownRequest(initiatorName = "Admin"),
            )
            advanceUntilIdle()
            h.clock.advance(200)
            advanceUntilIdle()

            val outs = drain(h.outbound)
            assertTrue(
                outs.any { it is OutboundEvent.SendText && "shutdown" in (it as OutboundEvent.SendText).text.lowercase() },
                "Local player should see shutdown message. got=$outs",
            )
            assertTrue(shutdownCalled, "onShutdown callback should be called")

            job.cancel()
        }
}
