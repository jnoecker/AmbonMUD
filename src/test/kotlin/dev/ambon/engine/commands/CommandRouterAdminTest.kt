package dev.ambon.engine.commands

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.MobRemovalCoordinator
import dev.ambon.engine.MobSystem
import dev.ambon.engine.behavior.BehaviorTreeSystem
import dev.ambon.engine.dialogue.DialogueSystem
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.status.StatusEffectRegistry
import dev.ambon.engine.status.StatusEffectSystem
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.CommandRouterHarness
import dev.ambon.test.MutableClock
import dev.ambon.test.TestWorlds
import dev.ambon.test.buildTestPlayerRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommandRouterAdminTest {
    // ── staff guard ────────────────────────────────────────────────────────────────

    @Test
    fun `non-staff player receives error on goto`() =
        runTest {
            val h = CommandRouterHarness.create()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            h.drain()

            h.router.handle(sid, Command.Goto("test_zone:outpost"))
            val outs = h.drain()

            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.sessionId == sid && it.text.contains("not staff") },
                "Expected 'not staff' error. got=$outs",
            )
            assertTrue(outs.any { it is OutboundEvent.SendPrompt }, "Missing prompt. got=$outs")
        }

    // ── goto ───────────────────────────────────────────────────────────────────

    @Test
    fun `goto moves staff player to exact room`() =
        runTest {
            val h = CommandRouterHarness.create()
            val sid = SessionId(1)
            h.loginStaff(sid, "Alice")
            h.drain()

            val target = "test_zone:outpost"
            h.router.handle(sid, Command.Goto(target))
            h.drain()

            assertEquals(target, h.players.get(sid)!!.roomId.value)
        }

    @Test
    fun `goto with local room resolves in current zone`() =
        runTest {
            val h = CommandRouterHarness.create()
            val sid = SessionId(1)
            h.loginStaff(sid, "Alice")
            h.drain()

            // First teleport to test_zone zone
            h.router.handle(sid, Command.Goto("test_zone:outpost"))
            h.drain()

            // Now goto a local room name only — should resolve in test_zone
            h.router.handle(sid, Command.Goto("hub"))
            h.drain()

            assertEquals("test_zone:hub", h.players.get(sid)!!.roomId.value)
        }

    @Test
    fun `goto zone-colon resolves to a room in that zone`() =
        runTest {
            val h = CommandRouterHarness.create()
            val sid = SessionId(1)
            h.loginStaff(sid, "Alice")
            h.drain()

            h.router.handle(sid, Command.Goto("test_zone:"))
            h.drain()

            val roomId = h.players.get(sid)!!.roomId
            assertEquals("test_zone", roomId.zone, "Should have landed in test_zone zone. got=$roomId")
        }

    @Test
    fun `goto unknown room emits error and prompt`() =
        runTest {
            val h = CommandRouterHarness.create()
            val sid = SessionId(1)
            h.loginStaff(sid, "Alice")
            h.drain()

            h.router.handle(sid, Command.Goto("nonexistent:fakery"))
            val outs = h.drain()

            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.sessionId == sid },
                "Expected SendError. got=$outs",
            )
            assertTrue(outs.any { it is OutboundEvent.SendPrompt }, "Missing prompt. got=$outs")
        }

    // ── transfer ───────────────────────────────────────────────────────────────

    @Test
    fun `transfer moves target player and sends them a look`() =
        runTest {
            val h = CommandRouterHarness.create()
            val staffSid = SessionId(1)
            val bobSid = SessionId(2)
            h.loginStaff(staffSid, "Admin")
            h.loginPlayer(bobSid, "Bob")
            h.drain()

            h.router.handle(staffSid, Command.Transfer("Bob", "test_zone:outpost"))
            val outs = h.drain()

            assertEquals("test_zone:outpost", h.players.get(bobSid)!!.roomId.value, "Bob should be in new room")
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.sessionId == bobSid && it.text.contains("divine hand") },
                "Bob should receive divine transport message. got=$outs",
            )
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.sessionId == staffSid && it.text.contains("Bob") },
                "Staff should see confirmation. got=$outs",
            )
        }

    @Test
    fun `transfer unknown player emits error`() =
        runTest {
            val h = CommandRouterHarness.create()
            val staffSid = SessionId(1)
            h.loginStaff(staffSid, "Admin")
            h.drain()

            h.router.handle(staffSid, Command.Transfer("Ghost", "test_zone:outpost"))
            val outs = h.drain()

            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.sessionId == staffSid },
                "Expected SendError. got=$outs",
            )
        }

    // ── spawn ──────────────────────────────────────────────────────────────────

    @Test
    fun `spawn creates mob in staff room`() =
        runTest {
            val h = CommandRouterHarness.create()
            val staffSid = SessionId(1)
            h.loginStaff(staffSid, "Admin")
            val staffRoom = h.players.get(staffSid)!!.roomId
            h.drain()

            h.router.handle(staffSid, Command.Spawn("grunt"))
            val outs = h.drain()

            val spawned = h.mobs.mobsInRoom(staffRoom)
            assertTrue(spawned.isNotEmpty(), "Expected a mob to be spawned in staff room")
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.sessionId == staffSid },
                "Expected spawn confirmation. got=$outs",
            )
        }

    @Test
    fun `spawn with fully qualified name works`() =
        runTest {
            val h = CommandRouterHarness.create()
            val staffSid = SessionId(1)
            h.loginStaff(staffSid, "Admin")
            val staffRoom = h.players.get(staffSid)!!.roomId
            h.drain()

            h.router.handle(staffSid, Command.Spawn("test_zone:grunt"))
            h.drain()

            assertTrue(h.mobs.mobsInRoom(staffRoom).isNotEmpty(), "Expected a mob to be spawned in staff room")
        }

    @Test
    fun `spawn unknown template emits error`() =
        runTest {
            val h = CommandRouterHarness.create()
            val staffSid = SessionId(1)
            h.loginStaff(staffSid, "Admin")
            h.drain()

            h.router.handle(staffSid, Command.Spawn("nonexistent_mob"))
            val outs = h.drain()

            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.sessionId == staffSid },
                "Expected SendError for unknown template. got=$outs",
            )
        }

    // ── shutdown ───────────────────────────────────────────────────────────────

    @Test
    fun `shutdown broadcasts to all players and calls onShutdown callback`() =
        runTest {
            var shutdownCalled = false
            val h = CommandRouterHarness.create(onShutdown = { shutdownCalled = true })
            val staffSid = SessionId(1)
            val bobSid = SessionId(2)
            h.loginStaff(staffSid, "Admin")
            h.loginPlayer(bobSid, "Bob")
            h.drain()

            h.router.handle(staffSid, Command.Shutdown)
            val outs = h.drain()

            assertTrue(shutdownCalled, "onShutdown callback should have been invoked")
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.sessionId == staffSid && it.text.contains("shutdown") },
                "Staff should receive shutdown broadcast. got=$outs",
            )
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.sessionId == bobSid && it.text.contains("shutdown") },
                "Bob should receive shutdown broadcast. got=$outs",
            )
        }

    @Test
    fun `non-staff cannot trigger shutdown`() =
        runTest {
            var shutdownCalled = false
            val h = CommandRouterHarness.create(onShutdown = { shutdownCalled = true })
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            h.drain()

            h.router.handle(sid, Command.Shutdown)
            assertFalse(shutdownCalled, "onShutdown should not be called for non-staff")
        }

    // ── smite ──────────────────────────────────────────────────────────────────

    @Test
    fun `smite player sets hp to 1 and moves to start room`() =
        runTest {
            val h = CommandRouterHarness.create()
            val staffSid = SessionId(1)
            val bobSid = SessionId(2)
            h.loginStaff(staffSid, "Admin")
            h.loginPlayer(bobSid, "Bob")
            h.drain()

            // Move Bob to a different room first
            h.players.moveTo(bobSid, h.world.rooms.keys.first { it != h.world.startRoom })

            h.router.handle(staffSid, Command.Smite("Bob"))
            h.drain()

            val bob = h.players.get(bobSid)!!
            assertEquals(1, bob.hp, "Bob's HP should be 1 after smite")
            assertEquals(h.world.startRoom, bob.roomId, "Bob should be at start room after smite")
        }

    @Test
    fun `smite player sends divine message to target`() =
        runTest {
            val h = CommandRouterHarness.create()
            val staffSid = SessionId(1)
            val bobSid = SessionId(2)
            h.loginStaff(staffSid, "Admin")
            h.loginPlayer(bobSid, "Bob")
            h.drain()

            h.router.handle(staffSid, Command.Smite("Bob"))
            val outs = h.drain()

            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.sessionId == bobSid && it.text.contains("divine") },
                "Bob should receive divine smite message. got=$outs",
            )
        }

    @Test
    fun `smite mob removes it from registry`() =
        runTest {
            val world = TestWorlds.testWorld
            val repo = InMemoryPlayerRepository()
            val items = ItemRegistry()
            val players = buildTestPlayerRegistry(world.startRoom, repo, items)
            val mobs = MobRegistry()
            val outbound = LocalOutboundBus()
            val combat = CombatSystem(players, mobs, items, outbound)
            val clock = MutableClock(nowMs = 0L)
            val coordinator = MobRemovalCoordinator(
                combatSystem = combat,
                dialogueSystem = DialogueSystem(mobs, players, outbound),
                behaviorTreeSystem = BehaviorTreeSystem(world, mobs, players, outbound, clock),
                mobs = mobs,
                mobSystem = MobSystem(),
                statusEffectSystem = StatusEffectSystem(
                    StatusEffectRegistry(),
                    players,
                    mobs,
                    outbound,
                    clock,
                ),
            )
            val h = CommandRouterHarness.create(
                world = world,
                repo = repo,
                items = items,
                players = players,
                mobs = mobs,
                outbound = outbound,
                mobRemovalCoordinator = coordinator,
            )

            val staffSid = SessionId(1)
            h.loginStaff(staffSid, "Admin")
            val staffRoom = h.players.get(staffSid)!!.roomId
            h.drain()

            // Place a mob in the same room as staff
            val mobId = MobId("test_zone:test_grunt")
            mobs.upsert(MobState(id = mobId, name = "a wary caravan scout", roomId = staffRoom))

            h.router.handle(staffSid, Command.Smite("scout"))
            h.drain()

            assertNull(mobs.get(mobId), "Mob should be removed from registry after smite")
        }

    @Test
    fun `smite mob not found emits error`() =
        runTest {
            val h = CommandRouterHarness.create()
            val staffSid = SessionId(1)
            h.loginStaff(staffSid, "Admin")
            h.drain()

            h.router.handle(staffSid, Command.Smite("ghost_mob"))
            val outs = h.drain()

            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.sessionId == staffSid },
                "Expected SendError when target not found. got=$outs",
            )
        }

    // ── kick ───────────────────────────────────────────────────────────────────

    @Test
    fun `kick sends Close event to target session`() =
        runTest {
            val h = CommandRouterHarness.create()
            val staffSid = SessionId(1)
            val bobSid = SessionId(2)
            h.loginStaff(staffSid, "Admin")
            h.loginPlayer(bobSid, "Bob")
            h.drain()

            h.router.handle(staffSid, Command.Kick("Bob"))
            val outs = h.drain()

            assertTrue(
                outs.any { it is OutboundEvent.Close && it.sessionId == bobSid },
                "Expected Close event for Bob's session. got=$outs",
            )
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.sessionId == staffSid && it.text.contains("Bob") },
                "Staff should see kick confirmation. got=$outs",
            )
        }

    @Test
    fun `kick self emits error`() =
        runTest {
            val h = CommandRouterHarness.create()
            val staffSid = SessionId(1)
            h.loginStaff(staffSid, "Admin")
            h.drain()

            h.router.handle(staffSid, Command.Kick("Admin"))
            val outs = h.drain()

            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.sessionId == staffSid },
                "Expected error when trying to kick self. got=$outs",
            )
            assertFalse(
                outs.any { it is OutboundEvent.Close && it.sessionId == staffSid },
                "Staff should not be kicked. got=$outs",
            )
        }

    @Test
    fun `kick unknown player emits error`() =
        runTest {
            val h = CommandRouterHarness.create()
            val staffSid = SessionId(1)
            h.loginStaff(staffSid, "Admin")
            h.drain()

            h.router.handle(staffSid, Command.Kick("Ghost"))
            val outs = h.drain()

            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.sessionId == staffSid },
                "Expected SendError for unknown player. got=$outs",
            )
        }

    // ── setlevel ───────────────────────────────────────────────────────────────

    @Test
    fun `setlevel updates target player level and xpTotal`() =
        runTest {
            val h = CommandRouterHarness.create()
            val staffSid = SessionId(1)
            val bobSid = SessionId(2)
            h.loginStaff(staffSid, "Admin")
            h.loginPlayer(bobSid, "Bob")
            h.drain()

            h.router.handle(staffSid, Command.SetLevel("Bob", 10))
            val outs = h.drain()

            val bob = h.players.get(bobSid)!!
            assertEquals(10, bob.level, "Bob's level should be 10 after setlevel")
            assertTrue(bob.xpTotal > 0, "Bob's xpTotal should be > 0 after setlevel to 10")
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.sessionId == bobSid },
                "Target should receive notification. got=$outs",
            )
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.sessionId == staffSid && it.text.contains("Bob") },
                "Staff should see confirmation. got=$outs",
            )
        }

    @Test
    fun `setlevel rejects out-of-range level`() =
        runTest {
            val h = CommandRouterHarness.create()
            val staffSid = SessionId(1)
            val bobSid = SessionId(2)
            h.loginStaff(staffSid, "Admin")
            h.loginPlayer(bobSid, "Bob")
            h.drain()

            h.router.handle(staffSid, Command.SetLevel("Bob", 99))
            val outs = h.drain()

            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.sessionId == staffSid },
                "Expected error for out-of-range level. got=$outs",
            )
            assertEquals(1, h.players.get(bobSid)!!.level, "Bob's level should be unchanged")
        }

    @Test
    fun `setlevel for offline player emits error`() =
        runTest {
            val h = CommandRouterHarness.create()
            val staffSid = SessionId(1)
            h.loginStaff(staffSid, "Admin")
            h.drain()

            h.router.handle(staffSid, Command.SetLevel("Ghost", 10))
            val outs = h.drain()

            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.sessionId == staffSid },
                "Expected error for offline player. got=$outs",
            )
        }

    @Test
    fun `setlevel requires staff`() =
        runTest {
            val h = CommandRouterHarness.create()
            val bobSid = SessionId(1)
            h.loginPlayer(bobSid, "Bob")
            h.drain()

            h.router.handle(bobSid, Command.SetLevel("Bob", 10))
            val outs = h.drain()

            assertEquals(1, h.players.get(bobSid)!!.level, "Non-staff should not be able to setlevel")
            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.sessionId == bobSid },
                "Non-staff should receive error. got=$outs",
            )
        }
}
