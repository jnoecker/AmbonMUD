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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mindrot.jbcrypt.BCrypt
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class GameEngineLoginFlowTest {
    @Test
    fun `connect shows login screen before name prompt`() =
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
            val engineJob = launch { engine.run() }

            val sid = SessionId(99L)
            runCurrent()

            inbound.send(InboundEvent.Connected(sid))
            advanceTimeBy(tickMillis)
            runCurrent()

            val outs = drainOutbound(outbound)
            val loginIndex = outs.indexOfFirst { it is OutboundEvent.ShowLoginScreen && it.sessionId == sid }
            val namePromptIndex =
                outs.indexOfFirst {
                    it is OutboundEvent.SendInfo &&
                        it.sessionId == sid &&
                        it.text == "Enter your name:"
                }

            assertTrue(loginIndex >= 0, "Expected ShowLoginScreen for new connection. got=$outs")
            assertTrue(
                namePromptIndex > loginIndex,
                "Expected name prompt after login screen. got=$outs",
            )
            assertTrue(
                outs.any { it is OutboundEvent.SendPrompt && it.sessionId == sid },
                "Expected a prompt after connect. got=$outs",
            )

            engineJob.cancel()
            inbound.close()
            outbound.close()
        }

    @Test
    fun `blank password returns to name prompt`() =
        runTest {
            val inbound = LocalInboundBus()
            val outbound = LocalOutboundBus()

            val world = WorldLoader.loadFromResource("world/test_world.yaml")
            val repo = InMemoryPlayerRepository()
            repo.create("Alice", world.startRoom, 0L, BCrypt.hashpw("secret", BCrypt.gensalt()), ansiEnabled = false)
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

            val sid = SessionId(1L)
            runCurrent()

            inbound.send(InboundEvent.Connected(sid))
            inbound.send(InboundEvent.LineReceived(sid, "Alice"))
            inbound.send(InboundEvent.LineReceived(sid, ""))

            advanceTimeBy(tickMillis)
            runCurrent()

            val outs = drainOutbound(outbound)

            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.sessionId == sid && it.text == "Password:" },
                "Expected password prompt for existing user. got=$outs",
            )
            assertTrue(
                outs.any {
                    it is OutboundEvent.SendError &&
                        it.sessionId == sid &&
                        it.text == "Blank password. Returning to login."
                },
                "Expected blank password error. got=$outs",
            )
            assertTrue(
                outs.count {
                    it is OutboundEvent.SendInfo &&
                        it.sessionId == sid &&
                        it.text == "Enter your name:"
                } >= 2,
                "Expected return to name prompt after blank password. got=$outs",
            )
            assertFalse(
                outs.any { it is OutboundEvent.Close && it.sessionId == sid },
                "Did not expect disconnect after one blank password. got=$outs",
            )

            engineJob.cancel()
            inbound.close()
            outbound.close()
        }

    @Test
    fun `fourth wrong password returns to login`() =
        runTest {
            val inbound = LocalInboundBus()
            val outbound = LocalOutboundBus()

            val world = WorldLoader.loadFromResource("world/test_world.yaml")
            val repo = InMemoryPlayerRepository()
            repo.create("Alice", world.startRoom, 0L, BCrypt.hashpw("secret", BCrypt.gensalt()), ansiEnabled = false)
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

            val sid = SessionId(1L)
            runCurrent()

            inbound.send(InboundEvent.Connected(sid))
            inbound.send(InboundEvent.LineReceived(sid, "Alice"))
            repeat(4) { inbound.send(InboundEvent.LineReceived(sid, "wrong")) }

            advanceTimeBy(tickMillis)
            runCurrent()

            val outs = drainOutbound(outbound)

            assertTrue(
                outs.any {
                    it is OutboundEvent.SendError &&
                        it.sessionId == sid &&
                        it.text == "Incorrect password. 3 attempt(s) before returning to login."
                },
                "Expected first wrong-password warning. got=$outs",
            )
            assertTrue(
                outs.any {
                    it is OutboundEvent.SendError &&
                        it.sessionId == sid &&
                        it.text == "Incorrect password. 2 attempt(s) before returning to login."
                },
                "Expected second wrong-password warning. got=$outs",
            )
            assertTrue(
                outs.any {
                    it is OutboundEvent.SendError &&
                        it.sessionId == sid &&
                        it.text == "Incorrect password. 1 attempt(s) before returning to login."
                },
                "Expected third wrong-password warning. got=$outs",
            )
            assertTrue(
                outs.any {
                    it is OutboundEvent.SendError &&
                        it.sessionId == sid &&
                        it.text == "Incorrect password too many times. Returning to login."
                },
                "Expected 4th wrong-password to return to login. got=$outs",
            )
            assertTrue(
                outs.count {
                    it is OutboundEvent.SendInfo &&
                        it.sessionId == sid &&
                        it.text == "Enter your name:"
                } >= 2,
                "Expected return to name prompt after 4th wrong password. got=$outs",
            )
            assertFalse(
                outs.any { it is OutboundEvent.Close && it.sessionId == sid },
                "Did not expect disconnect after first failed login cycle. got=$outs",
            )

            engineJob.cancel()
            inbound.close()
            outbound.close()
        }

    @Test
    fun `disconnects after three failed login cycles`() =
        runTest {
            val inbound = LocalInboundBus()
            val outbound = LocalOutboundBus()

            val world = WorldLoader.loadFromResource("world/test_world.yaml")
            val repo = InMemoryPlayerRepository()
            repo.create("Alice", world.startRoom, 0L, BCrypt.hashpw("secret", BCrypt.gensalt()), ansiEnabled = false)
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

            val sid = SessionId(1L)
            runCurrent()

            inbound.send(InboundEvent.Connected(sid))
            repeat(3) {
                inbound.send(InboundEvent.LineReceived(sid, "Alice"))
                repeat(4) { inbound.send(InboundEvent.LineReceived(sid, "wrong")) }
            }

            advanceTimeBy(tickMillis)
            runCurrent()

            val got = mutableListOf<OutboundEvent>()
            withTimeout(500) {
                while (got.none { it is OutboundEvent.Close && it.sessionId == sid }) {
                    got += outbound.asReceiveChannel().receive()
                }
            }

            val close = got.filterIsInstance<OutboundEvent.Close>().first { it.sessionId == sid }
            assertEquals("Too many failed login attempts.", close.reason)

            engineJob.cancel()
            inbound.close()
            outbound.close()
        }

    @Test
    fun `new user requires confirmation before password prompt`() =
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
            val engineJob = launch { engine.run() }

            val sid = SessionId(1L)
            runCurrent()

            inbound.send(InboundEvent.Connected(sid))
            inbound.send(InboundEvent.LineReceived(sid, "NewUser"))
            advanceTimeBy(tickMillis)
            runCurrent()

            val beforeConfirm = drainOutbound(outbound)
            assertTrue(
                beforeConfirm.any {
                    it is OutboundEvent.SendInfo &&
                        it.sessionId == sid &&
                        it.text == "No user named 'NewUser' was found. Create a new user? (yes/no)"
                },
                "Expected create-confirmation prompt for unknown name. got=$beforeConfirm",
            )
            assertFalse(
                beforeConfirm.any {
                    it is OutboundEvent.SendInfo &&
                        it.sessionId == sid &&
                        it.text == "Password:"
                },
                "Did not expect password prompt before create confirmation. got=$beforeConfirm",
            )

            inbound.send(InboundEvent.LineReceived(sid, "no"))
            advanceTimeBy(tickMillis)
            runCurrent()

            val afterNo = drainOutbound(outbound)
            assertTrue(
                afterNo.any {
                    it is OutboundEvent.SendInfo &&
                        it.sessionId == sid &&
                        it.text == "Enter your name:"
                },
                "Expected return to name prompt after declining account creation. got=$afterNo",
            )

            inbound.send(InboundEvent.LineReceived(sid, "NewUser"))
            inbound.send(InboundEvent.LineReceived(sid, "yes"))
            advanceTimeBy(tickMillis)
            runCurrent()

            val afterYes = drainOutbound(outbound)
            assertTrue(
                afterYes.any {
                    it is OutboundEvent.SendInfo &&
                        it.sessionId == sid &&
                        it.text == "Create a password:"
                },
                "Expected create-password prompt after confirmation. got=$afterYes",
            )
            assertFalse(
                afterYes.any {
                    it is OutboundEvent.SendInfo &&
                        it.sessionId == sid &&
                        it.text == "Password:"
                },
                "Did not expect existing-user password prompt for new-account flow. got=$afterYes",
            )

            inbound.send(InboundEvent.LineReceived(sid, "password"))
            advanceTimeBy(tickMillis)
            runCurrent()

            assertEquals("NewUser", players.get(sid)?.name)

            engineJob.cancel()
            inbound.close()
            outbound.close()
        }

    @Test
    fun `second login with correct password kicks first session and observer sees flicker`() =
        runTest {
            val inbound = LocalInboundBus()
            val outbound = LocalOutboundBus()

            val world = WorldLoader.loadFromResource("world/test_world.yaml")
            val repo = InMemoryPlayerRepository()
            repo.create("Alice", world.startRoom, 0L, BCrypt.hashpw("secret", BCrypt.gensalt()), ansiEnabled = false)
            repo.create("Observer", world.startRoom, 0L, BCrypt.hashpw("pw", BCrypt.gensalt()), ansiEnabled = false)
            val items = ItemRegistry()
            val players = PlayerRegistry(world.startRoom, repo, items)

            val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
            val mobs = MobRegistry()
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
            val sid3 = SessionId(3L)
            runCurrent()

            // Login Observer (sid3) and Alice (sid1) — both land in start room
            inbound.send(InboundEvent.Connected(sid3))
            inbound.send(InboundEvent.LineReceived(sid3, "Observer"))
            inbound.send(InboundEvent.LineReceived(sid3, "pw"))
            inbound.send(InboundEvent.Connected(sid1))
            inbound.send(InboundEvent.LineReceived(sid1, "Alice"))
            inbound.send(InboundEvent.LineReceived(sid1, "secret"))

            advanceTimeBy(tickMillis)
            runCurrent()

            assertEquals("Alice", players.get(sid1)?.name, "Expected Alice logged in on sid1")
            drainOutbound(outbound)

            // Second session takes over Alice with correct password
            inbound.send(InboundEvent.Connected(sid2))
            inbound.send(InboundEvent.LineReceived(sid2, "Alice"))
            inbound.send(InboundEvent.LineReceived(sid2, "secret"))

            advanceTimeBy(tickMillis)
            runCurrent()

            val outs = drainOutbound(outbound)

            // Old session is closed with kick message
            val kick = outs.filterIsInstance<OutboundEvent.Close>().firstOrNull { it.sessionId == sid1 }
            assertNotNull(kick, "Expected Close event for old session. got=$outs")
            assertEquals("Your account has logged in from another location.", kick!!.reason)

            // Observer in the same room sees the flicker message
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.sessionId == sid3 && it.text == "Alice briefly flickers." },
                "Expected observer to see flicker message. got=$outs",
            )

            // New session does NOT receive an "enters." broadcast
            assertFalse(
                outs
                    .filterIsInstance<OutboundEvent.SendText>()
                    .any { it.sessionId == sid2 && it.text.contains("enters.") },
                "Expected no 'enters.' broadcast for takeover. got=$outs",
            )

            // New session now owns Alice; old session is gone
            assertNull(players.get(sid1), "Expected old session removed from registry")
            assertEquals("Alice", players.get(sid2)?.name)
            assertEquals(sid2, players.findSessionByName("Alice"))

            engineJob.cancel()
            inbound.close()
            outbound.close()
        }

    @Test
    fun `wrong password on live name does not kick original session`() =
        runTest {
            val inbound = LocalInboundBus()
            val outbound = LocalOutboundBus()

            val world = WorldLoader.loadFromResource("world/test_world.yaml")
            val repo = InMemoryPlayerRepository()
            repo.create("Alice", world.startRoom, 0L, BCrypt.hashpw("secret", BCrypt.gensalt()), ansiEnabled = false)
            val items = ItemRegistry()
            val players = PlayerRegistry(world.startRoom, repo, items)

            val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
            val mobs = MobRegistry()
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
            runCurrent()

            // Login Alice on sid1
            inbound.send(InboundEvent.Connected(sid1))
            inbound.send(InboundEvent.LineReceived(sid1, "Alice"))
            inbound.send(InboundEvent.LineReceived(sid1, "secret"))

            advanceTimeBy(tickMillis)
            runCurrent()

            assertEquals("Alice", players.get(sid1)?.name)
            drainOutbound(outbound)

            // Second session attempts takeover with wrong password
            inbound.send(InboundEvent.Connected(sid2))
            inbound.send(InboundEvent.LineReceived(sid2, "Alice"))
            inbound.send(InboundEvent.LineReceived(sid2, "wrongpassword"))

            advanceTimeBy(tickMillis)
            runCurrent()

            val outs = drainOutbound(outbound)

            // Original session is NOT closed
            assertFalse(
                outs.any { it is OutboundEvent.Close && it.sessionId == sid1 },
                "Expected old session to remain open after wrong password. got=$outs",
            )

            // Original session still owns Alice
            assertEquals("Alice", players.get(sid1)?.name)
            assertNull(players.get(sid2), "Expected sid2 not logged in after wrong password")

            engineJob.cancel()
            inbound.close()
            outbound.close()
        }

    @Test
    fun `combat is inherited after session takeover`() =
        runTest {
            val inbound = LocalInboundBus()
            val outbound = LocalOutboundBus()

            // ok_small world: start room 'ok_small:a', mob 'rat' in 'ok_small:b', exit north from a to b
            val world = WorldLoader.loadFromResource("world/ok_small.yaml")
            val repo = InMemoryPlayerRepository()
            repo.create("Alice", world.startRoom, 0L, BCrypt.hashpw("secret", BCrypt.gensalt()), ansiEnabled = false)
            val items = ItemRegistry()
            val players = PlayerRegistry(world.startRoom, repo, items)

            val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
            val mobs = MobRegistry()
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
            runCurrent()

            // Login Alice, move to rat's room, start combat
            inbound.send(InboundEvent.Connected(sid1))
            inbound.send(InboundEvent.LineReceived(sid1, "Alice"))
            inbound.send(InboundEvent.LineReceived(sid1, "secret"))
            inbound.send(InboundEvent.LineReceived(sid1, "n"))
            inbound.send(InboundEvent.LineReceived(sid1, "kill rat"))

            advanceTimeBy(tickMillis)
            runCurrent()

            drainOutbound(outbound)

            // Take over Alice on sid2
            inbound.send(InboundEvent.Connected(sid2))
            inbound.send(InboundEvent.LineReceived(sid2, "Alice"))
            inbound.send(InboundEvent.LineReceived(sid2, "secret"))

            advanceTimeBy(tickMillis)
            runCurrent()

            drainOutbound(outbound)

            // Flee on sid2 — succeeds only if combat was inherited
            inbound.send(InboundEvent.LineReceived(sid2, "flee"))

            advanceTimeBy(tickMillis)
            runCurrent()

            val outs = drainOutbound(outbound)

            assertFalse(
                outs.any { it is OutboundEvent.SendError && it.sessionId == sid2 && it.text == "You are not in combat." },
                "Expected flee to succeed (combat inherited). got=$outs",
            )
            assertTrue(
                outs
                    .filterIsInstance<OutboundEvent.SendText>()
                    .any { it.sessionId == sid2 && it.text.startsWith("You flee from") },
                "Expected 'You flee from...' message on new session. got=$outs",
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
