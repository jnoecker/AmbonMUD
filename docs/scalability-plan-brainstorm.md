# AmbonMUD Scalability Refactor Plan

## Context

AmbonMUD is currently a single-process MUD server. We want to evolve toward a horizontally-scaled gateway architecture ("scale the edge") with one authoritative game engine, async workers for heavy I/O, and Redis for caching/pub-sub — while keeping the single-process mode fully functional at every step.

**Target architecture:**
```
Clients (telnet/WS)                     Clients (telnet/WS)
       │                                        │
   Gateway A ──── gRPC bidi stream ────┐    Gateway B
       │                               │        │
       └────── Redis pub/sub ──────────┼────────┘
                                       │
                               Engine Server
                            (single-threaded tick,
                             authoritative world)
                                       │
                              ┌────────┴────────┐
                         PersistenceWorker    Redis Cache
                              │
                         YAML (durable)
```

**Choices made:**
- **gRPC** bidirectional streaming for gateway↔engine
- **Incremental refactor** — 4 phases, each independently deployable
- **Async worker** starting with persistence only
- **Redis** for message bus (pub/sub) + cache; YAML stays as durable store

---

## Phase 1: Abstract the Event Transport Layer ✅ IMPLEMENTED

**Goal:** Extract `EventBus` interfaces to replace direct `Channel` usage, creating the seam for future gRPC swap. Also abstract session ID allocation.

### New files

| File | Purpose |
|------|---------|
| `src/main/kotlin/dev/ambon/bus/InboundBus.kt` | Interface: `suspend send()`, `trySend()`, `tryReceive()`, `close()` |
| `src/main/kotlin/dev/ambon/bus/OutboundBus.kt` | Interface: `suspend send()`, `tryReceive()`, `asReceiveChannel()`, `close()` |
| `src/main/kotlin/dev/ambon/bus/LocalInboundBus.kt` | Wraps `Channel<InboundEvent>` — preserves current behavior |
| `src/main/kotlin/dev/ambon/bus/LocalOutboundBus.kt` | Wraps `Channel<OutboundEvent>` — preserves current behavior |
| `src/main/kotlin/dev/ambon/session/SessionIdFactory.kt` | Interface: `fun allocate(): SessionId` |
| `src/main/kotlin/dev/ambon/session/AtomicSessionIdFactory.kt` | Current `AtomicLong` behavior, extracted from `MudServer` |

### Files to modify

| File | Change |
|------|--------|
| `MudServer.kt` | Replace `Channel<*>` with `Local*Bus`. Replace `sessionIdSeq` with `AtomicSessionIdFactory`. |
| `GameEngine.kt` | Params: `ReceiveChannel` → `InboundBus`, `SendChannel` → `OutboundBus` |
| `CommandRouter.kt` | `SendChannel<OutboundEvent>` → `OutboundBus` |
| `CombatSystem.kt` | `SendChannel<OutboundEvent>` → `OutboundBus` |
| `MobSystem.kt` | `SendChannel<OutboundEvent>` → `OutboundBus` |
| `OutboundRouter.kt` | `ReceiveChannel<OutboundEvent>` → `OutboundBus`, use `asReceiveChannel()` |
| `BlockingSocketTransport.kt` | `SendChannel<InboundEvent>` → `InboundBus` |
| `KtorWebSocketTransport.kt` | Same as telnet |
| `NetworkSession.kt` | `SendChannel<InboundEvent>` → `InboundBus` |
| All test files (~14) | Mechanical: `Channel(capacity)` → `Local*Bus(capacity)` |

### Dependencies: None
### Config changes: None
### Risk: Low — pure interface extraction, zero behavioral change

### Verification
- `ktlintCheck test` passes
- All 31+ existing tests pass after mechanical constructor updates
- New unit tests for `LocalInboundBus`, `LocalOutboundBus`, `AtomicSessionIdFactory`

---

## Phase 2: Async Persistence Worker ✅ IMPLEMENTED

**Goal:** Move player saves off the engine tick into a background worker. Write-behind with dirty-flag coalescing.

