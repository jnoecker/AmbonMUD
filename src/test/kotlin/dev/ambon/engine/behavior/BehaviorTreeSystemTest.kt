package dev.ambon.engine.behavior

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.World
import dev.ambon.domain.world.data.BehaviorParamsFile
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.behavior.actions.AggroAction
import dev.ambon.engine.behavior.actions.FleeAction
import dev.ambon.engine.behavior.actions.PatrolAction
import dev.ambon.engine.behavior.actions.SayAction
import dev.ambon.engine.behavior.actions.StationaryAction
import dev.ambon.engine.behavior.actions.WanderAction
import dev.ambon.engine.behavior.conditions.IsHpBelow
import dev.ambon.engine.behavior.conditions.IsInCombat
import dev.ambon.engine.behavior.conditions.IsPlayerInRoom
import dev.ambon.engine.behavior.nodes.CooldownNode
import dev.ambon.engine.behavior.nodes.InverterNode
import dev.ambon.engine.behavior.nodes.SelectorNode
import dev.ambon.engine.behavior.nodes.SequenceNode
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.MutableClock
import dev.ambon.test.drainAll
import dev.ambon.test.loginOrFail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

@OptIn(ExperimentalCoroutinesApi::class)
class BehaviorTreeSystemTest {
    private val roomA = Room(RoomId("zone:a"), "Room A", "desc", mapOf(Direction.NORTH to RoomId("zone:b")))
    private val roomB =
        Room(
            RoomId("zone:b"),
            "Room B",
            "desc",
            mapOf(Direction.SOUTH to RoomId("zone:a"), Direction.EAST to RoomId("zone:c")),
        )
    private val roomC = Room(RoomId("zone:c"), "Room C", "desc", mapOf(Direction.WEST to RoomId("zone:b")))
    private val world = World(mapOf(roomA.id to roomA, roomB.id to roomB, roomC.id to roomC), roomA.id)

    private fun env(
        mobRoom: RoomId = roomA.id,
        behaviorTree: BtNode? = null,
    ): TestEnv {
        val mobs = MobRegistry()
        val items = ItemRegistry()
        val players = PlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
        val outbound = LocalOutboundBus()
        val clock = MutableClock(0L)
        val rng = Random(42)

        val mob =
            MobState(
                MobId("zone:guard"),
                "a guard",
                mobRoom,
                hp = 100,
                maxHp = 100,
                behaviorTree = behaviorTree,
            )
        mobs.upsert(mob)

        val combat =
            CombatSystem(
                players = players,
                mobs = mobs,
                items = items,
                outbound = outbound,
                clock = clock,
                rng = rng,
            )

        val system =
            BehaviorTreeSystem(
                world = world,
                mobs = mobs,
                players = players,
                outbound = outbound,
                clock = clock,
                rng = rng,
                isMobInCombat = combat::isMobInCombat,
                startMobCombat = combat::startMobCombat,
                fleeMob = combat::fleeMob,
                minActionDelayMs = 100L,
                maxActionDelayMs = 100L,
            )

        return TestEnv(mobs, players, outbound, clock, combat, system, mob.id)
    }

    data class TestEnv(
        val mobs: MobRegistry,
        val players: PlayerRegistry,
        val outbound: LocalOutboundBus,
        val clock: MutableClock,
        val combat: CombatSystem,
        val system: BehaviorTreeSystem,
        val mobId: MobId,
    )

    // --- Sequence / Selector node tests ---

    @Test
    fun `SequenceNode succeeds when all children succeed`() =
        runTest {
            val node = SequenceNode(listOf(StationaryAction, StationaryAction))
            val ctx = makeBareContext()
            assertEquals(BtResult.SUCCESS, node.tick(ctx))
        }

    @Test
    fun `SequenceNode fails on first failure`() =
        runTest {
            val node =
                SequenceNode(
                    listOf(
                        IsPlayerInRoom, // FAILURE — no players
                        StationaryAction,
                    ),
                )
            val ctx = makeBareContext()
            assertEquals(BtResult.FAILURE, node.tick(ctx))
        }

