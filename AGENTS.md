# AGENTS.md

## Purpose
This repository is a Kotlin MUD server ("AmbonMUD") with a tick-based event loop, telnet + WebSocket transports, YAML world loading, a small browser demo client, and a layered persistence stack with selectable YAML or PostgreSQL backends and optional Redis caching and pub/sub. It supports three deployment modes: `STANDALONE` (single-process, default), `ENGINE` (game logic + gRPC server), and `GATEWAY` (transports + gRPC client) for horizontal scaling.

Use this document as the default engineering playbook when making code or content changes.

## Environment
- JDK: 17 toolchain in Gradle (`build.gradle.kts`), CI currently runs on Java 21 (`.github/workflows/ci.yml`).
- Build tool: Gradle wrapper (`gradlew`, `gradlew.bat`).
- Kotlin style: `kotlin.code.style=official` (`gradle.properties`).

## Core Commands
- Run server (Windows): `.\gradlew.bat run`
- Run server (Unix): `./gradlew run`
- Demo (Windows): `.\gradlew.bat demo`
- Demo (Unix): `./gradlew demo`
- Lint: `.\gradlew.bat ktlintCheck`
- Tests: `.\gradlew.bat test`
- CI parity (Unix/CI): `./gradlew ktlintCheck test`
- CI parity (Windows): `.\gradlew.bat ktlintCheck test`

By default the server listens on telnet port `4000` and web port `8080` (configured in `src/main/resources/application.yaml`, printed by `src/main/kotlin/dev/ambon/Main.kt`).

## Project Map
- Bootstrap/runtime wiring: `src/main/kotlin/dev/ambon/Main.kt`, `src/main/kotlin/dev/ambon/MudServer.kt`
- Configuration: `src/main/kotlin/dev/ambon/config`, `src/main/resources/application.yaml`
- Engine and gameplay: `src/main/kotlin/dev/ambon/engine`
- Transport and protocol: `src/main/kotlin/dev/ambon/transport`
- Event bus interfaces + impls: `src/main/kotlin/dev/ambon/bus` (`InboundBus`, `OutboundBus`, `Local*Bus`, `Redis*Bus`, `Grpc*Bus`)
- Redis connection management + JSON: `src/main/kotlin/dev/ambon/redis`
- Session ID allocation + gateway lease: `src/main/kotlin/dev/ambon/session` (`AtomicSessionIdFactory`, `SnowflakeSessionIdFactory`, `GatewayIdLeaseManager`)
- Metrics (Micrometer / Prometheus): `src/main/kotlin/dev/ambon/metrics` (`GameMetrics`, `MetricsHttpServer`)
- Web demo client (static): `src/main/resources/web`
- Login banner UI: `src/main/kotlin/dev/ambon/ui/login`, `src/main/resources/login.txt`, `src/main/resources/login.styles.yaml`
- World loading and validation: `src/main/kotlin/dev/ambon/domain/world/load/WorldLoader.kt`
- World content: `src/main/resources/world`
- World format contract: `docs/world-zone-yaml-spec.md`
- Persistence abstractions/impl: `src/main/kotlin/dev/ambon/persistence` (`PlayerRepository`, `YamlPlayerRepository`, `PostgresPlayerRepository`, `DatabaseManager`, `PlayersTable`)
- Flyway schema migrations: `src/main/resources/db/migration`
- Tests: `src/test/kotlin`, fixtures in `src/test/resources/world`
- Runtime player data (git-ignored): `data/players`

## Architecture Contracts (Do Not Break)
1. Engine vs transport boundary
- Engine communicates using semantic events only (`InboundEvent`, `OutboundEvent`).
- ANSI/control behavior remains semantic in engine (`SetAnsi`, `ClearScreen`, `ShowAnsiDemo`), with raw escape rendering in transport renderers only.
- Transports (telnet + WebSocket) are adapters only: decode lines into `InboundEvent`s and render `OutboundEvent`s; no gameplay/state in transport.

2. Engine loop model
- `GameEngine` is single-threaded via its dispatcher and tick loop.
- Keep blocking I/O out of engine systems/router.
- Use injected `Clock` for time-based logic; do not introduce direct wall-clock calls.

3. Session output semantics
- `OutboundRouter` applies backpressure (slow clients may be disconnected).
- Prompt coalescing is intentional: consecutive prompts collapse.
- `Close` sends final text then closes via callback.

4. World and ID invariants
- `RoomId` must be namespaced: `<zone>:<room>`.
- Multi-zone world loading and cross-zone exits are supported.
- Loader validates exits, start room, mob/item placement, stats, and slot/direction values.
- Item placement is exclusive: room or mob (or unplaced), never both.
- Zone `lifespan` is in minutes; `lifespan <= 0` disables resets. The engine uses `lifespan` to reset a zone's mob/item spawns.

5. Player and persistence invariants
- Name validation: length 2..16, alnum/underscore only, cannot start with digit.
- Password validation: non-blank, max 72 chars (BCrypt-safe).
- Case-insensitive online-name uniqueness is enforced.
- Player room/last-seen persistence must stay intact.
- Player progression persistence must stay intact (level/xp/constitution).
- Keep atomic-write behavior for YAML persistence files.
- The persistence backend is selectable via `ambonMUD.persistence.backend` (`YAML` or `POSTGRES`). When `POSTGRES`, `ambonMUD.database.jdbcUrl` is required.
- The persistence chain is: `WriteCoalescingPlayerRepository` → `RedisCachingPlayerRepository` (optional) → `YamlPlayerRepository` or `PostgresPlayerRepository`. Changes to `PlayerRecord` must survive all three layers including JSON round-trip through Redis.
- `PostgresPlayerRepository` uses Exposed DSL, Flyway migrations, and HikariCP. Schema lives in `src/main/resources/db/migration/`. Tests use H2 in PostgreSQL-compatibility mode (no Docker required).
- `isStaff` is a `PlayerRecord` field; it is faithfully serialized through all persistence layers. Grant by editing the player YAML directly or updating the `players` table row.

