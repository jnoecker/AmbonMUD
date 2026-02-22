# AmbonMUD Scalability Plan (Status Update)

This document tracks the scalability roadmap and the current code reality.

## Goal
Scale AmbonMUD from standalone single-process runtime toward a gateway/engine split while preserving a working standalone mode at every phase.

Target direction:
- Multiple edge gateways (telnet/ws connections)
- One authoritative engine process
- Redis for shared cache/pub-sub
- Event stream boundary suitable for gRPC split

## Current Architecture (as implemented)
```text
Clients (telnet/ws)
  -> Transport adapters
  -> InboundBus / OutboundBus
  -> GameEngine (authoritative state)
  -> OutboundRouter
  -> Session queues/renderers

Persistence path:
WriteCoalescing (optional)
  -> Redis cache (optional)
    -> YAML durable store
```

## Phase Status

| Phase | Scope | Status |
|------|-------|--------|
| 1 | Bus abstraction + session ID factory | Complete |
| 2 | Async write-behind persistence worker | Complete |
| 3 | Redis L2 cache + Redis bus wrappers | Complete |
| 4 | Gateway/engine process split (gRPC) | Planned |

---

## Phase 1 (Complete)
Delivered:
- `InboundBus`, `OutboundBus`
- Local implementations: `LocalInboundBus`, `LocalOutboundBus`
- Session ID abstraction: `SessionIdFactory`, `AtomicSessionIdFactory`
- Engine and transport wiring moved from raw channels to bus interfaces

Result:
- Engine is bus-agnostic and can run against local or Redis bus implementations.

## Phase 2 (Complete)
Delivered:
- `WriteCoalescingPlayerRepository`
- `PersistenceWorker`
- Config: `ambonMUD.persistence.worker.enabled`, `flushIntervalMs`

Result:
- `repo.save()` no longer implies immediate durable write when coalescing is enabled.
- Dirty records flush periodically and flush-all on shutdown.

## Phase 3 (Complete)
Delivered:
- `RedisConnectionManager` (`StringCache` implementation)
- `RedisCachingPlayerRepository` (name->id and id->record keys)
- `RedisInboundBus` and `RedisOutboundBus` wrappers with envelope serialization
- Config under `ambonMUD.redis.*` including `redis.bus.*`

Key behavior:
- Redis is optional and disabled by default.
- Cache and bus failures are logged and treated as best-effort.
- Bus mode filters self-originated messages by `instanceId`.

Current caveat:
- Startup logs explicitly label Redis bus mode as experimental.

---

## Phase 4 (Planned): Gateway/Engine Split
Objective:
- Move telnet/ws handling into gateway processes.
- Keep gameplay authoritative in engine process.
- Replace in-process bus linkage with remote event streaming.

Likely deliverables:
- Protobuf schema for inbound/outbound event envelopes
- gRPC bidirectional stream service
- Gateway runtime that bridges transport <-> gRPC stream
- Engine runtime mode that binds stream <-> bus and routes by session ownership
- Deployment mode config (`standalone`, `engine`, `gateway`)

Design constraints to preserve:
- Engine remains single-threaded for gameplay state.
- Standalone mode remains functional for local/dev use.
- Backpressure and disconnect semantics remain explicit.
- Redis remains optional for standalone mode.

---

## Open Design Questions for Phase 4
1. Session ownership and failover:
- Source of truth for `sessionId -> gateway` mapping.
- Recovery behavior when a gateway drops mid-session.

2. Ordering and delivery guarantees:
- Required semantics for per-session ordering across stream reconnects.
- Retry/idempotency policy for control events (`Close`, `SetAnsi`).

3. Security model:
- Gateway <-> engine authn/authz.
- Input sanitization boundaries and trust model.

4. Operational model:
- Startup dependency ordering between gateway, engine, Redis.
- Health/readiness probes and draining semantics.

---

## Verification Checklist (per phase)
- All existing tests pass (`ktlintCheck test`).
- New phase-specific unit/integration tests exist.
- Standalone mode behavior is unchanged unless intentionally modified.
- Failure mode tests cover Redis unavailable and reconnect scenarios.
- Docs (`README`, `AGENTS`, onboarding) reflect the implemented state.

---

## Practical Recommendation
Before starting Phase 4 implementation, run a focused design spike on stream protocol and session routing with a minimal vertical slice:
- one gateway,
- one engine,
- login -> look -> say -> quit flow,
- controlled disconnect/reconnect tests.

This will validate protocol and ownership assumptions before broad refactor work.