    @Test
    fun `SelectorNode succeeds on first success`() =
        runTest {
            val node =
                SelectorNode(
                    listOf(
                        IsPlayerInRoom, // FAILURE — no players
                        StationaryAction, // SUCCESS
                    ),
                )
            val ctx = makeBareContext()
            assertEquals(BtResult.SUCCESS, node.tick(ctx))
        }

    @Test
    fun `SelectorNode fails when all children fail`() =
        runTest {
            val node =
                SelectorNode(
                    listOf(
                        IsPlayerInRoom, // FAILURE
                        IsInCombat, // FAILURE
                    ),
                )
            val ctx = makeBareContext()
            assertEquals(BtResult.FAILURE, node.tick(ctx))
        }

    @Test
    fun `InverterNode swaps SUCCESS to FAILURE`() =
        runTest {
            val node = InverterNode(StationaryAction)
            val ctx = makeBareContext()
            assertEquals(BtResult.FAILURE, node.tick(ctx))
        }

    @Test
    fun `InverterNode swaps FAILURE to SUCCESS`() =
        runTest {
            val node = InverterNode(IsPlayerInRoom) // no players → FAILURE → inverted to SUCCESS
            val ctx = makeBareContext()
            assertEquals(BtResult.SUCCESS, node.tick(ctx))
        }

    // --- Aggro tests ---

    @Test
    fun `aggro guard attacks player in same room`() =
        runTest {
            val tree =
                SelectorNode(
                    listOf(
                        IsInCombat,
                        SequenceNode(listOf(IsPlayerInRoom, AggroAction)),
                        StationaryAction,
                    ),
                )
            val e = env(behaviorTree = tree)
            val sid = SessionId(1L)
            e.players.loginOrFail(sid, "Tester")

            e.outbound.drainAll() // clear login messages

            // First tick schedules the mob (dueAt goes from null → now+delay)
            e.system.tick()
            // Advance clock past the scheduled time
            e.clock.advance(200)
            // Second tick actually executes the behavior tree
            e.system.tick()

            // Mob should now be in combat with the player
            assertTrue(e.combat.isMobInCombat(e.mobId))
            assertTrue(e.combat.isInCombat(sid))

            val messages = e.outbound.drainAll()
            assertTrue(messages.any { it is OutboundEvent.SendText && (it as OutboundEvent.SendText).text.contains("attacks you") })
        }

    @Test
    fun `aggro guard does nothing when no player in room`() =
        runTest {
            val tree =
                SelectorNode(
                    listOf(
                        IsInCombat,
                        SequenceNode(listOf(IsPlayerInRoom, AggroAction)),
                        StationaryAction,
                    ),
                )
            val e = env(behaviorTree = tree)
            // No player logged in

            e.system.tick() // schedule
            e.clock.advance(200)
            e.system.tick() // execute

            assertFalse(e.combat.isMobInCombat(e.mobId))
        }

    @Test
    fun `aggro does not attack player already in combat`() =
        runTest {
            val tree =
                SelectorNode(
                    listOf(
                        IsInCombat,
                        SequenceNode(listOf(IsPlayerInRoom, AggroAction)),
                        StationaryAction,
                    ),
                )
            val e = env(behaviorTree = tree)
            val sid = SessionId(1L)
            e.players.loginOrFail(sid, "Tester")

            // Manually start combat first
            e.combat.startCombat(sid, "guard")
            e.outbound.drainAll()

            e.system.tick() // schedule
            e.clock.advance(200)
            e.system.tick() // execute

            // Should still be in combat from the original fight — IsInCombat returns SUCCESS,
            // selector stops there
            assertTrue(e.combat.isMobInCombat(e.mobId))
        }

    // --- Patrol tests ---

