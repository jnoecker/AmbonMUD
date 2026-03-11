package dev.ambon.engine

import dev.ambon.bus.LocalInboundBus
import dev.ambon.bus.LocalOutboundBus
import dev.ambon.config.EngineConfig
import dev.ambon.config.EngineDebugConfig
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.InboundEvent
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.scheduler.Scheduler
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.GameEngineHarness
import dev.ambon.test.TestWorlds
import dev.ambon.test.buildTestPlayerRegistry
import dev.ambon.test.createTestPlayer
import dev.ambon.test.drainAll
import dev.ambon.test.testClassEngineConfig
import dev.ambon.test.testRaceEngineConfig
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class GameEngineLoginFlowTest {
    @Test
    fun `connect shows login screen before name prompt`() =
        runTest {
            val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
            val h = GameEngineHarness.start(scope = this, clock = clock)

            val sid = SessionId(99L)
            runCurrent()

            h.inbound.send(InboundEvent.Connected(sid))
            advanceTimeBy(h.tickMillis)
            runCurrent()

            val outs = h.outbound.drainAll()
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

            h.close()
        }

    @Test
    fun `blank password returns to name prompt`() =
        runTest {
            val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
            val repo = InMemoryPlayerRepository()
            repo.createTestPlayer("Alice", TestWorlds.testWorld.startRoom, password = "secret", ansiEnabled = false)
            val h = GameEngineHarness.start(scope = this, clock = clock, repo = repo)

            val sid = SessionId(1L)
            runCurrent()

            h.inbound.send(InboundEvent.Connected(sid))
            h.inbound.send(InboundEvent.LineReceived(sid, "Alice"))
            h.inbound.send(InboundEvent.LineReceived(sid, ""))

            advanceTimeBy(h.tickMillis)
            runCurrent()

            val outs = h.outbound.drainAll()

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

            h.close()
        }

    @Test
    fun `fourth wrong password returns to login`() =
        runTest {
            val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
            val repo = InMemoryPlayerRepository()
            repo.createTestPlayer("Alice", TestWorlds.testWorld.startRoom, password = "secret", ansiEnabled = false)
            val h = GameEngineHarness.start(scope = this, clock = clock, repo = repo)

            val sid = SessionId(1L)
            runCurrent()

            h.inbound.send(InboundEvent.Connected(sid))
            h.inbound.send(InboundEvent.LineReceived(sid, "Alice"))
            repeat(4) { h.inbound.send(InboundEvent.LineReceived(sid, "wrong")) }

            advanceTimeBy(h.tickMillis)
            runCurrent()

            val outs = h.outbound.drainAll()

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

            h.close()
        }

    @Test
    fun `disconnects after three failed login cycles`() =
        runTest {
            val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
            val repo = InMemoryPlayerRepository()
            repo.createTestPlayer("Alice", TestWorlds.testWorld.startRoom, password = "secret", ansiEnabled = false)
            val h = GameEngineHarness.start(scope = this, clock = clock, repo = repo)

            val sid = SessionId(1L)
            runCurrent()

            h.inbound.send(InboundEvent.Connected(sid))
            repeat(3) {
                h.inbound.send(InboundEvent.LineReceived(sid, "Alice"))
                repeat(4) { h.inbound.send(InboundEvent.LineReceived(sid, "wrong")) }
            }

            advanceTimeBy(h.tickMillis)
            runCurrent()

            val got = mutableListOf<OutboundEvent>()
            withTimeout(500) {
                while (got.none { it is OutboundEvent.Close && it.sessionId == sid }) {
                    got += h.outbound.asReceiveChannel().receive()
                }
            }

            val close = got.filterIsInstance<OutboundEvent.Close>().first { it.sessionId == sid }
            assertEquals("Too many failed login attempts.", close.reason)

            h.close()
        }

    @Test
    fun `new user requires confirmation before password prompt`() =
        runTest {
            val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
            val h = GameEngineHarness.start(scope = this, clock = clock)

            val sid = SessionId(1L)
            runCurrent()

            h.inbound.send(InboundEvent.Connected(sid))
            h.inbound.send(InboundEvent.LineReceived(sid, "NewUser"))
            advanceTimeBy(h.tickMillis)
            runCurrent()

            val beforeConfirm = h.outbound.drainAll()
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

            h.inbound.send(InboundEvent.LineReceived(sid, "no"))
            advanceTimeBy(h.tickMillis)
            runCurrent()

            val afterNo = h.outbound.drainAll()
            assertTrue(
                afterNo.any {
                    it is OutboundEvent.SendInfo &&
                        it.sessionId == sid &&
                        it.text == "Enter your name:"
                },
                "Expected return to name prompt after declining account creation. got=$afterNo",
            )

            h.inbound.send(InboundEvent.LineReceived(sid, "NewUser"))
            h.inbound.send(InboundEvent.LineReceived(sid, "yes"))
            advanceTimeBy(h.tickMillis)
            runCurrent()

            val afterYes = h.outbound.drainAll()
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

            h.inbound.send(InboundEvent.LineReceived(sid, "password"))
            advanceTimeBy(h.tickMillis)
            runCurrent()

            val afterPassword = h.outbound.drainAll()
            assertTrue(
                afterPassword.any {
                    it is OutboundEvent.SendInfo &&
                        it.sessionId == sid &&
                        it.text == "Choose your race:"
                },
                "Expected race selection prompt after password. got=$afterPassword",
            )

            h.inbound.send(InboundEvent.LineReceived(sid, "1"))
            advanceTimeBy(h.tickMillis)
            runCurrent()

            val afterRace = h.outbound.drainAll()
            assertTrue(
                afterRace.any {
                    it is OutboundEvent.SendInfo &&
                        it.sessionId == sid &&
                        it.text == "Choose your class:"
                },
                "Expected class selection prompt after race. got=$afterRace",
            )

            h.inbound.send(InboundEvent.LineReceived(sid, "1"))
            advanceTimeBy(h.tickMillis)
            runCurrent()

            val ps = h.players.get(sid)
            assertNotNull(ps)
            assertEquals("NewUser", ps!!.name)
            // Human race (choice 1): STR +1, CHA +1, all others +0
            assertEquals(11, ps.stats["STR"], "Human STR should be BASE_STAT + 1")
            assertEquals(10, ps.stats["DEX"], "Human DEX should be BASE_STAT + 0")
            assertEquals(10, ps.stats["CON"], "Human CON should be BASE_STAT + 0")
            assertEquals(10, ps.stats["INT"], "Human INT should be BASE_STAT + 0")
            assertEquals(10, ps.stats["WIS"], "Human WIS should be BASE_STAT + 0")
            assertEquals(11, ps.stats["CHA"], "Human CHA should be BASE_STAT + 1")
            assertEquals("HUMAN", ps.race)
            assertEquals("WARRIOR", ps.playerClass)

            h.close()
        }

    @Test
    fun `swarm class is hidden when debug flag is disabled`() =
        runTest {
            val inbound = LocalInboundBus()
            val outbound = LocalOutboundBus()

            val world = dev.ambon.test.TestWorlds.testWorld
            val repo = InMemoryPlayerRepository()
            val items = ItemRegistry()
            val classRegistry =
                PlayerClassRegistry().also { reg ->
                    PlayerClassRegistryLoader.load(testClassEngineConfig(), reg)
                }
            val raceRegistry =
                RaceRegistry().also { reg ->
                    RaceRegistryLoader.load(testRaceEngineConfig(), reg)
                }
            val players =
                dev.ambon.test.buildTestPlayerRegistry(
                    world.startRoom,
                    repo,
                    items,
                    classRegistry = classRegistry,
                    raceRegistry = raceRegistry,
                )

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
                    engineConfig = EngineConfig(debug = EngineDebugConfig(enableSwarmClass = false)),
                    classRegistryOverride = classRegistry,
                    raceRegistryOverride = raceRegistry,
                )
            val engineJob = launch { engine.run() }

            val sid = SessionId(1L)
            runCurrent()

            inbound.send(InboundEvent.Connected(sid))
            inbound.send(InboundEvent.LineReceived(sid, "NoSwarm"))
            advanceTimeBy(tickMillis)
            runCurrent()
            outbound.drainAll()

            inbound.send(InboundEvent.LineReceived(sid, "yes"))
            advanceTimeBy(tickMillis)
            runCurrent()
            outbound.drainAll()

            inbound.send(InboundEvent.LineReceived(sid, "password"))
            advanceTimeBy(tickMillis)
            runCurrent()
            outbound.drainAll()

            inbound.send(InboundEvent.LineReceived(sid, "1"))
            advanceTimeBy(tickMillis)
            runCurrent()

            val classPrompt = outbound.drainAll()
            assertTrue(
                classPrompt.any {
                    it is OutboundEvent.SendInfo &&
                        it.sessionId == sid &&
                        it.text == "Choose your class:"
                },
                "Expected class selection prompt. got=$classPrompt",
            )
            assertFalse(
                classPrompt.any {
                    it is OutboundEvent.SendInfo &&
                        it.sessionId == sid &&
                        it.text.contains("Swarm")
                },
                "Swarm should not be listed when debug flag is disabled. got=$classPrompt",
            )

            inbound.send(InboundEvent.LineReceived(sid, "5"))
            advanceTimeBy(tickMillis)
            runCurrent()

            val invalidAttempt = outbound.drainAll()
            assertTrue(
                invalidAttempt.any {
                    it is OutboundEvent.SendError &&
                        it.sessionId == sid &&
                        it.text.contains("Invalid choice")
                },
                "Expected invalid-choice error for hidden Swarm class. got=$invalidAttempt",
            )
            assertFalse(
                invalidAttempt.any {
                    it is OutboundEvent.SendInfo &&
                        it.sessionId == sid &&
                        it.text.contains("Swarm")
                },
                "Swarm should remain hidden after invalid selection. got=$invalidAttempt",
            )

            engineJob.cancel()
            inbound.close()
            outbound.close()
        }

    @Test
    fun `elf mage creation applies correct racial modifiers and class`() =
        runTest {
            val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
            val h = GameEngineHarness.start(scope = this, clock = clock)

            val sid = SessionId(1L)
            runCurrent()

            h.inbound.send(InboundEvent.Connected(sid))
            h.inbound.send(InboundEvent.LineReceived(sid, "ElfMage"))
            h.inbound.send(InboundEvent.LineReceived(sid, "yes"))
            h.inbound.send(InboundEvent.LineReceived(sid, "password"))
            h.inbound.send(InboundEvent.LineReceived(sid, "2")) // race: Elf
            h.inbound.send(InboundEvent.LineReceived(sid, "2")) // class: Mage
            advanceTimeBy(h.tickMillis)
            runCurrent()

            val ps = h.players.get(sid)
            assertNotNull(ps)
            // Elf: STR -1, DEX +2, CON -2, INT +1, WIS +0, CHA +0
            assertEquals(9, ps!!.stats["STR"], "Elf STR should be BASE_STAT - 1")
            assertEquals(12, ps.stats["DEX"], "Elf DEX should be BASE_STAT + 2")
            assertEquals(8, ps.stats["CON"], "Elf CON should be BASE_STAT - 2")
            assertEquals(11, ps.stats["INT"], "Elf INT should be BASE_STAT + 1")
            assertEquals(10, ps.stats["WIS"], "Elf WIS should be BASE_STAT + 0")
            assertEquals(10, ps.stats["CHA"], "Elf CHA should be BASE_STAT + 0")
            assertEquals("ELF", ps.race)
            assertEquals("MAGE", ps.playerClass)

            h.close()
        }

    @Test
    fun `swarm class is selectable behind debug flag and uses swarm start room`() =
        runTest {
            val inbound = LocalInboundBus()
            val outbound = LocalOutboundBus()

            val world = dev.ambon.test.TestWorlds.testWorld
            val repo = InMemoryPlayerRepository()
            val items = ItemRegistry()
            val swarmRoom = RoomId("test_zone:outpost")
            val classRegistry =
                PlayerClassRegistry().also { reg ->
                    PlayerClassRegistryLoader.load(testClassEngineConfig(), reg)
                }
            val raceRegistry =
                RaceRegistry().also { reg ->
                    RaceRegistryLoader.load(testRaceEngineConfig(), reg)
                }
            val players =
                dev.ambon.test.buildTestPlayerRegistry(
                    world.startRoom,
                    repo,
                    items,
                    classStartRooms =
                        mapOf(
                            "SWARM" to swarmRoom,
                        ),
                    classRegistry = classRegistry,
                    raceRegistry = raceRegistry,
                )

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
                    engineConfig = EngineConfig(debug = EngineDebugConfig(enableSwarmClass = true)),
                    classRegistryOverride = classRegistry,
                    raceRegistryOverride = raceRegistry,
                )
            val engineJob = launch { engine.run() }

            val sid = SessionId(1L)
            runCurrent()

            inbound.send(InboundEvent.Connected(sid))
            inbound.send(InboundEvent.LineReceived(sid, "SwarmBot"))
            advanceTimeBy(tickMillis)
            runCurrent()
            outbound.drainAll()

            inbound.send(InboundEvent.LineReceived(sid, "yes"))
            advanceTimeBy(tickMillis)
            runCurrent()
            outbound.drainAll()

            inbound.send(InboundEvent.LineReceived(sid, "password"))
            advanceTimeBy(tickMillis)
            runCurrent()
            outbound.drainAll()

            inbound.send(InboundEvent.LineReceived(sid, "1"))
            advanceTimeBy(tickMillis)
            runCurrent()

            val classPrompt = outbound.drainAll()
            assertTrue(
                classPrompt.any {
                    it is OutboundEvent.SendInfo &&
                        it.sessionId == sid &&
                        it.text.contains("5. Swarm")
                },
                "Expected Swarm in class list when debug flag is enabled. got=$classPrompt",
            )

            inbound.send(InboundEvent.LineReceived(sid, "5"))
            advanceTimeBy(tickMillis)
            runCurrent()

            val player = players.get(sid)
            assertNotNull(player)
            assertEquals("SWARM", player!!.playerClass)
            assertEquals(swarmRoom, player.roomId)

            engineJob.cancel()
            inbound.close()
            outbound.close()
        }

    @Test
    fun `second login with correct password kicks first session and observer sees flicker`() =
        runTest {
            val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
            val repo = InMemoryPlayerRepository()
            repo.createTestPlayer("Alice", TestWorlds.testWorld.startRoom, password = "secret", ansiEnabled = false)
            repo.createTestPlayer("Observer", TestWorlds.testWorld.startRoom, password = "pw", ansiEnabled = false)
            val h = GameEngineHarness.start(scope = this, clock = clock, repo = repo)

            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)
            val sid3 = SessionId(3L)
            runCurrent()

            // Login Observer (sid3) and Alice (sid1) — both land in start room
            h.inbound.send(InboundEvent.Connected(sid3))
            h.inbound.send(InboundEvent.LineReceived(sid3, "Observer"))
            h.inbound.send(InboundEvent.LineReceived(sid3, "pw"))
            h.inbound.send(InboundEvent.Connected(sid1))
            h.inbound.send(InboundEvent.LineReceived(sid1, "Alice"))
            h.inbound.send(InboundEvent.LineReceived(sid1, "secret"))

            advanceTimeBy(h.tickMillis)
            runCurrent()

            assertEquals("Alice", h.players.get(sid1)?.name, "Expected Alice logged in on sid1")
            h.outbound.drainAll()

            // Second session takes over Alice with correct password
            h.inbound.send(InboundEvent.Connected(sid2))
            h.inbound.send(InboundEvent.LineReceived(sid2, "Alice"))
            h.inbound.send(InboundEvent.LineReceived(sid2, "secret"))

            advanceTimeBy(h.tickMillis)
            runCurrent()

            val outs = h.outbound.drainAll()

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
            assertNull(h.players.get(sid1), "Expected old session removed from registry")
            assertEquals("Alice", h.players.get(sid2)?.name)
            assertEquals(sid2, h.players.findSessionByName("Alice"))

            h.close()
        }

    @Test
    fun `wrong password on live name does not kick original session`() =
        runTest {
            val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
            val repo = InMemoryPlayerRepository()
            repo.createTestPlayer("Alice", TestWorlds.testWorld.startRoom, password = "secret", ansiEnabled = false)
            val h = GameEngineHarness.start(scope = this, clock = clock, repo = repo)

            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)
            runCurrent()

            // Login Alice on sid1
            h.inbound.send(InboundEvent.Connected(sid1))
            h.inbound.send(InboundEvent.LineReceived(sid1, "Alice"))
            h.inbound.send(InboundEvent.LineReceived(sid1, "secret"))

            advanceTimeBy(h.tickMillis)
            runCurrent()

            assertEquals("Alice", h.players.get(sid1)?.name)
            h.outbound.drainAll()

            // Second session attempts takeover with wrong password
            h.inbound.send(InboundEvent.Connected(sid2))
            h.inbound.send(InboundEvent.LineReceived(sid2, "Alice"))
            h.inbound.send(InboundEvent.LineReceived(sid2, "wrongpassword"))

            advanceTimeBy(h.tickMillis)
            runCurrent()

            val outs = h.outbound.drainAll()

            // Original session is NOT closed
            assertFalse(
                outs.any { it is OutboundEvent.Close && it.sessionId == sid1 },
                "Expected old session to remain open after wrong password. got=$outs",
            )

            // Original session still owns Alice
            assertEquals("Alice", h.players.get(sid1)?.name)
            assertNull(h.players.get(sid2), "Expected sid2 not logged in after wrong password")

            h.close()
        }

    @Test
    fun `combat is inherited after session takeover`() =
        runTest {
            // ok_small world: start room 'ok_small:a', mob 'rat' in 'ok_small:b', exit north from a to b
            val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
            val repo = InMemoryPlayerRepository()
            repo.createTestPlayer("Alice", TestWorlds.okSmall.startRoom, password = "secret", ansiEnabled = false)
            val h = GameEngineHarness.start(scope = this, world = TestWorlds.okSmall, clock = clock, repo = repo)

            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)
            runCurrent()

            // Login Alice, move to rat's room, start combat
            h.inbound.send(InboundEvent.Connected(sid1))
            h.inbound.send(InboundEvent.LineReceived(sid1, "Alice"))
            h.inbound.send(InboundEvent.LineReceived(sid1, "secret"))
            h.inbound.send(InboundEvent.LineReceived(sid1, "n"))
            h.inbound.send(InboundEvent.LineReceived(sid1, "kill rat"))

            advanceTimeBy(h.tickMillis)
            runCurrent()

            h.outbound.drainAll()

            // Take over Alice on sid2
            h.inbound.send(InboundEvent.Connected(sid2))
            h.inbound.send(InboundEvent.LineReceived(sid2, "Alice"))
            h.inbound.send(InboundEvent.LineReceived(sid2, "secret"))

            advanceTimeBy(h.tickMillis)
            runCurrent()

            h.outbound.drainAll()

            // Flee on sid2 — succeeds only if combat was inherited
            h.inbound.send(InboundEvent.LineReceived(sid2, "flee"))

            advanceTimeBy(h.tickMillis)
            runCurrent()

            val outs = h.outbound.drainAll()

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

            h.close()
        }

    @Test
    fun `new character spawns in class-specific start room when classStartRooms is configured`() =
        runTest {
            val world = dev.ambon.test.TestWorlds.testWorld
            val warriorRoom = RoomId("test_zone:outpost")
            val classStartRooms =
                mapOf(
                    "WARRIOR" to warriorRoom,
                )
            val repo = InMemoryPlayerRepository()
            val items = ItemRegistry()
            val players =
                buildTestPlayerRegistry(
                    startRoom = world.startRoom,
                    repo = repo,
                    items = items,
                    classStartRooms = classStartRooms,
                )

            val sid = SessionId(1L)
            val result =
                players.create(
                    sessionId = sid,
                    nameRaw = "Grunt",
                    passwordRaw = "password",
                    race = "HUMAN",
                    playerClass = "WARRIOR",
                )

            assertEquals(CreateResult.Ok, result)
            val ps = players.get(sid)
            assertNotNull(ps)
            assertEquals(warriorRoom, ps!!.roomId, "Warrior should spawn in configured warrior start room")
        }

    @Test
    fun `new character uses default start room when class has no configured override`() =
        runTest {
            val world = dev.ambon.test.TestWorlds.testWorld
            val classStartRooms =
                mapOf(
                    "WARRIOR" to RoomId("test_zone:outpost"),
                )
            val repo = InMemoryPlayerRepository()
            val items = ItemRegistry()
            val players =
                buildTestPlayerRegistry(
                    startRoom = world.startRoom,
                    repo = repo,
                    items = items,
                    classStartRooms = classStartRooms,
                )

            val sid = SessionId(1L)
            val result =
                players.create(
                    sessionId = sid,
                    nameRaw = "Rogueling",
                    passwordRaw = "password",
                    race = "HUMAN",
                    playerClass = "ROGUE",
                )

            assertEquals(CreateResult.Ok, result)
            val ps = players.get(sid)
            assertNotNull(ps)
            assertEquals(world.startRoom, ps!!.roomId, "Rogue with no override should spawn in world default start room")
        }

    @Test
    fun `two sessions creating same name concurrently does not crash`() =
        runTest {
            val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
            val h = GameEngineHarness.start(scope = this, clock = clock)

            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)
            runCurrent()

            // Both sessions connect and enter the same name
            h.inbound.send(InboundEvent.Connected(sid1))
            h.inbound.send(InboundEvent.Connected(sid2))
            advanceTimeBy(h.tickMillis)
            runCurrent()
            h.outbound.drainAll()

            h.inbound.send(InboundEvent.LineReceived(sid1, "DupeName"))
            h.inbound.send(InboundEvent.LineReceived(sid2, "DupeName"))
            advanceTimeBy(h.tickMillis)
            runCurrent()
            h.outbound.drainAll()

            // Both confirm "yes" to create
            h.inbound.send(InboundEvent.LineReceived(sid1, "yes"))
            h.inbound.send(InboundEvent.LineReceived(sid2, "yes"))
            advanceTimeBy(h.tickMillis)
            runCurrent()
            h.outbound.drainAll()

            // Both enter passwords
            h.inbound.send(InboundEvent.LineReceived(sid1, "password"))
            h.inbound.send(InboundEvent.LineReceived(sid2, "password"))
            advanceTimeBy(h.tickMillis)
            runCurrent()
            h.outbound.drainAll()

            // Both pick race (1 = Human)
            h.inbound.send(InboundEvent.LineReceived(sid1, "1"))
            h.inbound.send(InboundEvent.LineReceived(sid2, "1"))
            advanceTimeBy(h.tickMillis)
            runCurrent()
            h.outbound.drainAll()

            // Both pick class (1 = Warrior) — triggers prepareCreateAccount
            h.inbound.send(InboundEvent.LineReceived(sid1, "1"))
            h.inbound.send(InboundEvent.LineReceived(sid2, "1"))
            advanceTimeBy(h.tickMillis)
            runCurrent()

            val outs = h.outbound.drainAll()

            // Exactly one session should be logged in, the other should see "name taken"
            val loggedIn1 = h.players.get(sid1) != null
            val loggedIn2 = h.players.get(sid2) != null

            assertTrue(
                loggedIn1 || loggedIn2,
                "At least one session should have been created successfully",
            )
            assertFalse(
                loggedIn1 && loggedIn2,
                "Both sessions should not have been created with the same name",
            )

            // The session that failed should have received a "name taken" error
            val failedSid = if (loggedIn1) sid2 else sid1
            assertTrue(
                outs.any {
                    it is OutboundEvent.SendError &&
                        it.sessionId == failedSid &&
                        it.text.contains("taken", ignoreCase = true)
                },
                "The duplicate session should receive a 'name taken' error. got=$outs",
            )

            h.close()
        }
}
