# Testing Patterns

**Analysis Date:** 2026-04-05

## Test Framework

**Runner:**
- JUnit Jupiter 6.0.3 (JUnit 5)
- Config: `src/test/resources/junit-platform.properties`
- Test timeout (per-test): **30 seconds** (global per-test limit prevents hangs)
- Test suite timeout: **5 minutes** (Gradle backstop)

**Assertion Library:**
- `org.junit.jupiter.api.Assertions.*` (standard JUnit)
- Common: `assertEquals()`, `assertTrue()`, `assertFalse()`, `assertNull()`, `assertNotNull()`
- Custom failure messages in second arg: `assertTrue(cond, "Expected X, got Y")`

**Run Commands:**
```bash
./gradlew test                    # Run all unit tests (excludes integration)
./gradlew test --tests "*Combat*" # Run tests matching pattern
./gradlew test --tests "dev.ambon.engine.commands.CommandParserTest"  # Specific class
./gradlew integrationTest         # Run integration-tagged tests
./gradlew ktlintCheck test integrationTest  # Full CI check (lint + unit + integration)
```

**Test Tagging:**
- Unit tests: no tag (default)
- Integration tests: `@Tag("integration")` annotation
- Gradle excludes/includes via `useJUnitPlatform { excludeTags(...) }`

## Test File Organization

**Location:**
- Mirror source structure: `src/main/kotlin/dev/ambon/X/Y.kt` → `src/test/kotlin/dev/ambon/X/YTest.kt`
- Test utilities: `src/test/kotlin/dev/ambon/test/` (shared fixtures, helpers, constants)
- Test resources (YAML): `src/test/resources/world/` (test world files, bad YAML for error testing)

**Naming:**
- Unit test: `{ClassName}Test.kt`
- Integration test: `{ClassName}IntegrationTest.kt` (rare; most tests are unit with fixtures)
- Test for commands: `{CommandName}Test.kt` or in `CommandParserTest.kt` / `CommandRouterTest.kt`

**Structure - Example:**
```
src/test/kotlin/dev/ambon/
├── engine/
│   ├── CombatSystemTest.kt
│   ├── abilities/
│   │   └── AbilitySystemTest.kt
│   └── commands/
│       ├── CommandParserTest.kt
│       ├── CommandRouterTest.kt
│       └── CommandRouterAdminTest.kt
├── persistence/
│   ├── YamlPlayerRepositoryTest.kt
│   └── PostgresPlayerRepositoryTest.kt
└── test/
    ├── MutableClock.kt
    ├── EngineTestHelpers.kt
    ├── CombatTestFixture.kt
    └── TestFixtures.kt
```

## Test Structure

**Standard Pattern with Fixtures:**
```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class CombatSystemTest {
    @Test
    fun `combat tick damages both sides`() =
        runTest {
            val fixture = CombatTestFixture()  // Set up test state
            val mob = MobState(MobId("demo:rat"), "a rat", fixture.roomId, hp = 10, maxHp = 10)
            fixture.mobs.upsert(mob)

            val combat = fixture.buildCombat(rng = Random(1))

            val sid = SessionId(1L)
            fixture.players.loginOrFail(sid, "Player1")  // Login helper

            // Act
            val err = combat.startCombat(sid, "rat")
            assertNull(err)

            // Tick combat
            fixture.tickCombat(combat)

            // Assert
            val player = fixture.players.get(sid)
            assertNotNull(player)
            assertTrue(player!!.hp < player.maxHp, "Expected player to take damage")
        }
}
```

**Standard Pattern without Fixtures (Simple Unit Test):**
```kotlin
class CommandParserTest {
    @Test
    fun `parses movement aliases`() {
        assertEquals(Command.Move(Direction.NORTH), CommandParser.parse("n"))
        assertEquals(Command.Move(Direction.NORTH), CommandParser.parse("north"))
        assertEquals(Command.Move(Direction.SOUTH), CommandParser.parse("s"))
    }

    @Test
    fun `parser returns Invalid for incomplete tell`() {
        assertTrue(CommandParser.parse("tell Bob") is Command.Invalid)
    }
}
```

**Async Test Pattern (Coroutine Suspension):**
```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class GameEngineLoginFlowTest {
    @Test
    fun `connect shows login screen before name prompt`() =
        runTest {
            val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
            val h = GameEngineHarness.start(scope = this, clock = clock)

            val sid = SessionId(99L)
            runCurrent()  // Let engine process setup

            h.inbound.send(InboundEvent.Connected(sid))
            advanceTimeBy(h.tickMillis)  // Advance virtual time
            runCurrent()                  // Let engine process events

            val outs = h.outbound.drainAll()  // Collect all outbound events
            
            assertTrue(outs.any { it is OutboundEvent.ShowLoginScreen && it.sessionId == sid })
            h.close()
        }
}
```

