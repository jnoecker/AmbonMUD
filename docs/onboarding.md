# AmbonMUD Developer Onboarding

This guide gets you from zero to productive against the current architecture.

## 1. What You Are Working On
AmbonMUD is a Kotlin/JVM MUD server with:
- A single-threaded tick engine (`GameEngine`)
- Two transports (telnet + WebSocket)
- YAML world content with validation
- Layered player persistence with optional Redis cache and bus

The codebase is designed around strict boundaries:
- Engine owns gameplay state and emits semantic events.
- Transport owns protocol I/O and rendering.
- Bus interfaces decouple engine from transport plumbing.

## 2. Quick Start
Prereqs:
- JDK 17+

Run server:

```bash
./gradlew run
```

Windows:

```powershell
.\gradlew.bat run
```

Default endpoints:
- Telnet: `localhost:4000`
- Web client: `http://localhost:8080`

Demo mode (auto-open browser when possible):

```bash
./gradlew demo
```

## 3. Core Commands
- Tests: `./gradlew test`
- Lint: `./gradlew ktlintCheck`
- CI parity: `./gradlew ktlintCheck test`
- Single class: `./gradlew test --tests "dev.ambon.engine.commands.CommandParserTest"`

Use `.\gradlew.bat` on Windows.

## 4. Project Layout
Main runtime:
- `src/main/kotlin/dev/ambon/Main.kt`
- `src/main/kotlin/dev/ambon/MudServer.kt`

Architecture modules:
- `src/main/kotlin/dev/ambon/engine` - gameplay loop and systems
- `src/main/kotlin/dev/ambon/transport` - telnet/ws adapters and rendering
- `src/main/kotlin/dev/ambon/bus` - `InboundBus`/`OutboundBus` + local/Redis impls
- `src/main/kotlin/dev/ambon/persistence` - repository layers + worker
- `src/main/kotlin/dev/ambon/redis` - Redis connection and JSON config
- `src/main/kotlin/dev/ambon/metrics` - Micrometer metrics
- `src/main/kotlin/dev/ambon/domain/world/load/WorldLoader.kt` - world loader

Config and data:
- `src/main/resources/application.yaml`
- `src/main/resources/world`
- `data/players` (runtime, git-ignored)

Docs:
- `AGENTS.md` - engineering contract
- `README.md` - user/developer overview
- `docs/world-zone-yaml-spec.md` - world YAML contract
- `docs/scalability-plan-brainstorm.md` - scalability roadmap/status

## 5. Runtime Flow
```text
Client input (telnet/ws)
  -> InboundEvent
  -> InboundBus
  -> GameEngine
  -> OutboundEvent
  -> OutboundBus
  -> OutboundRouter
  -> session renderer + socket frame
```

`OutboundRouter` behavior to preserve:
- Prompt coalescing (`SendPrompt` collapse)
- Per-session queue backpressure
- Slow-client disconnect protection

## 6. Event and Bus Contracts
Events:
- `InboundEvent`: `Connected`, `Disconnected`, `LineReceived`
- `OutboundEvent`: text/info/error/prompt/login/ansi/clear/close/demo

Bus interfaces:
- `InboundBus`: `send`, `trySend`, `tryReceive`, `close`
- `OutboundBus`: `send`, `tryReceive`, `asReceiveChannel`, `close`

Implementations:
- Local mode: `LocalInboundBus`, `LocalOutboundBus`
- Redis mode: `RedisInboundBus`, `RedisOutboundBus`

Rule:
- Engine code takes bus interfaces only. No raw channel dependency in engine classes.

## 7. Engine Model
`GameEngine` tick loop (default `100ms`) does:
1. Drain inbound events (bounded)
2. Tick mob system (bounded)
3. Tick combat system (bounded)
4. Tick regen system (bounded)
5. Run scheduler due actions (bounded)
6. Reset zones whose lifespan elapsed

All mutable gameplay state is assumed to run on the engine dispatcher.
Do not inject blocking I/O into this path.

## 8. Login and Session Lifecycle
Login flow is in `PlayerRegistry` + `GameEngine` login FSM.

Validation:
- Name: 2..16 chars, alnum/underscore, not starting with digit
- Password: non-blank, <=72 chars

Behavior:
- Existing player: password check (bcrypt)
- New player: confirmation + create
- Same-name login takeover: old session is closed, state remapped to new session

Staff control:
- `PlayerRecord.isStaff` is loaded into `PlayerState.isStaff`
- Grant staff by editing player YAML

## 9. Commands and Gameplay Surfaces
Command parser/router files:
- `src/main/kotlin/dev/ambon/engine/commands/CommandParser.kt`
- `src/main/kotlin/dev/ambon/engine/commands/CommandRouter.kt`

