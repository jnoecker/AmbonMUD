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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
            val inbound = Channel<InboundEvent>(capacity = Channel.UNLIMITED)
            val outbound = Channel<OutboundEvent>(capacity = Channel.UNLIMITED)

            val world = WorldFactory.demoWorld()
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
            val inbound = Channel<InboundEvent>(capacity = Channel.UNLIMITED)
            val outbound = Channel<OutboundEvent>(capacity = Channel.UNLIMITED)

            val world = WorldFactory.demoWorld()
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
            val inbound = Channel<InboundEvent>(capacity = Channel.UNLIMITED)
            val outbound = Channel<OutboundEvent>(capacity = Channel.UNLIMITED)

            val world = WorldFactory.demoWorld()
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
                    got += outbound.receive()
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

    private fun drainOutbound(outbound: Channel<OutboundEvent>): List<OutboundEvent> {
        val out = mutableListOf<OutboundEvent>()
        while (true) {
            val ev = outbound.tryReceive().getOrNull() ?: break
            out += ev
        }
        return out
    }
}
