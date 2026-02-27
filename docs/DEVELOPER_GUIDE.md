# AmbonMUD — Developer Guide

Welcome! This guide takes you from zero to productive on the AmbonMUD codebase in minimal time.

**What is AmbonMUD?** A production-grade, tick-based MUD (Multi-User Dungeon) server in Kotlin with telnet + WebSocket transports, YAML-defined world content, class-based character progression, 102 class-specific abilities, real-time combat, and three deployment modes (STANDALONE, ENGINE, GATEWAY) for horizontal scaling.

---

## Table of Contents

1. [Prerequisites & Setup](#1-prerequisites--setup)
2. [Quick Start](#2-quick-start)
3. [Project Layout](#3-project-layout)
4. [Architecture & Core Contracts](#4-architecture--core-contracts)
5. [The Game Engine](#5-the-game-engine)
6. [Command System](#6-command-system)
7. [Domain Model](#7-domain-model)
8. [Subsystems](#8-subsystems)
9. [Persistence](#9-persistence)
10. [Configuration](#10-configuration)
11. [Deployment Modes](#11-deployment-modes)
12. [Testing](#12-testing)
13. [Common Tasks](#13-common-tasks)
14. [Troubleshooting](#14-troubleshooting)
15. [Cloud/Remote Development](#15-cloudremote-development)

---

## 1. Prerequisites & Setup

**Requirements:**
- JDK 21 (CI runs on Java 21)
- Git
- Optional: Docker & Docker Compose (for PostgreSQL, Redis, Prometheus, Grafana)

**Clone & build:**
```bash
git clone https://github.com/jnoecker/AmbonMUD.git
cd AmbonMUD
./gradlew build
```

**Verify installation:**
```bash
./gradlew test
```

---

## 2. Quick Start

**Start the server (YAML persistence, in-memory, no Docker needed):**
```bash
./gradlew run
```

**Connect:**
- Telnet: `telnet localhost 4000`
- Browser: `http://localhost:8080`

**Launch demo with browser auto-open:**
```bash
./gradlew demo
```

**Run tests:**
```bash
./gradlew test
```

**Lint (Kotlin style):**
```bash
./gradlew ktlintCheck
```

**Full infrastructure (Docker Compose):**
```bash
docker compose up -d  # Brings up PostgreSQL, Redis, Prometheus, Grafana
./gradlew run -Pconfig.ambonMUD.persistence.backend=POSTGRES
./gradlew run -Pconfig.ambonMUD.redis.enabled=true
```

---

## 3. Project Layout

```
src/main/kotlin/dev/ambon/
├── Main.kt                      # Entry point
├── MudServer.kt                 # Bootstrap & wiring for STANDALONE/ENGINE modes
├── GatewayServer.kt             # Bootstrap for GATEWAY mode
├── config/                      # Configuration schema (AppConfig.kt)
├── engine/                      # Game logic (GameEngine.kt, CommandRouter, systems)
│   ├── commands/                # CommandParser.kt, CommandRouter.kt, CommandHandler.kt
│   │   └── handlers/            # NavigationHandler, CombatHandler, ItemHandler, etc.
│   ├── abilities/               # AbilitySystem, spell definitions
│   ├── status/                  # StatusEffectSystem, status effect definitions
│   ├── events/                  # InboundEvent, OutboundEvent (sealed types)
│   ├── items/                   # ItemRegistry
│   ├── behavior/                # NPC behavior trees
│   ├── dialogue/                # NPC dialogue system
│   ├── scheduler/               # Delayed/recurring callbacks
│   ├── CombatSystem.kt          # Combat logic
│   ├── MobSystem.kt             # NPC movement/AI
│   ├── RegenSystem.kt           # HP/mana regeneration
│   ├── PlayerRegistry.kt        # Session ↔ Player, login FSM
│   ├── PlayerProgression.kt     # XP curves, leveling
│   ├── GmcpEmitter.kt           # GMCP structured data
│   ├── GroupSystem.kt           # Party/group logic
│   ├── QuestSystem.kt           # Quest tracking
│   ├── AchievementSystem.kt     # Achievements
│   └── ShopRegistry.kt          # Shops & economy
├── transport/                   # Network I/O
│   ├── BlockingSocketTransport.kt  # Telnet server
│   ├── KtorWebSocketTransport.kt   # WebSocket / browser client
│   ├── OutboundRouter.kt        # Event → renderer dispatch
│   ├── AnsiRenderer.kt          # Color rendering
│   └── PlainRenderer.kt         # Plain text rendering
├── persistence/                 # Player save/load
│   ├── PlayerRepository.kt      # Interface
│   ├── YamlPlayerRepository.kt  # YAML backend
│   ├── PostgresPlayerRepository.kt  # PostgreSQL backend
│   ├── RedisCachingPlayerRepository.kt  # L2 cache
│   ├── WriteCoalescingPlayerRepository.kt  # Write-behind
│   └── PersistenceWorker.kt     # Background flush
├── bus/                         # Event bus (phase 1)
│   ├── InboundBus.kt            # Interface
│   ├── OutboundBus.kt           # Interface
│   ├── LocalInboundBus.kt       # Single-process impl
│   ├── LocalOutboundBus.kt      # Single-process impl
│   ├── RedisInboundBus.kt       # Multi-process impl
│   ├── RedisOutboundBus.kt      # Multi-process impl
│   ├── GrpcInboundBus.kt        # gRPC gateway impl
│   └── GrpcOutboundBus.kt       # gRPC gateway impl
├── grpc/                        # gRPC engine/gateway (phase 4)
│   ├── EngineGrpcServer.kt
│   ├── EngineServiceImpl.kt
│   └── ProtoMapper.kt
├── sharding/                    # Zone-based sharding (phase 5)
│   ├── ZoneRegistry.kt
│   ├── InterEngineBus.kt
│   ├── HandoffManager.kt
│   ├── InstanceSelector.kt
│   └── PlayerLocationIndex.kt
├── domain/                      # Domain model
│   ├── PlayerClass.kt           # Warrior, Mage, Cleric, Rogue
│   ├── Race.kt                  # Human, Elf, Dwarf, Halfling
│   └── world/                   # Room, Direction, World, etc.
│       └── load/WorldLoader.kt  # YAML → domain
├── redis/                       # Redis infrastructure
├── session/                     # Session ID allocation
├── metrics/                     # Prometheus metrics
└── ui/login/                    # Login banner rendering

src/main/resources/
├── application.yaml             # Runtime config
├── db/migration/                # Flyway SQL migrations (Postgres)
├── world/                       # Zone YAML files
│   ├── tutorial_glade.yaml
│   ├── ambon_hub.yaml
│   └── ... (8 zones total)
├── web/                         # Legacy static web client
└── web-v3/                      # Current static web client bundle

src/test/kotlin/
├── dev/ambon/engine/            # ~50 engine & command tests
├── dev/ambon/persistence/       # YAML, PostgreSQL, Redis tests
├── dev/ambon/transport/         # Telnet, WebSocket tests
├── dev/ambon/bus/               # Event bus tests
├── dev/ambon/world/load/        # World loader tests
└── dev/ambon/test/              # Test utilities (MutableClock, helpers)

docs/
├── ARCHITECTURE.md              # Design decisions & architectural principles
├── WORLD_YAML_SPEC.md           # Zone YAML format specification
├── ROADMAP.md                   # Planned features & roadmap
├── WEB_CLIENT_V3.md             # Web client v3 architecture & gaps
├── SCALING_STORY.md             # Scaling narrative (interview talk track)
└── STYLE_GUIDE.md               # UI design system spec

CLAUDE.md                         # Claude Code orientation (DO NOT MODIFY)
AGENTS.md                         # Engineering playbook (DO NOT MODIFY)
```

---

## 4. Architecture & Core Contracts

### Three Inviolable Rules

1. **Engine is isolated.** The engine communicates only via `InboundEvent` / `OutboundEvent`. No transport code touches the engine; no gameplay logic leaks into transport.
2. **Engine is single-threaded.** `GameEngine` runs on a dedicated dispatcher with a 100 ms tick loop. Never call blocking I/O inside engine systems. Use the injected `Clock` for time-based logic.
3. **Engine uses bus interfaces.** Pass `InboundBus` / `OutboundBus` to the engine; never raw `Channel` references. This enables swappable implementations (Local, Redis, gRPC).

### Data Flow

```
Clients (telnet / browser)
        │
        ▼
Transports  (decode → InboundEvent, render OutboundEvent)
        │
        ▼
InboundBus / OutboundBus  (Local, Redis, or gRPC-wrapped)
        │
        ▼
GameEngine  (100 ms tick loop, CommandRouter, subsystems)
        │
        ▼
OutboundRouter  (dispatch to AnsiRenderer / PlainRenderer)
        │
        ▼
Sessions  (telnet sockets or WebSocket clients)
```

### Event Model

**InboundEvent** (sealed interface):
- `Connected(sessionId, defaultAnsiEnabled)` — new client
- `Disconnected(sessionId, reason)` — client lost
- `LineReceived(sessionId, line)` — one line of text
- `GmcpReceived(sessionId, gmcpPackage, jsonData)` — structured GMCP data

**OutboundEvent** (sealed interface):
- `SendText`, `SendInfo`, `SendError` — text to player
- `SendPrompt` — show `> ` prompt
- `ShowLoginScreen`, `SetAnsi`, `ClearScreen` — UI control
- `Close(sessionId, reason)` — disconnect session
- `GmcpData(sessionId, gmcpPackage, jsonData)` — telemetry to client

---

## 5. The Game Engine

**File:** `src/main/kotlin/dev/ambon/engine/GameEngine.kt`

### Tick Loop (100 ms)

Each tick:
1. Drain up to `maxInboundEventsPerTick` from the inbound bus
2. `MobSystem.tick()` — NPC wandering
3. `CombatSystem.tick()` — active fights
4. `RegenSystem.tick()` — HP/mana regeneration
5. `Scheduler.runDue()` — delayed callbacks
6. `resetZonesIfDue()` — zone respawns (if lifespan expired)

### Inbound Handler

For each `InboundEvent`:
- `Connected` → register session, show login screen, start login FSM
- `Disconnected` → clean up session, broadcast to room, end combat
- `LineReceived` → if in login FSM, advance FSM; else pass to `CommandRouter`

### Zone Resets

If a zone has `lifespan > 0` (minutes), the engine periodically respawns all mobs and items in that zone and notifies affected players.

---

## 6. Command System

### CommandParser

**File:** `src/main/kotlin/dev/ambon/engine/commands/CommandParser.kt`

Pure function `parse(line: String): Command` that returns a sealed `Command` variant. No side effects.

**Command Categories:**

| Category | Examples |
|----------|----------|
| Movement | `Move(dir)`, `LookDir(dir)` |
| Combat | `Kill`, `Flee`, `Cast` |
| Items | `Get`, `Drop`, `Wear`, `Remove`, `Inventory`, `Equipment`, `Use`, `Give` |
| Communication | `Say`, `Tell`, `Gossip`, `Whisper`, `Emote`, `Ooc` |
| Character | `Score`, `Balance` (gold) |
| Progression | `Spells`, `Effects`, `QuestLog` |
| Economy | `Buy`, `Sell`, `ShopList` |
| Admin | `Goto`, `Transfer`, `Spawn`, `Smite`, `Kick`, `Shutdown` (staff only) |

### CommandRouter

**File:** `src/main/kotlin/dev/ambon/engine/commands/CommandRouter.kt`

Thin dispatch layer (~62 lines) that routes each `Command` variant to the appropriate handler. All gameplay logic lives in handler classes under `commands/handlers/`, each implementing the `CommandHandler` interface:

- `NavigationHandler` — movement, look, exits
- `CombatHandler` — kill, flee, cast
- `CommunicationHandler` — say, tell, gossip, emote
- `ItemHandler` — get, drop, wear, remove, inventory, equipment
- `ShopHandler` — buy, sell, list
- `DialogueQuestHandler` — talk, choice, quest commands
- `GroupHandler` — party invite/accept/leave/kick
- `ProgressionHandler` — score, spells, effects, achievements
- `WorldFeaturesHandler` — zone-specific interactions
- `AdminHandler` — goto, transfer, spawn, smite, kick, shutdown (staff only)
- `UiHandler` — help, clear, colors, ansi, phase

### Adding a New Command

1. Add variant to `Command` sealed interface in `CommandParser.kt`
2. Add parsing logic in `CommandParser.parse()`
3. Implement the handler in the appropriate file under `commands/handlers/` (or add a new handler class implementing `CommandHandler` and wire it in `GameEngine.kt`)
4. Add tests in `CommandParserTest` and `CommandRouterTest`

---

## 7. Domain Model

### ID Types

All IDs are inline value classes with namespacing:
- `RoomId(value: String)` — format `zone:room` (e.g. `demo:trailhead`)
- `MobId(value: String)` — format `zone:mob_id`
- `ItemId(value: String)` — format `zone:item_id`
- `SessionId(value: String)` — unique per session

### Player Attributes

Six primary attributes (base 10, modified by race):
- **STR** (Strength) — scales melee damage
- **DEX** (Dexterity) — adds dodge chance
- **CON** (Constitution) — scales HP regen
- **INT** (Intelligence) — scales spell damage
- **WIS** (Wisdom) — scales mana regen
- **CHA** (Charisma) — reserved for future use

### PlayerState (Runtime)

```kotlin
sessionId: SessionId
name: String                          // 2-16 chars
playerId: PlayerId
race: Race                            // Human, Elf, Dwarf, Halfling
playerClass: PlayerClass              // Warrior, Mage, Cleric, Rogue
strength, dexterity, ..., charisma: Int

hp: Int, maxHp: Int
mana: Int, maxMana: Int
level: Int, xpTotal: Long
gold: Long                            // persistent currency

roomId: RoomId
ansiEnabled: Boolean
isStaff: Boolean
```

Persisted via `PlayerRecord` to YAML or PostgreSQL on save.

### PlayerRecord (Persistent)

Serializable DTO; same fields as `PlayerState` plus timestamps.

---

## 8. Subsystems

### CombatSystem

**File:** `src/main/kotlin/dev/ambon/engine/CombatSystem.kt`

- 1v1 player-vs-mob combat
- Melee damage: random in `[minDamage, maxDamage]` scaled by STR, minus mob's armor
- Spell damage: bypasses armor, scales with INT
- Dodge: DEX provides dodge chance
- One round per second
- On mob death: drop items, grant XP, auto-level if threshold crossed
- `Flee` ends combat immediately

### MobSystem

**File:** `src/main/kotlin/dev/ambon/engine/MobSystem.kt`

- Mobs wander to random adjacent rooms on per-mob timers (5–12 s, randomized)
- Disabled during combat
- Broadcasts `"<name> leaves <dir>."` and `"<name> enters from <dir>."` to affected rooms

### AbilitySystem

**File:** `src/main/kotlin/dev/ambon/engine/abilities/AbilitySystem.kt`

- **102 abilities** across 4 classes (25+ per class, levels 1–50)
- Mana pool; mana cost deducted on cast
- Per-ability cooldowns (tracked per-session)
- Auto-learned on level-up (based on class and level)
- `cast <spell> [target]` command, `spells` list command
- Effect types: `DIRECT_DAMAGE`, `DIRECT_HEAL`, `APPLY_STATUS`, `AREA_DAMAGE`, `TAUNT`

### StatusEffectSystem

**File:** `src/main/kotlin/dev/ambon/engine/status/StatusEffectSystem.kt`

- Timed status effects: DoT, HoT, STAT_BUFF, STAT_DEBUFF, STUN, ROOT, SHIELD
- Defined in `application.yaml` under `ambonMUD.engine.statusEffects.definitions`
- Configurable stacking rules (REFRESH, STACK, NONE)
- `effects`, `buffs`, `debuffs` commands expose active effects to players

### RegenSystem

**File:** `src/main/kotlin/dev/ambon/engine/RegenSystem.kt`

- HP regen: base 5 s interval, CON shortens; restores 1 HP per tick
- Mana regen: base 5 s interval, WIS shortens; restores 1 mana per tick

### ItemRegistry

**File:** `src/main/kotlin/dev/ambon/engine/items/ItemRegistry.kt`

Tracks every item instance's location:
- Room floor
- Player inventory
- Equipped (head/body/hand)
- Mob inventory (for drops)

Key operations: `takeFromRoom`, `dropToRoom`, `equipFromInventory`, `unequip`, `dropMobItemsToRoom`

### ShopRegistry

**File:** `src/main/kotlin/dev/ambon/engine/ShopRegistry.kt`

- Shops defined per-room in zone YAML
- `buy`/`sell`/`list` commands
- Buy price = `basePrice * buyMultiplier`
- Sell price = `basePrice * sellMultiplier`

### PlayerProgression

**File:** `src/main/kotlin/dev/ambon/engine/PlayerProgression.kt`

XP curve: `totalXpForLevel(L) = baseXp * (L-1)^exponent + linearXp * (L-1)`
- Default: `baseXp=100`, `exponent=2.0`, max level 50
- HP scaling: class-dependent per-level (Warrior 3, Mage 1, etc.)
- Mana scaling: class-dependent per-level
- Full heal/mana on level-up (configurable)

---

## 9. Persistence

### Backends

**YAML** (default):
- Player files under `data/players/` (configurable via `ambonMUD.persistence.rootDir`)
- Atomic writes; no external infrastructure needed
- IDs allocated in `data/players/next_player_id.txt`

**PostgreSQL**:
- Requires running PostgreSQL instance
- Schema managed by Flyway migrations (`src/main/resources/db/migration/`)
- Connection defaults: `localhost:5432/ambonmud`, user `ambon`, password `ambon` (matches docker compose)

### Persistence Stack

Three layers (regardless of backend):

```
WriteCoalescingPlayerRepository    (dirty-flag write-behind, configurable flush)
    ↓
RedisCachingPlayerRepository       (optional L2 cache, if redis.enabled=true)
    ↓
YamlPlayerRepository  OR  PostgresPlayerRepository
```

### Adding a Field to PlayerRecord

1. Add field with default to `PlayerRecord` data class
2. For YAML: Jackson handles new defaults for existing files
3. For Postgres: create Flyway migration, update `PlayersTable.kt` and `PostgresPlayerRepository.kt`
4. For Redis: verify JSON round-trip in `RedisCachingPlayerRepositoryTest`

### Grant Staff Access

**YAML:** Add `isStaff: true` to `data/players/players/<id>.yaml`
**Postgres:** Set `is_staff = true` on the player's row in the `players` table

---

## 10. Configuration

**Config file:** `src/main/resources/application.yaml`

**Top-level key:**
```yaml
ambonMUD:
  mode: STANDALONE              # STANDALONE, ENGINE, or GATEWAY
  server:
    telnetPort: 4000            # Telnet port
    webPort: 8080               # WebSocket / web client port
  persistence:
    backend: YAML               # YAML or POSTGRES
    rootDir: data/players       # YAML backend only
  database:
    jdbcUrl: jdbc:postgresql://localhost:5432/ambonmud
    username: ambon
    password: ambon
  redis:
    enabled: false
    uri: redis://localhost:6379
  engine:
    abilities:
      definitions: { ... }      # Spell/ability definitions
    statusEffects:
      definitions: { ... }      # Status effect definitions
  logging:
    level: INFO
    packageLevels:
      dev.ambon.transport: DEBUG
```

**Override at runtime:**
```bash
./gradlew run -Pconfig.ambonMUD.server.telnetPort=5000
./gradlew run -Pconfig.ambonMUD.logging.level=DEBUG
./gradlew run -Pconfig.ambonMUD.persistence.backend=POSTGRES
./gradlew run -Pconfig.ambonMUD.redis.enabled=true
```

---

## 11. Deployment Modes

### STANDALONE (Default)

Single-process deployment:
- All components in-memory (engine, transports, persistence)
- No external infrastructure needed
- Perfect for local development and small deployments

```bash
./gradlew run
```

### ENGINE Mode

Dedicated game logic process:
- Runs `GameEngine` + persistence + gRPC server (port 9090)
- Gateways connect remotely via gRPC
- Used in multi-process deployments

```bash
./gradlew runEngine1     # ENGINE mode, gRPC :9091
```

### GATEWAY Mode

Dedicated transport process:
- Runs telnet/WebSocket transports only
- Connects to a remote engine via gRPC
- Multiple gateways can connect to the same engine

```bash
./gradlew runGateway1    # GATEWAY mode, telnet :4000, web :8080
```

---

## 12. Testing

### Run Tests

```bash
./gradlew test                          # Fast unit suite
./gradlew integrationTest               # Integration-tagged suite
./gradlew test integrationTest          # Full suite
./gradlew test --tests "ClassName"      # Single class
./gradlew test --tests "*CommandRouter*"  # Pattern
```

### Test Structure

- **Engine tests:** `GameEngineIntegrationTest`, `GameEngineLoginFlowTest`, `CommandParserTest`, `CommandRouterTest` (~40 test files)
- **Persistence tests:** `YamlPlayerRepositoryTest`, `PostgresPlayerRepositoryTest`, `RedisCachingPlayerRepositoryTest`
- **Transport tests:** `OutboundRouterTest`, `AnsiRendererTest`, `TelnetLineDecoderTest`
- **System tests:** `CombatSystemTest`, `AbilitySystemTest`, `StatusEffectSystemTest`, `MobSystemTest`, etc.

### Test Utilities

- **`MutableClock`** — Deterministic time (use instead of `System.currentTimeMillis()`)
- **`InMemoryPlayerRepository`** — Fast in-memory repo for testing
- **`EngineTestHelpers`** — `LocalOutboundBus.drainAll()`, `PlayerRegistry.loginOrFail()`
- **World fixtures** — `test_world.yaml`, `ok_*.yaml` (valid), `bad_*.yaml` (invalid for error testing)

### Test Patterns

**Async/coroutine tests:**
```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
fun `test name`() = runTest {
    val engine = GameEngine(inbound, outbound, ...)
    val engineJob = launch { engine.run() }

    inbound.send(InboundEvent.Connected(sid))
    runCurrent()
    advanceTimeBy(100)
    runCurrent()

    val events = outbound.drainAll()
    // assertions...
    engineJob.cancel()
}
```

**Database tests:** Use H2 in PostgreSQL-compatibility mode (no Docker required)
```kotlin
val jdbcUrl = "jdbc:h2:mem:test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
```

---

## 13. Common Tasks

### Add a New Command

1. Add variant to `Command` sealed interface in `CommandParser.kt`
2. Add parsing logic in `CommandParser.parse()`
3. Implement the handler in the appropriate file under `commands/handlers/`
4. Preserve prompt behavior for success/failure
5. Add tests in `CommandParserTest` and `CommandRouterTest`

**Example:** Adding `look <item>` command

```kotlin
// CommandParser.kt
sealed interface Command { ... }
data class LookItem(val itemKeyword: String) : Command
// parse("look potion") → LookItem("potion")

// commands/handlers/NavigationHandler.kt (or wherever it belongs)
router.on<Command.LookItem> { sid, cmd ->
    val item = ctx.items.findInRoom(ctx.players.get(sid)!!.roomId, cmd.itemKeyword)
    if (item == null) {
        ctx.outbound.send(SendError(sid, "Item not found."))
    } else {
        ctx.outbound.send(SendText(sid, item.description))
    }
    ctx.outbound.send(SendPrompt(sid))
}
```

### Add a New Ability

1. Define in `application.yaml` under `ambonMUD.engine.abilities.definitions`
2. Set `requiredClass`, `levelRequired`, `manaCost`, `cooldownMs`
3. Set `effect` (type + parameters)
4. Add tests in `AbilitySystemTest`

```yaml
ambonMUD:
  engine:
    abilities:
      definitions:
        my_spell:
          displayName: "My Spell"
          description: "A powerful spell."
          manaCost: 20
          cooldownMs: 5000
          levelRequired: 10
          targetType: ENEMY
          requiredClass: MAGE
          effect:
            type: DIRECT_DAMAGE
            minDamage: 15
            maxDamage: 25
```

### Add a New Status Effect

1. Define in `application.yaml` under `ambonMUD.engine.statusEffects.definitions`
2. Set `effectType` (DOT, HOT, STAT_BUFF, STAT_DEBUFF, STUN, ROOT, SHIELD)
3. Set duration, tick interval, damage/heal, stat mods
4. Reference in ability via `APPLY_STATUS` effect

```yaml
ambonMUD:
  engine:
    statusEffects:
      definitions:
        my_debuff:
          displayName: "My Debuff"
          effectType: STAT_DEBUFF
          durationMs: 8000
          strMod: -2
          stackBehavior: REFRESH
```

### Create a New Zone

1. Create YAML file in `src/main/resources/world/my_zone.yaml`
2. Define zone, startRoom, rooms, mobs, items, shops
3. Reference in `application.yaml` under `world.resources`
4. Use `WorldLoader` validation to catch errors early

See [WORLD_YAML_SPEC.md](./WORLD_YAML_SPEC.md) for full schema.

### Run with PostgreSQL Backend

```bash
# Ensure Docker Compose is running
docker compose up -d

# Run server with Postgres backend
./gradlew run -Pconfig.ambonMUD.persistence.backend=POSTGRES

# Flyway migrations run automatically on startup
# Test via: SELECT * FROM players;
```

### Enable Redis Caching

```bash
# With Postgres + Redis
./gradlew run -Pconfig.ambonMUD.persistence.backend=POSTGRES \
              -Pconfig.ambonMUD.redis.enabled=true
```

### Run Multi-Instance (Engine + Gateways)

Terminal 1 (Engine):
```bash
./gradlew runEngine1    # gRPC :9091
```

Terminal 2 (Gateway 1):
```bash
./gradlew runGateway1   # telnet :4000, web :8080
```

Terminal 3 (Gateway 2):
```bash
./gradlew runGateway2   # telnet :4001, web :8081
```

---

## 14. Troubleshooting

### Build fails: "JDK 21 not found"
- Ensure JDK 21 is installed: `java -version`
- Gradle uses the toolchain in `build.gradle.kts`; it will auto-download if needed

### Tests fail with "Cannot acquire database connection"
- Postgres tests use H2 (in-memory, no Docker required)
- If still failing, check `@BeforeEach` cleanup in test file

### Server won't start: "Address already in use"
- Port 4000 or 8080 is in use
- Override: `./gradlew run -Pconfig.ambonMUD.server.telnetPort=5000`
- Or kill the old process: `lsof -i :4000 | grep -v PID | awk '{print $2}' | xargs kill -9`

### Redis/PostgreSQL connection errors
- Ensure Docker Compose is running: `docker compose up -d`
- Check connection defaults in `application.yaml`
- Check Docker logs: `docker compose logs postgres` or `docker compose logs redis`

### Lint errors: "Trailing comma missing"
- ktlint requires trailing commas in multiline parameter/argument lists
- See [CLAUDE.md](../CLAUDE.md#kotlin-style) for details

### "Cloud environment" errors in tests
- Cloud/CI environments have strict timing constraints
- Use `MutableClock` for deterministic time; avoid `delay(50)` for async sync
- Use `withTimeout(2.seconds)` + polling instead
- See [CLAUDE.md](../CLAUDE.md#cloud--ci-environment) for full notes

---

## 15. Cloud/Remote Development

### Constraints in Claude Code Cloud Sessions

- **GitHub CLI (`gh`) is not available** — use `git` commands directly for push/branch/fetch
- **No hardcoded timing in tests** — never use short `delay()` (e.g. `delay(50)`) for async sync. Use `withTimeout` + polling or proper synchronization primitives
- **Egress proxy** — all HTTP/HTTPS traffic goes through a proxy; Gradle dependency resolution works through it
- **JVM Toolchain** — must match installed JDK (currently 21). If it drifts, update `build.gradle.kts`
- **First build is slow** — Gradle wrapper downloads dependencies on first run; subsequent builds use cached daemon

### Workflow in Cloud Sessions

```bash
# Clone and build
git clone https://github.com/jnoecker/AmbonMUD.git
cd AmbonMUD
./gradlew build

# Run tests (before committing)
./gradlew ktlintCheck test integrationTest

# Make changes, push to feature branch
git checkout -b feature/my-feature
git add src/...
git commit -m "feat: description"
git push -u origin feature/my-feature

# Create PR in GitHub web UI (gh CLI not available in cloud)
```

---

## Next Steps

- Read [ARCHITECTURE.md](../docs/ARCHITECTURE.md) for design rationale
- Read [WORLD_YAML_SPEC.md](./WORLD_YAML_SPEC.md) to understand zone creation
- Read [GMCP_PROTOCOL.md](./GMCP_PROTOCOL.md) to understand the structured data channel
- Read [CLAUDE.md](../CLAUDE.md) for architectural contracts and change playbooks
- Explore `src/main/kotlin/dev/ambon/engine/` to understand the engine
- Run the full test suite to build confidence: `./gradlew ktlintCheck test integrationTest`

---

**Questions?** See [README.md](../README.md) or open an issue on GitHub.