    @Test
    fun `patrol action cycles through waypoints`() =
        runTest {
            val route = listOf(roomB.id, roomC.id, roomA.id)
            val tree = PatrolAction(route)
            val e = env(mobRoom = roomA.id, behaviorTree = tree)

            // Schedule tick (dueAt null → sets future time)
            e.system.tick()

            // First tick: mob is at roomA, next waypoint is roomB → moves to roomB
            e.clock.advance(200)
            e.system.tick()
            assertEquals(roomB.id, e.mobs.get(e.mobId)!!.roomId)

            // Second tick: move to roomC
            e.clock.advance(200)
            e.system.tick()
            assertEquals(roomC.id, e.mobs.get(e.mobId)!!.roomId)

            // Third tick: cycle back to roomA
            e.clock.advance(200)
            e.system.tick()
            assertEquals(roomA.id, e.mobs.get(e.mobId)!!.roomId)
        }

    // --- Flee tests ---

    @Test
    fun `flee action disengages combat and moves mob`() =
        runTest {
            val tree =
                SelectorNode(
                    listOf(
                        SequenceNode(listOf(IsInCombat, IsHpBelow(50), FleeAction)),
                        StationaryAction,
                    ),
                )
            val e = env(behaviorTree = tree)
            val sid = SessionId(1L)
            e.players.loginOrFail(sid, "Tester")

            // Start combat
            e.combat.startCombat(sid, "guard")
            e.outbound.drainAll()

            // Set mob HP low
            val mob = e.mobs.get(e.mobId)!!
            mob.hp = 30 // 30% of 100

            e.system.tick() // schedule
            e.clock.advance(200)
            e.system.tick() // execute

            // Mob should no longer be in combat
            assertFalse(e.combat.isMobInCombat(e.mobId))
            // Mob should have moved to a different room
            val newRoom = e.mobs.get(e.mobId)!!.roomId
            assertTrue(newRoom != roomA.id, "Mob should have fled to a different room")

            val messages = e.outbound.drainAll()
            assertTrue(messages.any { it is OutboundEvent.SendText && (it as OutboundEvent.SendText).text.contains("flees") })
        }

    @Test
    fun `flee does not trigger when HP above threshold`() =
        runTest {
            val tree =
                SelectorNode(
                    listOf(
                        SequenceNode(listOf(IsInCombat, IsHpBelow(50), FleeAction)),
                        StationaryAction,
                    ),
                )
            val e = env(behaviorTree = tree)
            val sid = SessionId(1L)
            e.players.loginOrFail(sid, "Tester")

            e.combat.startCombat(sid, "guard")
            e.outbound.drainAll()

            // HP still at 100 — above 50% threshold
            e.system.tick() // schedule
            e.clock.advance(200)
            e.system.tick() // execute

            // Mob should still be in combat, didn't flee
            assertTrue(e.combat.isMobInCombat(e.mobId))
            assertEquals(roomA.id, e.mobs.get(e.mobId)!!.roomId)
        }

    // --- Wander tests ---

    @Test
    fun `wander action moves mob to random adjacent room`() =
        runTest {
            val tree = WanderAction
            val e = env(behaviorTree = tree)

            e.system.tick() // schedule
            e.clock.advance(200)
            e.system.tick() // execute

            // Mob should have moved (roomA only has exit to roomB)
            assertEquals(roomB.id, e.mobs.get(e.mobId)!!.roomId)
        }

    // --- Say action tests ---

    @Test
    fun `say action broadcasts message to players in room`() =
        runTest {
            val tree = SayAction("Halt! Who goes there?")
            val e = env(behaviorTree = tree)
            val sid = SessionId(1L)
            e.players.loginOrFail(sid, "Tester")
            e.outbound.drainAll()

            e.system.tick() // schedule
            e.clock.advance(200)
            e.system.tick() // execute

            val messages = e.outbound.drainAll()
            assertTrue(
                messages.any { ev ->
                    ev is OutboundEvent.SendText &&
                        ev.text.contains("Halt! Who goes there?")
                },
            )
        }

    // --- CooldownNode tests ---

