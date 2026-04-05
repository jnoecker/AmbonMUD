# Coding Conventions

**Analysis Date:** 2026-04-05

## Naming Patterns

**Files:**
- PascalCase for classes: `PlayerRegistry.kt`, `CombatSystem.kt`, `CommandParser.kt`
- camelCase for function/variable files: `commandRouter.kt` (unlikely; most files are class-based)
- Sealed interfaces and data classes grouped in same file: `Command.kt` contains sealed `Command` hierarchy with 30+ variants
- Test files: `{ClassName}Test.kt` for unit tests, `{ClassName}IntegrationTest.kt` for integration tests
- Fixtures/helpers: `{SystemName}TestFixture.kt`, `{Domain}Harness.kt`

**Functions:**
- camelCase for all functions: `startCombat()`, `handleKill()`, `drainAll()`
- Private functions prefixed with `private suspend`: `private suspend fun handleMove()`
- Suspend functions explicitly marked: `suspend fun startCombat()`
- Extension functions declared at package level or as member extensions
- Handler methods follow pattern: `handle{Command}` (e.g., `handleKill`, `handleFlee`, `handleCast`)

**Variables:**
- camelCase for all variables: `sessionId`, `playerTarget`, `mobRegistry`
- Constants (module-level): UPPER_SNAKE_CASE: `AUTH_TOKEN_EXPIRY_DAYS`, `THREAT_CLEANUP_INTERVAL_MS`
- Private mutable collections: `playerTarget`, `activeMobs`, `pvpTarget` (not prefixed with underscore)
- Boolean properties: descriptive names starting with `is` or `has`: `isStaff`, `isInCombat()`, `hasPrompt()`
- Sealed interface variants: PascalCase: `LoginResult.Ok`, `LoginResult.InvalidName`
- Data class fields: camelCase matching property semantics: `nextTickAtMs`, `onMobRemoved`

**Types:**
- Type aliases avoided; prefer explicit types: `SessionId`, `RoomId`, `MobId` (value classes in `domain.ids`)
- Sealed interfaces for result types: `LoginResult`, `CreateResult`, `PhaseResult`
- Configuration data classes: `{System}Config` (e.g., `CombatSystemConfig`)
- Callback data classes: `{System}Callbacks` (e.g., `CombatSystemCallbacks`)
- Test helpers: `Test{Domain}` or `{System}TestFixture`

## Code Style

**Formatting:**
- Tool: **ktlint 1.5.0** with `kotlin.code.style=official`
- Editorconfig file: `.editorconfig` (4 rules disabled for flexibility)

**Disabled ktlint Rules (by project choice):**
- `multiline-expression-wrapping` — no forced newline after `=` for multiline RHS; allow author judgment
- `string-template-indent` — corollary of above; disabled together per ktlint #2288
- `chain-method-continuation` — short 2-3 element chains can stay on fewer lines
- `function-signature` — parameters don't have to be one-per-line for single-param functions

**Enforced ktlint Rules:**
- Trailing commas (REQUIRED): Every multiline parameter list, argument list, and collection literal must end with a trailing comma
  ```kotlin
  class Foo(
      val bar: String,
      val baz: Int,      // <- required
  )
  ```
- No wildcard imports: Always `import foo.Bar` not `import foo.*`
- Single space after control keywords: `if (condition)`, `for (item in list)`, `when (x)`
- Single space around operators and colons; no space before commas
- No blank lines at start/end of class or function bodies
- No blank line before first declaration in a class

**Line Length:**
- No hard max enforced; keep reasonable but don't force-wrap short expressions
- Long function signatures may extend beyond 100 chars; use judgment

**Multiline Collections:**
```kotlin
data class CombatSystemConfig(
    val tickMillis: Long = 1_000L,
    val minDamage: Int = 1,
    val maxDamage: Int = 4,  // <- trailing comma required
)
```

**Multiline String Literals:**
- Closing `"""` on its own line, trimmed:
  ```kotlin
  val msg = """
      This is a
      multiline string
  """.trimIndent()
  ```

## Linting & Formatting

**Linting:**
- Tool: ktlint 1.5.0
- Config: `.editorconfig` in repo root
- Run before every PR: `./gradlew ktlintCheck`
- Auto-format: `./gradlew ktlintFormat`

**Pre-commit Hook:**
- Hook enforces `ktlintCheck` automatically (cannot commit if it fails)
- Do not use `--no-verify` to skip hooks