### Current persistence call sites (all in `PlayerRegistry.kt`)
- `bindSession()` — on login
- `disconnect()` → `persistIfClaimed()` — on logout
- `moveTo()` → `persistIfClaimed()` — on every room change
- `grantXp()` → `persistIfClaimed()` — on every kill
- `setAnsiEnabled()` → `persistIfClaimed()` — on ANSI toggle

Reads (`findByName`, `findById`) must stay synchronous for login flow. Only writes become async.

### New files

| File | Purpose |
|------|---------|
| `persistence/WriteCoalescingPlayerRepository.kt` | Wraps `PlayerRepository`. `save()` → marks dirty (no I/O). `find*()` → checks in-memory cache first. Exposes `flushDirty()` and `flushAll()`. |
| `persistence/PersistenceWorker.kt` | Background coroutine: calls `flushDirty()` every N seconds on `Dispatchers.IO`. |

### Files to modify

| File | Change |
|------|--------|
| `MudServer.kt` | Wrap `YamlPlayerRepository` in `WriteCoalescingPlayerRepository`. Create and start `PersistenceWorker`. On `stop()`: flush before closing. |
| `AppConfig.kt` | Add `PersistenceWorkerConfig(flushIntervalMs: Long = 5000, enabled: Boolean = true)` |
| `application.yaml` | Add `persistence.worker.*` defaults |

### Key design: `PlayerRegistry` does NOT change — it still calls `repo.save()` and `repo.find*()`. The coalescing wrapper intercepts transparently.

### Shutdown sequence
1. Stop transports (no new connections)
2. `worker.shutdown()` → `flushAll()` (write all dirty records)
3. Cancel engine
4. Close channels

### Dependencies: None
### Risk: Medium — crash between save and flush loses up to `flushIntervalMs` of data

### Verification
- `WriteCoalescingPlayerRepositoryTest` — verify cache, dirty tracking, coalescing (10 saves → 1 write)
- `PersistenceWorkerTest` — verify periodic flush, shutdown flush
- Manual: connect, walk 10 rooms quickly, check YAML only written once
- `ktlintCheck test` passes

---

## Phase 3: Redis Integration ✅ IMPLEMENTED

**Goal:** Add Redis as cache + pub/sub bus. YAML stays durable. Redis is opt-in (`enabled: false` default).

> **Implementation notes (actual vs plan):**
> - `RedisCachingPlayerRepository` wraps the coalescing repo (not YAML directly as originally sketched)
> - Key scheme uses `player:id:<id>` instead of a hash (`player:<playerId>`) — JSON string, not Redis hash
> - Bus is split into `RedisInboundBus` / `RedisOutboundBus` (separate from a monolithic `RedisPubSubBus`)
> - `RedisSessionRegistry` was deferred to Phase 4 (not yet needed in single-engine mode)
> - `instanceId` is UUID auto-generated if not set in config

### New files

| File | Purpose |
|------|---------|
| `redis/RedisConnectionManager.kt` | Lettuce client lifecycle, typed connections |
| `persistence/RedisCachingPlayerRepository.kt` | Wraps coalescing repo. Write-through to Redis hash + dirty flag for YAML. Read: Redis → in-memory cache → YAML. |
| `bus/RedisPubSubBus.kt` | Publish/subscribe for server-wide broadcasts (gossip, shutdown). Injects received messages into local outbound bus. |
| `session/RedisSessionRegistry.kt` | Tracks `session:<id> → {gatewayId, playerName}` in Redis. Prepares for Phase 4. |

### Redis data model
```
player:name:<lowercase>     → playerId              # name index
player:<playerId>           → {name, roomId, ...}   # full record hash
session:<sessionId>         → {gatewayId, player}    # session ownership
channel:broadcast           → pub/sub for gossip/shutdown
channel:gateway:<id>        → targeted gateway messages
```

### Files to modify

| File | Change |
|------|--------|
| `AppConfig.kt` | Add `RedisConfig(enabled, host, port, password, database, cacheTtlSeconds)` |
| `application.yaml` | Add `ambonMUD.redis.*` defaults (disabled) |
| `MudServer.kt` | Conditionally create Redis components. Repository chain: YAML → Redis cache → write coalescing. Wire `RedisPubSubBus`. |
| `CommandRouter.kt` | Inject optional `broadcastBus` for gossip/shutdown events |

