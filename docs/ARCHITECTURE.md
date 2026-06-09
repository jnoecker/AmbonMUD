# AmbonMUD — Architecture & Design Decisions

This document captures the architectural principles and design decisions that make AmbonMUD a production-grade MUD server. The philosophy is to avoid premature complexity while building extensible foundations for world content, commands, transports, and persistence.

---

## Core Architecture

### Data Flow Diagram

```
┌─────────────────────────────┐
│ Clients (telnet/browser)    │
└──────────────┬──────────────┘
               │
               ▼
┌─────────────────────────────────────────┐
│ Transports (BlockingSocketTransport,   │
│            KtorWebSocketTransport)      │
│ • Decode raw I/O → InboundEvent        │
│ • Render OutboundEvent → text/bytes    │
└──────────────┬──────────────────────────┘
               │
               ▼
┌─────────────────────────────┐
│ InboundBus / OutboundBus   │
│ (interface layer)           │
│ • Local (in-process)        │
│ • Redis (pub/sub)           │
│ • gRPC (gateway split)      │
└──────────────┬──────────────┘
               │
               ▼
┌──────────────────────────────────────────┐
│ GameEngine (single-threaded, 100ms tick)│
│ • CommandRouter (command dispatch)       │
│ • CombatSystem (fight resolution)        │
│ • MobSystem (NPC wandering)              │
│ • RegenSystem (HP/mana ticks)            │
│ • Scheduler (delayed callbacks)          │
│ • StatusEffectSystem (DoT/HoT/buffs)    │
│ • PlayerRegistry (session ↔ player)     │
│ • AbilitySystem (spell casting)          │
│ • ShopRegistry (economy)                 │
│ • QuestSystem (quest tracking)           │
│ • AchievementSystem (achievements)       │
│ • GroupSystem (party management)         │
│ • GmcpEmitter (structured data)          │
└──────────────┬───────────────────────────┘
               │
               ▼
┌──────────────────────────────┐
│ OutboundRouter               │
│ • Per-session queues         │
│ • Backpressure (disconnect   │
│   slow clients)              │
│ • Prompt coalescing          │
└──────┬──────────────┬────────┘
       │              │
       ▼              ▼
┌─────────────┐  ┌──────────────┐
│ AnsiRenderer│  │ PlainRenderer│
│ (colors on) │  │ (colors off) │
└─────────────┘  └──────────────┘
```

### Deployment Modes

**STANDALONE** (default, single-process):
- All app components run in one JVM process
- `LocalInboundBus` / `LocalOutboundBus` (wrapped channels)
- YAML persistence by default (zero external dependencies)
- PostgreSQL + Redis available via Docker Compose

**ENGINE** (multi-process, game logic only):
- Runs `GameEngine` + persistence + gRPC server
- `LocalInboundBus` / `LocalOutboundBus` internally
- Gateways connect remotely via gRPC
- Requires gRPC infrastructure but no Redis

**GATEWAY** (multi-process, transports only):
- Runs telnet/WebSocket transports only
- Connects to remote ENGINE via gRPC
- Multiple gateways can share one engine
- Session IDs are globally unique (Snowflake-based)

**Container / Production (AWS ECS Fargate):**
- Single Docker image; mode set via `AMBONMUD_MODE` env var
- `standalone` topology: one Fargate service, `STANDALONE` mode
- `split` topology: separate Engine + Gateway Fargate services connected over Cloud Map DNS
- Config injected via `AMBONMUD_*` env vars (Hoplite env var source)
- `docker-entrypoint.sh` auto-derives unique `engineId` and `advertiseHost` per task
- See `infra/` (CDK project) and [DEPLOYMENT.md](./DEPLOYMENT.md) for full details

---

## Architectural Contracts

These are the inviolable rules of the codebase. Breaking them causes subtle, hard-to-diagnose failures.

### 1. Engine ↔ Transport Isolation

The engine communicates only via semantic events (`InboundEvent` / `OutboundEvent`). No transport code (sockets, HTTP, ANSI escapes) inside the engine; no gameplay logic or state transitions inside transport. ANSI rendering is semantic in engine (`SetAnsi`, `ClearScreen`, `ShowAnsiDemo`) — raw escape sequences are produced by transport renderers only.

### 2. Single-Threaded Engine

`GameEngine` runs on a dedicated `engineDispatcher` with a 100 ms tick loop. Never call blocking I/O inside engine systems or the command router. Use the injected `Clock` for all time-based logic — never `System.currentTimeMillis()`.

### 3. Bus Abstraction

The engine receives an `InboundBus` and sends to an `OutboundBus` — never raw `Channel<T>`. This interface boundary is what allows `Local*Bus` ↔ `Redis*Bus` ↔ `Grpc*Bus` to swap without touching game code. All engine tests use `LocalInboundBus` / `LocalOutboundBus` directly.

### 4. Session Output Semantics

`OutboundRouter` applies backpressure — slow clients may be disconnected. Consecutive prompts coalesce intentionally. `Close` sends final text then closes via callback. Never bypass the outbound router.

### 5. World and ID Invariants

- `RoomId` format: `<zone>:<room>` (always namespaced).
- Player name: 2–16 chars, alnum/underscore, no leading digit.
- Password: non-blank, max 72 (BCrypt-safe).
- Case-insensitive online-name uniqueness is enforced.

### 6. Persistence Chain Integrity