**Test Organization Within Class:**
1. Setup helpers (builders, constants): top
2. Test methods: grouped by feature/scenario
3. Assertion helpers: bottom
4. Inner test data classes: as needed

## Test Patterns

**Setup Pattern - Fixtures:**
Three levels of test setup:

1. **Minimal per-test** — `CombatTestFixture()` creates: `InMemoryPlayerRepository`, `LocalOutboundBus`, `ItemRegistry`, `MobRegistry`, `MutableClock`
2. **System builder** — `fixture.buildCombat(...)` with configurable parameters
3. **Reusable components** — Test fixtures expose components so tests can wire custom behaviors

See `src/test/kotlin/dev/ambon/test/CombatTestFixture.kt` (90 lines) and `TestFixtures.kt` (250+ lines) for the pattern.

**Setup Pattern - Mutable Clock:**
```kotlin
val clock = MutableClock(0L)  // Initialize at epoch
// Later in test:
clock.advance(1_000)  // Add 1 second
clock.set(5_000)      // Jump to 5 seconds
```

Never use `System.currentTimeMillis()` in production code or tests. The engine receives a `Clock` interface (injected), and tests substitute `MutableClock` for determinism.

**Setup Pattern - Test Players:**
```kotlin
val fixture = CombatTestFixture()
val sid = SessionId(1L)
fixture.players.loginOrFail(sid, "PlayerName")  // Create + login in one call

// Later:
val player = fixture.players.get(sid)!!  // Get current player state
```

Extension function `loginOrFail()` is in `EngineTestHelpers.kt`:
```kotlin
suspend fun PlayerRegistry.loginOrFail(
    sessionId: SessionId,
    name: String,
    password: String = "password",
) {
    val res = login(sessionId, name, password)
    require(res == LoginResult.Ok) { "Login failed: $res" }
}
```

**Assertion Pattern - Event Draining:**
```kotlin
val fixture = CombatTestFixture()
// ... do stuff ...
val outs = fixture.outbound.drainAll()  // Collect all events since last drain

// Assert on events:
assertTrue(outs.any { it is OutboundEvent.SendError && it.text.contains("Kill what?") })
assertTrue(outs.any { it is OutboundEvent.SendPrompt })
```

Extension function in `EngineTestHelpers.kt`:
```kotlin
fun LocalOutboundBus.drainAll(): List<OutboundEvent> {
    val out = mutableListOf<OutboundEvent>()
    while (true) {
        val ev = tryReceive().getOrNull() ?: break
        out += ev
    }
    return out
}
```

**Assertion Pattern - Event Filtering:**
```kotlin
val outs: List<OutboundEvent> = ...
val textMsgs = outs.textMessages(sessionId)  // Filter text events for a session
val errorMsgs = outs.errorMessages(sessionId)  // Filter error events
val hasPompt = outs.hasPrompt(sessionId)  // Check for prompt

// Helper functions in EngineTestHelpers.kt:
fun List<OutboundEvent>.textMessages(sessionId: SessionId?): List<String>
fun List<OutboundEvent>.errorMessages(sessionId: SessionId?): List<String>
fun List<OutboundEvent>.hasPrompt(sessionId: SessionId?): Boolean
```

## Mocking

**Framework:** No external mocking library; use test doubles (fake implementations, stubs, spies).

**Patterns:**

**InMemoryPlayerRepository (Test Double):**
Located at `src/test/kotlin/dev/ambon/persistence/InMemoryPlayerRepository.kt`:
```kotlin
class InMemoryPlayerRepository : PlayerRepository {
    private val players = mutableMapOf<PlayerId, PlayerRecord>()
    override suspend fun findByName(name: String): PlayerRecord? = 
        players.values.find { it.name.equals(name, ignoreCase = true) }
    override suspend fun save(record: PlayerRecord) { players[record.id] = record }
    fun clear() { players.clear() }  // For test isolation
}
```

Usage:
```kotlin
@BeforeEach
fun setup() {
    repo.clear()  // Fresh state before each test
}

@Test
fun `login creates session`() = runTest {
    val repo = InMemoryPlayerRepository()
    repo.create(PlayerCreationRequest("Alice", startRoom, now, hash))
    val result = playerRegistry.login(sessionId, "Alice", "password")
    assertEquals(LoginResult.Ok, result)
}
```

**LocalOutboundBus / LocalInboundBus (Fake):**
In-memory event buses (no Redis, no gRPC) used in all system tests:
```kotlin
val inbound = LocalInboundBus()
val outbound = LocalOutboundBus()

inbound.send(InboundEvent.Connected(sessionId))
val events = outbound.drainAll()
```

