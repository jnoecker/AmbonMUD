# AmbonMUD - Architecture and Design Decisions

This document defines the architecture that current code relies on. It also consolidates the previous scaling narrative and tradeoff guidance.

## Core contracts (do not break)

### 1. Engine and transport isolation

- Engine communicates only through semantic `InboundEvent` and `OutboundEvent`.
- Transport code handles protocol parsing/rendering only.
- Gameplay/state logic stays in engine packages.

### 2. Single-threaded authoritative engine

- `GameEngine` runs on a dedicated single-thread dispatcher with a 100ms tick loop.
- Blocking I/O must stay out of engine systems.
- Time-based logic should use injected `Clock`.

### 3. Bus abstraction boundary

- Engine depends on `InboundBus`/`OutboundBus` interfaces, never raw channels.
- Bus implementation can be local, Redis-backed, or gRPC-bridged without engine changes.

## Runtime architecture

```text
Clients (telnet / browser)
  -> Transports (decode/encode)
  -> InboundBus/OutboundBus
  -> GameEngine (tick loop + systems)
  -> OutboundRouter (queues, backpressure, coalescing)
  -> Session renderers (ANSI/plain)
```

Primary composition roots:
- `Main.kt`
- `MudServer.kt` (standalone/engine wiring)
- `GatewayServer.kt` (gateway wiring)

## Deployment modes

### STANDALONE

- Single process
- Local bus implementations
- No required external services

### ENGINE

- Runs game engine + persistence + gRPC server
- Accepts gateway traffic over gRPC

### GATEWAY

- Runs telnet/websocket transports and outbound routing
- Connects to one or more engine processes over gRPC

## Event model

### Inbound events

- `Connected`
- `Disconnected`
- `LineReceived`
- `GmcpReceived`

### Outbound events

- Messaging: `SendText`, `SendInfo`, `SendError`, `SendPrompt`
- UI/control: `ShowLoginScreen`, `SetAnsi`, `ClearScreen`, `ShowAnsiDemo`
- Session: `Close`, `SessionRedirect`
- Structured telemetry: `GmcpData`

Design rule: introduce new semantic events rather than embedding protocol-specific bytes in gameplay code.

## Persistence architecture

Repository chain:

```text
WriteCoalescingPlayerRepository
  -> RedisCachingPlayerRepository (optional)
  -> YamlPlayerRepository or PostgresPlayerRepository
```

Backend choice:
- `ambonMUD.persistence.backend = YAML | POSTGRES`

Invariants:
- Name/password validation rules must remain intact.
- Case-insensitive uniqueness and lookups must remain intact.
- Player progression fields must round-trip across YAML, Redis JSON, and Postgres mappings.

## Scaling architecture

### Implemented scaling layers

1. Bus abstraction (`InboundBus`/`OutboundBus`)
2. Write-behind/coalesced persistence worker
3. Optional Redis cache and pub/sub transport
4. Engine/gateway split over gRPC
5. Zone-based sharding + zone instancing

### Zone sharding model

- Zones are assigned to engine owners (`ZoneRegistry`).
- Cross-engine operations use `InterEngineBus`.
- Cross-zone movement uses handoff protocol with ACK timeout and rollback behavior.
- Optional Redis player-location index supports efficient cross-engine tell routing.
- Instancing allows multiple copies of hot zones with load-based selection.

### Why this shape

- Keep gameplay deterministic by preserving single-threaded authority per engine shard.
- Scale connection fan-in and transport edges with gateways.
- Add horizontal capacity by distributing zone ownership across engines.

## Operational tradeoffs

### Determinism over fully parallel gameplay

- Benefit: predictable state transitions, fewer race conditions.
- Cost: each engine shard still has a finite tick budget.

### Write-behind persistence over sync-write on every change

- Benefit: lower tick jitter and better throughput.
- Cost: bounded crash-loss window up to flush interval.

### Optional infrastructure dependencies

- Benefit: local development remains lightweight (`STANDALONE`).
- Cost: more configuration permutations to validate.

### Distributed flexibility over single-path simplicity

- Benefit: same codebase supports standalone through sharded topologies.
- Cost: more wiring and observability needs in split deployments.

## Current bottlenecks and practical next focus

- Per-shard tick budget under heavy combat/event load.
- Cross-engine coordination latency for handoff/global communication.
- Observability depth for saturation/correlation across gateway + engine processes.

Short-term architecture priorities:
- Expand queue-depth and failure-reason metrics.
- Improve correlation IDs and structured logs across gateway/engine flows.
- Strengthen telemetry contract tests in CI.

## Related documentation

- Developer onboarding and workflow: [DEVELOPER_GUIDE.md](./DEVELOPER_GUIDE.md)
- World YAML schema: [WORLD_YAML_SPEC.md](./WORLD_YAML_SPEC.md)
- Web client v3 architecture: [WEB_V3.md](./WEB_V3.md)
- Current product roadmap: [ROADMAP.md](./ROADMAP.md)