The persistence chain is: `WriteCoalescingPlayerRepository` → `RedisCachingPlayerRepository` (optional) → `YamlPlayerRepository` or `PostgresPlayerRepository`. Changes to `PlayerRecord` must survive all three layers including JSON round-trip through Redis.

---

## Event Model

### Inbound Events (sealed interface)

| Variant | Trigger |
|---------|---------|
| `Connected` | New session established (telnet or WebSocket) |
| `Disconnected` | Session closed or timed out |
| `LineReceived` | Player typed a command |
| `GmcpReceived` | Client sent a GMCP package |

### Outbound Events (sealed interface)

| Variant | Purpose |
|---------|---------|
| `SendText` | Normal game text |
| `SendInfo` | Informational message |
| `SendError` | Error message |
| `SendPrompt` | Player prompt (coalesces) |
| `ShowLoginScreen` | Login banner display |
| `SetAnsi` / `ClearScreen` / `ShowAnsiDemo` | Terminal control (semantic, not raw escapes) |
| `Close` | Graceful disconnect with final message |
| `SessionRedirect` | Cross-engine handoff |
| `GmcpData` | Structured GMCP payload |

**Rule:** never leak escape codes into event output. If a new rendering need arises, add a new sealed variant and handle it in the renderers.

---

## Engine Tick Loop

**File:** `engine/GameEngine.kt`

Each 100 ms tick runs, in order:

1. **Drain inbound** — up to `maxInboundEventsPerTick` from `InboundBus`
2. **Dispatch commands** — `CommandRouter` → handler modules (37 handler files)
3. **MobSystem.tick()** — NPC wandering and behavior trees
4. **CombatSystem.tick()** — active fight resolution
5. **RegenSystem.tick()** — HP / mana regen
6. **StatusEffectSystem.tick()** — DoT / HoT / buffs / debuffs
7. **Scheduler.runDue()** — delayed and recurring callbacks
8. **resetZonesIfDue()** — zone respawns for zones with `lifespan > 0`

Inbound event handling:
- `Connected` → register session, show login banner, start login FSM
- `Disconnected` → clean up 8+ subsystems (groups, trades, duels, pets, status effects, cooldowns, dialogue, dungeons)
- `LineReceived` → if in login FSM, advance it; otherwise `CommandRouter.route()`
- `GmcpReceived` → store package support, emit snapshots

---

## Persistence Architecture

### Backends

**YAML (default):** One file per player under `data/players/`, atomic writes. Zero external dependencies.

**PostgreSQL:** Flyway migrations V1–V38. Connection defaults match `docker-compose.yml`.

### The Write Stack

```
Caller (GameEngine / PlayerRegistry)
     │  repo.save(record)
     ▼
WriteCoalescingPlayerRepository   ← dirty flag, background flush
     │
     ▼
RedisCachingPlayerRepository      ← optional L2 cache
     │
     ▼
YamlPlayerRepository   or   PostgresPlayerRepository
```

Every write layer is transparent to the caller. Guild persistence mirrors this pattern with `GuildRepository` → `YamlGuildRepository` / `PostgresGuildRepository`.

---

## Key Design Decisions

### Commands: Sealed Hierarchy + Thin Router

`CommandParser.parse()` is a pure function that returns one of ~200 sealed `Command` variants. `CommandRouter` is thin dispatch (~110 lines) that routes each variant to one of 37 handler files. This separates parsing from execution and makes both independently testable.

### Config-Driven Game Content

Abilities, status effects, stats, classes, races, mob tiers, and economy tuning all live in `application.yaml` / `AppConfig.kt`. Adding a new ability or status effect requires zero Kotlin changes. The `validated()` function fails at startup for undefined references — errors surface at boot, not at tick time.

### Deterministic Testing

All time-dependent logic uses an injected `Clock`, enabling `MutableClock` in tests for deterministic time control via `advance(ms)` / `set(ms)`. Coroutine tests use `runTest` with `runCurrent()` / `advanceTimeBy()`. Database tests use H2 in PostgreSQL-compatibility mode — no Docker required.

### Three Deployment Modes, One Codebase

`STANDALONE`, `ENGINE`, and `GATEWAY` modes share the same codebase. The difference is which components `MudServer.kt` / `GatewayServer.kt` wire up and which bus implementations they inject. This avoids separate build artifacts while supporting anything from a single-process dev server to a Redis-sharded Fargate split.

### GMCP as the Client Protocol

All structured data between server and client flows through GMCP packages (~50 families, ~100 emitted packages). Telnet clients negotiate GMCP opt-in; WebSocket clients auto-subscribe. This means the web client never scrapes text output — it reacts to typed JSON payloads. See [GMCP_PROTOCOL.md](./GMCP_PROTOCOL.md) for the full reference.

### Server-Resolved Media URLs

All media the web client displays — painted panel art, sprites, voice-over clips, videos — is referenced by **fully-resolved URLs that the engine computes and sends over GMCP** (`Server.Assets`, `image` fields, `voiceUrl`). The client never concatenates paths, and every asset degrades to a CSS-only or text-only fallback when absent. The asset contracts live in [ART_CONTRACT.md](./ART_CONTRACT.md) (painted art / panel reskins) and [VOICE_OVER_CONTRACT.md](./VOICE_OVER_CONTRACT.md) (NPC dialogue audio).

### Zone-Based Sharding

Each engine instance owns a subset of zones via `ZoneRegistry`. Cross-zone player movement is handled by `HandoffManager` with ACK timeout and rollback. Zone instancing uses `InstanceSelector` + `ThresholdInstanceScaler` for load-balanced routing within a zone.

---