**Callback Lambdas (Spy):**
Systems accept callbacks; tests provide lambdas to track calls:
```kotlin
val xpGainCalls = mutableListOf<Pair<SessionId, Long>>()
combat.onXpGained = { sid, xp, _ -> xpGainCalls.add(sid to xp) }

combat.startCombat(sid, "rat")
fixture.tickCombat(combat)

assertTrue(xpGainCalls.isNotEmpty(), "Expected XP gain event")
```

**FakePublisher / FakeSubscriberSetup (Redis Tests):**
Located at `src/test/kotlin/dev/ambon/bus/RedisBusTestFixtures.kt`:
```kotlin
val fakePublisher = FakePublisher()
val busUnderTest = RedisInboundBus(publisher = fakePublisher)

fakePublisher.publish("channel", data)
val result = busUnderTest.tryReceive()
assertEquals(event, result.getOrNull())
```

**No Mocking of Systems:**
Game systems are not mocked; tests use real instances with fake dependencies. This catches integration bugs.

**What to Mock:**
- I/O (HTTP, Redis, database) → Use test doubles or in-memory fakes
- Time → Use `MutableClock` instead of `System.currentTimeMillis()`
- Randomness → Use deterministic `Random(seed)` for reproducibility

**What NOT to Mock:**
- Game logic (CombatSystem, AbilitySystem, etc.) → Use real implementations
- Registries (ItemRegistry, MobRegistry, etc.) → Use real implementations
- PlayerRegistry → Use real implementation with `InMemoryPlayerRepository` backend

## Fixtures and Factories

**Test Data Creation:**

**Test Constants:**
```kotlin
val TEST_SESSION_ID = SessionId(1L)
val TEST_ROOM_ID = RoomId("zone:room")
```
Located in `src/test/kotlin/dev/ambon/test/TestFixtures.kt`.

**Test Player Factory:**
```kotlin
suspend fun PlayerRepository.createTestPlayer(
    name: String,
    startRoom: RoomId,
    password: String = "password",
    ansiEnabled: Boolean = true,
    level: Int = 1,
): PlayerRecord
```
Extension function in `TestFixtures.kt` creates a player in the repo and returns the record.

**Test Builder Functions:**
```kotlin
fun buildTestPlayerRegistry(
    startRoom: RoomId,
    repo: PlayerRepository = InMemoryPlayerRepository(),
    items: ItemRegistry = ItemRegistry(),
    clock: Clock = Clock.systemUTC(),
    progression: PlayerProgression = PlayerProgression(),
    classRegistry: PlayerClassRegistry? = null,
    ...
): PlayerRegistry
```

**Fixture Classes (Composition Root):**
Three main fixtures compose related systems and utilities:

1. **CombatTestFixture** (`src/test/kotlin/dev/ambon/test/CombatTestFixture.kt`)
   - Provides: `players`, `mobs`, `items`, `outbound`, `clock`
   - Builder: `buildCombat(...)` with 15+ optional parameters
   - Helper: `tickCombat(combat, tickMillis)` advances clock and ticks combat
   - Helper: `equipItem(sessionId, slot, item)` for equipment testing

2. **AbilityTestFixture** (`src/test/kotlin/dev/ambon/test/AbilityTestFixture.kt`)
   - Extends: `TestFixtureBase`
   - Provides: `players`, `mobs`, `items`, `outbound`, `clock`, `combat`, `statusEffects`
   - Builder: `buildAbilitySystem(...)` with registry and status effects
   - Status effect helper: `applyEffect(sessionId, effectId)`

3. **StatusEffectTestFixture** (`src/test/kotlin/dev/ambon/test/StatusEffectTestFixture.kt`)
   - Minimal fixture for status effect system testing
   - Provides: `players`, `outbound`, `clock`
   - Builder: `buildStatusEffectSystem(...)`

**Location:**
- Fixtures in: `src/test/kotlin/dev/ambon/test/{Domain}TestFixture.kt`
- Shared base: `src/test/kotlin/dev/ambon/test/TestFixtureBase.kt`
- Factory functions: `src/test/kotlin/dev/ambon/test/TestFixtures.kt`

## Coverage

**Requirements:**
- No enforced minimum (config may exist but is not strict in CI)
- JaCoCo report generated: `./gradlew jacocoTestReport` → `build/reports/jacoco/html/`

**View Coverage:**
```bash
./gradlew jacocoTestReport
# Then open build/reports/jacoco/html/index.html
```

**Coverage Profile (Observed):**
- Engine systems: 80-95% (most paths covered)
- Command handlers: 70-90% (error paths, edge cases sometimes skipped)
- Persistence layers: 85-95% (critical path covered)
- Transport code: 40-60% (integration tests less comprehensive)

## Test Types

