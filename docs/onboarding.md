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
12. [Persistence Layer](#12-persistence-layer)
13. [World Loading & YAML Format](#13-world-loading--yaml-format)
14. [Configuration](#14-configuration)
15. [Testing](#15-testing)
16. [Common Change Playbooks](#16-common-change-playbooks)
17. [Critical Invariants: Never Break These](#17-critical-invariants-never-break-these)

---

## 1. What Is This?

AmbonMUD is a **single-process, tick-based MUD (Multi-User Dungeon) server** written in Kotlin/JVM. It supports simultaneous telnet and WebSocket clients, YAML-defined world content, bcrypt-hashed player accounts, and real-time combat/mob/regen systems.

The codebase is intentionally designed like a production backend service, not a toy project. The emphasis is on clean architectural boundaries, testability, and incremental extensibility.
    
Key stats:
- **Transport**: Telnet (port 4000) + WebSocket / browser demo (port 8080)
- **Engine**: Single-threaded coroutine dispatcher, 100 ms ticks
- **Persistence**: Per-player YAML files with atomic writes
- **World content**: YAML zone files, multi-zone with cross-zone exits

---

## 2. Quick Start

### Prerequisites

- JDK 17+ (CI runs Java 21; the Gradle toolchain targets 17)
- No other infrastructure needed

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
│   │   ├── mob/                   # MobState
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
│   ├── transport/                 # Protocol adapters
│   │   ├── BlockingSocketTransport.kt  # Telnet server
│   │   ├── KtorWebSocketTransport.kt   # WebSocket / Ktor
│   │   ├── NetworkSession.kt      # Per-session I/O (telnet)
│   │   ├── OutboundRouter.kt      # Route OutboundEvents → renderers
│   │   ├── AnsiRenderer.kt        # ANSI color rendering
│   │   ├── PlainRenderer.kt       # Plain text (no color)
│   │   ├── TelnetLineDecoder.kt   # Telnet protocol handling
│   │   └── InboundBackpressure.kt # Drop / disconnect slow inbound
│   ├── persistence/
│   │   ├── PlayerRepository.kt    # Interface (swappable)
│   │   ├── YamlPlayerRepository.kt# YAML impl with atomic writes
│   │   └── PlayerRecord.kt        # Serializable player data
│   └── ui/login/                  # Login banner rendering
├── src/main/resources/
│   ├── application.yaml           # Runtime config (ports, tuning, world files)
│   ├── login.txt                  # Login banner text
│   ├── login.styles.yaml          # ANSI styles for banner
│   ├── world/                     # Zone YAML files
│   │   ├── ambon_hub.yaml
│   │   ├── demo_ruins.yaml
│   │   └── noecker_resume.yaml
│   └── web/                       # Static browser client (xterm.js)
├── src/test/kotlin/               # Full test suite
├── src/test/resources/world/      # World fixtures (valid + invalid)
├── data/players/                  # Runtime player saves (git-ignored)
├── docs/
│   ├── world-zone-yaml-spec.md    # World YAML format contract
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
                │ InboundEvent channel
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
                │ OutboundEvent channel
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

**The two rules that everything else follows from:**

1. **Engine communicates only via `InboundEvent` / `OutboundEvent`.** No transport code in the engine; no gameplay logic in transport.
2. **GameEngine is single-threaded.** Never call blocking I/O inside engine systems. Use the injected `Clock` for any time-based logic.

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
| Social | `Who`, `Help` |
| UI / Settings | `Look`, `Exits`, `AnsiOn`, `AnsiOff`, `Clear`, `Colors` |
| System | `Quit`, `Noop`, `Invalid`, `Unknown` |

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
)
```

### PlayerState

Runtime-only (not persisted directly):

```kotlin
// Key fields:
sessionId, name, roomId, playerId
hp, maxHp, baseMaxHp, constitution
level, xpTotal
ansiEnabled
```

Persisted via `PlayerRecord` to YAML on save.

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

## 12. Persistence Layer

**Files:** `src/main/kotlin/dev/ambon/persistence/`

### Interface

```kotlin
interface PlayerRepository {
    suspend fun findByName(name: String): PlayerRecord?
    suspend fun findById(id: PlayerId): PlayerRecord?
    suspend fun create(...): PlayerRecord
    suspend fun save(record: PlayerRecord)
}
```

Test code uses `InMemoryPlayerRepository`. Production uses `YamlPlayerRepository`.

### YamlPlayerRepository

- Player files stored at `data/players/players/{id}.yaml`
- Sequential ID allocation from `data/players/next_player_id.txt`
- Case-insensitive name lookup (scans directory)
- Atomic writes via temp-file + rename (crash-safe)

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
)
```

The `data/players/` directory is git-ignored. Do not commit player save files.

---

## 13. World Loading & YAML Format

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
- All item placements resolve (room or mob, never both)
- No duplicate room/mob/item IDs across files
- `lifespan` values are consistent if a zone spans multiple files

### Adding World Content

Edit or add YAML files in `src/main/resources/world/`, then register them in `src/main/resources/application.yaml` under `world.resources`. No code changes required.

---

## 14. Configuration

**Schema:** `src/main/kotlin/dev/ambon/config/AppConfig.kt`
**Values:** `src/main/resources/application.yaml`
**Loader:** Hoplite library (strict validation via `validated()`)

### Top-Level Sections

| Section | Key Settings |
|---------|-------------|
| `server` | `telnetPort` (4000), `webPort` (8080), `tickMillis` (100), `sessionOutboundQueueCapacity` (200) |
| `world` | `resources` — list of zone YAML classpath paths |
| `persistence` | `dataDir` — root directory for player files |
| `login` | `maxPasswordAttempts` (3) |
| `engine.combat` | `minDamage`, `maxDamage`, `roundMillis`, `maxCombatsPerTick` |
| `engine.regen` | `baseIntervalMillis` (5000), `hpPerTick` (1), `msPerConstitution` |
| `engine.mob` | `minWanderDelayMillis`, `maxWanderDelayMillis`, `maxMovesPerTick` |
| `progression` | `xp.baseXp`, `xp.exponent`, `xp.linearXp`, `hpPerLevel`, `maxLevel`, `fullHealOnLevelUp` |

When adding a new config key, update both `AppConfig.kt` (data class + `validated()` checks) and `application.yaml`.

---

## 15. Testing

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
| `CombatSystemTest` | Damage, HP, death, XP grant, armor math |
| `GameEngineIntegrationTest` | Full login-to-gameplay integration |
| `GameEngineLoginFlowTest` | Login FSM edge cases and takeover |
| `MobSystemTest` | Wander scheduling, time-gating, per-tick cap |
| `PlayerProgressionTest` | XP curves, level computation, HP scaling |
| `ItemRegistryTest` | Equip, inventory, loot drops, keyword matching |
| `YamlPlayerRepositoryTest` | File I/O, atomic writes, case-insensitive lookup |
| `WorldLoaderTest` | YAML parsing, per-file and cross-file validation |
| `AnsiRendererTest` | Color output, escape code correctness |
| `KtorWebSocketTransportTest` | WebSocket connect/disconnect |

### Testing Expectations

- **Every behavioral change needs tests.** This codebase treats tests as design constraints, not afterthoughts.
- Use `MutableClock` for any time-dependent logic — never depend on wall-clock timing in tests.
- Use `InMemoryPlayerRepository` for engine tests — keeps them fast and deterministic.
- Run `./gradlew ktlintCheck test` before considering any change finalized.

---

## 16. Common Change Playbooks

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

1. `PlayerRecord.kt` — add field (keep backward compatibility or migrate existing files)
2. `PlayerRegistry.kt` — map field to `PlayerState` on load; persist on save
3. `YamlPlayerRepositoryTest` — add coverage

### Add a New Transport

1. Implement a class that reads from the `inbound: Channel<InboundEvent>` and writes to `outbound: Channel<OutboundEvent>`
2. Wire it in `MudServer.kt`
3. No engine or gameplay changes needed

---

## 17. Critical Invariants: Never Break These

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

### Item Placement Exclusivity
- An item can be in a room, on a mob, or unplaced — never more than one
- Enforced by `WorldLoader` at load time; maintain this in `ItemRegistry`

### Per-Tick Processing Caps
- `CombatSystem`, `MobSystem`, and inbound event handling all have `max*PerTick` limits
- These prevent any single tick from running indefinitely under load — preserve them when adding new tick-driven systems

---

## Further Reading

- `DesignDecisions.md` — explains the "why" behind every major architectural choice
- `AGENTS.md` — the full engineering playbook (change procedures, invariants)
- `docs/world-zone-yaml-spec.md` — complete YAML world format reference
