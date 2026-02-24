# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> The full engineering playbook is in `AGENTS.md`. This file summarizes the most important points for quick orientation.

## Commands

```bash
./gradlew run            # Start server (telnet :4000, web :8080)
./gradlew demo           # Start server + auto-launch browser demo
./gradlew test           # Run full test suite
./gradlew ktlintCheck    # Lint (Kotlin official style)
./gradlew ktlintCheck test  # CI parity check
```

Run a single test class:
```bash
./gradlew test --tests "dev.ambon.engine.commands.CommandParserTest"
```

Override any config value at runtime with `-Pconfig.<key>=<value>`:
```bash
./gradlew run -Pconfig.ambonMUD.logging.level=DEBUG
./gradlew run -Pconfig.ambonMUD.logging.packageLevels.dev.ambon.transport=DEBUG
./gradlew run -Pconfig.ambonMUD.server.telnetPort=5000
./gradlew run -Pconfig.ambonMUD.persistence.backend=POSTGRES  # connection defaults match docker compose
```

On Windows use `.\gradlew.bat` instead of `./gradlew`.

## Architecture

AmbonMUD supports three deployment modes (set via `ambonMUD.mode`):
- **`STANDALONE`** (default): single-process, all components in-process.
- **`ENGINE`**: GameEngine + persistence + gRPC server; gateways connect remotely.
- **`GATEWAY`**: transports + gRPC client; game logic runs on a remote engine.

The core design separates gameplay from transport:

```
Transports (telnet / WebSocket)
    │  decode raw I/O into InboundEvent, render OutboundEvent
    ▼
InboundBus / OutboundBus  (interface layer; Local* impls in single-process mode)
    │                      (Redis* impls for multi-process pub/sub)
    │                      (Grpc* impls for gateway ↔ engine gRPC streaming)
    ▼
GameEngine  (single-threaded coroutine dispatcher, 100ms tick)
    │  CommandRouter, CombatSystem, MobSystem, RegenSystem,
    │  Scheduler, PlayerProgression, Registries
    ▼
OutboundRouter  (per-session queues, backpressure, prompt coalescing)
    │  AnsiRenderer / PlainRenderer
    ▼
Sessions
```

**Critical contracts:**
- Engine communicates only via `InboundEvent` / `OutboundEvent` — no transport code in engine, no gameplay code in transport.
- `GameEngine` is single-threaded. Never call blocking I/O inside engine systems. Use the injected `Clock` instead of wall-clock calls.
- `RoomId` must be namespaced: `<zone>:<room>`.
- Player name: 2–16 chars, alnum/underscore, cannot start with a digit.
- Password: non-blank, max 72 chars (BCrypt limit).
- YAML player files use atomic writes — preserve this in any persistence changes.
- Persistence flows through the full chain: `WriteCoalescingPlayerRepository` → `RedisCachingPlayerRepository` (if enabled) → `YamlPlayerRepository` or `PostgresPlayerRepository` (selected via `ambonMUD.persistence.backend`).

## Key Locations

| What | Where |
|------|-------|
| Entry point / wiring | `src/main/kotlin/dev/ambon/Main.kt`, `MudServer.kt` |
| Config schema + defaults | `src/main/kotlin/dev/ambon/config/AppConfig.kt`, `src/main/resources/application.yaml` |
| Game engine + subsystems | `src/main/kotlin/dev/ambon/engine/` |
| Command parsing + routing | `src/main/kotlin/dev/ambon/engine/commands/CommandParser.kt`, `CommandRouter.kt` |
| Event bus interfaces + impls | `src/main/kotlin/dev/ambon/bus/` (Local*, Redis*, Grpc*) |
| gRPC server + engine-mode root | `src/main/kotlin/dev/ambon/grpc/` |
| Gateway-mode composition root | `src/main/kotlin/dev/ambon/gateway/GatewayServer.kt` |
| Proto definitions | `src/main/proto/ambonmud/v1/` |
| Redis connection + JSON support | `src/main/kotlin/dev/ambon/redis/` |
| Session ID allocation | `src/main/kotlin/dev/ambon/session/` |
| Metrics (Micrometer/Prometheus) | `src/main/kotlin/dev/ambon/metrics/` |
| Transport adapters | `src/main/kotlin/dev/ambon/transport/` |
| World YAML content | `src/main/resources/world/` |
| World loader + validation | `src/main/kotlin/dev/ambon/domain/world/load/WorldLoader.kt` |
| World YAML format spec | `docs/world-zone-yaml-spec.md` |
| Persistence (YAML + Postgres) | `src/main/kotlin/dev/ambon/persistence/` |
| Flyway migrations | `src/main/resources/db/migration/` |
| Tests | `src/test/kotlin/` (fixtures: `src/test/resources/world/`) |
| Web demo client (static) | `src/main/resources/web/` |
| Runtime player saves | `data/players/` (git-ignored, do not commit) |

