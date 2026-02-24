package dev.ambon.engine.commands

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.domain.world.load.WorldLoader
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.LoginResult
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.sharding.EngineAddress
import dev.ambon.sharding.ZoneInstance
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhaseCommandTest {
    private val world = WorldLoader.loadFromResource("world/test_world.yaml")
    private val items = ItemRegistry()
    private val repo = InMemoryPlayerRepository()
    private val players = PlayerRegistry(world.startRoom, repo, items)
    private val mobs = MobRegistry()
    private val outbound = LocalOutboundBus()

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
    fun `phase when not enabled shows error`() =
        runTest {
            val router =
                CommandRouter(
                    world = world,
                    players = players,
                    mobs = mobs,
                    items = items,
                    combat = CombatSystem(players, mobs, items, outbound),
                    outbound = outbound,
                    onPhase = null,
                )

            val sid = SessionId(1)
            login(players, sid, "Alice")
            drain(outbound)

            router.handle(sid, Command.Phase(null))

            val outs = drain(outbound)
            assertTrue(
                outs.any { it is OutboundEvent.SendError && "not enabled" in it.text.lowercase() },
                "Expected 'not enabled' error. got=$outs",
            )
        }

    @Test
    fun `phase with no target lists instances`() =
        runTest {
            val instances =
                listOf(
                    ZoneInstance("e1", EngineAddress("e1", "h1", 9090), "test_zone", playerCount = 10),
                    ZoneInstance("e2", EngineAddress("e2", "h2", 9091), "test_zone", playerCount = 25),
                )
            val router =
                CommandRouter(
                    world = world,
                    players = players,
                    mobs = mobs,
                    items = items,
                    combat = CombatSystem(players, mobs, items, outbound),
                    outbound = outbound,
                    engineId = "e1",
                    onPhase = { _, _ ->
                        PhaseResult.InstanceList(currentEngineId = "e1", instances = instances)
                    },
                )

            val sid = SessionId(1)
            login(players, sid, "Alice")
            drain(outbound)

            router.handle(sid, Command.Phase(null))

            val outs = drain(outbound)
            assertTrue(
                outs.any { it is OutboundEvent.SendText && "you are here" in it.text.lowercase() },
                "Expected instance list with 'you are here' marker. got=$outs",
            )
            assertTrue(
                outs.any { it is OutboundEvent.SendText && "e2" in it.text },
                "Expected e2 in instance list. got=$outs",
            )
        }

    @Test
    fun `phase returns initiated when switching instance`() =
        runTest {
            val router =
                CommandRouter(
                    world = world,
                    players = players,
                    mobs = mobs,
                    items = items,
                    combat = CombatSystem(players, mobs, items, outbound),
                    outbound = outbound,
                    onPhase = { _, _ -> PhaseResult.Initiated },
                )

            val sid = SessionId(1)
            login(players, sid, "Alice")
            drain(outbound)

            router.handle(sid, Command.Phase("e2"))

            val outs = drain(outbound)
            // Initiated means handoff message already sent by HandoffManager — only prompt expected
            assertTrue(
                outs.any { it is OutboundEvent.SendPrompt },
                "Expected prompt after initiated. got=$outs",
            )
        }

    @Test
    fun `phase shows noop reason`() =
        runTest {
            val router =
                CommandRouter(
                    world = world,
                    players = players,
                    mobs = mobs,
                    items = items,
                    combat = CombatSystem(players, mobs, items, outbound),
                    outbound = outbound,
                    onPhase = { _, _ -> PhaseResult.NoOp("You are already on that instance.") },
                )

            val sid = SessionId(1)
            login(players, sid, "Alice")
            drain(outbound)

            router.handle(sid, Command.Phase("e1"))

            val outs = drain(outbound)
            assertTrue(
                outs.any { it is OutboundEvent.SendText && "already on that instance" in it.text.lowercase() },
                "Expected noop message. got=$outs",
            )
        }

    @Test
    fun `phase blocked in combat`() =
        runTest {
            val combat = CombatSystem(players, mobs, items, outbound)
            val router =
                CommandRouter(
                    world = world,
                    players = players,
                    mobs = mobs,
                    items = items,
                    combat = combat,
                    outbound = outbound,
                    onPhase = { _, _ -> PhaseResult.Initiated },
                )

            val sid = SessionId(1)
            login(players, sid, "Alice")
            drain(outbound)

            // Put Alice in combat by adding a mob and attacking it
            mobs.upsert(
                MobState(
                    id = MobId("rat:1"),
                    name = "a rat",
                    roomId = world.startRoom,
                    hp = 10,
                    maxHp = 10,
                ),
            )
            val combatErr = combat.startCombat(sid, "rat")
            assertNull(combatErr, "Expected combat start to succeed")
            drain(outbound)

            router.handle(sid, Command.Phase("e2"))

            val outs = drain(outbound)
            assertTrue(
                outs.any { it is OutboundEvent.SendText && "combat" in it.text.lowercase() },
                "Expected combat-blocked message. got=$outs",
            )
        }
}
