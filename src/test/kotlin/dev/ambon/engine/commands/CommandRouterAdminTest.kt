package dev.ambon.engine.commands

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.bus.OutboundBus
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.LoginResult
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommandRouterAdminTest {
    // ── helpers ────────────────────────────────────────────────────────────────

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

    private suspend fun loginStaff(
        players: PlayerRegistry,
        sessionId: SessionId,
        name: String,
    ) {
        login(players, sessionId, name)
        players.get(sessionId)!!.isStaff = true
    }

    private fun makeRouter(
        players: PlayerRegistry,
        mobs: MobRegistry,
        items: ItemRegistry,
        outbound: OutboundBus,
        onShutdown: suspend () -> Unit = {},
        onMobSmited: (MobId) -> Unit = {},
    ): CommandRouter {
        val world = dev.ambon.test.TestWorlds.testWorld
        val combat = CombatSystem(players, mobs, items, outbound)
        return buildTestRouter(
            world = world,
            players = players,
            mobs = mobs,
            items = items,
            combat = combat,
            outbound = outbound,
            onShutdown = onShutdown,
            onMobSmited = onMobSmited,
        )
    }

    // ── staff guard ────────────────────────────────────────────────────────────

    @Test
    fun `non-staff player receives error on goto`() =
        runTest {
            val world = dev.ambon.test.TestWorlds.testWorld
            val items = ItemRegistry()
            val players = dev.ambon.test.buildTestPlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val outbound = LocalOutboundBus()
            val router = makeRouter(players, mobs, items, outbound)

            val sid = SessionId(1)
            login(players, sid, "Alice")
            drain(outbound)

            router.handle(sid, Command.Goto("test_zone:outpost"))
            val outs = drain(outbound)

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
            val world = dev.ambon.test.TestWorlds.testWorld
            val items = ItemRegistry()
            val players = dev.ambon.test.buildTestPlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val outbound = LocalOutboundBus()
            val router = makeRouter(players, mobs, items, outbound)

            val sid = SessionId(1)
            loginStaff(players, sid, "Alice")
            drain(outbound)

            val target = "test_zone:outpost"
            router.handle(sid, Command.Goto(target))
            drain(outbound)

            assertEquals(target, players.get(sid)!!.roomId.value)
        }

    @Test
    fun `goto with local room resolves in current zone`() =
        runTest {
            val world = dev.ambon.test.TestWorlds.testWorld
            val items = ItemRegistry()
            val players = dev.ambon.test.buildTestPlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val outbound = LocalOutboundBus()
            val router = makeRouter(players, mobs, items, outbound)

            val sid = SessionId(1)
            loginStaff(players, sid, "Alice")
            drain(outbound)

            // First teleport to test_zone zone
            router.handle(sid, Command.Goto("test_zone:outpost"))
            drain(outbound)

            // Now goto a local room name only — should resolve in test_zone
            router.handle(sid, Command.Goto("hub"))
            drain(outbound)

            assertEquals("test_zone:hub", players.get(sid)!!.roomId.value)
        }

    @Test
    fun `goto zone-colon resolves to a room in that zone`() =
        runTest {
            val world = dev.ambon.test.TestWorlds.testWorld
            val items = ItemRegistry()
            val players = dev.ambon.test.buildTestPlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val outbound = LocalOutboundBus()
            val router = makeRouter(players, mobs, items, outbound)

            val sid = SessionId(1)
            loginStaff(players, sid, "Alice")
            drain(outbound)

            router.handle(sid, Command.Goto("test_zone:"))
            drain(outbound)

            val roomId = players.get(sid)!!.roomId
            assertEquals("test_zone", roomId.zone, "Should have landed in test_zone zone. got=$roomId")
        }

    @Test
    fun `goto unknown room emits error and prompt`() =
        runTest {
            val world = dev.ambon.test.TestWorlds.testWorld
            val items = ItemRegistry()
            val players = dev.ambon.test.buildTestPlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val outbound = LocalOutboundBus()
            val router = makeRouter(players, mobs, items, outbound)

            val sid = SessionId(1)
            loginStaff(players, sid, "Alice")
            drain(outbound)

            router.handle(sid, Command.Goto("nonexistent:fakery"))
            val outs = drain(outbound)

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
            val world = dev.ambon.test.TestWorlds.testWorld
            val items = ItemRegistry()
            val players = dev.ambon.test.buildTestPlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val outbound = LocalOutboundBus()
            val router = makeRouter(players, mobs, items, outbound)

            val staffSid = SessionId(1)
            val bobSid = SessionId(2)
            loginStaff(players, staffSid, "Admin")
            login(players, bobSid, "Bob")
            drain(outbound)

            router.handle(staffSid, Command.Transfer("Bob", "test_zone:outpost"))
            val outs = drain(outbound)

            assertEquals("test_zone:outpost", players.get(bobSid)!!.roomId.value, "Bob should be in new room")
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
            val world = dev.ambon.test.TestWorlds.testWorld
            val items = ItemRegistry()
            val players = dev.ambon.test.buildTestPlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val outbound = LocalOutboundBus()
            val router = makeRouter(players, mobs, items, outbound)

            val staffSid = SessionId(1)
            loginStaff(players, staffSid, "Admin")
            drain(outbound)

            router.handle(staffSid, Command.Transfer("Ghost", "test_zone:outpost"))
            val outs = drain(outbound)

            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.sessionId == staffSid },
                "Expected SendError. got=$outs",
            )
        }

    // ── spawn ──────────────────────────────────────────────────────────────────

    @Test
    fun `spawn creates mob in staff room`() =
        runTest {
            val world = dev.ambon.test.TestWorlds.testWorld
            val items = ItemRegistry()
            val players = dev.ambon.test.buildTestPlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val outbound = LocalOutboundBus()
            val router = makeRouter(players, mobs, items, outbound)

            val staffSid = SessionId(1)
            loginStaff(players, staffSid, "Admin")
            val staffRoom = players.get(staffSid)!!.roomId
            drain(outbound)

            router.handle(staffSid, Command.Spawn("grunt"))
            val outs = drain(outbound)

            val spawned = mobs.mobsInRoom(staffRoom)
            assertTrue(spawned.isNotEmpty(), "Expected a mob to be spawned in staff room")
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.sessionId == staffSid },
                "Expected spawn confirmation. got=$outs",
            )
        }

    @Test
    fun `spawn with fully qualified name works`() =
        runTest {
            val world = dev.ambon.test.TestWorlds.testWorld
            val items = ItemRegistry()
            val players = dev.ambon.test.buildTestPlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val outbound = LocalOutboundBus()
            val router = makeRouter(players, mobs, items, outbound)

            val staffSid = SessionId(1)
            loginStaff(players, staffSid, "Admin")
            val staffRoom = players.get(staffSid)!!.roomId
            drain(outbound)

            router.handle(staffSid, Command.Spawn("test_zone:grunt"))
            drain(outbound)

            assertTrue(mobs.mobsInRoom(staffRoom).isNotEmpty(), "Expected a mob to be spawned in staff room")
        }

    @Test
    fun `spawn unknown template emits error`() =
        runTest {
            val world = dev.ambon.test.TestWorlds.testWorld
            val items = ItemRegistry()
            val players = dev.ambon.test.buildTestPlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val outbound = LocalOutboundBus()
            val router = makeRouter(players, mobs, items, outbound)

            val staffSid = SessionId(1)
            loginStaff(players, staffSid, "Admin")
            drain(outbound)

            router.handle(staffSid, Command.Spawn("nonexistent_mob"))
            val outs = drain(outbound)

            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.sessionId == staffSid },
                "Expected SendError for unknown template. got=$outs",
            )
        }

    // ── shutdown ───────────────────────────────────────────────────────────────

    @Test
    fun `shutdown broadcasts to all players and calls onShutdown callback`() =
        runTest {
            val world = dev.ambon.test.TestWorlds.testWorld
            val items = ItemRegistry()
            val players = dev.ambon.test.buildTestPlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val outbound = LocalOutboundBus()
            var shutdownCalled = false
            val router = makeRouter(players, mobs, items, outbound, onShutdown = { shutdownCalled = true })

            val staffSid = SessionId(1)
            val bobSid = SessionId(2)
            loginStaff(players, staffSid, "Admin")
            login(players, bobSid, "Bob")
            drain(outbound)

            router.handle(staffSid, Command.Shutdown)
            val outs = drain(outbound)

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
            val world = dev.ambon.test.TestWorlds.testWorld
            val items = ItemRegistry()
            val players = dev.ambon.test.buildTestPlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val outbound = LocalOutboundBus()
            var shutdownCalled = false
            val router = makeRouter(players, mobs, items, outbound, onShutdown = { shutdownCalled = true })

            val sid = SessionId(1)
            login(players, sid, "Alice")
            drain(outbound)

            router.handle(sid, Command.Shutdown)
            assertFalse(shutdownCalled, "onShutdown should not be called for non-staff")
        }

    // ── smite ──────────────────────────────────────────────────────────────────

    @Test
    fun `smite player sets hp to 1 and moves to start room`() =
        runTest {
            val world = dev.ambon.test.TestWorlds.testWorld
            val items = ItemRegistry()
            val players = dev.ambon.test.buildTestPlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val outbound = LocalOutboundBus()
            val router = makeRouter(players, mobs, items, outbound)

            val staffSid = SessionId(1)
            val bobSid = SessionId(2)
            loginStaff(players, staffSid, "Admin")
            login(players, bobSid, "Bob")
            drain(outbound)

            // Move Bob to a different room first
            players.moveTo(bobSid, world.rooms.keys.first { it != world.startRoom })

            router.handle(staffSid, Command.Smite("Bob"))
            drain(outbound)

            val bob = players.get(bobSid)!!
            assertEquals(1, bob.hp, "Bob's HP should be 1 after smite")
            assertEquals(world.startRoom, bob.roomId, "Bob should be at start room after smite")
        }

    @Test
    fun `smite player sends divine message to target`() =
        runTest {
            val world = dev.ambon.test.TestWorlds.testWorld
            val items = ItemRegistry()
            val players = dev.ambon.test.buildTestPlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val outbound = LocalOutboundBus()
            val router = makeRouter(players, mobs, items, outbound)

            val staffSid = SessionId(1)
            val bobSid = SessionId(2)
            loginStaff(players, staffSid, "Admin")
            login(players, bobSid, "Bob")
            drain(outbound)

            router.handle(staffSid, Command.Smite("Bob"))
            val outs = drain(outbound)

            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.sessionId == bobSid && it.text.contains("divine") },
                "Bob should receive divine smite message. got=$outs",
            )
        }

    @Test
    fun `smite mob removes it from registry`() =
        runTest {
            val world = dev.ambon.test.TestWorlds.testWorld
            val items = ItemRegistry()
            val players = dev.ambon.test.buildTestPlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val outbound = LocalOutboundBus()
            var smitedMobId: MobId? = null
            val router = makeRouter(players, mobs, items, outbound, onMobSmited = { smitedMobId = it })

            val staffSid = SessionId(1)
            loginStaff(players, staffSid, "Admin")
            val staffRoom = players.get(staffSid)!!.roomId
            drain(outbound)

            // Place a mob in the same room as staff
            val mobId = MobId("test_zone:test_grunt")
            mobs.upsert(MobState(id = mobId, name = "a wary caravan scout", roomId = staffRoom))

            router.handle(staffSid, Command.Smite("scout"))
            drain(outbound)

            assertNull(mobs.get(mobId), "Mob should be removed from registry after smite")
            assertEquals(mobId, smitedMobId, "onMobSmited callback should have been called with mob ID")
        }

    @Test
    fun `smite mob not found emits error`() =
        runTest {
            val world = dev.ambon.test.TestWorlds.testWorld
            val items = ItemRegistry()
            val players = dev.ambon.test.buildTestPlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val outbound = LocalOutboundBus()
            val router = makeRouter(players, mobs, items, outbound)

            val staffSid = SessionId(1)
            loginStaff(players, staffSid, "Admin")
            drain(outbound)

            router.handle(staffSid, Command.Smite("ghost_mob"))
            val outs = drain(outbound)

            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.sessionId == staffSid },
                "Expected SendError when target not found. got=$outs",
            )
        }

    // ── kick ───────────────────────────────────────────────────────────────────

    @Test
    fun `kick sends Close event to target session`() =
        runTest {
            val world = dev.ambon.test.TestWorlds.testWorld
            val items = ItemRegistry()
            val players = dev.ambon.test.buildTestPlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val outbound = LocalOutboundBus()
            val router = makeRouter(players, mobs, items, outbound)

            val staffSid = SessionId(1)
            val bobSid = SessionId(2)
            loginStaff(players, staffSid, "Admin")
            login(players, bobSid, "Bob")
            drain(outbound)

            router.handle(staffSid, Command.Kick("Bob"))
            val outs = drain(outbound)

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
            val world = dev.ambon.test.TestWorlds.testWorld
            val items = ItemRegistry()
            val players = dev.ambon.test.buildTestPlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val outbound = LocalOutboundBus()
            val router = makeRouter(players, mobs, items, outbound)

            val staffSid = SessionId(1)
            loginStaff(players, staffSid, "Admin")
            drain(outbound)

            router.handle(staffSid, Command.Kick("Admin"))
            val outs = drain(outbound)

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
            val world = dev.ambon.test.TestWorlds.testWorld
            val items = ItemRegistry()
            val players = dev.ambon.test.buildTestPlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val outbound = LocalOutboundBus()
            val router = makeRouter(players, mobs, items, outbound)

            val staffSid = SessionId(1)
            loginStaff(players, staffSid, "Admin")
            drain(outbound)

            router.handle(staffSid, Command.Kick("Ghost"))
            val outs = drain(outbound)

            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.sessionId == staffSid },
                "Expected SendError for unknown player. got=$outs",
            )
        }

    // ── setlevel ───────────────────────────────────────────────────────────────

    @Test
    fun `setlevel updates target player level and xpTotal`() =
        runTest {
            val world = dev.ambon.test.TestWorlds.testWorld
            val items = ItemRegistry()
            val players = dev.ambon.test.buildTestPlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val outbound = LocalOutboundBus()
            val router = makeRouter(players, mobs, items, outbound)

            val staffSid = SessionId(1)
            val bobSid = SessionId(2)
            loginStaff(players, staffSid, "Admin")
            login(players, bobSid, "Bob")
            drain(outbound)

            router.handle(staffSid, Command.SetLevel("Bob", 10))
            val outs = drain(outbound)

            val bob = players.get(bobSid)!!
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
            val world = dev.ambon.test.TestWorlds.testWorld
            val items = ItemRegistry()
            val players = dev.ambon.test.buildTestPlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val outbound = LocalOutboundBus()
            val router = makeRouter(players, mobs, items, outbound)

            val staffSid = SessionId(1)
            val bobSid = SessionId(2)
            loginStaff(players, staffSid, "Admin")
            login(players, bobSid, "Bob")
            drain(outbound)

            router.handle(staffSid, Command.SetLevel("Bob", 99))
            val outs = drain(outbound)

            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.sessionId == staffSid },
                "Expected error for out-of-range level. got=$outs",
            )
            assertEquals(1, players.get(bobSid)!!.level, "Bob's level should be unchanged")
        }

    @Test
    fun `setlevel for offline player emits error`() =
        runTest {
            val world = dev.ambon.test.TestWorlds.testWorld
            val items = ItemRegistry()
            val players = dev.ambon.test.buildTestPlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val outbound = LocalOutboundBus()
            val router = makeRouter(players, mobs, items, outbound)

            val staffSid = SessionId(1)
            loginStaff(players, staffSid, "Admin")
            drain(outbound)

            router.handle(staffSid, Command.SetLevel("Ghost", 10))
            val outs = drain(outbound)

            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.sessionId == staffSid },
                "Expected error for offline player. got=$outs",
            )
        }

    @Test
    fun `setlevel requires staff`() =
        runTest {
            val world = dev.ambon.test.TestWorlds.testWorld
            val items = ItemRegistry()
            val players = dev.ambon.test.buildTestPlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val outbound = LocalOutboundBus()
            val router = makeRouter(players, mobs, items, outbound)

            val bobSid = SessionId(1)
            login(players, bobSid, "Bob")
            drain(outbound)

            router.handle(bobSid, Command.SetLevel("Bob", 10))
            val outs = drain(outbound)

            assertEquals(1, players.get(bobSid)!!.level, "Non-staff should not be able to setlevel")
            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.sessionId == bobSid },
                "Non-staff should receive error. got=$outs",
            )
        }
}