Player-facing command groups:
- Movement/look/exits
- Chat (`say`, `tell`, `gossip`, `emote`, `who`)
- Items/equipment (`get`, `drop`, `wear`, `remove`, `inventory`, `equipment`)
- Combat (`kill`, `flee`)
- Character (`score`)
- UI (`ansi`, `colors`, `clear`)
- Session (`help`, `quit`)

Staff commands:
- `goto`, `transfer`, `spawn`, `smite`, `kick`, `shutdown`

## 10. World Content
World content lives in YAML under `src/main/resources/world`.
Loader contract is enforced by `WorldLoader`.

Important constraints:
- IDs normalize to namespaced `zone:id`
- Multi-zone load and cross-zone exits are supported
- Item placement supports `room` or unplaced only
- `items.*.mob` placement is deprecated and rejected
- Mob loot is defined in `mobs.*.drops`
- `lifespan > 0` enables zone reset scheduling

Reference spec:
- `docs/world-zone-yaml-spec.md`

## 11. Persistence Stack
Current repository layering in `MudServer`:

```text
WriteCoalescingPlayerRepository   (optional worker layer)
  -> RedisCachingPlayerRepository (optional Redis cache layer)
    -> YamlPlayerRepository       (durable atomic YAML writes)
```

Notes:
- Worker flushes dirty records periodically and flushes all on shutdown.
- Redis cache stores name->id and id->record JSON entries.
- YAML file naming is by zero-padded numeric player ID.

Data path defaults:
- Root: `data/players`
- Files: `data/players/players/<id>.yaml`

## 12. Redis Integration
Redis config is under `ambonMUD.redis`.

Modes:
- Cache only (`redis.enabled=true`, `redis.bus.enabled=false`)
- Cache + bus (`redis.enabled=true`, `redis.bus.enabled=true`)

Current warning:
- Bus mode is marked experimental at startup.

Failure posture:
- Redis failures should be logged and treated as best-effort; avoid hard engine failure.

## 13. Metrics and Observability
Metrics are emitted through Micrometer (`GameMetrics`).

Default scrape endpoint:
- `http://localhost:8080/metrics`

Config:
- `ambonMUD.observability.metricsEnabled`
- `ambonMUD.observability.metricsEndpoint`
- `ambonMUD.observability.staticTags`

Local dashboards:

```bash
docker compose up -d
```

Grafana:
- URL: `http://localhost:3000`
- Credentials: `admin` / `admin`

## 14. Configuration Patterns
Schema: `src/main/kotlin/dev/ambon/config/AppConfig.kt`
Values: `src/main/resources/application.yaml`

High-value sections:
- `server` (ports, capacities, tick)
- `world` (resource list)
- `persistence` (rootDir, worker)
- `engine.mob`, `engine.combat`, `engine.regen`, `engine.scheduler`
- `transport.telnet`, `transport.websocket`
- `redis` (uri/ttl/bus)
- `observability`
- `logging`

Runtime overrides pattern:

```bash
./gradlew run -Pconfig.ambonMUD.server.tickMillis=250
./gradlew run -Pconfig.ambonMUD.logging.packageLevels.dev.ambon.transport=DEBUG
```

## 15. Testing Expectations
- Every behavior change should ship with tests.
- Prefer deterministic test helpers:
  - `MutableClock`
  - `InMemoryPlayerRepository`
- World loader changes need both positive and negative fixture coverage.
- Run full `ktlintCheck test` before finalizing non-trivial changes.

## 16. Common Change Playbooks
Add command:
1. Extend `Command` + parse in `CommandParser`
2. Implement in `CommandRouter`
3. Add parser + router tests

Add config key:
1. Extend `AppConfig.kt`
2. Add default in `application.yaml`
3. Extend validation and tests

Add `PlayerRecord` field:
1. Add default value in data class
2. Wire load/save path in relevant code
3. Verify YAML and Redis round-trip tests

Adjust world schema:
1. Update data classes and `WorldLoader`
2. Update `docs/world-zone-yaml-spec.md`
3. Add fixtures + `WorldLoaderTest` coverage

## 17. Critical Invariants Checklist
- Engine remains transport-agnostic
- Transport remains gameplay-agnostic
- Engine loop stays single-threaded and bounded per tick
- Clock abstraction is preserved in engine logic
- Backpressure protections remain active
- Namespaced IDs remain enforced
- Player validation rules remain unchanged
- YAML writes remain atomic
- Persistence chain ordering remains explicit in `MudServer`
- Redis remains optional