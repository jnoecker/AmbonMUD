package dev.ambon.engine.commands

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.WorldFactory
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.LoginResult
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.sharding.BroadcastType
import dev.ambon.sharding.InterEngineMessage
import dev.ambon.sharding.LocalInterEngineBus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CrossEngineCommandsTest {
    private val world = WorldFactory.demoWorld()
    private val items = ItemRegistry()
    private val repo = InMemoryPlayerRepository()
    private val players = PlayerRegistry(world.startRoom, repo, items)
    private val mobs = MobRegistry()
    private val outbound = LocalOutboundBus()
    private val bus = LocalInterEngineBus()
    private val engineId = "engine-1"

    private val router =
        CommandRouter(
            world = world,
            players = players,
            mobs = mobs,
            items = items,
            combat = CombatSystem(players, mobs, items, outbound),
            outbound = outbound,
            interEngineBus = bus,
            engineId = engineId,
        )

    @Test
    fun `gossip broadcasts GlobalBroadcast via inter-engine bus`() =
        runTest {
            val sid = SessionId(1)
            login(players, sid, "Alice")
            drain(outbound)

            router.handle(sid, Command.Gossip("hello world"))

            // Local delivery still works
            val outs = drain(outbound)
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.sessionId == sid && "You gossip" in (it as OutboundEvent.SendText).text },
            )

            // Bus should have a GlobalBroadcast
            val msg = bus.incoming().tryReceive().getOrNull()
            assertTrue(msg is InterEngineMessage.GlobalBroadcast)
            val broadcast = msg as InterEngineMessage.GlobalBroadcast
            assertEquals(BroadcastType.GOSSIP, broadcast.broadcastType)
            assertEquals("Alice", broadcast.senderName)
            assertEquals("hello world", broadcast.text)
            assertEquals(engineId, broadcast.sourceEngineId)
        }

    @Test
    fun `tell to unknown player sends TellMessage via bus`() =
        runTest {
            val sid = SessionId(1)
            login(players, sid, "Alice")
            drain(outbound)

            router.handle(sid, Command.Tell("Bob", "hey bob"))

            // Sender should see confirmation (not error)
            val outs = drain(outbound)
            assertTrue(
                outs.any { it is OutboundEvent.SendText && "You tell Bob" in (it as OutboundEvent.SendText).text },
            )
            assertTrue(
                outs.none { it is OutboundEvent.SendError },
                "Should not show error when bus is available",
            )

            // Bus should have a TellMessage
            val msg = bus.incoming().tryReceive().getOrNull()
            assertTrue(msg is InterEngineMessage.TellMessage)
            val tell = msg as InterEngineMessage.TellMessage
            assertEquals("Alice", tell.fromName)
            assertEquals("Bob", tell.toName)
            assertEquals("hey bob", tell.text)
        }

    @Test
    fun `tell to unknown player without bus shows error`() =
        runTest {
            // Router without bus
            val noBusRouter =
                CommandRouter(
                    world = world,
                    players = players,
                    mobs = mobs,
                    items = items,
                    combat = CombatSystem(players, mobs, items, outbound),
                    outbound = outbound,
                )

            val sid = SessionId(1)
            login(players, sid, "Alice")
            drain(outbound)

            noBusRouter.handle(sid, Command.Tell("Bob", "hey bob"))
            val outs = drain(outbound)

            assertTrue(
                outs.any { it is OutboundEvent.SendError && "No such player" in (it as OutboundEvent.SendError).text },
            )
        }

    @Test
    fun `kick unknown player sends KickRequest via bus`() =
        runTest {
            val sid = SessionId(1)
            login(players, sid, "Alice")
            players.get(sid)!!.isStaff = true
            drain(outbound)

            router.handle(sid, Command.Kick("Bob"))

            val outs = drain(outbound)
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && "Kick request sent" in (it as OutboundEvent.SendInfo).text },
            )

            val msg = bus.incoming().tryReceive().getOrNull()
            assertTrue(msg is InterEngineMessage.KickRequest)
            assertEquals("Bob", (msg as InterEngineMessage.KickRequest).targetPlayerName)
        }

    @Test
    fun `shutdown broadcasts GlobalBroadcast SHUTDOWN via bus`() =
        runTest {
            val sid = SessionId(1)
            login(players, sid, "Admin")
            players.get(sid)!!.isStaff = true
            drain(outbound)

            var shutdownCalled = false
            val shutdownRouter =
                CommandRouter(
                    world = world,
                    players = players,
                    mobs = mobs,
                    items = items,
                    combat = CombatSystem(players, mobs, items, outbound),
                    outbound = outbound,
                    onShutdown = { shutdownCalled = true },
                    interEngineBus = bus,
                    engineId = engineId,
                )

            shutdownRouter.handle(sid, Command.Shutdown)

            assertTrue(shutdownCalled)

            val msg = bus.incoming().tryReceive().getOrNull()
            assertTrue(msg is InterEngineMessage.GlobalBroadcast)
            val broadcast = msg as InterEngineMessage.GlobalBroadcast
            assertEquals(BroadcastType.SHUTDOWN, broadcast.broadcastType)
            assertEquals(engineId, broadcast.sourceEngineId)
        }

    @Test
    fun `transfer unknown player sends TransferRequest via bus`() =
        runTest {
            val sid = SessionId(1)
            login(players, sid, "Admin")
            players.get(sid)!!.isStaff = true
            drain(outbound)

            router.handle(sid, Command.Transfer("Bob", "forest:clearing"))

            val outs = drain(outbound)
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && "Transfer request sent" in (it as OutboundEvent.SendInfo).text },
            )

            val msg = bus.incoming().tryReceive().getOrNull()
            assertTrue(msg is InterEngineMessage.TransferRequest)
            val transfer = msg as InterEngineMessage.TransferRequest
            assertEquals("Admin", transfer.staffName)
            assertEquals("Bob", transfer.targetPlayerName)
            assertEquals("forest:clearing", transfer.targetRoomId)
        }

    private fun drain(ch: LocalOutboundBus): List<OutboundEvent> {
        val out = mutableListOf<OutboundEvent>()
        while (true) {
            val ev = ch.tryReceive().getOrNull() ?: break
            out += ev
        }
        return out
    }

    private suspend fun login(
        players: PlayerRegistry,
        sessionId: SessionId,
        name: String,
    ) {
        val res = players.login(sessionId, name, "password")
        require(res == LoginResult.Ok) { "Login failed: $res" }
    }

    @Test
    fun `tell to unknown player uses sendTo when player index returns target engine`() =
        runTest {
            val sid = SessionId(1)
            login(players, sid, "Alice")
            drain(outbound)

            val capturingBus = CapturingInterEngineBus()
            val index = MapPlayerLocationIndex(mapOf("bob" to "engine-2"))
            val indexRouter =
                CommandRouter(
                    world = world,
                    players = players,
                    mobs = mobs,
                    items = items,
                    combat = CombatSystem(players, mobs, items, outbound),
                    outbound = outbound,
                    interEngineBus = capturingBus,
                    engineId = "engine-1",
                    playerLocationIndex = index,
                )

            indexRouter.handle(sid, Command.Tell("Bob", "hello from alice"))

            val outs = drain(outbound)
            assertTrue(
                outs.any { it is OutboundEvent.SendText && "You tell Bob" in (it as OutboundEvent.SendText).text },
            )
            assertTrue(outs.none { it is OutboundEvent.SendError })

            assertEquals(1, capturingBus.sendToInvocations.size)
            assertEquals("engine-2", capturingBus.sendToInvocations[0].first)
            val msg = capturingBus.sendToInvocations[0].second
            assertTrue(msg is InterEngineMessage.TellMessage)
            assertEquals("Bob", (msg as InterEngineMessage.TellMessage).toName)
            assertEquals(0, capturingBus.broadcastInvocations.size)
        }

    @Test
    fun `tell falls back to broadcast when player index returns null`() =
        runTest {
            val sid = SessionId(1)
            login(players, sid, "Alice")
            drain(outbound)

            val capturingBus = CapturingInterEngineBus()
            val emptyIndex = MapPlayerLocationIndex(emptyMap())
            val indexRouter =
                CommandRouter(
                    world = world,
                    players = players,
                    mobs = mobs,
                    items = items,
                    combat = CombatSystem(players, mobs, items, outbound),
                    outbound = outbound,
                    interEngineBus = capturingBus,
                    engineId = "engine-1",
                    playerLocationIndex = emptyIndex,
                )

            indexRouter.handle(sid, Command.Tell("Bob", "hello"))

            assertEquals(0, capturingBus.sendToInvocations.size)
            assertEquals(1, capturingBus.broadcastInvocations.size)
            assertTrue(capturingBus.broadcastInvocations[0] is InterEngineMessage.TellMessage)
        }

    @Test
    fun `tell falls back to broadcast when player index returns own engine`() =
        runTest {
            val sid = SessionId(1)
            login(players, sid, "Alice")
            drain(outbound)

            val capturingBus = CapturingInterEngineBus()
            // index says "Bob" is on engine-1 (same engine), should still broadcast
            val sameEngineIndex = MapPlayerLocationIndex(mapOf("bob" to "engine-1"))
            val indexRouter =
                CommandRouter(
                    world = world,
                    players = players,
                    mobs = mobs,
                    items = items,
                    combat = CombatSystem(players, mobs, items, outbound),
                    outbound = outbound,
                    interEngineBus = capturingBus,
                    engineId = "engine-1",
                    playerLocationIndex = sameEngineIndex,
                )

            indexRouter.handle(sid, Command.Tell("Bob", "hello"))

            assertEquals(0, capturingBus.sendToInvocations.size)
            assertEquals(1, capturingBus.broadcastInvocations.size)
        }
}

