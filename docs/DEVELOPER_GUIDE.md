# AmbonMUD — Developer Guide

Welcome. This document takes you from a clean checkout to making meaningful changes. If you only read one file besides this one, read [`ARCHITECTURE.md`](./ARCHITECTURE.md).

**What is AmbonMUD?** A Kotlin MUD server with a 100 ms tick engine, telnet + WebSocket transports, YAML world content, config-driven abilities and status effects, class-based progression with skill-point training, 36 command handler subsystems, and three deployment modes (`STANDALONE` / `ENGINE` / `GATEWAY`) that scale from a single process to a Redis-sharded Fargate split.

---

## Table of Contents

1. [Prerequisites](#1-prerequisites)
2. [Quick start](#2-quick-start)
3. [Project layout](#3-project-layout)
4. [Architecture & contracts](#4-architecture--contracts)
5. [The engine loop](#5-the-engine-loop)
6. [Command system](#6-command-system)
7. [Domain model](#7-domain-model)
8. [Subsystem catalog](#8-subsystem-catalog)
9. [Persistence](#9-persistence)
10. [Configuration](#10-configuration)
11. [Deployment modes](#11-deployment-modes)
12. [Testing](#12-testing)
13. [Common tasks](#13-common-tasks)
14. [Troubleshooting](#14-troubleshooting)
15. [Cloud / remote development](#15-cloud--remote-development)

---

## 1. Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK | 21 | Gradle's `jvmToolchain(21)` will auto-select a matching JDK if one is on disk. CI runs Java 21 (Temurin). |
| Git | any recent | — |
| Docker + Docker Compose | optional | Only needed if you want to run against PostgreSQL / Redis / Prometheus / Grafana locally. The default YAML backend needs nothing. |
| Bun | optional | Only if you're modifying `web-v3/` source. The prebuilt client assets are committed into `src/main/resources/web-v3/`. |
| Node.js 22+ | optional | Only for the `infra/` CDK project. |

On Windows use `.\gradlew.bat` in place of `./gradlew`. The shell commands below assume Unix conventions.

---

## 2. Quick start

```bash
git clone https://github.com/jnoecker/AmbonMUD.git
cd AmbonMUD
./gradlew run                    # starts server; YAML persistence; no external services
```

**Connect:**
- Telnet: `telnet localhost 4000`
- Browser: `http://localhost:8080`

**Common tasks:**

```bash
./gradlew demo                              # run + auto-open the browser client
./gradlew test                              # fast unit suite (~175 test files)
./gradlew integrationTest                   # integration-tagged suite
./gradlew ktlintCheck                       # Kotlin lint — run before committing
./gradlew ktlintCheck test integrationTest  # CI parity
./gradlew buildWeb                          # rebuild web client (requires bun)
./gradlew shadowJar                         # produce the fat JAR used by the Dockerfile
./gradlew test --tests "CommandParserTest"  # single class
./gradlew test --tests "*CommandRouter*"    # pattern
```

**With PostgreSQL + Redis (Docker Compose):**

```bash
docker compose up -d
./gradlew run -Pconfig.ambonmud.persistence.backend=POSTGRES \
              -Pconfig.ambonmud.redis.enabled=true
```

**Multi-instance local run** (engine + two gateways over gRPC):

```bash
# Terminal 1:  ./gradlew runEngine1     # gRPC :9091
# Terminal 2:  ./gradlew runGateway1    # telnet :4000, web :8080
# Terminal 3:  ./gradlew runGateway2    # telnet :4001, web :8081
```

A second engine task exists (`runEngine2`, gRPC :9092) for testing zone-sharded deployments.

---

## 3. Project layout

```
src/main/kotlin/dev/ambon/
├── Main.kt                      # Entry point, JVM shutdown hook
├── MudServer.kt                 # STANDALONE / ENGINE composition root
├── GatewayServer.kt             # GATEWAY composition root
├── config/                      # AppConfig.kt schema + validated()
├── engine/                      # Game logic (tick loop + subsystems)
│   ├── GameEngine.kt            # 100 ms tick loop, inbound handler
│   ├── commands/
│   │   ├── CommandParser.kt     # sealed Command hierarchy (~200 variants)
│   │   ├── CommandRouter.kt     # thin dispatch
│   │   └── handlers/            # 36 handler files + EngineContext + HandlerHelpers
│   ├── abilities/               # AbilitySystem, ability registry loader
│   ├── status/                  # StatusEffectSystem
│   ├── crafting/                # CraftingSystem, recipes, gathering, quality
│   ├── dialogue/                # NPC dialogue trees
│   ├── behavior/                # Mob behavior-tree DSL
│   ├── housing/, dungeon/, auction/, trade/, duel/, faction/, pet/, prestige/, lottery/, puzzle/, weather/, worldtime/, worldevent/, leaderboard/, stylist/, bank/, currency/, quest/
│   ├── scheduler/               # Scheduler.kt — delayed/recurring callbacks
│   ├── PlayerRegistry.kt        # session ↔ player, login FSM
│   ├── GmcpEmitter.kt           # GMCP package emissions (~3500 lines)
│   └── ...
├── transport/                   # Network I/O
│   ├── BlockingSocketTransport.kt     # Telnet server (virtual threads)
│   ├── KtorWebSocketTransport.kt      # WebSocket / browser client
│   ├── OutboundRouter.kt              # Per-session queues, backpressure
│   ├── AnsiRenderer.kt, PlainRenderer.kt
│   └── TelnetLineDecoder.kt
├── bus/                         # InboundBus / OutboundBus interfaces
│   ├── Local*Bus.kt             # Single-process channels
│   ├── Redis*Bus.kt             # Multi-process pub/sub (HMAC-signed)
│   └── Grpc*Bus.kt              # gRPC gateway ↔ engine
├── grpc/                        # EngineGrpcServer, ProtoMapper
├── redis/                       # RedisConnectionManager, JSON helpers
├── sharding/                    # Zone-based sharding
│   ├── ZoneRegistry.kt, InterEngineBus.kt
│   ├── HandoffManager.kt, InstanceSelector.kt
│   └── PlayerLocationIndex.kt
├── persistence/                 # Player + guild persistence
│   ├── PlayerRepository.kt      # interface
│   ├── YamlPlayerRepository.kt, PostgresPlayerRepository.kt
│   ├── RedisCachingPlayerRepository.kt, WriteCoalescingPlayerRepository.kt
│   ├── PersistenceWorker.kt     # background flush
│   ├── PlayersTable.kt, PlayerRecord.kt
│   └── GuildRepository.kt / YamlGuildRepository.kt / PostgresGuildRepository.kt
├── admin/                       # AdminHttpServer (HTML dashboard + JSON API)
├── session/                     # Snowflake session IDs, gateway leases
├── metrics/                     # Micrometer / Prometheus
├── domain/                      # RoomId, PlayerClass, Race, world model
│   └── world/load/WorldLoader.kt
└── ui/login/                    # Login banner rendering

src/main/resources/
├── application.yaml             # Runtime config (~2000 lines)
├── db/migration/                # Flyway migrations V1–V38
├── world/                       # Academy tutorial zone + achievements.yaml + sprites.yaml
│   ├── academy.yaml
│   ├── achievements.yaml
│   ├── sprites.yaml
│   └── images/                  # default room/mob/item sprites
├── web-v3/                      # Prebuilt web client assets (gitignored source, committed build)
└── login.txt, login.styles.yaml

src/main/proto/ambonmud/v1/
├── engine_service.proto
└── events.proto

src/test/kotlin/                 # ~175 test files
└── dev/ambon/test/              # MutableClock, EngineTestHelpers, InMemoryPlayerRepository

infra/                           # TypeScript CDK project
├── bin/infra.ts                 # topology branch point (ec2 vs ECS)
└── lib/
    ├── config.ts                # topology × tier sizing table
    ├── ec2-stack.ts             # EC2 single-instance (~$4–5/mo)
    ├── vpc-stack.ts, data-stack.ts, lb-stack.ts, ecs-stack.ts
    ├── dns-stack.ts, monitoring-stack.ts
```

---

## 4. Architecture & contracts

Full architectural details — contracts, data flow, event model, persistence stack, and design decisions — live in [`ARCHITECTURE.md`](./ARCHITECTURE.md). The three inviolable contracts in brief:

1. **Engine isolation.** The engine communicates only via `InboundEvent` / `OutboundEvent`. No transport code inside the engine; no gameplay logic inside transport.
2. **Single-threaded engine.** `GameEngine` runs on a dedicated dispatcher with a 100 ms tick loop. Never call blocking I/O inside engine systems. Use the injected `Clock`, never `System.currentTimeMillis()`.
3. **Bus interfaces.** Pass `InboundBus` / `OutboundBus`, never raw `Channel<T>`. This is what lets `Local*Bus` ↔ `Redis*Bus` ↔ `Grpc*Bus` swap without touching game code.

---

## 5. The engine loop

**File:** `src/main/kotlin/dev/ambon/engine/GameEngine.kt`

Each 100 ms tick runs, in order:

1. Drain up to `maxInboundEventsPerTick` from `InboundBus`.
2. Dispatch commands through `CommandRouter` → handler modules.
3. `MobSystem.tick()` — NPC wandering and behavior trees.
4. `CombatSystem.tick()` — active fight resolution.
5. `RegenSystem.tick()` — HP / mana regen.
6. `StatusEffectSystem.tick()` — DoT / HoT / buffs / debuffs.
7. `Scheduler.runDue()` — delayed and recurring callbacks.
8. `resetZonesIfDue()` — zone respawns for zones with `lifespan > 0`.

Inbound handling:
- `Connected` → register session, show login banner, start login FSM.
- `Disconnected` → clean up (8+ subsystems: groups, trades, duels, pets, status, cooldowns, dialogue, dungeons).
- `LineReceived` → if in login FSM, advance it; otherwise `CommandRouter.route()`.
- `GmcpReceived` → store package support, emit snapshots.

---

## 6. Command system

### CommandParser

**File:** `engine/commands/CommandParser.kt` — pure function `parse(line): Command`. Sealed `Command` hierarchy with ~200 variants. No side effects.

### CommandRouter

**File:** `engine/commands/CommandRouter.kt` — thin dispatch (~110 lines). Every variant routes to one of the handlers under `engine/commands/handlers/`. Each handler implements the `CommandHandler` interface and receives an `EngineContext` carrying the required subsystem references.

### Handler catalog (36 handlers + 2 support files)

| Handler | Commands |
|---------|----------|
| `NavigationHandler` | `n/s/e/w/u/d`, `look`, `exits`, `recall` |
| `CombatHandler` | `kill`, `flee`, `cast` |
| `CommunicationHandler` | `say`, `tell`, `whisper`, `gossip`, `shout`, `ooc`, `pose`, `emote` |
| `ItemHandler` | `inventory`, `equipment`, `get`, `drop`, `wear`, `remove`, `use`, `give`, `put`, `examine` |
| `ShopHandler` | `buy`, `sell`, `list` |
| `DialogueQuestHandler` | `talk`, choice selection, quest `accept`/`abandon`/`log` |
| `GroupHandler` | `group invite`/`accept`/`leave`/`kick`, `gtell` |
| `ProgressionHandler` | `score`, `spells`, `effects`, `achievements` |
| `WorldFeaturesHandler` | doors, levers, containers, signs |
| `GuildHandler` | guild lifecycle + `gchat` |
| `CraftingHandler` | `gather`, `craft`, `recipes`, `craftskills`, `specialize` |
| `EnchantHandler` | `enchant`, `enchantments` |
| `FriendsHandler` | `friend list`/`add`/`remove` |
| `MailHandler` | `mail list`/`read`/`send`/`delete`, compose mode |
| `SpriteHandler` | `sprite list`/`set`/`default` |
| `TrainerHandler` | `train list`/`learn`/`unlock` (multi-classing) |
| `PetHandler` | `pet`, `pet dismiss`, `pet name` |
| `AuctionHandler` | `auction`, `auction sell`/`buy`/`cancel` |
| `BankHandler` | `deposit`, `withdraw`, `bank` |
| `CurrencyHandler` | `currencies`, `currency`, `wallet` |
| `TradeHandler` | `trade <player>`, `trade offer`/`accept`/`cancel` |
| `DuelHandler` | `duel`, `duel accept`/`decline` |
| `PrestigeHandler` | `prestige`, `prestige info` |
| `LotteryHandler` | `lottery`, `lottery buy` |
| `ReputationHandler` | `reputation` |
| `LeaderboardHandler` | `leaderboard`, `halloffame` |
| `DungeonHandler` | `dungeon enter`/`leave` |
| `HousingHandler` | `house` family (info/expand/furnish/describe/invite/kick) |
| `WorldInfoHandler` | `time` (day/night period + weather + seasonal events) |
| `StylistHandler` | `stylist`, `changerace` |
| `PuzzleHandler` | `answer`, sequence puzzles |
| `AutoQuestHandler` | session-only bounty quests |
| `DailyQuestHandler` | daily + weekly rotations |
| `GlobalQuestHandler` | server-wide cooperative quests |
| `UiHandler` | `help`, `clear`, `colors`, `ansi`, `phase`, `who`, `quit` |
| `AdminHandler` | `goto`, `transfer`, `spawn`, `smite`, `kick`, `setlevel`, `dispel`, `reload`, `broadcast`, `possess`, `return`, `invis`, `shutdown` (gated on `isStaff`) |
| `EngineContext`, `HandlerHelpers` | support files — not handlers |

### Adding a new command

1. Add a variant to the `Command` sealed interface in `CommandParser.kt`.
2. Add parse logic in `CommandParser.parse()` using the `matchPrefix` / `requiredArg` helpers.
3. Route it in `CommandRouter` to the appropriate handler. New category → new handler file implementing `CommandHandler` + wiring.
4. Always emit `SendPrompt` on both success and failure paths.
5. Add parser tests in `CommandParserTest` and router tests in `CommandRouterTest` (or a dedicated file). Staff commands: gate with `isStaff` in the handler and test in `CommandRouterAdminTest`.

---

## 7. Domain model

### ID types

Inline value classes; all namespaced `<zone>:<local>`:

- `RoomId(value: String)` — e.g. `academy:academy_gates`
- `MobId(value: String)`
- `ItemId(value: String)`
- `SessionId(value: String)` — Snowflake-based in distributed modes

### Player stats

Stats are data-driven via `StatRegistry` + `StatMap`. The canonical six stats (`STR`, `DEX`, `CON`, `INT`, `WIS`, `CHA`) are defined in `application.yaml` under `ambonmud.engine.stats.definitions`; adding or tuning a stat no longer requires Kotlin changes. 

Each stat binds to game mechanics (melee damage, dodge, HP regen, spell damage, mana regen, XP bonus) through configurable stat binding keys rather than hardcoded field access.

### PlayerState (runtime) ↔ PlayerRecord (persistent)

- `PlayerState` lives on the engine dispatcher; holds session linkage, transient combat state, cooldowns, compose buffers.
- `PlayerRecord` is a Jackson-serializable DTO for persistence.
- `toPlayerState()` / `toPlayerRecord()` handle the boundary conversion; `PersistenceFieldCoverageTest` catches dropped fields.
- Validation: name 2–16 chars alnum/underscore no-leading-digit, password non-blank max 72 (BCrypt).

---

## 8. Subsystem catalog

This is a working index, not an exhaustive spec. Each subsystem follows the pattern `*System.kt` (logic) + `*Handler.kt` (command routing) + config under `ambonmud.engine.*` + `*SystemTest` test.

| Subsystem | File | Summary |
|-----------|------|---------|
| **CombatSystem** | `engine/CombatSystem.kt` | 1v1 and group combat, threat tables, dodge, armor, on-death flow |
| **AbilitySystem** | `engine/abilities/AbilitySystem.kt` | Config-driven spells/abilities, mana, cooldowns, `requiredClass`, level scaling via `damagePerLevel` / `healPerLevel` |
| **StatusEffectSystem** | `engine/status/StatusEffectSystem.kt` | DoT, HoT, STAT_BUFF/DEBUFF, STUN, ROOT, SHIELD; trait-flag-driven effect types |
| **RegenSystem** | `engine/RegenSystem.kt` | HP + mana regen, intervals scale with CON / WIS |
| **MobSystem** | `engine/MobSystem.kt` | NPC wandering, behavior trees (YAML DSL), per-mob respawn |
| **BehaviorTreeSystem** | `engine/behavior/` | Composable behavior trees — 14 node types, inline YAML trees |
| **QuestSystem** | `engine/quest/QuestSystem.kt` | Objective/completion handler registries (data-driven), rewards, persistence |
| **AutoQuestSystem** | `engine/quest/AutoQuestSystem.kt` | Session-only bounty quests |
| **DailyQuestSystem** | `engine/quest/DailyQuestSystem.kt` | Daily + weekly rotations with streaks |
| **GlobalQuestSystem** | `engine/quest/GlobalQuestSystem.kt` | Server-wide cooperative quests |
| **AchievementSystem** | `engine/AchievementSystem.kt` | Criteria, progress, cosmetic titles |
| **DialogueSystem** | `engine/dialogue/` | NPC dialogue trees, choices, quest integration |
| **GroupSystem** | `engine/GroupSystem.kt` | Party invite/accept/leave/kick, N:M threat, XP sharing |
| **GuildSystem** | `engine/GuildSystem.kt` | Permission-based ranks (data-driven), MOTD, roster, `gchat` |
| **GuildHallSystem** | `engine/GuildHallSystem.kt` | Guild hall rooms (Flyway V33) |
| **FriendsSystem** | `engine/FriendsSystem.kt` | Friend list, online/offline notifications |
| **CraftingSystem** | `engine/crafting/CraftingSystem.kt` | Gathering, recipes, quality tiers (Normal → Masterwork), recipe discovery, specialization |
| **EnchantingSystem** | `engine/crafting/EnchantingSystem.kt` | `enchant` command, enchanting station, stat/damage bonuses |
| **HousingSystem** | `engine/housing/HousingSystem.kt` | Personal rooms, furniture, vaults, access control |
| **DungeonManager** | `engine/dungeon/DungeonManager.kt` | Template-driven procedural dungeons, 4 difficulty tiers (Lore → Heroic), party-level + difficulty scaling |
| **PetSystem** | `engine/pet/PetSystem.kt` | `SUMMON_PET` ability type, follows owner, level-scaled stats |
| **ReputationSystem** | `engine/faction/ReputationSystem.kt` | 7 standing tiers (Hated → Revered), enemy faction relationships |
| **AuctionSystem** | `engine/auction/AuctionSystem.kt` | Player marketplace, atomic gold/item transfers, persistent listings (atomic-write JSON) |
| **TradeSystem** | `engine/trade/TradeSystem.kt` | Bilateral item + gold trades with confirmation; gold re-validated at completion |
| **DuelSystem** | `engine/duel/DuelSystem.kt` | Consent-based PvP, normal combat, no item loss |
| **BankSystem** | `engine/bank/BankSystem.kt` | Bank rooms (`bank: true` flag); gold + item vault |
| **CurrencySystem** | `engine/CurrencySystem.kt` | Secondary currencies defined in `engine.currencies.definitions`; quest rewards can include currency grants |
| **PrestigeSystem** | `engine/prestige/PrestigeSystem.kt` | Ranks, perks, XP cost (Flyway V28) |
| **LotterySystem** | `engine/lottery/LotterySystem.kt` | Tickets, drawings, jackpot, atomic-write JSON persistence |
| **LeaderboardSystem** | `engine/leaderboard/LeaderboardSystem.kt` | 7 categories, top-N, hall of fame |
| **WeatherSystem** | `engine/weather/WeatherSystem.kt` | Per-zone weather transitions, config-driven types, `World.Weather` GMCP |
| **WorldTimeSystem** | `engine/worldtime/WorldTimeSystem.kt` | 24-hour clock, four periods (NIGHT/DAWN/DAY/DUSK), `World.Time` GMCP |
| **WorldEventSystem** | `engine/worldevent/WorldEventSystem.kt` | Date-triggered seasonal events with flag system, `World.Events` GMCP |
| **PuzzleSystem** | `engine/puzzle/PuzzleSystem.kt` | Riddle + sequence puzzles with rewards |
| **StylistSystem** | `engine/stylist/StylistSystem.kt` | In-game race change via stylist NPC, preserves progression |
| **SpriteSystem** | `engine/SpriteRegistry.kt` + `domain/sprites/` | Player sprite registry with flexible unlock criteria |
| **ScreenReaderFilter** | `transport/ScreenReaderFilter.kt` | ANSI stripping + box-drawing replacement for assistive tech |
| **GmcpEmitter** | `engine/GmcpEmitter.kt` | ~50 GMCP package families (Char / Room / Comm / Quest / Crafting / Housing / Guild / etc.) — see [`GMCP_PROTOCOL.md`](./GMCP_PROTOCOL.md) |

---

## 9. Persistence

### Backends

**YAML (default):** One file per player under `data/players/`, atomic writes. Zero dependencies — this is what `./gradlew run` uses and what the EC2 demo ships with.

**PostgreSQL:** Schema managed by Flyway migrations (`src/main/resources/db/migration/`, **V1 through V38**). Connection defaults match `docker-compose.yml` (`localhost:5432/ambonmud`, user `ambon`). Switching backends is one flag: `-Pconfig.ambonmud.persistence.backend=POSTGRES`.

### The stack

```
Caller (GameEngine / PlayerRegistry)
     │  repo.save(record)
     ▼
WriteCoalescingPlayerRepository   ← dirty flag, background flush every flushIntervalMs
     │
     ▼
RedisCachingPlayerRepository      ← optional L2 cache (redis.enabled=true)
     │
     ▼
YamlPlayerRepository   or   PostgresPlayerRepository
```

Every write layer is transparent to the caller: `PlayerRegistry` just calls `repo.save()`.

Guild persistence mirrors this: `GuildRepository` interface with `YamlGuildRepository` (one file per guild under `data/guilds/`) and `PostgresGuildRepository` (Exposed + JSON members column).

### Adding a field to `PlayerRecord`

1. Add the field with a default value on `PlayerRecord` (existing YAML files must still deserialize).
2. Update `PlayersTable.kt`, `readRecord()`, and `writeRecord()` — add the column.
3. Create a new Flyway migration in `db/migration/` (`V<N>__description.sql`).
4. If the field belongs in runtime state too, add it to `PlayerState` and update `toPlayerState()` / `toPlayerRecord()`.
5. `PersistenceFieldCoverageTest` will fail loudly if you forget a mapping.
6. Verify JSON round-trip through Redis via `RedisCachingPlayerRepositoryTest`.

### Grant staff access

- **YAML:** set `isStaff: true` in the player's YAML file.
- **Postgres:** `UPDATE players SET is_staff = true WHERE name = '…'`.

There is no in-game promotion command — by design.

---

## 10. Configuration

**Source of truth:** `src/main/resources/application.yaml` (~2000 lines). Hoplite loads it plus any env var or system property overrides.

**Priority order (highest wins):**
1. `AMBONMUD_*` environment variables
2. `-Pconfig.<key>=<value>` Gradle properties (mapped to `config.override.<key>` system properties)
3. `AMBONMUD_DATA_DIR/application-local.yaml` overlay if `AMBONMUD_DATA_DIR` is set
4. Bundled `application.yaml`

**Shape:**

```yaml
ambonmud:
  mode: STANDALONE                  # STANDALONE | ENGINE | GATEWAY
  server:
    telnetPort: 4000
    webPort: 8080
    productionMode: false           # true → reject placeholder secrets at startup
  world:
    startRoom: academy:academy_gates
    resources: []                   # additional zone YAMLs (relative to data dir / classpath)
  persistence:
    backend: YAML                   # YAML | POSTGRES
    rootDir: data/players
    worker:
      enabled: true
      flushIntervalMs: 5000
  database:
    jdbcUrl: jdbc:postgresql://localhost:5432/ambonmud
    username: ambon
    password: ambon
  redis:
    enabled: false
    uri: redis://localhost:6379
    bus:
      enabled: false
      sharedSecret: "CHANGE_ME"     # HMAC signing for Redis pub/sub
  grpc:
    sharedSecret: ""                # HMAC for ENGINE↔GATEWAY
    allowPlaintext: true
  observability:
    metricsHttpHost: "0.0.0.0"
    metricsHttpPort: 9099           # NOTE: moved from 9090 to avoid gRPC conflict
  engine:
    stats: { definitions: { STR: { ... }, ... } }
    abilities: { definitions: { ... } }
    statusEffects: { definitions: { ... } }
    classes: { definitions: { WARRIOR: { ... }, MAGE: { ... }, ... } }
    races: { definitions: { HUMAN: { ... }, ... } }
    economy: { buyMultiplier: 1.0, sellMultiplier: 0.5 }
    skillPoints: { interval: 2 }
    multiclass: { minLevel: 10, goldCost: 500 }
    # ... and so on
  logging:
    level: INFO
```

**Override at runtime:**

```bash
./gradlew run -Pconfig.ambonmud.server.telnetPort=5000
./gradlew run -Pconfig.ambonmud.logging.level=DEBUG
./gradlew run -Pconfig.ambonmud.persistence.backend=POSTGRES
./gradlew run -Pconfig.ambonmud.redis.enabled=true
```

**Override via environment (containers):**

Hoplite lowercases env var names and replaces `_` with `.` — `AMBONMUD_PERSISTENCE_BACKEND` resolves to `ambonmud.persistence.backend`. See [`DEPLOYMENT.md § Environment Variable Reference`](./DEPLOYMENT.md#6-environment-variable-reference) for the full production env var table.

**Changing the config schema:**

Edit `AppConfig.kt` and `application.yaml` in the same commit. Keep `validated()` strict — undefined references (unknown stat keys, unknown status effect IDs, unknown class IDs) should fail at startup, not at tick time.

---

## 11. Deployment modes

### STANDALONE (default)

Single-process: all app components in one JVM. Uses `LocalInboundBus` / `LocalOutboundBus`. YAML persistence by default. Redis and Postgres are optional.

```bash
./gradlew run
```

### ENGINE

Game logic + persistence + gRPC server. Gateways connect remotely over gRPC. Used in multi-process deployments. Requires a shared secret (`ambonmud.grpc.sharedSecret`). Zone sharding can be enabled so multiple engines each own a subset of zones.

```bash
./gradlew runEngine1     # gRPC :9091
./gradlew runEngine2     # gRPC :9092
```

### GATEWAY

Telnet + WebSocket transports only. Connects to a remote engine over gRPC. Multiple gateways can share one engine; session IDs are globally unique via Snowflake allocation + gateway ID leasing.

```bash
./gradlew runGateway1    # telnet :4000, web :8080
./gradlew runGateway2    # telnet :4001, web :8081
```

Full topology table (EC2, standalone/hobby, split/moderate, split/production) lives in [`DEPLOYMENT.md § 5 Topology & Tier Reference`](./DEPLOYMENT.md#5-topology--tier-reference).

---

## 12. Testing

### Running

```bash
./gradlew test                              # fast unit suite
./gradlew integrationTest                   # integration-tagged suite
./gradlew test integrationTest              # both
./gradlew test --tests "CombatSystemTest"   # single class
./gradlew test --tests "*CommandRouter*"    # pattern
```

CI runs `./gradlew ktlintCheck test integrationTest` on every push and PR, plus frontend `bun run lint && bun run build` in `web-v3/`, plus Docker build + ECR push on `main`.

### Structure

- **Engine tests:** `GameEngineIntegrationTest`, `GameEngineLoginFlowTest`, `CommandParserTest`, `CommandRouterTest`, `CommandRouterAdminTest`, per-subsystem tests (~100+ files).
- **Persistence:** `YamlPlayerRepositoryTest`, `PostgresPlayerRepositoryTest`, `RedisCachingPlayerRepositoryTest`, `WriteCoalescingPlayerRepositoryTest`, `PersistenceFieldCoverageTest`, guild equivalents.
- **Transport:** `OutboundRouterTest`, `AnsiRendererTest`, `TelnetLineDecoderTest`, `KtorWebSocketTransportTest`, `ScreenReaderFilterTest`.
- **Bus:** 6 bus test files (Local / Redis / gRPC × inbound/outbound).
- **Sharding:** `ZoneRegistryTest`, `HandoffManagerTest`, `InterEngineBusTest`, `InstanceSelectorTest` (6 files).
- **System tests:** one per subsystem — `CraftingSystemTest`, `GuildSystemTest`, `FriendsSystemTest`, `HousingSystemTest`, `DungeonManagerTest`, `AuctionSystemTest`, `TradeSystemTest`, `DuelSystemTest`, `PrestigeSystemTest`, `ReputationSystemTest`, `LeaderboardSystemTest`, `CurrencySystemTest`, `LotterySystemTest`, `PuzzleSystemTest`, `WeatherSystemTest`, `WorldTimeSystemTest`, `WorldEventSystemTest`, `PetSystemTest`, etc.
- **World loading:** `WorldLoaderTest` with positive + negative fixtures in `src/test/resources/world/` (`ok_*.yaml` vs `bad_*.yaml`).

### Test utilities

- **`MutableClock`** — deterministic time via `advance(ms)` / `set(ms)`. Never use `System.currentTimeMillis()` in production code.
- **`InMemoryPlayerRepository`** — fast in-memory repo with `clear()` for `@BeforeEach` isolation.
- **`EngineTestHelpers`** — `outbound.drainAll()`, `registry.loginOrFail()`.

### Test patterns

**Coroutine / async:**

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
fun `my test`() = runTest {
    val engine = GameEngine(inbound, outbound, ...)
    val job = launch { engine.run() }

    inbound.send(InboundEvent.Connected(sid))
    runCurrent()
    advanceTimeBy(100)
    runCurrent()

    val events = outbound.drainAll()
    // assertions...
    job.cancel()
}
```

**Database:** H2 in PostgreSQL compatibility mode — no Docker needed.

```kotlin
val jdbcUrl = "jdbc:h2:mem:test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
```

**Timing discipline:** in cloud / CI environments, never use short `delay(50)` for sync. Prefer `withTimeout(2.seconds)` with polling, or proper coroutine primitives. Negative tests should use `delay(200)` minimum.

---

## 13. Common tasks

### Add a new command

See [§ 6 Adding a new command](#6-command-system).

### Add a new ability

Data only — no Kotlin changes required.

```yaml
ambonmud:
  engine:
    abilities:
      definitions:
        my_spell:
          displayName: "My Spell"
          description: "A powerful spell."
          manaCostPct: 20        # 20% of the player's level/class base mana pool
          cooldownMs: 5000
          levelRequired: 10
          targetType: ENEMY
          requiredClass: MAGE
          effect:
            type: DIRECT_DAMAGE
            minDamage: 15
            maxDamage: 25
            damagePerLevel: 1.5     # scales with player level
```

**Mana cost scales with level.** Costs are authored as a *percentage* of the
player's level/class base mana pool (the pool computed with default INT, so the
stat investment that grows the pool gives players more casts rather than
discounting individual spells). The absolute per-cast mana spend is resolved by
`AbilitySystem.computeManaCost(player, ability)` and emitted to the client as
the `manaCost` field of `Char.Skills` GMCP. Suggested ranges: 0% (free
spammable), 8–12% (basic), 15–20% (standard), 25–30% (signature/ultimate).

Add a test in `AbilitySystemTest` exercising the new ability.

### Add a new status effect

```yaml
ambonmud:
  engine:
    statusEffects:
      definitions:
        my_debuff:
          displayName: "My Debuff"
          effectType: STAT_DEBUFF
          durationMs: 8000
          statMods: { STR: -2 }
          stackBehavior: REFRESH
```

Reference it from an ability via `effect.type: APPLY_STATUS` + `effect.statusEffectId: my_debuff`. Startup validation will fail if the ID doesn't exist.

### Add a new zone

1. Create `src/main/resources/world/my_zone.yaml` per [`WORLD_YAML_SPEC.md`](./WORLD_YAML_SPEC.md).
2. Add the file name under `ambonmud.world.resources` in `application.yaml` (or drop it into `$AMBONMUD_DATA_DIR/world/` for an overlay-based deployment).
3. `WorldLoader` validates on boot — fix whatever it complains about.

### Add a new GMCP package

1. Emit the package from `engine/GmcpEmitter.kt`.
2. Handle it on the client at `web-v3/src/gmcp/applyGmcpPackage.ts`.
3. **If it's a new package family**, register the prefix in `KtorWebSocketTransport.kt` (around line 208, `Core.Supports.Set`). Without this, GMCP is silently dropped. Prefix matching: `"Quest 1"` covers `Quest.List`, `Quest.Update`, etc.
4. Telnet clients negotiate via standard `WILL`/`DO`; the WebSocket client auto-opts-in to the full package set.

### Add a new bus / gRPC event variant

1. Add the variant to `InboundEvent` or `OutboundEvent`.
2. Add the type discriminator in `RedisInboundBus` / `RedisOutboundBus` (envelope).
3. Add the proto message in `src/main/proto/ambonmud/v1/events.proto`.
4. Update `ProtoMapper.kt` for bidirectional mapping.

### Run with PostgreSQL

```bash
docker compose up -d
./gradlew run -Pconfig.ambonmud.persistence.backend=POSTGRES \
              -Pconfig.ambonmud.redis.enabled=true
```

Flyway applies migrations automatically on startup.

### Build and run as a Docker container

```bash
./gradlew shadowJar
docker build -t ambonmud .

# STANDALONE against host Postgres/Redis
docker run --rm -p 4000:4000 -p 8080:8080 \
  -e AMBONMUD_DATABASE_JDBCURL=jdbc:postgresql://host.docker.internal:5432/ambonmud \
  -e AMBONMUD_DATABASE_USERNAME=ambon \
  -e AMBONMUD_DATABASE_PASSWORD=ambon \
  -e AMBONMUD_REDIS_URI=redis://host.docker.internal:6379 \
  ambonmud

# STANDALONE with YAML persistence (no external services)
docker run --rm -p 4000:4000 -p 8080:8080 \
  -e AMBONMUD_PERSISTENCE_BACKEND=YAML \
  -e AMBONMUD_REDIS_ENABLED=false \
  ambonmud
```

### Deploy to AWS

| Topology | Cost | Use case |
|----------|------|----------|
| `ec2` | ~$4–5/mo | Low-traffic demo — t4g.nano, YAML persistence, nginx TLS, auto-deploy from `main` |
| `standalone` + `hobby` | ~$30–60/mo | Single Fargate task + managed Postgres + Redis |
| `split` + `moderate` | ~$200–500/mo | Auto-scaling ENGINE + GATEWAY, 2-AZ |
| `split` + `production` | varies | Full HA, 3-AZ, paging alarms |

```bash
cd infra && npm ci
npx cdk deploy --context topology=ec2 --context imageTag=<sha>
npx cdk deploy --all --context topology=standalone --context tier=hobby
```

Full runbook in [`DEPLOYMENT.md`](./DEPLOYMENT.md).

---

## 14. Troubleshooting

### Build fails: "JDK 21 not found"

Install JDK 21 (Temurin / Corretto / Zulu). Gradle's toolchain will auto-select it if it's on disk. In restricted cloud environments, Foojay auto-provisioning may be blocked by egress proxies — install the JDK manually in that case.

### Tests fail: "Cannot acquire database connection"

Postgres tests use H2 in-memory. If you see a connection error, check the `@BeforeEach` cleanup in the failing test file — a prior test may have leaked schema.

### Server won't start: "Address already in use"

Port 4000 or 8080 is already bound.

```bash
./gradlew run -Pconfig.ambonmud.server.telnetPort=5000
./gradlew run -Pconfig.ambonmud.server.webPort=8081
```

Or identify the holder: `lsof -i :4000` (Unix), `netstat -ano | findstr :4000` (Windows).

### "Server at max login capacity" rejections under load

The login funnel is a semaphore guarded by `login.maxConcurrentLogins` (default 150) and a BCrypt thread pool sized by `login.authThreads` (default 8). Under burst ramps, the semaphore saturates before BCrypt clears sessions — bots time out in `WAIT_NAME`. Raise both in lock-step with CPU count.

### Lint errors: "Trailing comma missing"

ktlint 1.5.0 requires trailing commas on all multiline parameter/argument lists and collection literals. `./gradlew ktlintFormat` will fix most of these automatically. See [`CLAUDE.md § Kotlin Style`](../CLAUDE.md#kotlin-style-ktlint) for the full rule set.

### Flaky async tests in cloud / CI

Never use short `delay(50)` for async sync. Use `MutableClock` for time-sensitive logic, and `withTimeout(2.seconds)` with polling for bus drain assertions. Negative tests should use `delay(200)` minimum.

### Hoplite `ConfigException: Missing required value at ambonmud.X.Y` on startup

If you're running against a data-dir overlay (`AMBONMUD_DATA_DIR`), remember that `application-local.yaml` **replaces** the base config entirely — there is no deep merge. Every required field must be present in the overlay. When you add a new required key to `AppConfig`, you must also update the lore overlay. See [`DEPLOYMENT.md § Hoplite ConfigException`](./DEPLOYMENT.md) for the full troubleshooting flow.

### Redis / Postgres connection errors

`docker compose up -d` brings up the full stack. Check container logs: `docker compose logs postgres`, `docker compose logs redis`. Redis failures degrade gracefully — the engine logs a warning and falls back to the local bus/cache.

---

## 15. Cloud / remote development

### Claude Code cloud sessions

- **GitHub CLI (`gh`)** is available — use normally for `gh pr create`, `gh run watch`, etc.
- **JVM toolchain** must match the installed JDK 21. Foojay auto-provision can fail through restrictive egress proxies, so the JDK is installed locally in the image.
- **No hardcoded delays.** Always use polling + timeouts. Negative tests `delay(200)` minimum.
- **First build is slow.** Gradle wrapper downloads dependencies through the egress proxy; subsequent builds use the cached daemon.
- **Test budgets.** 30 seconds per test, 5 minutes per suite — design accordingly.

### Cloud workflow

```bash
git clone https://github.com/jnoecker/AmbonMUD.git
cd AmbonMUD
./gradlew ktlintCheck test integrationTest   # CI parity

git checkout -b feature/my-feature
# edit...
git add src/...
git commit -m "feat: short description"
git push -u origin feature/my-feature

gh pr create --title "..." --body "..."
```

---

**Questions?** See the [README](../README.md) or open an issue on GitHub.
