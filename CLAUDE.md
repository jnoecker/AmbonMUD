# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> The full engineering playbook is in `AGENTS.md`. This file summarizes the most important points for quick orientation.

## Cloud/Remote Mode

In cloud/remote mode, GitHub API tools (gh CLI, curl to GitHub API) do not work for creating PRs or interacting with GitHub issues. Instead, just push the code to the branch and notify the user — they will create the PR themselves.

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
    │  CommandRouter, CombatSystem, AbilitySystem, MobSystem, RegenSystem,
    │  Scheduler, PlayerProgression, GmcpEmitter, Registries
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
| Game engine + subsystems | `src/main/kotlin/dev/ambon/engine/` (includes `AbilitySystem`, `GmcpEmitter`) |
| Command parsing + routing | `src/main/kotlin/dev/ambon/engine/commands/CommandParser.kt`, `CommandRouter.kt` |
| Zone sharding + instancing | `src/main/kotlin/dev/ambon/sharding/` (ZoneRegistry, InterEngineBus, HandoffManager, InstanceSelector) |
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

This project uses **ktlint 1.5.0** with `kotlin.code.style=official`. Rule overrides live in the root `.editorconfig`. The following rules are **disabled** to reduce formatting friction (see `.editorconfig` for rationale):

- `multiline-expression-wrapping` — no forced newline after `=` for multiline RHS.
- `string-template-indent` — disabled as a dependency of the above.
- `chain-method-continuation` — short chains can stay on fewer lines.
- `function-signature` — parameters don't have to be one-per-line.

All other standard rules remain enforced. The most important ones to know:

1. **Trailing commas (REQUIRED)** — Every multiline parameter list, argument list, and collection literal must end with a trailing comma:
   ```kotlin
   class Foo(
       val bar: String,
       val baz: Int,      // ← trailing comma
   )

   doSomething(
       first = 1,
       second = 2,        // ← trailing comma
   )
   ```

2. **No wildcard imports** — Always use explicit imports, never `import foo.bar.*`.

3. **Multiline `when` entries** — Arrow and body on the same line when short; braces for multi-statement bodies.

4. **Spacing** — Single space after `if`/`for`/`when`/`while` and around operators/colons. No space before commas or inside parentheses.

5. **Blank lines** — No blank lines at the start or end of a class/function body. No blank lines inside parameter lists.

6. **No max line length enforced** — Keep lines reasonable but don't force-wrap short expressions.

7. **Multiline string templates** — Closing `"""` must be on its own line, with `.trimIndent()` on the same line.

8. **No blank line before first declaration in a class** — The first property/function starts immediately after the opening brace (or after the constructor closing parenthesis).

## Testing

Use `MutableClock` and `InMemoryPlayerRepository` for deterministic unit tests. Run `ktlintCheck test` before finalizing any change.

## Cloud / CI Environment

When running in Claude Code cloud sessions (claude.ai/code), be aware of these constraints:

- **JVM Toolchain version:** The `jvmToolchain()` in `build.gradle.kts` must match the JDK installed in the build environment. Cloud sessions provide JDK 21; the toolchain is currently set to `jvmToolchain(21)`. If it ever drifts, update it in `build.gradle.kts` — the Foojay resolver cannot auto-provision through the cloud egress proxy.
- **Egress proxy:** All outbound HTTP/HTTPS traffic goes through a proxy injected via `JAVA_TOOL_OPTIONS`. Gradle dependency resolution works through this proxy. Do not add `mavenLocal()` or assume direct internet access.
- **GitHub CLI (`gh`) is not available:** The `gh` command is not installed in cloud sessions. Use `git` commands directly for all repository operations (push, fetch, branch). Git remotes are wired through a local proxy that handles authentication automatically.
- **No hardcoded timing in tests:** Cloud environments have variable CPU scheduling latency. Never use short `delay()` calls (e.g. `delay(50)`) to synchronize with async coroutines launched on `Dispatchers.Default`. Instead, use polling loops with `withTimeout` and a generous timeout (e.g. 2 seconds), or use proper coroutine synchronization primitives (channels, `CompletableDeferred`). For negative tests (asserting nothing arrives), use at least `delay(200)`.
- **First build is slow:** The Gradle wrapper downloads the distribution and all dependencies on first run. Subsequent builds use the cached daemon.

## Known Quirks

- **Compiler warnings in tests:** Several test files produce "No cast needed" warnings (e.g. `InterEngineMessageHandlingTest.kt`, `CrossEngineCommandsTest.kt`). These are harmless and do not affect test results.