**CI Enforcement:**
- `.github/workflows/ci.yml` runs `./gradlew ktlintCheck` on all PRs
- Must pass before merge

## Import Organization

**Order (most restrictive to least):**
1. Kotlin stdlib imports: `import kotlin.`
2. Java stdlib imports: `import java.`
3. Third-party libraries (alphabetical by package): `import io.ktor`, `import org.junit`, `import kotlinx.coroutines`
4. Project imports (alphabetical by package): `import dev.ambon.`

**Path Aliases:**
- No aliases are used in this codebase
- All imports are fully qualified explicit imports

**Specific to Engine Tests:**
- Test helper imports at top: `import dev.ambon.test.{Helper}`
- Event bus imports: `import dev.ambon.bus.{Local|Redis|Grpc}*Bus`
- System imports: `import dev.ambon.engine.{System}`
- Command imports: `import dev.ambon.engine.commands.{CommandParser|CommandRouter}`

## Error Handling

**Patterns Observed:**

**Sealed Interfaces for Results:**
Result types are sealed interfaces, not exceptions:
```kotlin
sealed interface LoginResult {
    data object Ok : LoginResult
    data object InvalidName : LoginResult
    data object InvalidPassword : LoginResult
    data object WrongPassword : LoginResult
    data class Takeover(val oldSessionId: SessionId) : LoginResult
}
```

Usage in code: check the result variant, don't throw exceptions for expected failures. See `PlayerRegistry.login()`.

**Nullable Returns for Optional Errors:**
Many functions return `String?` for error messages:
```kotlin
suspend fun startCombat(
    sessionId: SessionId,
    keywordRaw: String,
): String? {
    // Returns null on success, String (error message) on failure
    if (keyword.isEmpty()) return "Kill what?"
    if (existingTarget != null) return "You are already fighting $mobName."
    // ... success case
    return null
}
```

**Validation at Entry Points:**
- `require()` for contract violations (fail-fast for preconditions)
- `check()` for state invariants
- `error()` for unrecoverable failures (rarely used)

Example from `CombatSystem.startCombat()`:
```kotlin
val player = players.get(sessionId) ?: return ERR_NOT_CONNECTED
if (keyword.isEmpty()) return "Kill what?"
```

**Error Message Constants:**
Module-level constants for reusable errors:
```kotlin
private const val ERR_NOT_CONNECTED = "You are not connected."
```

**OutboundEvent Variants for User Feedback:**
- `OutboundEvent.SendError` — errors the player caused (wrong syntax, not enough mana, etc.)
- `OutboundEvent.SendInfo` — informational messages (status, prompts, system messages)
- `OutboundEvent.SendText` — regular narrative/room text

Usage pattern in handlers:
```kotlin
outbound.send(OutboundEvent.SendError(sessionId, "Error message here."))
outbound.send(OutboundEvent.SendInfo(sessionId, "Something happened."))
outbound.send(OutboundEvent.SendText(sessionId, "Room narrative."))
outbound.send(OutboundEvent.SendPrompt(sessionId))  // sent after each command
```

**Silent Failures (No User Feedback):**
Functions like `tick()` in game systems silently do nothing if preconditions aren't met:
```kotlin
fun tick() {
    // No loop if nothing to do
    activeMobs.forEach { (mobId, state) ->
        // Only tick if enough time has passed
        if (clock.millis() < state.nextTickAtMs) return@forEach
        // ... actual tick logic
    }
}
```

## Logging

**Framework:** `io.github.oshai.kotlin-logging` (KotlinLogging)

**Pattern:**
```kotlin
private val log = KotlinLogging.logger {}
```

**Usage:**
- `log.debug { "message" }` — lazy evaluation (message only computed if debug enabled)
- `log.info { "message" }` — informational
- `log.warn { "message" }` — warnings
- `log.error { "message" }` — errors (not recoverable, but not crash)

**No Logging in Engine Systems:**
Engine code generally avoids logging to reduce noise during rapid tick loops. Critical events are emitted via callbacks (`onCombatEvent`, `onXpGained`, `onPlayerDeath`) which higher layers can decide to log.

**No Console.log Equivalents:**
The engine and persistence layers do not log to stdout. Use outbound events and test assertions for verification.

## Comments

