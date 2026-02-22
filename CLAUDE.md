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

## Testing

Use `MutableClock` and `InMemoryPlayerRepository` for deterministic unit tests. Run `ktlintCheck test` before finalizing any change.

## Known Quirks

None currently.