/** Test double: captures sendTo and broadcast invocations separately. */
private class CapturingInterEngineBus : dev.ambon.sharding.InterEngineBus {
    val sendToInvocations = mutableListOf<Pair<String, InterEngineMessage>>()
    val broadcastInvocations = mutableListOf<InterEngineMessage>()
    private val ch = kotlinx.coroutines.channels.Channel<InterEngineMessage>(1_000)

    override suspend fun sendTo(
        targetEngineId: String,
        message: InterEngineMessage,
    ) {
        sendToInvocations += targetEngineId to message
        ch.send(message)
    }

    override suspend fun broadcast(message: InterEngineMessage) {
        broadcastInvocations += message
        ch.send(message)
    }

    override fun incoming(): kotlinx.coroutines.channels.ReceiveChannel<InterEngineMessage> = ch

    override suspend fun start() {}

    override fun close() {
        ch.close()
    }
}

/** Test double: simple map-backed PlayerLocationIndex. */
private class MapPlayerLocationIndex(
    private val entries: Map<String, String>,
) : dev.ambon.sharding.PlayerLocationIndex {
    override fun register(playerName: String) {}

    override fun unregister(playerName: String) {}

    override fun lookupEngineId(playerName: String): String? = entries[playerName.lowercase()]

    override fun refreshAll(playerNames: Collection<String>) {}
}
