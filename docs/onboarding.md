# AmbonMUD — Developer Onboarding Guide

Welcome to AmbonMUD. This guide walks a new developer from zero to productive as quickly as possible.

---

## Table of Contents

1. [What Is This?](#1-what-is-this)
2. [Quick Start](#2-quick-start)
3. [Project Layout](#3-project-layout)
4. [Architecture Overview](#4-architecture-overview)
5. [Event Model: The Core Contract](#5-event-model-the-core-contract)
6. [The Game Engine](#6-the-game-engine)
7. [Command System](#7-command-system)
8. [Login Flow](#8-login-flow)
9. [Domain Model](#9-domain-model)
10. [Subsystems](#10-subsystems)
11. [Transport Adapters](#11-transport-adapters)
12. [Event Bus System](#12-event-bus-system)
13. [Persistence Layer](#13-persistence-layer)
14. [Redis Integration](#14-redis-integration)
15. [gRPC Gateway Split & Deployment Modes](#15-grpc-gateway-split--deployment-modes)
16. [Metrics & Observability](#16-metrics--observability)
17. [Staff / Admin System](#17-staff--admin-system)
18. [World Loading & YAML Format](#18-world-loading--yaml-format)
19. [Configuration](#19-configuration)
20. [Testing](#20-testing)
21. [Common Change Playbooks](#21-common-change-playbooks)
22. [Critical Invariants: Never Break These](#22-critical-invariants-never-break-these)

---

## 1. What Is This?

AmbonMUD is a **tick-based MUD (Multi-User Dungeon) server** written in Kotlin/JVM. It supports simultaneous telnet and WebSocket clients, YAML-defined world content, bcrypt-hashed player accounts, and real-time combat/mob/regen systems. It can run as a single process or split into separate engine and gateway processes for horizontal scaling.

The codebase is intentionally designed like a production backend service, not a toy project. The emphasis is on clean architectural boundaries, testability, and incremental extensibility.

Key stats:
- **Transport**: Telnet (port 4000) + WebSocket / browser demo (port 8080)
- **Engine**: Single-threaded coroutine dispatcher, 100 ms ticks
- **Persistence**: Write-behind coalescing → optional Redis L2 cache → YAML files or PostgreSQL (selectable via config)
- **World content**: YAML zone files, multi-zone with cross-zone exits
- **Scalability**: Phases 1–4 complete (bus abstraction, async persistence, Redis integration, gRPC gateway split); three deployment modes: `STANDALONE`, `ENGINE`, `GATEWAY`
- **Gateway resilience**: Exponential-backoff reconnect when the engine gRPC stream is lost; Snowflake session IDs with overflow wait and clock-rollback hardening

---

## 2. Quick Start

### Prerequisites

- JDK 17+ (CI runs Java 21; the Gradle toolchain targets 17)
- Docker (optional — for PostgreSQL, Redis, and observability stack)
- No other infrastructure needed for the default YAML persistence mode

### Run the Server

```bash
# Unix
./gradlew run

# Windows
.\gradlew.bat run
```

Outputs:
- Telnet: `telnet localhost 4000`
- Web client: `http://localhost:8080`

### Browser Demo (auto-opens a tab)

```bash
./gradlew demo
```

### Run Tests

```bash
./gradlew test
```

### Lint

```bash
./gradlew ktlintCheck
```

### Full Infrastructure (Docker Compose)

The `docker-compose.yml` in the repo root brings up PostgreSQL, Redis, Prometheus, and Grafana:

```bash
docker compose up -d
```

This gives you everything needed for all features and deployment modes. The `application.yaml` defaults for database and Redis connections already match the compose stack, so you only need to toggle the feature flags:

```bash
# Postgres backend
./gradlew run -Pconfig.ambonMUD.persistence.backend=POSTGRES

# Postgres + Redis caching
./gradlew run -Pconfig.ambonMUD.persistence.backend=POSTGRES \
              -Pconfig.ambonMUD.redis.enabled=true
```

Flyway creates the schema automatically on first startup. Data is persisted in a Docker named volume (`pgdata`); use `docker compose down -v` to wipe it.

### CI Parity (run before finalizing any change)

```bash
./gradlew ktlintCheck test
```

### Run a Single Test Class

```bash
./gradlew test --tests "dev.ambon.engine.commands.CommandParserTest"
```

---

## 3. Project Layout

```
AmbonMUD/
├── src/main/kotlin/dev/ambon/
│   ├── Main.kt                    # Entry point
│   ├── MudServer.kt               # Bootstrap & dependency wiring
│   ├── config/                    # Config schema + Hoplite loader
│   ├── domain/                    # Pure domain types
│   │   ├── ids/                   # RoomId, MobId, ItemId, SessionId (value classes)
│   │   ├── items/                 # Item, ItemSlot
│   │   ├── mob/                   # MobState (with tiered combat stats)
│   │   └── world/                 # Room, World, Direction
│   │       └── load/              # WorldLoader (YAML → domain)
│   ├── engine/                    # All gameplay logic
│   │   ├── GameEngine.kt          # Tick loop + inbound handler
│   │   ├── PlayerRegistry.kt      # Session ↔ PlayerState, login FSM
│   │   ├── PlayerState.kt         # Per-session mutable player data
│   │   ├── PlayerProgression.kt   # XP curves, leveling, HP scaling
│   │   ├── MobRegistry.kt         # NPC registry + room membership index
│   │   ├── MobSystem.kt           # NPC wandering AI
│   │   ├── CombatSystem.kt        # Fight resolution
│   │   ├── RegenSystem.kt         # HP regeneration
│   │   ├── commands/
│   │   │   ├── CommandParser.kt   # String → Command (pure function)
│   │   │   └── CommandRouter.kt   # Command → OutboundEvents (logic)
│   │   ├── events/                # InboundEvent, OutboundEvent sealed types
│   │   ├── items/                 # ItemRegistry
│   │   └── scheduler/             # Scheduler (delayed/recurring actions)
│   ├── bus/                       # Event bus abstraction (Phase 1)
│   │   ├── InboundBus.kt          # Interface for engine ← transport events
│   │   ├── OutboundBus.kt         # Interface for engine → transport events
│   │   ├── LocalInboundBus.kt     # Wraps Channel<InboundEvent>
│   │   ├── LocalOutboundBus.kt    # Wraps Channel<OutboundEvent>
│   │   ├── RedisInboundBus.kt     # Local + Redis pub/sub (Phase 3)
│   │   ├── RedisOutboundBus.kt    # Local + Redis pub/sub (Phase 3)
│   │   ├── GrpcInboundBus.kt      # Wraps Local + forwards to gRPC stream (Phase 4)
│   │   └── GrpcOutboundBus.kt     # Wraps Local + receives from gRPC stream (Phase 4)
│   ├── grpc/                      # gRPC gateway/engine split (Phase 4)
│   │   ├── EngineGrpcServer.kt    # gRPC server lifecycle wrapper
│   │   ├── EngineServiceImpl.kt   # Bidi-streaming service impl; sessionToStream map
│   │   ├── GrpcOutboundDispatcher.kt  # Engine-side: demux OutboundBus → gateway streams
│   │   ├── EngineServer.kt        # ENGINE-mode composition root
│   │   └── ProtoMapper.kt         # InboundEvent/OutboundEvent ↔ proto extension functions
│   ├── gateway/                   # GATEWAY-mode composition root (Phase 4)
│   │   └── GatewayServer.kt       # Transports + gRPC client; no engine/persistence
│   ├── redis/                     # Redis infrastructure (Phase 3)
│   │   ├── RedisConnectionManager.kt  # Lettuce lifecycle, sync/async commands
│   │   └── JsonSupport.kt         # Jackson ObjectMapper (KotlinModule)
│   ├── session/                   # Session ID allocation
│   │   ├── SessionIdFactory.kt    # Interface: allocate() → SessionId
│   │   ├── AtomicSessionIdFactory.kt  # AtomicLong-based impl (standalone mode)
│   │   ├── SnowflakeSessionIdFactory.kt  # Bit-packed IDs for multi-gateway (Phase 4)
│   │   └── GatewayIdLeaseManager.kt  # Redis-based exclusive lease for gateway IDs
│   ├── metrics/                   # Observability
│   │   ├── GameMetrics.kt         # Micrometer gauges/counters for Prometheus
│   │   └── MetricsHttpServer.kt   # Standalone Prometheus scrape endpoint (ENGINE mode)
│   ├── transport/                 # Protocol adapters
│   │   ├── BlockingSocketTransport.kt  # Telnet server
│   │   ├── KtorWebSocketTransport.kt   # WebSocket / Ktor
│   │   ├── NetworkSession.kt      # Per-session I/O (telnet)
│   │   ├── OutboundRouter.kt      # Route OutboundEvents → renderers
│   │   ├── AnsiRenderer.kt        # ANSI color rendering
│   │   ├── PlainRenderer.kt       # Plain text (no color)
│   │   ├── TelnetLineDecoder.kt   # Telnet protocol handling
│   │   └── InboundBackpressure.kt # Drop / disconnect slow inbound
│   ├── persistence/               # Player persistence stack (Phases 2–3)
│   │   ├── PlayerRepository.kt    # Interface (swappable)
│   │   ├── YamlPlayerRepository.kt         # Durable YAML, atomic writes
│   │   ├── PostgresPlayerRepository.kt     # Exposed DSL + HikariCP
│   │   ├── PlayersTable.kt        # Exposed table definition
│   │   ├── DatabaseManager.kt     # HikariCP pool + Flyway + Exposed wiring
│   │   ├── RedisCachingPlayerRepository.kt # L2 cache (Phase 3, opt-in)
│   │   ├── WriteCoalescingPlayerRepository.kt  # Write-behind (Phase 2)
│   │   ├── PersistenceWorker.kt   # Background flush coroutine
│   │   └── PlayerRecord.kt        # Serializable player data
│   └── ui/login/                  # Login banner rendering
├── src/main/resources/
│   ├── application.yaml           # Runtime config (ports, tuning, world files)
│   ├── db/migration/              # Flyway SQL migrations (Postgres backend)
│   │   └── V1__create_players_table.sql
│   ├── login.txt                  # Login banner text
│   ├── login.styles.yaml          # ANSI styles for banner
│   ├── world/                     # Zone YAML files
│   │   ├── ambon_hub.yaml
│   │   ├── demo_ruins.yaml
│   │   └── noecker_resume.yaml
│   └── web/                       # Static browser client (xterm.js)
├── src/test/kotlin/               # Full test suite (~49 test files)
├── src/test/resources/world/      # World fixtures (valid + invalid)
├── data/players/                  # Runtime player saves (git-ignored)
├── docs/
│   ├── world-zone-yaml-spec.md    # World YAML format contract
│   ├── scalability-plan-brainstorm.md  # 4-phase scaling roadmap
│   └── onboarding.md              # This file
├── AGENTS.md                      # Engineering playbook
├── CLAUDE.md                      # Claude Code orientation
└── DesignDecisions.md             # Architecture rationale (worth reading)
```

---

## 4. Architecture Overview

```
Clients (telnet / browser WebSocket)
        │
        ▼
 ┌──────────────────────────────────┐
 │  Transport Adapters              │
 │  (BlockingSocketTransport,       │
 │   KtorWebSocketTransport)        │
 │  decode raw I/O → InboundEvent   │
 └──────────────┬───────────────────┘
                │
                ▼
 ┌──────────────────────────────────┐
 │  InboundBus / OutboundBus        │
 │  (interface layer)               │
 │  Local*Bus   — single-process    │
 │  Redis*Bus   — multi-process     │
 └──────────────┬───────────────────┘
                │
                ▼
 ┌──────────────────────────────────┐
 │  GameEngine  (single-threaded)   │
 │  100 ms tick loop:               │
 │    1. drain InboundEvents        │
 │    2. MobSystem.tick()           │
 │    3. CombatSystem.tick()        │
 │    4. RegenSystem.tick()         │
 │    5. Scheduler.runDue()         │
 │    6. resetZonesIfDue()          │
 │  CommandRouter executes commands │
 └──────────────┬───────────────────┘
                │
                ▼
 ┌──────────────────────────────────┐
 │  OutboundRouter                  │
 │  routes events → per-session     │
 │  renderers; coalesces prompts;   │
 │  applies backpressure            │
 └──────────────┬───────────────────┘
                │
         ┌──────┴──────┐
         ▼             ▼
   AnsiRenderer   PlainRenderer
   (colors on)    (colors off)
```

**The three rules that everything else follows from:**

1. **Engine communicates only via `InboundEvent` / `OutboundEvent`.** No transport code in the engine; no gameplay logic in transport.
2. **GameEngine is single-threaded.** Never call blocking I/O inside engine systems. Use the injected `Clock` for any time-based logic.
3. **Engine speaks to the bus, not to channels.** Always accept `InboundBus` / `OutboundBus`; never pass raw `Channel` references into the engine.

---

## 5. Event Model: The Core Contract

Events live in `src/main/kotlin/dev/ambon/engine/events/`.

### InboundEvent (transport → engine)

| Event | Fields | Meaning |
|-------|--------|---------|
| `Connected` | `sessionId`, `defaultAnsiEnabled` | New client connected |
| `Disconnected` | `sessionId`, `reason` | Client gone |
| `LineReceived` | `sessionId`, `line` | One line of text from client |

### OutboundEvent (engine → transport renderers)

| Event | Fields | Meaning |
|-------|--------|---------|
| `SendText` | `sessionId`, `text` | Normal message to player |
| `SendInfo` | `sessionId`, `text` | Info-level message (cyan in ANSI) |
| `SendError` | `sessionId`, `text` | Error message (red in ANSI) |
| `SendPrompt` | `sessionId` | Show `> ` prompt |
| `ShowLoginScreen` | `sessionId` | Show the ASCII banner |
| `SetAnsi` | `sessionId`, `enabled` | Enable/disable ANSI color |
| `ClearScreen` | `sessionId` | Clear terminal |
| `ShowAnsiDemo` | `sessionId` | Show color palette |
| `Close` | `sessionId`, `reason` | Send goodbye + disconnect |

**Do not add raw escape codes to engine output.** If a new semantic rendering need arises, add a new `OutboundEvent` variant and handle it in `AnsiRenderer` / `PlainRenderer`.

---

## 6. The Game Engine

**File:** `src/main/kotlin/dev/ambon/engine/GameEngine.kt`

### Tick Loop (100 ms default)

Each tick, the engine:

1. Drains up to `maxInboundEventsPerTick` from the inbound channel
2. Runs `MobSystem.tick()` — NPC wandering
3. Runs `CombatSystem.tick()` — active fights
4. Runs `RegenSystem.tick()` — HP regeneration
5. Runs `Scheduler.runDue()` — scheduled callbacks
6. Calls `resetZonesIfDue()` — respawns zones whose `lifespan` has expired
7. Sleeps to maintain tick frequency

### Inbound Handler

For each `InboundEvent`:
- `Connected` → registers session, sends login screen, starts login FSM
- `Disconnected` → removes session, broadcasts to room, cleans up combat
- `LineReceived` → if player is in login FSM, advances FSM; else passes to `CommandRouter`

### Zone Resets

If a zone has `lifespan > 0` (minutes), the engine periodically:
- Removes all mobs in the zone
- Clears all items in the zone
- Re-spawns mobs and items per the zone's spawn definitions
- Notifies any players in affected rooms

---

## 7. Command System

### CommandParser

**File:** `src/main/kotlin/dev/ambon/engine/commands/CommandParser.kt`

A pure function `parse(line: String): Command`. No side effects. Returns a sealed `Command` variant.

**Key Command Groups:**

| Group | Commands |
|-------|----------|
| Movement | `Move(dir)`, `LookDir(dir)` |
| Communication | `Say`, `Emote`, `Tell`, `Gossip` |
| Items | `Get`, `Drop`, `Wear`, `Remove`, `Inventory`, `Equipment` |
| Combat | `Kill`, `Flee` |
| Character | `Score` |
| Social | `Who`, `Help` |
| UI / Settings | `Look`, `Exits`, `AnsiOn`, `AnsiOff`, `Clear`, `Colors` |
| System | `Quit`, `Noop`, `Invalid`, `Unknown` |
| Staff/Admin | `Goto`, `Transfer`, `Spawn`, `Smite`, `Kick`, `Shutdown` |

Aliases are handled here (e.g. `t` → `tell`, `i` → `inventory`). Direction parsing normalizes `n/north/s/south/e/east/w/west/u/up/d/down`.

### CommandRouter

**File:** `src/main/kotlin/dev/ambon/engine/commands/CommandRouter.kt`

Receives `(playerState, command)` and emits `OutboundEvent`s to the channel. Contains the actual gameplay logic:
- Room description, exits
- Item pickup/drop/equip
- Combat initiation
- Broadcasts to other players in the room
- Prompt after each command

### Adding a New Command

1. Add a variant to the `Command` sealed interface in `CommandParser.kt`
2. Add the parsing logic in `CommandParser.parse()` (and aliases if needed)
3. Add a `when` branch in `CommandRouter.handle()`
4. Add tests in `CommandParserTest` and `CommandRouterTest` (or `GameEngineIntegrationTest`)

---

## 8. Login Flow

**File:** `src/main/kotlin/dev/ambon/engine/PlayerRegistry.kt`

The login flow is a state machine per session. States:

```
Connected
    │ → ShowLoginScreen, prompt for name
    ▼
AwaitingName
    │ → validate format (2–16 chars, alnum/underscore, no leading digit)
    │   if name exists: AwaitingExistingPassword
    │   if new:         AwaitingCreateConfirmation
    ▼
AwaitingExistingPassword         AwaitingCreateConfirmation
    │ → bcrypt verify                │ → yes/no
    │   up to 3 wrong attempts       ▼
    │   then disconnect          AwaitingNewPassword
    ▼                                │ → bcrypt hash + create record
LoggedIn ◄───────────────────────────┘
    │ → broadcast enter, show room, emit prompt
```

**Login Takeover**: If a second connection logs in with the same name and correct password, the first session receives "Your account has logged in from another location" and is closed. The new session inherits any active combat/regen state.

---

## 9. Domain Model

### ID Types

All IDs are Kotlin inline value classes that enforce namespacing.

**`RoomId(value: String)`** — format `zone:room` (e.g. `demo:trailhead`)
- `.zone` — part before `:`
- `.local` — part after `:`
- Construction fails if `:` is absent

**`MobId`, `ItemId`, `SessionId`** — same pattern.

### Room

```kotlin
data class Room(
    val id: RoomId,
    val title: String,
    val description: String,
    val exits: Map<Direction, RoomId>,
)
```

### Direction

```kotlin
enum class Direction { NORTH, SOUTH, EAST, WEST, UP, DOWN }
```

### World

```kotlin
data class World(
    val rooms: Map<RoomId, Room>,
    val startRoom: RoomId,
    val mobSpawns: List<MobSpawn>,
    val itemSpawns: List<ItemSpawn>,
    val zoneLifespansMinutes: Map<String, Long>,
)
```

### Item

```kotlin
data class Item(
    val keyword: String,        // used in get/drop/wear commands
    val displayName: String,    // shown to players
    val description: String,    // "look <item>"
    val slot: ItemSlot?,        // HEAD, BODY, HAND — null means not wearable
    val damage: Int,            // bonus damage when equipped
    val armor: Int,             // damage reduction when equipped
    val constitution: Int,      // faster HP regen when equipped
    val matchByKey: Boolean,    // if true, exact keyword only (no substring fallback)
)
```

### MobState

```kotlin
data class MobState(
    val id: MobId,
    val name: String,
    val roomId: RoomId,
    var hp: Int,
    val maxHp: Int,
    val minDamage: Int,      // per-round damage range
    val maxDamage: Int,
    val armor: Int,          // flat damage reduction vs player
    val xpReward: Int,       // granted to killer on death
)
```

Mob stats are resolved at world load time from a combination of the mob's tier (weak/standard/elite/boss), level, and any per-mob overrides in the zone YAML. See `docs/world-zone-yaml-spec.md` for the full override syntax.

### PlayerState

Runtime-only (not persisted directly):

```kotlin
// Key fields:
sessionId, name, roomId, playerId
hp, maxHp, baseMaxHp, constitution
level, xpTotal
ansiEnabled
```

Persisted via `PlayerRecord` to YAML or PostgreSQL on save (depending on configured backend).

---

## 10. Subsystems

### CombatSystem

**File:** `src/main/kotlin/dev/ambon/engine/CombatSystem.kt`

- 1v1 player-vs-mob only
- Damage per round: random in `[minDamage, maxDamage]` minus mob's armor; player takes damage minus equipped armor total
- One round per second (configurable)
- On mob death: drop inventory items to room, grant XP to killer, auto-level if XP threshold crossed
- `Flee` ends combat immediately (player stays in room)

### MobSystem

**File:** `src/main/kotlin/dev/ambon/engine/MobSystem.kt`

- Mobs wander to random adjacent rooms on a per-mob timer (5–12 s default, randomized)
- Disabled while mob is in combat
- Broadcasts `"<name> leaves <dir>."` / `"<name> enters from <dir>."` to players in affected rooms
- `maxMovesPerTick` cap prevents tick starvation

### RegenSystem

**File:** `src/main/kotlin/dev/ambon/engine/RegenSystem.kt`

- Base regen interval: 5 s (configurable)
- Constitution (base + equipped item bonuses) shortens the interval
- Restores 1 HP per trigger (configurable)
- Does nothing at full HP

### Scheduler

**File:** `src/main/kotlin/dev/ambon/engine/scheduler/Scheduler.kt`

General-purpose delayed/recurring callback runner. `Scheduler.runDue()` is called each tick. Used internally by `MobSystem` for per-mob wander timers.

### ItemRegistry

**File:** `src/main/kotlin/dev/ambon/engine/items/ItemRegistry.kt`

Tracks where every item instance lives:

| Location | Description |
|----------|-------------|
| Room inventory | Items on the floor of a room |
| Player inventory | Items carried by a player session |
| Equipped | Items worn/held by a player |
| Mob inventory | Items carried by a mob (drops on death) |
| Unplaced | Exists but not in any location |

Key operations:
- `takeFromRoom(sessionId, roomId, keyword)` — pick up item
- `dropToRoom(sessionId, roomId, keyword)` — drop item
- `equipFromInventory(sessionId, keyword)` — wear item (checks slot availability)
- `unequip(sessionId, slot)` — remove item, return to inventory
- `dropMobItemsToRoom(mobId, roomId)` — loot drop on mob death

**Keyword matching:** Case-insensitive substring match on `displayName` first, then `description` as fallback. If `matchByKey = true`, only exact `keyword` match is accepted.

### PlayerProgression

**File:** `src/main/kotlin/dev/ambon/engine/PlayerProgression.kt`

XP curve formula:
```
totalXpForLevel(L) = baseXp * (L-1)^exponent + linearXp * (L-1)
```

Level is computed from accumulated XP via binary search. Default config: `baseXp=100`, `exponent=2.0` (quadratic), max level 50.

HP scaling: base 10 HP + `hpPerLevel` per level (default +2). Full heal on level-up (configurable).

---

## 11. Transport Adapters

### BlockingSocketTransport (Telnet)

**File:** `src/main/kotlin/dev/ambon/transport/BlockingSocketTransport.kt`

- Raw TCP socket on port 4000
- Blocking I/O on `Dispatchers.IO` — never bleeds into engine
- `TelnetLineDecoder` strips telnet negotiation bytes and yields clean lines
- Per-session outbound channel with bounded capacity; overflow triggers disconnect

### KtorWebSocketTransport (Browser)

**File:** `src/main/kotlin/dev/ambon/transport/KtorWebSocketTransport.kt`

- Ktor WebSocket server on port 8080
- Serves the static xterm.js demo client from `src/main/resources/web/`
- Same event model as telnet; framing is handled by WebSocket protocol

### OutboundRouter

**File:** `src/main/kotlin/dev/ambon/transport/OutboundRouter.kt`

- Reads from the engine's outbound `Channel<OutboundEvent>`
- Dispatches to per-session `AnsiRenderer` or `PlainRenderer` based on `ansiEnabled`
- **Prompt coalescing**: consecutive `SendPrompt` events for the same session are collapsed into one
- **Backpressure**: if a session's output queue is full, the client is disconnected

### Renderers

| Renderer | Usage |
|----------|-------|
| `AnsiRenderer` | ANSI escape codes; info=cyan, error=red, prompt=green |
| `PlainRenderer` | Plain text; no escape codes |

Both implement `TextRenderer`. Selection is per-session and can be toggled at runtime with `ansi on` / `ansi off`.

---

## 12. Event Bus System

**Files:** `src/main/kotlin/dev/ambon/bus/`

The event bus system (Phase 1 of the scalability refactor) decouples the engine from raw Kotlin channels and creates the seam needed for future multi-process routing.

### Interfaces

```kotlin
interface InboundBus {
    suspend fun send(event: InboundEvent)
    fun trySend(event: InboundEvent): Boolean
    fun tryReceive(): InboundEvent?
    fun close()
}

interface OutboundBus {
    suspend fun send(event: OutboundEvent)
    fun tryReceive(): OutboundEvent?
    fun asReceiveChannel(): ReceiveChannel<OutboundEvent>
    fun close()
}
```

### Local Implementations (single-process)

- `LocalInboundBus` — wraps `Channel<InboundEvent>`, used in all tests and default runtime
- `LocalOutboundBus` — wraps `Channel<OutboundEvent>`, same

### Redis Implementations (multi-process pub/sub)

- `RedisInboundBus` — wraps `LocalInboundBus` as delegate; publishes to Redis on every `send`/`trySend`; subscribes to Redis channel and delivers remote events to the delegate
- `RedisOutboundBus` — same pattern for outbound events
- Each instance carries a UUID `instanceId`; events originating from this instance are filtered out on receive to prevent echo
- Envelope format: JSON with `instanceId`, `type`, `sessionId`, plus type-specific fields
- Enabled in `MudServer` when `redis.enabled && redis.bus.enabled`

### gRPC Implementations (gateway ↔ engine)

- `GrpcInboundBus` — wraps `LocalInboundBus` as delegate; on every `send`/`trySend`, also fire-and-forgets the proto-encoded event to the gateway's gRPC send channel. Used by the gateway to forward inbound events to the engine.
- `GrpcOutboundBus` — wraps `LocalOutboundBus` as delegate; a background coroutine collects from the engine's gRPC receive flow and delivers events into the delegate via `trySend`. Used by the gateway so `OutboundRouter` can consume events normally.

**Rule:** The engine always receives `InboundBus` / `OutboundBus` — never raw channels.

---

## 13. Persistence Layer

**Files:** `src/main/kotlin/dev/ambon/persistence/`

The persistence stack has three layers, assembled in `MudServer.kt`. The bottom layer is selected by `ambonMUD.persistence.backend` (`YAML` or `POSTGRES`):

```
WriteCoalescingPlayerRepository  ← write-behind (Phase 2)
  ↓
RedisCachingPlayerRepository     ← L2 cache (Phase 3, if redis.enabled)
  ↓
YamlPlayerRepository             ← durable YAML, atomic writes (backend=YAML)
  — or —
PostgresPlayerRepository         ← Exposed DSL + HikariCP (backend=POSTGRES)
```

### Interface

```kotlin
interface PlayerRepository {
    suspend fun findByName(name: String): PlayerRecord?
    suspend fun findById(id: PlayerId): PlayerRecord?
    suspend fun create(...): PlayerRecord
    suspend fun save(record: PlayerRecord)
}
```

Test code uses `InMemoryPlayerRepository`. Production uses the full chain.

### WriteCoalescingPlayerRepository

- Wraps any `PlayerRepository`
- `save()` marks the record dirty and caches it in memory — no I/O
- `findByName()`/`findById()` check in-memory dirty cache before delegating
- `flushDirty()` writes only changed records; `flushAll()` writes everything
- `PersistenceWorker` calls `flushDirty()` on a configurable interval (default 5 s) from `Dispatchers.IO`
- On server shutdown: `worker.shutdown()` calls `flushAll()` before stopping

### YamlPlayerRepository (backend=YAML, default)

- Player files stored at `data/players/players/{id}.yaml`
- Sequential ID allocation from `data/players/next_player_id.txt`
- Case-insensitive name lookup (scans directory)
- Atomic writes via temp-file + rename (crash-safe)

### PostgresPlayerRepository (backend=POSTGRES)

- Uses Exposed DSL for type-safe SQL queries
- HikariCP connection pool (configurable pool size)
- Flyway migrations run automatically on startup (schema in `src/main/resources/db/migration/`)
- Case-insensitive name uniqueness enforced by a unique index on `name_lower`
- `save()` uses upsert (insert-or-update) keyed on the player ID
- `DatabaseManager` owns the HikariCP DataSource and Exposed `Database` instance

The easiest way to get a local Postgres instance is via `docker compose up -d` (see [Quick Start](#2-quick-start)), which starts a `postgres:16-alpine` container with database `ambonmud` and user/password `ambon`/`ambon` on port 5432.

### PlayerRecord

```kotlin
data class PlayerRecord(
    val id: PlayerId,
    val name: String,
    val roomId: RoomId,
    val constitution: Int,
    val level: Int,
    val xpTotal: Long,
    val createdAtEpochMs: Long,
    val lastSeenEpochMs: Long,
    val passwordHash: String,   // bcrypt
    val ansiEnabled: Boolean,
    val isStaff: Boolean = false,  // grant manually via YAML edit or DB update
)
```

The `data/players/` directory is git-ignored. Do not commit player save files.

To grant staff access:
- **YAML backend:** open `data/players/players/<id>.yaml` and add `isStaff: true`
- **Postgres backend:** set `is_staff = true` on the player's row in the `players` table

---

## 14. Redis Integration

**Files:** `src/main/kotlin/dev/ambon/redis/`, `src/main/kotlin/dev/ambon/bus/Redis*Bus.kt`, `src/main/kotlin/dev/ambon/persistence/RedisCachingPlayerRepository.kt`

Redis integration is **disabled by default** (`redis.enabled = false`). The server runs identically without Redis.

### RedisConnectionManager

- Lettuce-based lifecycle (`AutoCloseable`)
- Attempts connection at startup; logs warning and degrades gracefully on failure
- Exposes `commands` (sync) and `asyncCommands` (async) — both nullable; code must null-check
- `connectPubSub()` creates a separate connection for pub/sub (required by Lettuce)

### RedisCachingPlayerRepository

- Wraps `WriteCoalescingPlayerRepository` as delegate
- Key scheme:
  - `player:name:<lowercase>` → `<playerId>` (name → ID index)
  - `player:id:<playerId>` → JSON `PlayerRecord` (full record)
- Uses Jackson `ObjectMapper` with `KotlinModule` + `FAIL_ON_UNKNOWN_PROPERTIES=false`
- Writes: `SETEX` with configurable TTL (default 3600 s)
- Reads: Redis hit → return; Redis miss or error → delegate to YAML chain
- Gracefully falls back to delegate on any Redis exception

### Redis Bus Config

```yaml
ambonMUD:
  redis:
    enabled: true
    uri: "redis://localhost:6379"
    cacheTtlSeconds: 3600
    bus:
      enabled: true
      inboundChannel: "ambon:inbound"
      outboundChannel: "ambon:outbound"
      instanceId: ""           # auto-UUID if blank
```

### Running with Redis

The `docker compose up -d` stack (see [Quick Start](#2-quick-start)) includes Redis on port 6379. To enable it:

```bash
./gradlew run -Pconfig.ambonMUD.redis.enabled=true
```

Or run Redis standalone if you prefer:

```bash
docker run --rm -p 6379:6379 redis:7-alpine
```

---

## 15. gRPC Gateway Split & Deployment Modes

**Files:** `src/main/kotlin/dev/ambon/grpc/`, `src/main/kotlin/dev/ambon/gateway/`, `src/main/proto/`

Phase 4 of the scalability refactor splits the monolith into two independently deployable processes. The mode is controlled by `ambonMUD.mode` in `application.yaml`.

### Three Deployment Modes

| Mode | What runs | gRPC role |
|------|-----------|-----------|
| `STANDALONE` (default) | Everything in-process: engine + transports + persistence | None |
| `ENGINE` | `GameEngine` + persistence + `EngineGrpcServer`; no transports | gRPC server |
| `GATEWAY` | Transports + `OutboundRouter` + gRPC client to remote engine; no local game logic | gRPC client |

`Main.kt` routes to `MudServer`, `EngineServer`, or `GatewayServer` based on `config.mode`.

### gRPC Protocol

A single bidirectional streaming RPC connects each gateway to the engine:

```protobuf
service EngineService {
  rpc EventStream(stream InboundEventProto) returns (stream OutboundEventProto);
}
```

- **One stream per gateway** (not per session). All sessions from one gateway share one stream.
- `InboundEventProto` / `OutboundEventProto` use `oneof` for type safety; each carries a top-level `session_id`.
- `ProtoMapper.kt` provides `InboundEvent.toProto()` / `InboundEventProto.toDomain()` extension functions.

### Engine-Side Components

- **`EngineServiceImpl`**: Implements `EventStream`. For each connected gateway: collects inbound proto events → decodes via `ProtoMapper` → delivers to `InboundBus`. Registers `sessionId → channel` in `sessionToStream` map on `Connected`.
- **`GrpcOutboundDispatcher`**: Background coroutine that drains the engine's `OutboundBus` and routes each event to the owning gateway's send channel via `sessionToStream` lookup.
- **`EngineGrpcServer`**: Lifecycle wrapper (Netty-based); owns `EngineServiceImpl` + `GrpcOutboundDispatcher`.

### Gateway-Side Components

- **`GatewayServer`**: Creates a `ManagedChannel` to the engine, opens the bidirectional stream, and wires `GrpcInboundBus` + `GrpcOutboundBus`. Transport adapters (`BlockingSocketTransport`, `KtorWebSocketTransport`) use the gRPC buses.
- **`SnowflakeSessionIdFactory`**: Generates globally unique session IDs across multiple gateways. Bit layout: `[16-bit gatewayId][32-bit unix_seconds][16-bit sequence]`. `GatewayConfig.id` (0–65535) is the gateway's assigned ID.

### Disconnect Handling

When a gateway's gRPC stream closes (network failure or restart), the engine synthesizes `InboundEvent.Disconnected` for every session that was registered to that stream, cleanly removing them from the world.

### Gateway Reconnect

When the gateway detects a gRPC stream failure, it performs bounded exponential-backoff reconnect:

1. All existing local sessions are disconnected with an informational message.
2. The old inbound channel is closed so new session attempts fail immediately.
3. The gateway retries up to `gateway.reconnect.maxAttempts` times with exponential backoff (configurable initial/max delay and jitter factor).
4. Each attempt opens a new bidi gRPC stream and waits `gateway.reconnect.streamVerifyMs` to confirm the stream is healthy.
5. On success: the inbound bus is reattached and new connections are accepted.
6. On budget exhaustion: the gateway shuts down.

Configuration (`application.yaml`):
```yaml
ambonMUD:
  gateway:
    reconnect:
      maxAttempts: 10
      initialDelayMs: 1000
      maxDelayMs: 30000
      jitterFactor: 0.2
      streamVerifyMs: 2000
```

### Running in Split Mode

```bash
# Terminal 1 — start the engine
./gradlew run -Pconfig.ambonMUD.mode=ENGINE

# Terminal 2 — start a gateway (connects to engine on localhost:9090)
./gradlew run -Pconfig.ambonMUD.mode=GATEWAY
```

Config overrides:
```bash
# Engine on custom port
./gradlew run -Pconfig.ambonMUD.mode=ENGINE -Pconfig.ambonMUD.grpc.server.port=9191

# Gateway pointing to remote engine
./gradlew run -Pconfig.ambonMUD.mode=GATEWAY \
  -Pconfig.ambonMUD.grpc.client.engineHost=engine.example.com \
  -Pconfig.ambonMUD.grpc.client.enginePort=9090 \
  -Pconfig.ambonMUD.gateway.id=1
```

---

## 16. Metrics & Observability


**File:** `src/main/kotlin/dev/ambon/metrics/GameMetrics.kt`

AmbonMUD exposes Prometheus metrics via Micrometer. In `STANDALONE` and `GATEWAY` modes, the scrape endpoint is served by the Ktor web server at `http://localhost:8080/metrics`. In `ENGINE` mode (which has no Ktor server), a standalone `MetricsHttpServer` exposes the endpoint at `http://localhost:9090/metrics` (port configurable via `observability.metricsHttpPort`).

Key metrics:
- Online session count (gauge)
- Total connections / disconnections (counters)
- Combat rounds and kills (counters)
- Mob wandering moves (counter)
- HP regen ticks (counter)

The `docker-compose.yml` in the repo root starts a Prometheus + Grafana stack for local dashboards.

```bash
docker compose up -d
# Grafana: http://localhost:3000  (admin / admin)
```

**Package note:** Uses `io.micrometer.prometheusmetrics.PrometheusMeterRegistry` (Micrometer ≥ 1.12). Do not use the deprecated `io.micrometer.prometheus.*` package.

---

## 17. Staff / Admin System

**Gate:** `PlayerRecord.isStaff` / `PlayerState.isStaff`

Staff status is granted out-of-band. There is no in-game promotion command.

- **YAML backend:** edit the player's YAML file
  ```yaml
  # data/players/players/1.yaml
  isStaff: true
  ```
- **Postgres backend:** update the database row
  ```sql
  UPDATE players SET is_staff = true WHERE name_lower = 'alice';
  ```

The flag is copied from `PlayerRecord` → `PlayerState` at login (`PlayerRegistry.bindSession()`). All admin commands check `if (!playerState.isStaff)` before executing.

### Admin Commands

| Command | Effect |
|---------|--------|
| `goto <dest>` | Teleport self. `zone:room` = exact; `room` = current zone; `zone:` = zone start |
| `transfer <player> <room>` | Move a player to a room |
| `spawn <mob-template>` | Spawn a mob from a world template ID (local part match against `world.mobSpawns`) |
| `smite <target>` | Instantly kill a player or mob |
| `kick <player>` | Disconnect a player with a message |
| `shutdown` | Gracefully shut down the server (flush persistence, stop all transports) |

---

## 18. World Loading & YAML Format

**Loader:** `src/main/kotlin/dev/ambon/domain/world/load/WorldLoader.kt`
**Full spec:** `docs/world-zone-yaml-spec.md`

### Zone File Structure

```yaml
zone: myzone           # required — zone name prefix for all local IDs
lifespan: 30           # optional — minutes until zone auto-resets (0 = disabled)
startRoom: entrance    # required — must exist in this file's rooms

mobs:
  goblin:
    name: "a small goblin"
    room: cave           # local ID → normalized to myzone:cave

items:
  sword:
    displayName: "a rusty sword"
    description: "Pitted with age, still sharp."
    slot: hand           # head | body | hand (optional)
    damage: 2            # optional (default 0)
    armor: 0             # optional (default 0)
    constitution: 0      # optional (default 0)
    room: entrance       # optional (mutually exclusive with mob)
    matchByKey: false    # optional (default false)

rooms:
  entrance:
    title: "Cave Entrance"
    description: "Daylight fades behind you."
    exits:
      north: cave
      east: otherzone:border   # cross-zone: use fully qualified ID
  cave:
    title: "Cave Interior"
    description: "Damp walls drip."
    exits:
      south: entrance
```

### ID Normalization

Local IDs (no `:`) are automatically prefixed with the zone name. Fully qualified IDs (containing `:`) are used as-is. This means `entrance` becomes `myzone:entrance` and `otherzone:border` stays `otherzone:border`.

### Validation

The loader validates both per-file and cross-file (merged world):
- All exit targets resolve to existing rooms
- All mob rooms resolve
- Item room placements resolve; deprecated `mob` placement and dual `room`+`mob` placement are rejected by the loader
- No duplicate room/mob/item IDs across files
- `lifespan` values are consistent if a zone spans multiple files

### Adding World Content

Edit or add YAML files in `src/main/resources/world/`, then register them in `src/main/resources/application.yaml` under `world.resources`. No code changes required.

---

## 19. Configuration

**Schema:** `src/main/kotlin/dev/ambon/config/AppConfig.kt`
**Values:** `src/main/resources/application.yaml`
**Loader:** Hoplite library (strict validation via `validated()`)

### Top-Level Sections

| Section | Key Settings |
|---------|-------------|
| `mode` | `STANDALONE` (default), `ENGINE`, `GATEWAY` |
| `server` | `telnetPort` (4000), `webPort` (8080), `tickMillis` (100), `sessionOutboundQueueCapacity` (200) |
| `world` | `resources` — list of zone YAML classpath paths |
| `persistence` | `backend` (`YAML`/`POSTGRES`), `rootDir` — root directory for player files (YAML only) |
| `database` | `jdbcUrl`, `username`, `password`, `maxPoolSize`, `minimumIdle` (defaults match docker compose) |
| `login` | `maxWrongPasswordRetries` (3), `maxFailedAttemptsBeforeDisconnect` (3) |
| `engine.combat` | `minDamage`, `maxDamage`, `tickMillis`, `maxCombatsPerTick` |
| `engine.regen` | `baseIntervalMillis` (5000), `regenAmount` (1), `msPerConstitution`, `minIntervalMillis` |
| `engine.mob` | `minWanderDelayMillis`, `maxWanderDelayMillis`, `maxMovesPerTick`, `tiers` |
| `progression` | `xp.baseXp`, `xp.exponent`, `xp.linearXp`, `hpPerLevel`, `maxLevel`, `fullHealOnLevelUp` |
| `persistence.worker` | `flushIntervalMs` (5000), `enabled` (true) |
| `redis` | `enabled` (false), `uri`, `cacheTtlSeconds`, `bus.*` |
| `grpc.server` | `port` (9090) — gRPC listen port (engine mode) |
| `grpc.client` | `engineHost` (localhost), `enginePort` (9090) — engine address (gateway mode) |
| `gateway` | `id` (0) — 16-bit gateway ID for `SnowflakeSessionIdFactory` (0–65535) |
| `gateway.snowflake` | `idLeaseTtlSeconds` (300) — TTL for Redis gateway-ID exclusive lease |
| `gateway.reconnect` | `maxAttempts` (10), `initialDelayMs` (1000), `maxDelayMs` (30000), `jitterFactor` (0.2), `streamVerifyMs` (2000) |
| `observability` | Prometheus metrics endpoint |
| `logging` | `level` (INFO), `packageLevels` — per-package level overrides |

When adding a new config key, update both `AppConfig.kt` (data class + `validated()` checks) and `application.yaml`.

### Runtime Overrides

Any config value can be overridden at the command line using `-P` project properties. The pattern is `-Pconfig.<key>=<value>`, which maps to Hoplite's `config.override.<key>` system property internally:

```bash
# Override root log level
./gradlew run -Pconfig.ambonMUD.logging.level=DEBUG

# Override a specific package log level
./gradlew run -Pconfig.ambonMUD.logging.packageLevels.dev.ambon.transport=DEBUG

# Override server port
./gradlew run -Pconfig.ambonMUD.server.telnetPort=5000

# Override tick speed
./gradlew run -Pconfig.ambonMUD.server.tickMillis=500

# Use PostgreSQL backend (connection defaults match docker compose)
./gradlew run -Pconfig.ambonMUD.persistence.backend=POSTGRES
```

This works in all shells including Windows PowerShell — no quoting issues.

### Logging

Structured logging uses [Logback](https://logback.qos.ch/) + [kotlin-logging](https://github.com/oshai/kotlin-logging). Default configuration is in `src/main/resources/logback.xml` (INFO level, console output). Tests use `src/test/resources/logback-test.xml` (WARN+ only, suppresses test noise).

Key log events by package:

| Package | Notable Events |
|---------|---------------|
| `dev.ambon` (Main, MudServer) | Startup ports, metrics endpoint, server stopped |
| `dev.ambon.engine` | Player login/logout (INFO), slow tick >2× tickMillis (WARN), unhandled tick exception (ERROR) |
| `dev.ambon.engine.scheduler` | Failed scheduled action with throwable (ERROR), dropped actions (WARN) |
| `dev.ambon.transport` | Bind (INFO), connection lifecycle (DEBUG), backpressure/protocol disconnect (WARN), read errors (ERROR) |

To trace connection lifecycle at runtime:

```bash
./gradlew run -Pconfig.ambonMUD.logging.packageLevels.dev.ambon.transport=DEBUG
```

---

## 20. Testing

### Test Helpers

| Helper | File | Use For |
|--------|------|---------|
| `MutableClock` | `src/test/kotlin/.../test/MutableClock.kt` | Deterministic time in tests |
| `InMemoryPlayerRepository` | `src/test/kotlin/.../test/` | Fast player stub |
| World fixtures | `src/test/resources/world/` | Valid + invalid YAML loading |

### Test Coverage Map

| Test Class | What It Covers |
|------------|---------------|
| `CommandParserTest` | All command aliases and parse variants |
| `CommandRouterTest` | Command execution, broadcasts, error cases |
| `CommandRouterAdminTest` | Staff/admin command gating and execution |
| `CommandRouterBroadcastTest` | Room and global broadcast behavior |
| `CommandRouterItemsTest` | Item get/drop/wear/remove command execution |
| `CommandRouterScoreTest` | Score command output |
| `NamesTellGossipTest` | Tell and gossip name resolution edge cases |
| `CombatSystemTest` | Damage, HP, death, XP grant, armor math |
| `GameEngineIntegrationTest` | Full login-to-gameplay integration |
| `GameEngineLoginFlowTest` | Login FSM edge cases and takeover |
| `GameEngineAnsiBehaviorTest` | ANSI toggle and rendering integration |
| `MobRegistryTest` | Mob registration, room membership index |
| `MobSystemTest` | Wander scheduling, time-gating, per-tick cap |
| `PlayerProgressionTest` | XP curves, level computation, HP scaling |
| `ItemRegistryTest` | Equip, inventory, loot drops, keyword matching |
| `SchedulerTest` | Delayed/recurring callback scheduling |
| `SchedulerDropsTest` | Scheduler per-tick cap and action dropping |
| `AppConfigLoaderTest` | Config loading and validation |
| `YamlPlayerRepositoryTest` | File I/O, atomic writes, case-insensitive lookup |
| `PostgresPlayerRepositoryTest` | Exposed CRUD, upsert, unique-name enforcement (H2 in PG mode) |
| `RedisCachingPlayerRepositoryTest` | Redis L2 cache hit/miss, TTL, fallback |
| `WriteCoalescingPlayerRepositoryTest` | Dirty tracking, coalescing (N saves → 1 write) |
| `PersistenceWorkerTest` | Periodic flush, shutdown flush |
| `LocalInboundBusTest` | Channel wrapping, capacity behavior |
| `LocalOutboundBusTest` | Channel wrapping |
| `RedisInboundBusTest` | Pub/sub delivery, instanceId filtering |
| `RedisOutboundBusTest` | Pub/sub delivery, instanceId filtering |
| `GrpcInboundBusTest` | Delegate pattern + gRPC forward |
| `GrpcOutboundBusTest` | Delegate pattern + gRPC receive |
| `WorldLoaderTest` | YAML parsing, per-file and cross-file validation |
| `AnsiRendererTest` | Color output, escape code correctness |
| `PlainRendererTest` | Plain text rendering (no escape codes) |
| `TelnetLineDecoderTest` | Telnet protocol byte stripping |
| `OutboundRouterTest` | Event routing to per-session renderers |
| `OutboundRouterPromptCoalescingTest` | Consecutive prompt collapsing |
| `OutboundRouterAnsiControlsTest` | ANSI control event routing |
| `KtorWebSocketTransportTest` | WebSocket connect/disconnect |
| `MetricsEndpointTest` | Prometheus scrape endpoint via Ktor |
| `GameMetricsTest` | Metric registration and updates |
| `MetricsHttpServerTest` | Standalone metrics HTTP server (ENGINE mode) |
| `LoginScreenLoaderTest` | Login banner file loading |
| `LoginScreenRendererTest` | Login banner ANSI rendering |
| `ProtoMapperTest` | Round-trip every InboundEvent/OutboundEvent variant through proto |
| `SnowflakeSessionIdFactoryTest` | Uniqueness, monotonicity, bit-field correctness |
| `AtomicSessionIdFactoryTest` | Sequential ID allocation |
| `GatewayIdLeaseManagerTest` | Redis-based gateway ID exclusive lease |
| `EngineServiceImplTest` | In-process gRPC bidirectional streaming |
| `EngineGrpcServerTest` | gRPC server lifecycle |
| `GrpcOutboundDispatcherTest` | Per-session stream routing, unknown-session drop |
| `GatewayEngineIntegrationTest` | Full in-process connect → login → say → quit over gRPC |

### Testing Expectations

- **Every behavioral change needs tests.** This codebase treats tests as design constraints, not afterthoughts.
- Use `MutableClock` for any time-dependent logic — never depend on wall-clock timing in tests.
- Use `InMemoryPlayerRepository` for engine tests — keeps them fast and deterministic.
- Run `./gradlew ktlintCheck test` before considering any change finalized.

---

## 21. Common Change Playbooks

### Add a New Command

1. `CommandParser.kt` — add variant to `Command` sealed interface; add parsing logic; add aliases
2. `CommandRouter.kt` — add `when` branch; emit `OutboundEvent`s; emit prompt on all paths
3. `CommandParserTest` — add parse cases
4. `CommandRouterTest` or `GameEngineIntegrationTest` — add behavior tests

### Add a New Item Stat

1. `Item.kt` in `domain/items/` — add field
2. `ItemFile.kt` in `domain/world/data/` — add field for YAML deserialization
3. `WorldLoader.kt` — map `ItemFile` field to `Item`
4. `docs/world-zone-yaml-spec.md` — document the new field
5. Update relevant system (e.g. `CombatSystem`, `RegenSystem`) to use the stat
6. Add tests

### Add/Modify World Content (No Code Change)

Edit YAML in `src/main/resources/world/`. If adding a new file, register it in `application.yaml` under `world.resources`. Run `./gradlew test` to verify world loads cleanly.

### Add a Config Key

1. `AppConfig.kt` — add field to the appropriate data class; add validation in `validated()` if needed
2. `application.yaml` — add the default value
3. `AppConfigTest` — verify loading (if validation logic changed)

### Add a Persistence Field

1. `PlayerRecord.kt` — add field with a default value (backward compat with existing YAML and Redis JSON)
2. `PlayerRegistry.kt` — map field to `PlayerState` on load; persist on save
3. `YamlPlayerRepositoryTest` — verify round-trip through YAML
4. `RedisCachingPlayerRepositoryTest` — verify round-trip through Jackson/Redis
5. For Postgres: add a Flyway migration (`V<N>__description.sql`), update `PlayersTable.kt` (column definition), and update `PostgresPlayerRepository.kt` (`toPlayerRecord()`, `insert`, `upsert` mappings)
6. `PostgresPlayerRepositoryTest` — verify round-trip through H2/Postgres

### Add a Staff/Admin Command

1. `CommandParser.kt` — add to the admin command block; add `Command.SomeName` variant
2. `CommandRouter.kt` — add `when` branch; gate with `if (!playerState.isStaff) { ... return }`
3. `CommandRouterAdminTest` — add tests for gated + ungated behavior

### Add a New Transport

1. Implement a class that reads from the `inbound: InboundBus` and writes to `outbound: OutboundBus`
2. Wire it in `MudServer.kt`
3. No engine or gameplay changes needed

---

## 22. Critical Invariants: Never Break These

### Engine/Transport Boundary
- Engine code must never reference transport types
- Transport code must never contain gameplay state or logic
- All engine output is semantic `OutboundEvent`s — ANSI escape bytes live only in renderers

### Single-Threaded Engine
- All mutable engine state is accessed from the engine's dedicated coroutine dispatcher only
- Never dispatch blocking I/O from inside `GameEngine`, `CommandRouter`, or any system

### Use `Clock` for Time
- `GameEngine` and all systems receive an injected `Clock` interface
- Never call `System.currentTimeMillis()` or `Instant.now()` in engine code

### ID Namespacing
- Every `RoomId`, `MobId`, `ItemId` must have the format `zone:local`
- The `RoomId` constructor enforces this — don't bypass it

### Name and Password Validation
- Player name: 2–16 chars, `[a-zA-Z0-9_]`, cannot start with digit
- Password: non-blank, max 72 chars (BCrypt hard limit)
- Enforce these in `PlayerRegistry` — do not relax them silently

### Atomic YAML Writes
- `YamlPlayerRepository` writes to a temp file then renames atomically
- Do not replace this with direct file writes

### Item Placement Rules (Current Loader Behavior)
- The loader currently accepts `room` placement or unplaced items
- `mob` placement is deprecated/rejected, and setting both `room` and `mob` is rejected
- Treat this as loader behavior (subject to future evolution), not a core engine architecture boundary

### Per-Tick Processing Caps
- `CombatSystem`, `MobSystem`, and inbound event handling all have `max*PerTick` limits
- These prevent any single tick from running indefinitely under load — preserve them when adding new tick-driven systems

### Event Bus Boundary
- The engine always receives `InboundBus`/`OutboundBus` — never raw channels
- `Redis*Bus` wraps `Local*Bus`; both honor the same interface contract
- A `Redis*Bus` failure must never propagate to the engine; catch and log

### Persistence Chain Ordering
- `MudServer.kt` constructs the chain in this order (outermost first):
  `WriteCoalescing` → `RedisCache` (if enabled) → `Yaml` or `Postgres` (selected by `ambonMUD.persistence.backend`)
- `PlayerRegistry` calls `repo.save()` — it does not know which layer handles it

---

## Further Reading

- `DesignDecisions.md` — explains the "why" behind every major architectural choice
- `AGENTS.md` — the full engineering playbook (change procedures, invariants)
- `docs/world-zone-yaml-spec.md` — complete YAML world format reference
- `docs/scalability-plan-brainstorm.md` — 4-phase scalability roadmap (all phases complete)
