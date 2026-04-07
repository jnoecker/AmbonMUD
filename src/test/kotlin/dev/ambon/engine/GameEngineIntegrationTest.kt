package dev.ambon.engine

import dev.ambon.bus.LocalInboundBus
import dev.ambon.bus.LocalOutboundBus
import dev.ambon.config.EngineConfig
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.InboundEvent
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.scheduler.Scheduler
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.MutableClock
import dev.ambon.test.collectUntil
import dev.ambon.test.drainAll
import dev.ambon.test.testClassEngineConfig
import dev.ambon.test.testRaceEngineConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class GameEngineIntegrationTest {
    @Test
    fun `connect then say hello then quit`() =
        runTest {
            val inbound = LocalInboundBus()
            val outbound = LocalOutboundBus()

            val world = dev.ambon.test.TestWorlds.testWorld
            val repo = InMemoryPlayerRepository()
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
                    ItemRegistry(),
                    classRegistry = classRegistry,
                    raceRegistry = raceRegistry,
                )

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
                    classRegistryOverride = classRegistry,
                    raceRegistryOverride = raceRegistry,
                )
            val engineJob = launch { engine.run() }

            val sid = SessionId(1L)

            inbound.send(InboundEvent.Connected(sid))
            inbound.send(InboundEvent.LineReceived(sid, "Alice"))
            inbound.send(InboundEvent.LineReceived(sid, "yes"))
            inbound.send(InboundEvent.LineReceived(sid, "password"))
            inbound.send(InboundEvent.LineReceived(sid, "1")) // race: Human
            inbound.send(InboundEvent.LineReceived(sid, "1")) // class: Warrior
            inbound.send(InboundEvent.LineReceived(sid, "Hello"))
            inbound.send(InboundEvent.LineReceived(sid, "quit"))

            // Let the engine start + process any immediate work
            runCurrent()
            // Step time forward a few ticks so the engine loop definitely runs
            advanceTimeBy(5)
            runCurrent()

            // Collect events deterministically
            val got = outbound.collectUntil { events ->
                events.any { it is OutboundEvent.Close && it.sessionId == sid }
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
            val inbound = LocalInboundBus()
            val outbound = LocalOutboundBus()

            val world = dev.ambon.test.TestWorlds.testWorld
            val repo = InMemoryPlayerRepository()
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
                    ItemRegistry(),
                    classRegistry = classRegistry,
                    raceRegistry = raceRegistry,
                )

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
                    classRegistryOverride = classRegistry,
                    raceRegistryOverride = raceRegistry,
                    engineConfig = EngineConfig(sessionResumeGracePeriodMs = 0),
                )
            val engineJob = launch { engine.run() }

            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)

            inbound.send(InboundEvent.Connected(sid1))
            inbound.send(InboundEvent.Connected(sid2))
            inbound.send(InboundEvent.LineReceived(sid1, "Alice"))
            inbound.send(InboundEvent.LineReceived(sid2, "Bob"))
            inbound.send(InboundEvent.LineReceived(sid1, "yes"))
            inbound.send(InboundEvent.LineReceived(sid2, "yes"))
            inbound.send(InboundEvent.LineReceived(sid1, "password"))
            inbound.send(InboundEvent.LineReceived(sid2, "password"))
            inbound.send(InboundEvent.LineReceived(sid1, "1")) // race
            inbound.send(InboundEvent.LineReceived(sid2, "1")) // race
            inbound.send(InboundEvent.LineReceived(sid1, "1")) // class
            inbound.send(InboundEvent.LineReceived(sid2, "1")) // class

            runCurrent()
            advanceTimeBy(5)
            runCurrent()

            outbound.collectUntil { events ->
                events.any { it is OutboundEvent.SendText && it.sessionId == sid1 && it.text == "Bob enters." }
            }

            inbound.send(InboundEvent.Disconnected(sid2, "test"))
            runCurrent()
            advanceTimeBy(5)
            runCurrent()

            outbound.collectUntil { events ->
                events.any { it is OutboundEvent.SendText && it.sessionId == sid1 && it.text == "Bob leaves." }
            }

            engineJob.cancel()
            inbound.close()
            outbound.close()
        }

    @Test
    fun `zone reset notifies players and restores spawn state`() =
        runTest {
            val inbound = LocalInboundBus()
            val outbound = LocalOutboundBus()

            val world = dev.ambon.test.TestWorlds.okSmall
            val items = ItemRegistry()
            val repo = InMemoryPlayerRepository()
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
            val mobs = MobRegistry()
            val clock = MutableClock(0L)
            val scheduler = Scheduler(clock)
            val tickMillis = 1_000L

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
                    classRegistryOverride = classRegistry,
                    raceRegistryOverride = raceRegistry,
                )
            val engineJob = launch { engine.run() }

            suspend fun step(ms: Long) {
                clock.advance(ms)
                advanceTimeBy(ms)
                runCurrent()
            }

            val sid = SessionId(1L)

            runCurrent()

            inbound.send(InboundEvent.Connected(sid))
            inbound.send(InboundEvent.LineReceived(sid, "Alice"))
            inbound.send(InboundEvent.LineReceived(sid, "yes"))
            inbound.send(InboundEvent.LineReceived(sid, "password"))
            inbound.send(InboundEvent.LineReceived(sid, "1")) // race
            inbound.send(InboundEvent.LineReceived(sid, "1")) // class
            step(tickMillis)

            inbound.send(InboundEvent.LineReceived(sid, "get coin"))
            step(tickMillis)

            assertTrue(items.inventory(sid).any { it.item.keyword == "coin" })
            assertTrue(items.itemsInRoom(world.startRoom).none { it.item.keyword == "coin" })

            outbound.drainAll()

            step(60_000L)
            val resetEvents = outbound.drainAll()

            assertTrue(
                resetEvents.any {
                    it is OutboundEvent.SendText &&
                        it.sessionId == sid &&
                        it.text == "The air shimmers as the area resets around you."
                },
                "Expected zone reset notification after zone reset; got=$resetEvents",
            )
            assertEquals(
                1,
                items.itemsInRoom(world.startRoom).count { it.item.keyword == "coin" },
            )

            engineJob.cancel()
            inbound.close()
            outbound.close()
        }

    @Test
    fun `ansi preference persists and is restored on login`() =
        runTest {
            val inbound = LocalInboundBus()
            val outbound = LocalOutboundBus()

            val world = dev.ambon.test.TestWorlds.testWorld
            val repo = InMemoryPlayerRepository()
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
                    ItemRegistry(),
                    classRegistry = classRegistry,
                    raceRegistry = raceRegistry,
                )

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
                    classRegistryOverride = classRegistry,
                    raceRegistryOverride = raceRegistry,
                    engineConfig = EngineConfig(sessionResumeGracePeriodMs = 0),
                )
            val engineJob = launch { engine.run() }

            fun step() {
                advanceTimeBy(tickMillis)
                runCurrent()
            }

            val sid1 = SessionId(1L)

            runCurrent()
            inbound.send(InboundEvent.Connected(sid1))
            inbound.send(InboundEvent.LineReceived(sid1, "Alice"))
            inbound.send(InboundEvent.LineReceived(sid1, "yes"))
            inbound.send(InboundEvent.LineReceived(sid1, "password"))
            inbound.send(InboundEvent.LineReceived(sid1, "1")) // race
            inbound.send(InboundEvent.LineReceived(sid1, "1")) // class
            step()

            inbound.send(InboundEvent.LineReceived(sid1, "ansi on"))
            step()

            inbound.send(InboundEvent.Disconnected(sid1, "test"))
            step()

            outbound.drainAll()

            val sid2 = SessionId(2L)
            inbound.send(InboundEvent.Connected(sid2))
            inbound.send(InboundEvent.LineReceived(sid2, "Alice"))
            inbound.send(InboundEvent.LineReceived(sid2, "password"))
            step()

            val outs = outbound.drainAll()
            assertTrue(
                outs.any { it is OutboundEvent.SetAnsi && it.sessionId == sid2 && it.enabled },
                "Expected ANSI preference to be restored on login; got=$outs",
            )

            engineJob.cancel()
            inbound.close()
            outbound.close()
        }

    @Test
    fun `auto-relog via saved auth token succeeds without password and keeps token stable`() =
        runTest {
            val inbound = LocalInboundBus()
            val outbound = LocalOutboundBus()

            val world = dev.ambon.test.TestWorlds.testWorld
            val repo = InMemoryPlayerRepository()
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
                    ItemRegistry(),
                    classRegistry = classRegistry,
                    raceRegistry = raceRegistry,
                )

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
                    classRegistryOverride = classRegistry,
                    raceRegistryOverride = raceRegistry,
                    persistence = dev.ambon.engine.PersistenceContext(playerRepo = repo),
                    engineConfig = EngineConfig(sessionResumeGracePeriodMs = 0),
                )
            val engineJob = launch { engine.run() }

            fun step() {
                advanceTimeBy(tickMillis)
                runCurrent()
            }

            // Capture Session.AuthToken payloads (the server only emits them for
            // sessions that declared support, so every session must send
            // Core.Supports.Set first).
            fun extractTokenFrom(events: List<OutboundEvent>, sid: SessionId): String? =
                events
                    .filterIsInstance<OutboundEvent.GmcpData>()
                    .filter { it.sessionId == sid && it.gmcpPackage == "Session.AuthToken" }
                    .mapNotNull { ev ->
                        Regex(""""token"\s*:\s*"([^"]+)"""").find(ev.jsonData)?.groupValues?.get(1)
                    }
                    .lastOrNull()

            fun countAuthTokenMessages(events: List<OutboundEvent>, sid: SessionId): Int =
                events.count {
                    it is OutboundEvent.GmcpData &&
                        it.sessionId == sid &&
                        it.gmcpPackage == "Session.AuthToken"
                }

            // --- Phase 1: create account via normal password login ---
            val sid1 = SessionId(1L)
            runCurrent()
            inbound.send(InboundEvent.Connected(sid1))
            inbound.send(
                InboundEvent.GmcpReceived(
                    sid1,
                    "Core.Supports.Set",
                    """["Session.AuthToken 1","Session.AuthResult 1","Char.Name 1","Login.Prompt 1"]""",
                ),
            )
            inbound.send(InboundEvent.LineReceived(sid1, "Ribbon"))
            inbound.send(InboundEvent.LineReceived(sid1, "yes"))
            inbound.send(InboundEvent.LineReceived(sid1, "secretpw"))
            inbound.send(InboundEvent.LineReceived(sid1, "1")) // race
            inbound.send(InboundEvent.LineReceived(sid1, "1")) // class
            step()
            step()

            val createEvents = outbound.drainAll()
            val firstToken = extractTokenFrom(createEvents, sid1)
            assertTrue(firstToken != null, "Expected Session.AuthToken after password login; got=$createEvents")
            assertEquals(
                1,
                countAuthTokenMessages(createEvents, sid1),
                "Password login should emit exactly one Session.AuthToken",
            )

            inbound.send(InboundEvent.Disconnected(sid1, "test"))
            step()
            outbound.drainAll()

            // --- Phase 2: reconnect and auto-relog with the captured token ---
            val sid2 = SessionId(2L)
            inbound.send(InboundEvent.Connected(sid2))
            inbound.send(
                InboundEvent.GmcpReceived(
                    sid2,
                    "Core.Supports.Set",
                    """["Session.AuthToken 1","Session.AuthResult 1","Char.Name 1","Login.Prompt 1"]""",
                ),
            )
            inbound.send(
                InboundEvent.GmcpReceived(
                    sid2,
                    "Session.Authenticate",
                    """{"token":"$firstToken","name":"Ribbon"}""",
                ),
            )
            step()
            step()

            val reauthEvents = outbound.drainAll()
            assertTrue(
                reauthEvents.any {
                    it is OutboundEvent.GmcpData &&
                        it.sessionId == sid2 &&
                        it.gmcpPackage == "Session.AuthResult" &&
                        it.jsonData.contains("\"success\":true")
                },
                "Expected Session.AuthResult success on auto-relog; got=$reauthEvents",
            )
            assertTrue(
                reauthEvents.any {
                    it is OutboundEvent.GmcpData && it.sessionId == sid2 && it.gmcpPackage == "Char.Name"
                },
                "Expected Char.Name full sync on auto-relog success; got=$reauthEvents",
            )
            // Token stability: auto-relog must NOT rotate the token. If it did,
            // the client's localStorage would go stale on the next reconnect
            // (the previous behaviour that was causing password prompts).
            assertEquals(
                0,
                countAuthTokenMessages(reauthEvents, sid2),
                "Auto-relog should not rotate or re-send Session.AuthToken; got=$reauthEvents",
            )

            inbound.send(InboundEvent.Disconnected(sid2, "test"))
            step()
            outbound.drainAll()

            // --- Phase 3: second auto-relog with the same (never-rotated) token ---
            val sid3 = SessionId(3L)
            inbound.send(InboundEvent.Connected(sid3))
            inbound.send(
                InboundEvent.GmcpReceived(
                    sid3,
                    "Core.Supports.Set",
                    """["Session.AuthToken 1","Session.AuthResult 1","Char.Name 1","Login.Prompt 1"]""",
                ),
            )
            inbound.send(
                InboundEvent.GmcpReceived(
                    sid3,
                    "Session.Authenticate",
                    """{"token":"$firstToken","name":"Ribbon"}""",
                ),
            )
            step()
            step()

            val secondReauthEvents = outbound.drainAll()
            assertTrue(
                secondReauthEvents.any {
                    it is OutboundEvent.GmcpData &&
                        it.sessionId == sid3 &&
                        it.gmcpPackage == "Session.AuthResult" &&
                        it.jsonData.contains("\"success\":true")
                },
                "The same token must keep working across reconnects; got=$secondReauthEvents",
            )

            engineJob.cancel()
            inbound.close()
            outbound.close()
        }

    @Test
    fun `failed auto-relog with valid character name jumps straight to password prompt`() =
        runTest {
            val inbound = LocalInboundBus()
            val outbound = LocalOutboundBus()

            val world = dev.ambon.test.TestWorlds.testWorld
            val repo = InMemoryPlayerRepository()
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
                    ItemRegistry(),
                    classRegistry = classRegistry,
                    raceRegistry = raceRegistry,
                )

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
                    classRegistryOverride = classRegistry,
                    raceRegistryOverride = raceRegistry,
                    persistence = dev.ambon.engine.PersistenceContext(playerRepo = repo),
                    engineConfig = EngineConfig(sessionResumeGracePeriodMs = 0),
                )
            val engineJob = launch { engine.run() }

            fun step() {
                advanceTimeBy(tickMillis)
                runCurrent()
            }

            // Phase 1: create the account so the name exists in the repo.
            val sid1 = SessionId(1L)
            runCurrent()
            inbound.send(InboundEvent.Connected(sid1))
            inbound.send(
                InboundEvent.GmcpReceived(
                    sid1,
                    "Core.Supports.Set",
                    """["Session.AuthResult 1","Login.Prompt 1"]""",
                ),
            )
            inbound.send(InboundEvent.LineReceived(sid1, "Linnet"))
            inbound.send(InboundEvent.LineReceived(sid1, "yes"))
            inbound.send(InboundEvent.LineReceived(sid1, "correctpw"))
            inbound.send(InboundEvent.LineReceived(sid1, "1"))
            inbound.send(InboundEvent.LineReceived(sid1, "1"))
            step()
            step()
            inbound.send(InboundEvent.Disconnected(sid1, "test"))
            step()
            outbound.drainAll()

            // Phase 2: present a bogus token with the real character name.
            val sid2 = SessionId(2L)
            inbound.send(InboundEvent.Connected(sid2))
            inbound.send(
                InboundEvent.GmcpReceived(
                    sid2,
                    "Core.Supports.Set",
                    """["Session.AuthResult 1","Login.Prompt 1"]""",
                ),
            )
            inbound.send(
                InboundEvent.GmcpReceived(
                    sid2,
                    "Session.Authenticate",
                    """{"token":"not-a-real-token","name":"Linnet"}""",
                ),
            )
            step()
            step()

            val events = outbound.drainAll()
            assertTrue(
                events.any {
                    it is OutboundEvent.GmcpData &&
                        it.sessionId == sid2 &&
                        it.gmcpPackage == "Session.AuthResult" &&
                        it.jsonData.contains("\"success\":false")
                },
                "Expected Session.AuthResult failure on bad token; got=$events",
            )
            // The key behavioural guarantee: after a bad token we jump straight
            // to the password prompt for the same character, bypassing the name
            // step that used to loop the picker and confuse mobile users.
            val promptPayloads =
                events
                    .filterIsInstance<OutboundEvent.GmcpData>()
                    .filter { it.sessionId == sid2 && it.gmcpPackage == "Login.Prompt" }
                    .map { it.jsonData }
            assertTrue(
                promptPayloads.any { it.contains("\"state\":\"password\"") && it.contains("\"name\":\"Linnet\"") },
                "Expected password prompt for Linnet after failed auto-relog; got=$promptPayloads",
            )

            engineJob.cancel()
            inbound.close()
            outbound.close()
        }
}