**When to Comment:**
- Explain the "why" not the "what" (code should be readable enough to explain the what)
- Clarify non-obvious algorithm choices: `// Threat table persists across tick cycles to prevent target-switching`
- Mark temporary workarounds: `// TODO: replace with native X when available` (none currently in codebase)
- Document public API contracts (JSDoc-style below)

**JSDoc/KDoc Style:**
```kotlin
/**
 * Describes what this function does in 1-2 sentences.
 *
 * @param sessionId The player's session ID.
 * @param keyword The search term (case-insensitive).
 * @return The matching mob state, or null if not found in the room.
 */
fun findMobInRoom(sessionId: SessionId, keyword: String): MobState?
```

**KDoc Usage:**
- Used on public functions in API-facing modules (systems, registries, repositories)
- Sparse in handler code (handler purpose is obvious from context)
- Not required on private functions unless algorithm is non-obvious

**Section Comments (ASCII art borders):**
Logical code sections separated with:
```kotlin
// ── Event assertion helpers ─────────────────────────────────────────────────

fun List<OutboundEvent>.textMessages(): List<String> = ...
```

## Function Design

**Size Guidelines:**
- Handlers: 10-50 lines (dispatch logic, error checks, outbound sends)
- System methods: 20-100 lines (complex game logic, state management)
- Utility functions: 5-20 lines
- Not enforced by linter; judgment call based on readability

**Parameters:**
- Max 5 parameters before considering a config data class
- Required parameters first, optional/default parameters last
- Use named arguments for clarity: `startCombat(sessionId = sid, keyword = "rat")`

**Return Values:**
- Prefer explicit types over inference when signature is public
- Return sealed interfaces for operation results (not exceptions)
- Return `String?` (null on success, error message on failure) for optional errors
- Use callbacks for events that handlers and systems emit: `var onCombatEvent: suspend (SessionId, CombatEvent) -> Unit`

## Module Design

**Exports:**
- Each system (`CombatSystem`, `AbilitySystem`, etc.) is a class with public methods
- No barrel exports (no `index.kt` files)
- Handlers register themselves via `override fun register(router: CommandRouter)`

**Sealing Strategies:**
- `sealed interface` for command types: `Command` hierarchy
- `sealed interface` for results: `LoginResult`, `CreateResult`, `PhaseResult`
- `sealed interface` for events: `InboundEvent`, `OutboundEvent`
- Not used for config or domain models (open data classes)

**Companion Objects:**
- Rarely used; prefer top-level functions or object singletons
- `TestPasswordHasher : PasswordHasher` in test fixtures is an `object` not a companion

**Value Classes (Inline):**
```kotlin
@JvmInline
value class SessionId(val value: Long)

@JvmInline
value class RoomId(val value: String)
```
All domain IDs are value classes to prevent accidental mixing of types while avoiding allocation overhead.

## Data Classes

**Convention:**
- Used for domain models, config, and test data
- Copy semantics preferred: `record.copy(field = newValue)`
- Destructuring used in tests: `val (sessionId, event) = pair`
- Equals/hashCode auto-generated (standard data class behavior)

**Example:**
```kotlin
data class CombatSystemConfig(
    val tickMillis: Long = 1_000L,
    val minDamage: Int = 1,
    val maxDamage: Int = 4,
    val healingThreatMultiplier: Double = 0.5,
    val groupXpBonusPerMember: Double = 0.10,
    val detailedFeedbackEnabled: Boolean = false,
    val bindings: StatBindingsConfig = StatBindingsConfig(),
)
```

## Kotlin-Specific Patterns

**Coroutines:**
- All I/O is `suspend` (no blocking in engine thread)
- Game engine runs on single-thread dispatcher `engineDispatcher`
- Tests use `runTest` with `ExperimentalCoroutinesApi`
- Mutable state is safe on single thread (no locks needed)

**Extension Functions:**
Used for test helpers and OutboundBus convenience:
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

**Reified Type Parameters:**
Used in router registration:
```kotlin
inline fun <reified T : Command> CommandRouter.on(
    noinline handler: suspend (SessionId, T) -> Unit,
) {
    on(T::class, handler)
}
```

**Scope Functions:**
- `let` for null-coalescing: `player?.let { handlePlayer(it) }`
- `apply` for builder patterns (rare in this codebase)
- `also` for side effects in chains (rare)

---

*Convention analysis: 2026-04-05*