**Unit Tests** (90% of test suite):
- Test isolated functionality: parser, system methods, data transformations
- Use fixtures for dependencies
- Run in <100ms
- Example: `CommandParserTest`, `AbilitySystemTest`, `CombatSystemTest`

**Integration Tests** (10%, tagged with `@Tag("integration")`):
- Test interaction between systems: end-to-end login flow, multi-system commands
- Use full harness or game engine
- Run in 100-500ms
- Example: `GameEngineLoginFlowTest`, `WorldLoaderTest`, `GatewayEngineIntegrationTest`

**End-to-End Tests:**
- Not common in unit test suite (no Selenium/browser automation)
- Demo client at `./gradlew demo` for manual testing

## Common Patterns

**Async Testing:**
```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
@Test
fun `ability cast with cooldown`() =
    runTest {
        val fixture = AbilityTestFixture()
        fixture.players.loginOrFail(TEST_SESSION_ID, "Caster")
        
        val abilitySystem = fixture.buildAbilitySystem()
        abilitySystem.cast(TEST_SESSION_ID, "heal", "self")
        
        fixture.clock.advance(2_000)  // Advance past cooldown
        runCurrent()                   // Let coroutines settle
        
        val err = abilitySystem.cast(TEST_SESSION_ID, "heal", "self")
        assertNull(err)  // Should succeed now
    }
```

Key functions in async tests:
- `runTest { ... }` — Coroutine test scope with virtual time
- `advanceTimeBy(ms)` — Move virtual clock forward
- `runCurrent()` — Let all pending tasks on test scheduler run to completion
- `withTimeout(ms) { ... }` — Fail if not completed within timeout

**Error Testing (Negative Cases):**
```kotlin
@Test
fun `kill without target returns error`() {
    val h = CommandRouterHarness.create()
    h.loginPlayer(TEST_SESSION_ID, "Player1")
    
    h.router.handle(TEST_SESSION_ID, Command.Kill(""))
    
    val outs = h.drain()
    assertTrue(outs.any { 
        it is OutboundEvent.SendError && it.text.contains("Kill what?") 
    })
}
```

**Event Sequence Testing:**
```kotlin
@Test
fun `login sequence shows screen then prompts`() =
    runTest {
        val h = GameEngineHarness.start(scope = this, clock = fixedClock)
        val sid = SessionId(99L)
        runCurrent()
        
        h.inbound.send(InboundEvent.Connected(sid))
        advanceTimeBy(h.tickMillis)
        runCurrent()
        
        val outs = h.outbound.drainAll()
        
        val screenIdx = outs.indexOfFirst { it is OutboundEvent.ShowLoginScreen }
        val promptIdx = outs.indexOfFirst { it is OutboundEvent.SendInfo && it.text == "Enter your name:" }
        
        assertTrue(screenIdx >= 0, "Missing login screen")
        assertTrue(promptIdx > screenIdx, "Expected prompt after screen")
    }
```

**Deterministic Randomness:**
```kotlin
@Test
fun `crit damage ranges with seed`() {
    val rng = Random(42)  // Same seed = same sequence
    val damage1 = calculateDamage(rng)
    
    val rng2 = Random(42)
    val damage2 = calculateDamage(rng2)
    
    assertEquals(damage1, damage2)  // Reproducible
}
```

## Test Organization Tips

**Grouped Test Methods:**
```kotlin
class CombatSystemTest {
    // Setup helpers and data
    private val fixture = CombatTestFixture()
    
    // Damage calculation tests
    @Test
    fun `attack bonus adds flat damage`() { ... }
    
    @Test
    fun `armor reduces damage`() { ... }
    
    // Threat tracking tests
    @Test
    fun `threat accumulates per hit`() { ... }
    
    @Test
    fun `threat decays over time`() { ... }
    
    // Death and rewards tests
    @Test
    fun `mob death awards xp`() { ... }
}
```

**Test Isolation:**
- Use `@BeforeEach` to clear shared repositories:
  ```kotlin
  @BeforeEach
  fun setup() {
      repo.clear()  // Clears InMemoryPlayerRepository
  }
  ```
- Prefer immutable test data or fresh instances per test
- Fixtures create new instances (not singletons)

**Naming Test Methods:**
- Descriptive backtick names: `` `combat tick damages both sides`() ``
- Not `testCombat()` or `test1()` — use full English descriptions
- Describes scenario, action, and expected result

## CI/CD Integration

**Automated Test Run:**
- `.github/workflows/ci.yml` runs `./gradlew ktlintCheck test integrationTest` on every PR
- JaCoCo coverage report uploaded to artifacts: `build/reports/jacoco/`
- Tests must pass before merge to `main`

**Test Result Upload:**
- Test results: `build/test-results/`
- Coverage report: `build/reports/jacoco/`
- Both uploaded as GitHub artifacts for review

---

*Testing analysis: 2026-04-05*
