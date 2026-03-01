# AmbonMUD — Architecture & Design Decisions

This document captures the architectural principles and design decisions that make AmbonMUD a production-grade MUD server. The philosophy is to avoid premature complexity while building extensible foundations for world content, commands, transports, and persistence.

---

## Table of Contents

1. [Three Critical Contracts](#three-critical-contracts)
2. [Core Architecture](#core-architecture)
3. [Event-Driven Design](#event-driven-design)
4. [18 Design Decisions](#18-design-decisions)

---

## Three Critical Contracts

These three rules form the foundation of AmbonMUD's architecture. **Never violate them.**

### 1. Engine Isolation

The engine communicates **only via `InboundEvent` / `OutboundEvent`** sealed interfaces. No transport code touches the engine; no gameplay logic leaks into transport.

**Why:**
- Clean separation of concerns: transport handles I/O protocols; engine handles game logic.
- Easy to test: engine tests verify semantics; transport tests verify bytes/lines.
- Easy to extend: add new transport (SSH, IRC, etc.) without touching engine code.

**How:**
- Transports decode raw I/O → `InboundEvent` (Connected, Disconnected, LineReceived, GmcpReceived)
- Engine consumes events and produces → `OutboundEvent` (SendText, SendPrompt, SetAnsi, Close, etc.)
- Renderers (AnsiRenderer, PlainRenderer) interpret semantic events → escape sequences

**Violations:**
- ❌ Raw escape sequences in CommandRouter output
- ❌ Socket writes inside engine systems
- ❌ "Is this a telnet or WebSocket client?" checks in engine code

### 2. Single-Threaded Engine

The `GameEngine` runs on a dedicated single-threaded dispatcher with a 100 ms tick loop. **Never call blocking I/O inside engine systems.** Use the injected `Clock` for time-based logic.

**Why:**
- Predictable, deterministic execution makes reasoning about game state straightforward.
- No races, no locking, no coordination overhead.
- Tick loop naturally batches operations (MobSystem, CombatSystem, RegenSystem all run once per tick).

**How:**
- All blocking I/O (`Dispatchers.IO`) happens outside the engine: transports, persistence worker, Redis commands.
- Engine injects a `Clock` interface; all time logic uses `clock.millis()`, never `System.currentTimeMillis()`.
- Engine → OutboundBus is async; persistence writes are fire-and-forget via a background worker.

**Violations:**
- ❌ Blocking network calls inside engine (even with `Dispatchers.IO` wrapping)
- ❌ `Thread.sleep()` or `Thread.currentThread().sleep()`
- ❌ Direct `System.currentTimeMillis()` (use injected Clock)

### 3. Bus Interfaces, Not Raw Channels

Pass `InboundBus` / `OutboundBus` to the engine; **never raw `Channel<T>` references.**

**Why:**
- Bus interfaces create a substitution point: swap `LocalInboundBus` ↔ `RedisInboundBus` ↔ `GrpcInboundBus` without changing engine code.
- Mirrors the transport adapter pattern at the bus layer.
- Makes multi-process, multi-engine deployments possible.

**How:**
- `InboundBus`: interface with `send(event)`, `trySend(event)`, `tryReceive()`, `close()`
- `OutboundBus`: same interface
- Implementations: `Local*Bus` (channels), `Redis*Bus` (pub/sub), `Grpc*Bus` (remote streaming)
- Engine code is agnostic to implementation.

**Violations:**
- ❌ Passing raw `Channel<InboundEvent>` to GameEngine
- ❌ Casting bus to concrete impl inside engine
- ❌ Checking bus type to enable/disable features

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
- Default local workflow uses PostgreSQL + Redis on `localhost`
- YAML persistence remains available as a fallback profile

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

## Event-Driven Design

The engine consumes `InboundEvent`s and produces `OutboundEvent`s. This event model is the core abstraction boundary.

### InboundEvent

| Event | Fields | Meaning |
|-------|--------|---------|
| `Connected` | `sessionId`, `defaultAnsiEnabled` | New client connected |
| `Disconnected` | `sessionId`, `reason` | Client gone |
| `LineReceived` | `sessionId`, `line` | One line of text from client |
| `GmcpReceived` | `sessionId`, `gmcpPackage`, `jsonData` | Structured GMCP data |

### OutboundEvent

| Event | Fields | Meaning |
|-------|--------|---------|
| `SendText` | `sessionId`, `text` | Normal message |
| `SendInfo` | `sessionId`, `text` | Info-level (cyan in ANSI) |
| `SendError` | `sessionId`, `text` | Error message (red in ANSI) |
| `SendPrompt` | `sessionId` | Show `> ` prompt |
| `ShowLoginScreen` | `sessionId` | Show ASCII banner |
| `SetAnsi` | `sessionId`, `enabled` | Enable/disable ANSI |
| `ClearScreen` | `sessionId` | Clear terminal |
| `ShowAnsiDemo` | `sessionId` | Show color palette |
| `Close` | `sessionId`, `reason` | Send goodbye + disconnect |
| `SessionRedirect` | `sessionId`, `targetEngineId` | Cross-engine handoff (sharding) |
| `GmcpData` | `sessionId`, `gmcpPackage`, `jsonData` | Telemetry to client |

**Key principle:** Never add raw escape codes to event output. If a new semantic rendering need arises, add a new `OutboundEvent` variant and handle it in the renderers.

---

## 18 Design Decisions

### 1. Event-Driven Engine (Not Request/Response)

**Decision:** The server runs as a long-lived event loop (tick-based, 100 ms) consuming inbound events and emitting outbound events.

**Why:**
- A MUD is a stateful real-time system with ongoing world ticks, not a stateless request/response API.
- The event model provides a clean boundary between transport and game logic.
- A tick loop naturally batches operations without re-architecting on every new feature.

**Tradeoff:** Requires discipline about blocking I/O; must not stall the hot path.

---

### 2. Transport as Replaceable Adapter

**Decision:** Keep engine unaware of transport specifics, supporting both telnet and WebSockets as adapters without rewriting game logic.

**Why:**
- Telnet: maximum simplicity and compatibility
- Engine speaks semantic events (SendText, SendPrompt, Close), not raw socket writes
- WebSockets (Ktor): low-friction browser client via static v3 bundle + GMCP-driven panels
- Clean path to future transports (SSH, IRC, etc.)

**Tradeoff:** Telnet negotiation (IAC/SB/SE) and WebSocket framing are isolated to transport layer, adding some complexity at the edge.

Current web serving note:
- Ktor static resources serve the current web bundle from classpath package `web-v3` at `/`.
- Compatibility routes `/v3` and `/v3/` redirect to `/`.

---

### 3. Backpressure Handled Explicitly

**Decision:** Outbound writes use bounded queues; slow clients are disconnected rather than allowing unbounded memory growth.

**Why:**
- Single slow client should never degrade the server or other players
- Prompt coalescing prevents spamming prompts when client is not reading

**Tradeoff:** Some output may be dropped (prompts are disposable), but correctness and stability win.

---

### 4. ANSI Support is Semantic

**Decision:** ANSI behavior is represented as semantic events (SetAnsi, ClearScreen, ShowAnsiDemo) and rendered by per-session renderers.

**Why:**
- Prevents escape sequences from leaking into domain logic
- Testable: engine tests verify semantics; transport tests verify formatting
- Avoids scattered `\u001B[...] everywhere` drift

**Tradeoff:** Slightly more plumbing up front, but much less tech debt.

---

### 5. World Content is Data (Not Code)

**Decision:** Rooms, exits, mobs, items, shops, dialogues, quests live in YAML and are validated on load.

**Why:**
- Iterating on world design should not require recompilation
- Validation catches broken exits, invalid directions, missing references early
- Foundation for more advanced area formats

**Tradeoff:** Data-loading and validation code is extra work early, but pays off immediately.

---

### 6. Namespaced IDs for Multi-Zone Worlds

**Decision:** Room, mob, item IDs are namespaced as `zone:id` rather than globally unique.

**Why:**
- Avoids global ID collisions as world grows
- Makes multi-zone loading and cross-zone exits possible
- Mirrors how large MUDs partition content

**Tradeoff:** Slightly more verbose YAML, but prevents future constraints.

---

### 7. Persistence is Phased

**Decision:** Keep persistence behind one repository abstraction so the default runtime can use PostgreSQL + Redis while YAML remains available as a fallback.

**Why:**
- PostgreSQL: durable, indexed, and better suited for the default hot path
- Redis cache: faster reads, enables cross-process scaling
- YAML: still available as a low-infrastructure fallback and debugging tool
- Repository abstraction: clean migration path without touching game logic
- Atomic writes: prevent corruption at every layer
- Write-behind: removes persistence from hot path (every room move was hitting disk)

**Tradeoff:** Write-behind creates data loss window (~5 s, configurable); acceptable for game server.

---

### 8. Dependency Injection Without Framework

**Decision:** Compose dependencies in bootstrap layer (constructor injection), not via globals or singletons.

**Why:**
- Keeps tests lightweight (swap repo or clock easily)
- Makes ownership boundaries explicit
- Avoids early framework lock-in

**Tradeoff:** Slightly more wiring in main (MudServer.kt), but increased clarity and testability.

---

### 9. Tests as Design Constraints

**Decision:** Tests written early, including regression tests for real bugs encountered during development.

**Why:**
- Prevents subtle protocol and ANSI regressions
- Encourages semantic event boundaries
- Makes refactors safer

**Tradeoff:** Some extra time up front, but faster iteration over time.

---

### 10. Event Bus Abstraction

**Decision:** Extract `InboundBus` and `OutboundBus` interfaces wrapping channels, rather than passing raw `Channel<T>` directly.

**Why:**
- Clean substitution point: swap `LocalInboundBus` ↔ `RedisInboundBus` ↔ `GrpcInboundBus` without engine changes
- Full decoupling from both transport and bus technology
- Minimal interface: `send`, `trySend`, `tryReceive`, `close`

**Tradeoff:** One extra indirection, but demonstrated payoff in substitutability (Redis bus works today).

---

### 11. Write-Behind Persistence Worker

**Decision:** Move player saves off engine tick into background coroutine with dirty-flag coalescing.

**Why:**
- Every room move was calling `repo.save()` synchronously (even with `Dispatchers.IO`, still off hot path)
- Coalescing: 10 rapid room changes = 1 file write
- `PlayerRegistry` calls `repo.save()` exactly as before — coalescing is transparent
- Shutdown flushes all dirty records

**Tradeoff:** Up to `flushIntervalMs` (~5 s) of data loss on hard crash; acceptable and configurable.

---

### 12. Redis as Default Local Cache Layer

**Decision:** Redis is on by default for the local production-style runtime, but can still be disabled via config when using the YAML fallback path.

**Why:**
- Better alignment between local defaults and the production-style persistence path
- Bus and cache layers degrade gracefully: Redis failure logs warning, falls back to local impl
- Incremental adoption: cache only, or both, or neither
- Fast tests: Redis integration uses Testcontainers

**Tradeoff:** Dual-path code (enabled/disabled), but intentional and visible.

---

### 13. Gateway Reconnect with Bounded Backoff

**Decision:** Gateway gRPC stream failure triggers bounded exponential-backoff reconnect, not unbounded retry.

**Why:**
- Network partitions and engine restarts expected in split deployments
- Exponential backoff + jitter prevents thundering herd
- Hard attempt budget (`maxAttempts`, default 10) prevents forever-retrying against dead engine
- During reconnect, inbound channel closed so new connections fail fast
- Old sessions cleanly disconnected after successful reconnect

**Tradeoff:** All gateway sessions lost on stream failure (no session migration); simplifies significantly.

---

### 14. GMCP as Structured Data Channel

**Decision:** Send structured JSON data (vitals, room info, inventory, skills) via GMCP alongside plain text, not requiring ANSI parsing.

**Why:**
- Rich clients (web, graphical) need machine-readable data
- GMCP: well-established MUD protocol (telnet option 201)
- WebSocket sessions auto-opt into the core package set used by v3 (`Char.Vitals`, `Room.Info`, `Char.StatusVars`, `Char.Items`, `Room.Players`, `Room.Mobs`, `Room.Items`, `Char.Skills`, `Char.Name`, `Char.StatusEffects`, `Comm.Channel`, `Core.Ping`)
- Engine's `GmcpEmitter` emits data alongside `OutboundEvent`s — no engine/transport boundary changes
- WebSocket clients auto-opt in; telnet clients negotiate via standard `WILL`/`DO`

**Tradeoff:** Telnet subnegotiation complexity (IAC/SB/SE), but isolated to `TelnetLineDecoder` and `NetworkSession`.

---

### 15. Config-Driven Abilities and Debug-Only Classes

**Decision:** Define spell/ability definitions in `application.yaml`, not hardcoded in Kotlin. Debug-only gameplay variants (e.g. the `SWARM` class) carry a `debugOnly = true` flag and are filtered from production class lists unless explicitly enabled via config.

**Why:**
- Adding/tuning/rebalancing abilities should not require recompilation (same as world content being data)
- Config validation at startup catches misconfigured abilities early
- Class restrictions, mana costs, cooldowns, effect values all tunable per-deployment
- `debugOnly` classes expose experimental mechanics in test environments without polluting player-facing selection lists

**How:**
- `PlayerClass.selectable(debugClassesEnabled: Boolean)` returns only non-debug entries by default
- Enable in development with `-Pconfig.ambonMUD.engine.debug.enableSwarmClass=true`
- The `-Pconfig.<key>=<value>` Gradle property mechanism maps to `config.override.<key>` system properties, which Hoplite picks up as highest-priority overrides at startup

**Tradeoff:** No compile-time type safety for ability definitions; runtime validation at startup only. Debug classes require deliberate opt-in to keep them out of production character creation.

---

### 16. Zone-Based Sharding

**Decision:** Partition world across multiple engines by zone, using async inter-engine messaging for cross-zone operations.

**Why:**
- Zones are natural boundaries: namespaced rooms, independent resets
- Most game operations zone-local, minimizing cross-shard traffic
- Player handoff: serialized state transfer + ACK-based rollback on failure
- Single-engine deployment (`STANDALONE`) remains valid; sharding is opt-in

**Tradeoff:** Cross-zone operations (`tell`, `gossip`, `who`) require inter-engine messaging, adding latency. Redis becomes hard dependency for sharded deployments.

---

### 17. Zone Instancing for Load Distribution

**Decision:** Allow popular zones to run multiple instances, with players assigned via load-balanced instance selection and able to switch with `phase` command.

**Why:**
- Single zone (e.g., hub) can become bottleneck
- Instancing adds horizontal capacity without artificial sub-zones
- Auto-scaling based on capacity thresholds handles spikes
- Players on different instances can still use cross-instance communication

**Tradeoff:** Players on different instances cannot see each other in rooms (potentially confusing). Instance state increases memory footprint.

---

### 18. HMAC-Signed Redis Bus Envelopes

**Decision:** Sign all Redis pub/sub messages with HMAC-SHA256 using shared secret; drop messages with invalid signatures.

**Why:**
- In multi-process deployment, unsigned messages allow event injection
- HMAC: cheap, provides message integrity without encryption overhead
- Shared secret validated as non-blank at startup

**Tradeoff:** All processes must share same secret (operational requirement, but standard). Negligible per-message overhead.

---

## Revisiting Decisions

These 18 decisions work together coherently. They avoid premature complexity while providing clear paths for future scaling:

- **Isolated engine** + **event model** + **bus abstraction** = easy to test, extend, and deploy in multiple modes
- **Phased persistence** + **write-behind worker** = removes I/O from hot path without domain logic changes
- **Transport adapters** = can add new clients without touching engine
- **Config-driven abilities** + **YAML world content** = iterate without recompiling
- **Zone-based sharding** + **zone instancing** = horizontal scaling strategy

When in doubt about a new feature, ask: "Does this honor engine isolation, single-threaded contract, and bus abstraction?" If yes, it fits. If no, reconsider.

---

## Further Reading

- **[DEVELOPER_GUIDE.md](./DEVELOPER_GUIDE.md)** — Complete onboarding and common tasks
- **[WORLD_YAML_SPEC.md](./WORLD_YAML_SPEC.md)** — Zone YAML format specification
- **[ROADMAP.md](./ROADMAP.md)** — Planned features and future work
- **[CLAUDE.md](../CLAUDE.md)** — Architectural contracts and change playbooks (DO NOT MODIFY)