6. Event bus boundary
- The engine receives an `InboundBus` and sends to an `OutboundBus` — never raw `Channel` references.
- `Local*Bus` implementations wrap channels and preserve single-process behavior.
- `Redis*Bus` implementations publish to Redis and deliver remotely-originated events to the local delegate.
- `Grpc*Bus` implementations wrap `Local*Bus` and forward/receive events to/from the gRPC stream (used by gateways in `GATEWAY` mode).
- All engine tests use `LocalInboundBus`/`LocalOutboundBus` directly.

## Change Playbooks
### Commands
1. Update parse behavior in `src/main/kotlin/dev/ambon/engine/commands/CommandParser.kt`.
2. Add/adjust command variant in `src/main/kotlin/dev/ambon/engine/commands/CommandParser.kt` (`Command` sealed interface).
3. Implement behavior in `src/main/kotlin/dev/ambon/engine/commands/CommandRouter.kt`.
4. Preserve prompt behavior for success/failure paths.
5. Add/adjust parser tests and router/integration tests under `src/test/kotlin/dev/ambon/engine`.

### Combat, mobs, items
- Modify `CombatSystem`, `MobSystem`, `MobRegistry`, `ItemRegistry` carefully to preserve membership/index consistency.
- Maintain bounded per-tick processing caps (`max*PerTick`) to avoid starvation.
- Add/adjust tests for damage, equipment modifiers, death/drop flow, and move/broadcast behavior.

### World content and loader
- Content-only changes: edit YAML in `src/main/resources/world`.
- Loader/schema behavior changes: update `WorldLoader.kt` and add fixture coverage in `src/test/resources/world`.
- Keep both positive and negative validation tests in `src/test/kotlin/dev/ambon/world/load/WorldLoaderTest.kt`.

### Configuration / demo client
- Config schema changes: update `src/main/kotlin/dev/ambon/config/AppConfig.kt` and `src/main/resources/application.yaml` together; keep `validated()` strict.
- Web demo client changes: update `src/main/resources/web` and sanity-check connect/disconnect against `KtorWebSocketTransport`.
- Runtime config overrides use `-Pconfig.<key>=<value>` (e.g. `./gradlew run -Pconfig.ambonMUD.logging.level=DEBUG`). This works in all shells including Windows PowerShell.

### Persistence
- Keep `PlayerRepository` as the abstraction boundary.
- Preserve case-insensitive lookup and unique-name behavior.
- Use `YamlPlayerRepositoryTest` and `@TempDir` for YAML regression coverage; use `PostgresPlayerRepositoryTest` (H2 in-memory) for Postgres coverage.
- When adding fields to `PlayerRecord`: add with a default value so existing YAML files still deserialize; verify the field round-trips through Jackson/Redis JSON (`RedisCachingPlayerRepositoryTest`). For Postgres, add a new Flyway migration (`V<N>__description.sql`) and update `PlayersTable.kt` + `PostgresPlayerRepository.kt` (mapping in `toPlayerRecord()`, `insert`, and `upsert`).
- Do not add persistence logic directly to `GameEngine` or `PlayerRegistry` — all writes go through `repo.save()` which the coalescing wrapper intercepts.

### Staff/Admin commands
- Add parse logic in `CommandParser.kt` (alongside existing admin block).
- Gate with `if (!playerState.isStaff)` check in `CommandRouter.kt`.
- Add tests in `CommandRouterAdminTest`.

### Event bus / Redis / gRPC
- Bus implementations live in `dev.ambon.bus`; Redis infrastructure in `dev.ambon.redis`; gRPC proto mapping in `dev.ambon.grpc`.
- When adding new `InboundEvent` or `OutboundEvent` variants, also add them to:
  - the Redis bus envelope in `RedisInboundBus`/`RedisOutboundBus` (type discriminator string + new data class)
  - the proto definitions in `src/main/proto/ambonmud/v1/events.proto` and mapping in `ProtoMapper.kt`
- `RedisConnectionManager` degrades gracefully when Redis is unavailable — never let a Redis failure crash the engine.

## Testing Expectations
- Minimum verification for any meaningful change: `ktlintCheck` and `test`.
- Prefer focused test runs while iterating, then run full suite before finalizing.
- Add tests for every behavioral change; this codebase treats tests as design constraints.

## Practical Notes
- Keep protocol/network concerns in `transport`; keep gameplay/state transitions in `engine`.
- Keep bus/Redis concerns in `bus`/`redis`; wire them in `MudServer.kt` only.
- Reuse deterministic test helpers (`MutableClock`, in-memory repository) where possible.
- Do not commit runtime player save artifacts from `data/players`.
- The scheduler is at `src/main/kotlin/dev/ambon/engine/scheduler/Scheduler.kt`.
- Micrometer metrics use package `io.micrometer.prometheusmetrics` (not the deprecated `io.micrometer.prometheus`).
- Staff access is granted by setting `isStaff: true` in the player's YAML file (or updating the `is_staff` column in the `players` table when using Postgres) — there is no in-game promotion command.