## Change Playbooks (summary)

- **New command:** parse in `CommandParser.kt` → implement in `CommandRouter.kt` → add tests.
- **Staff command:** add to `CommandParser.kt`; gate with `playerState.isStaff` check in `CommandRouter.kt`.
- **Combat/mob/item:** edit `CombatSystem`, `MobSystem`, `MobRegistry`, `ItemRegistry`; preserve `max*PerTick` caps.
- **World content only:** edit YAML in `src/main/resources/world/`; no code change needed.
- **Config:** update `AppConfig.kt` and `application.yaml` together; keep `validated()` strict.
- **Persistence:** work through the `PlayerRepository` interface; maintain case-insensitive lookup and atomic writes (YAML) or unique-index enforcement (Postgres). The chain is `WriteCoalescing → RedisCache → Yaml/Postgres` — changes to `PlayerRecord` must survive all three layers. When adding columns to the Postgres backend, add a new Flyway migration in `src/main/resources/db/migration/` and update `PlayersTable.kt`.
- **Bus/Redis/gRPC:** `InboundBus`/`OutboundBus` are the engine boundaries; do not pass raw channels to engine code. Redis and gRPC variants are optional wrappers — always test with both `LocalInboundBus` and the mock bus in unit tests. When adding new event variants, update both the Redis bus envelope and the proto definitions + `ProtoMapper`.

## Kotlin Style (ktlint)

This project uses **ktlint 1.5.0** with `kotlin.code.style=official` (no `.editorconfig`). All defaults apply. The most commonly violated rules when writing new code:

1. **Trailing commas (REQUIRED)** — Every multiline parameter list, argument list, and collection literal must end with a trailing comma:
   ```kotlin
   // Function/constructor parameters (multiline)
   class Foo(
       val bar: String,
       val baz: Int,      // ← trailing comma
   )

   // Function call arguments (multiline)
   doSomething(
       first = 1,
       second = 2,        // ← trailing comma
   )

   // Single-parameter data classes still use trailing comma when multiline
   data class Move(
       val dir: Direction, // ← trailing comma
   )
   ```

2. **No wildcard imports** — Always use explicit imports, never `import foo.bar.*`.

3. **Multiline function signatures** — When a function signature doesn't fit on one line, put each parameter on its own line with a trailing comma:
   ```kotlin
   fun doSomething(
       param1: String,
       param2: Int,
   ): ReturnType {
   ```

4. **Multiline `when` entries** — Arrow and body on the same line when short; braces for multi-statement bodies.

5. **Spacing** — Single space after `if`/`for`/`when`/`while` and around operators/colons. No space before commas or inside parentheses.

6. **Blank lines** — No blank lines at the start or end of a class/function body. No blank lines inside parameter lists.

7. **Chain calls** — When wrapping chained calls, the `.` goes on the new line:
   ```kotlin
   val result =
       list
           .filter { it > 0 }
           .map { it * 2 }
   ```

8. **No max line length enforced** — No `.editorconfig` sets `max_line_length`, so it defaults to unlimited. Keep lines reasonable but don't force-wrap short expressions.

9. **Multiline string templates** — Closing `"""` must be on its own line, with `.trimIndent()` on the same line.

10. **No blank line before first declaration in a class** — The first property/function starts immediately after the opening brace (or after the constructor closing parenthesis).

## Testing

Use `MutableClock` and `InMemoryPlayerRepository` for deterministic unit tests. Run `ktlintCheck test` before finalizing any change.

## Known Quirks

- **JVM Toolchain version:** The `jvmToolchain()` in `build.gradle.kts` must match the JDK installed in the build environment. Cloud/CI environments typically provide JDK 21. If the build fails with `Cannot find a Java installation … matching: {languageVersion=17}` (and the Foojay toolchain resolver cannot auto-provision due to network/proxy restrictions), update `jvmToolchain(17)` → `jvmToolchain(21)` in `build.gradle.kts`.