### New dependency
```kotlin
implementation("io.lettuce:lettuce-core:6.3.2.RELEASE")
```

### Risk: Medium — Redis is opt-in. Must handle connection failures gracefully (fall through to local).

### Verification
- Integration tests with embedded Redis (testcontainers or jedis-mock)
- `RedisCachingPlayerRepositoryTest`, `RedisPubSubBusTest`, `RedisSessionRegistryTest`
- Manual: start with `redis.enabled=true`, verify cache hits in logs
- Without Redis: identical to Phase 2

---

## Phase 4: gRPC Gateway Split 🔲 PLANNED

**Goal:** Split into Gateway (transports + routing) and Engine (game logic + persistence) processes, communicating via gRPC bidirectional streaming.

### New files

| File | Purpose |
|------|---------|
| `proto/ambonmud/v1/events.proto` | Protobuf messages mirroring `InboundEvent`/`OutboundEvent` |
| `proto/ambonmud/v1/engine_service.proto` | `service EngineService { rpc EventStream(stream Inbound) returns (stream Outbound); }` |
| `grpc/EngineGrpcServer.kt` | Hosts gRPC service, accepts gateway connections |
| `grpc/EngineServiceImpl.kt` | Bidirectional streaming: proto↔domain conversion, session→stream routing |
| `grpc/ProtoMapper.kt` | Bidirectional `InboundEvent`/`OutboundEvent` ↔ proto conversion |
| `bus/GrpcInboundBus.kt` | `InboundBus` impl that sends over gRPC stream |
| `bus/GrpcOutboundBus.kt` | `OutboundBus` impl that receives from gRPC stream |
| `gateway/GatewayMain.kt` | Entry point for gateway process |
| `gateway/GatewayConfig.kt` | Gateway-specific config |
| `session/SnowflakeSessionIdFactory.kt` | Globally unique IDs: `(gatewayId << 48) \| (timestamp << 16) \| seq` |

### Three deployment modes (`ambonMUD.mode`)

| Mode | Runs | gRPC | Redis |
|------|------|------|-------|
| `standalone` (default) | Everything in-process | No | Optional |
| `engine` | GameEngine + gRPC server + persistence | Server | Required |
| `gateway` | Transports + OutboundRouter + gRPC client | Client | Required |

### Files to modify

| File | Change |
|------|--------|
| `MudServer.kt` | Add `mode` switch: standalone/engine/gateway component selection |
| `Main.kt` | Route to appropriate startup based on mode |
| `AppConfig.kt` | Add `GrpcConfig`, `GatewayConfig`, `DeploymentMode` enum |
| `application.yaml` | Add `ambonMUD.mode`, `grpc.*`, `gateway.*` defaults |

### New dependencies
```kotlin
implementation("io.grpc:grpc-netty-shaded:1.62.2")
implementation("io.grpc:grpc-protobuf:1.62.2")
implementation("io.grpc:grpc-kotlin-stub:1.4.1")
implementation("com.google.protobuf:protobuf-kotlin:3.25.3")
plugins { id("com.google.protobuf") version "0.9.4" }
```

### Session routing
- Each `Connected` event arrives on a specific gRPC stream → engine maps `sessionId → stream`
- Outbound events routed to owning stream by sessionId lookup
- Redis `session:*` keys provide backup/recovery and cross-gateway operations

### Risk: High — largest change, introduces network failure modes. Mitigated by standalone mode always working.

### Verification
- `ProtoMapperTest` — round-trip every event variant
- `EngineServiceImplTest` — bidirectional streaming with in-process gRPC
- `SnowflakeSessionIdFactoryTest` — uniqueness across gateway IDs
- Integration: engine + gateway in-JVM, run connect → login → say → quit flow
- `ktlintCheck test` passes

---

## Phase Dependency Graph

```
Phase 1 (EventBus)
    │
    ├──→ Phase 2 (Async persistence)
    │        │
    │        └──→ Phase 3 (Redis)
    │                 │
    └─────────────────┴──→ Phase 4 (gRPC split)
```

Each phase is independently deployable. Phase 1 is prerequisite for all others.

---

