# AGENTS.md

## Purpose
AmbonMUD is a Kotlin MUD server with a single-threaded engine loop, telnet + WebSocket transports, YAML world loading, and layered persistence with optional Redis cache/pub-sub.

Use this file as the default engineering playbook for code and content changes.

## Environment
- JDK: Gradle toolchain targets 17 (`build.gradle.kts`); CI runs Java 21 (`.github/workflows/ci.yml`).
- Build: Gradle wrapper (`gradlew`, `gradlew.bat`).
- Kotlin style: official (`kotlin.code.style=official`).

## Core Commands
- Run server (Windows): `.\gradlew.bat run`
- Run server (Unix): `./gradlew run`
- Demo mode (Windows): `.\gradlew.bat demo`
- Demo mode (Unix): `./gradlew demo`
- Lint: `.\gradlew.bat ktlintCheck`
- Tests: `.\gradlew.bat test`
- CI parity (Unix): `./gradlew ktlintCheck test`
- CI parity (Windows): `.\gradlew.bat ktlintCheck test`

Default ports from `src/main/resources/application.yaml`:
- Telnet: `4000`
- Web/WebSocket: `8080`

## Project Map
- Entry and wiring: `src/main/kotlin/dev/ambon/Main.kt`, `src/main/kotlin/dev/ambon/MudServer.kt`
- Config schema/loading: `src/main/kotlin/dev/ambon/config`, `src/main/resources/application.yaml`
- Engine and gameplay: `src/main/kotlin/dev/ambon/engine`
- Transport and protocol: `src/main/kotlin/dev/ambon/transport`
- Bus abstractions + impls: `src/main/kotlin/dev/ambon/bus`
- Redis connection and JSON support: `src/main/kotlin/dev/ambon/redis`
- Session ID allocation: `src/main/kotlin/dev/ambon/session`
- Metrics: `src/main/kotlin/dev/ambon/metrics`
- World loading: `src/main/kotlin/dev/ambon/domain/world/load/WorldLoader.kt`
- World content: `src/main/resources/world`
- World format spec: `docs/world-zone-yaml-spec.md`
- Persistence layers: `src/main/kotlin/dev/ambon/persistence`
- Login banner UI: `src/main/kotlin/dev/ambon/ui/login`, `src/main/resources/login.txt`, `src/main/resources/login.styles.yaml`
- Browser demo client: `src/main/resources/web`
- Tests: `src/test/kotlin` (fixtures: `src/test/resources/world`)
- Runtime player data (git-ignored): `data/players`

## Architecture Contracts (Do Not Break)
1. Engine/transport boundary
- Engine communicates via semantic events only: `InboundEvent`, `OutboundEvent`.
- Transport adapters decode raw protocol input and render outbound text.
- ANSI/control bytes are rendered only in transport renderers (`AnsiRenderer`, `PlainRenderer`).

2. Bus boundary
- Engine accepts `InboundBus` and `OutboundBus`; do not pass raw channels into engine code.
- Single-process mode uses `LocalInboundBus` + `LocalOutboundBus`.
- Redis mode wraps local buses with `RedisInboundBus` + `RedisOutboundBus`.

3. Engine execution model
- `GameEngine` runs on a single-threaded dispatcher.
- Keep blocking I/O out of engine systems and command routing.
- Use injected `Clock` for time logic; do not call wall clock APIs directly in engine code.

4. Session output semantics
- `OutboundRouter` owns per-session outbound queues.
- Prompt coalescing is intentional (`SendPrompt` collapse behavior).
- Slow outbound clients may be disconnected (backpressure protection).
- `Close` sends final text then invokes close callback.

5. World and ID invariants
- IDs are namespaced (`zone:id`) for rooms, mobs, and items.
- Multi-zone loading and cross-zone exits are supported.
- Zone `lifespan` is minutes; `lifespan > 0` enables runtime reset.
- Item placement uses `room` or unplaced only.
- Deprecated `items.*.mob` placement is rejected; mob loot uses `mobs.*.drops`.

6. Player and persistence invariants
- Name validation: length `2..16`, alnum/underscore, cannot start with digit.
- Password validation: non-blank, max 72 chars.
- Online name uniqueness is case-insensitive.
- Player location/last-seen/progression (`level`, `xpTotal`, `constitution`) must persist.
- YAML writes remain atomic (temp + move strategy).
- Repository layering (outer to inner):
  `WriteCoalescingPlayerRepository` -> `RedisCachingPlayerRepository` (optional) -> `YamlPlayerRepository`.
- `PlayerRecord` changes must round-trip through YAML and Redis JSON.
- Staff access comes from `PlayerRecord.isStaff` and is granted by editing the player YAML file.

7. Redis optionality and resilience
- Redis is opt-in (`ambonMUD.redis.enabled=false` by default).
- Redis errors should degrade gracefully; avoid hard dependency failures in engine flow.
- Redis bus mode is currently experimental and should stay explicitly guarded in docs/logs.

## Change Playbooks
### Commands
1. Add parse behavior in `src/main/kotlin/dev/ambon/engine/commands/CommandParser.kt`.
2. Add/adjust `Command` variant.
3. Implement behavior in `src/main/kotlin/dev/ambon/engine/commands/CommandRouter.kt`.
4. Preserve prompt semantics on success/failure paths.
5. Add tests in parser/router/integration suites under `src/test/kotlin/dev/ambon/engine`.

### Combat, mobs, items
- Edit `CombatSystem`, `MobSystem`, `MobRegistry`, `ItemRegistry` carefully; preserve membership/index consistency.
- Maintain bounded per-tick caps (`max*PerTick`) to prevent starvation.
- Add tests for damage/armor, drops, equip modifiers, death/flee flows, and movement broadcasts.

### World content and loader
- Content-only change: edit YAML under `src/main/resources/world`.
- Loader/schema change: update `WorldLoader.kt` + fixtures + `WorldLoaderTest`.
- Keep positive and negative validation coverage.

### Configuration and runtime wiring
- Update `AppConfig.kt` and `application.yaml` together.
- Keep `validated()` strict when adding fields.
- Runtime overrides use `-Pconfig.<key>=<value>`.

### Persistence
- Keep `PlayerRepository` as abstraction boundary.
- Do not place persistence logic directly inside gameplay systems.
- For new `PlayerRecord` fields:
  - add default values for backward compatibility,
  - verify YAML round-trip,
  - verify Redis JSON round-trip.
- Ensure worker shutdown still flushes dirty records.

### Bus/Redis
- New event variants in `InboundEvent`/`OutboundEvent` require matching updates in Redis bus envelope serialization/deserialization.
- Preserve same semantics between local and Redis buses.

### Transport
- Keep protocol safety checks (`maxLineLen`, non-printable thresholds, inbound backpressure failures).
- Keep gameplay logic out of transport classes.

## Testing Expectations
- Minimum for meaningful changes: `ktlintCheck` and `test`.
- Prefer focused tests while iterating, then run full suite.
- Add tests for every behavior change.

## Practical Notes
- Keep protocol concerns in `transport`; gameplay/state transitions in `engine`.
- Keep Redis/bus concerns in `redis` and `bus`; wire in `MudServer.kt`.
- Scheduler location: `src/main/kotlin/dev/ambon/engine/scheduler/Scheduler.kt`.
- Metrics endpoint is served from Ktor web module (`/metrics` by default).
- Do not commit runtime save files from `data/players`.