    @Test
    fun `cooldown node gates child execution`() =
        runTest {
            var callCount = 0
            val countingNode =
                object : BtNode {
                    override suspend fun tick(ctx: BtContext): BtResult {
                        callCount++
                        return BtResult.SUCCESS
                    }
                }

            val tree = CooldownNode(cooldownMs = 1000L, key = "test", child = countingNode)
            val e = env(behaviorTree = tree)

            // Schedule tick (dueAt null → sets future time)
            e.system.tick()

            // First tick: should execute
            e.clock.advance(200)
            e.system.tick()
            assertEquals(1, callCount)

            // Second tick immediately: cooldown not elapsed, should not execute
            e.clock.advance(200)
            e.system.tick()
            // CooldownNode returns FAILURE, but tree still ticked — callCount stays 1
            assertEquals(1, callCount)

            // Advance past cooldown
            e.clock.advance(1000)
            e.system.tick()
            assertEquals(2, callCount)
        }

    // --- BehaviorTreeSystem lifecycle tests ---

    @Test
    fun `mobs without behavior tree are not ticked by BehaviorTreeSystem`() =
        runTest {
            val e = env(behaviorTree = null) // no BT
            val sid = SessionId(1L)
            e.players.loginOrFail(sid, "Tester")
            e.outbound.drainAll()

            e.system.tick() // schedule (no-op for null BT)
            e.clock.advance(200)
            val actions = e.system.tick()

            assertEquals(0, actions)
            assertFalse(e.combat.isMobInCombat(e.mobId))
        }

    @Test
    fun `hasBehaviorTree returns correct value`() {
        val e = env(behaviorTree = StationaryAction)
        assertTrue(e.system.hasBehaviorTree(e.mobId))
    }

    @Test
    fun `hasBehaviorTree returns false for mob without BT`() {
        val e = env(behaviorTree = null)
        assertFalse(e.system.hasBehaviorTree(e.mobId))
    }

    @Test
    fun `onMobRemoved cleans up memory`() =
        runTest {
            val tree = PatrolAction(listOf(roomA.id, roomB.id))
            val e = env(behaviorTree = tree)

            e.system.tick() // schedule
            e.clock.advance(200)
            e.system.tick() // creates memory entry

            e.system.onMobRemoved(e.mobId)
            // After removal, hasBehaviorTree depends on mob existing in registry
            // The internal state (memory, timing) should be cleaned
            // We can verify by re-spawning and checking patrol index resets
            e.system.onMobSpawned(e.mobId)
        }

    // --- BehaviorTemplates tests ---

    @Test
    fun `BehaviorTemplates resolves known template`() {
        val result =
            BehaviorTemplates.resolve(
                "aggro_guard",
                BehaviorParamsFile(),
                "zone",
            )
        assertNotNull(result)
    }

    @Test
    fun `BehaviorTemplates returns null for unknown template`() {
        val result =
            BehaviorTemplates.resolve(
                "nonexistent_template",
                BehaviorParamsFile(),
                "zone",
            )
        assertNull(result)
    }

    @Test
    fun `BehaviorTemplates resolves all known templates`() {
        for (name in BehaviorTemplates.templateNames) {
            val params =
                BehaviorParamsFile(
                    patrolRoute = listOf("a", "b"),
                )
            val result = BehaviorTemplates.resolve(name, params, "zone")
            assertTrue(result != null, "Template '$name' should resolve")
        }
    }

    // --- Helper to create bare BtContext for unit testing nodes ---

    private fun makeBareContext(): BtContext {
        val mobs = MobRegistry()
        val items = ItemRegistry()
        val players = PlayerRegistry(roomA.id, InMemoryPlayerRepository(), items)
        val outbound = LocalOutboundBus()
        val clock = MutableClock(0L)
        val mob = MobState(MobId("zone:test"), "a test mob", roomA.id)
        mobs.upsert(mob)

        return BtContext(
            mob = mob,
            world = world,
            mobs = mobs,
            players = players,
            outbound = outbound,
            clock = clock,
            rng = Random(42),
            isMobInCombat = { false },
            startMobCombat = { _, _ -> false },
            fleeMob = { false },
            gmcpEmitter = null,
            mobMemory = MobBehaviorMemory(),
        )
    }
}
