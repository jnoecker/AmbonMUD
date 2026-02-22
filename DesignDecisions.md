# Design Decisions

AmbonMUD is intentionally built like a production backend, not a toy server.
This document explains the architectural choices and their tradeoffs.

## 1) Event-driven tick engine
Decision:
- Use a long-lived, tick-based engine loop (`GameEngine`) instead of request/response flow.

Why:
- MUD gameplay is stateful and real-time.
- Combat, regen, NPC movement, scheduled actions, and zone resets fit a deterministic tick model.

Tradeoff:
- Blocking calls inside the engine are dangerous and must be avoided.

## 2) Strict engine/transport separation
Decision:
- Engine only emits/consumes semantic events (`InboundEvent`, `OutboundEvent`).
- Transport adapters handle sockets, telnet/ws framing, and text rendering.

Why:
- Keeps gameplay logic protocol-agnostic.
- Makes new transports possible without rewriting engine systems.

Tradeoff:
- Requires explicit event plumbing, but prevents cross-layer leakage.

## 3) Bus abstraction over raw channels
Decision:
- Engine depends on `InboundBus` / `OutboundBus` interfaces.

Why:
- Local mode and Redis mode can share engine logic.
- Provides a seam for future gateway/engine split.

Tradeoff:
- Small abstraction overhead for significant deployment flexibility.

## 4) Backpressure as a first-class safety feature
Decision:
- Use bounded queues and disconnect slow clients under sustained pressure.
- Coalesce prompts in `OutboundRouter`.

Why:
- Protects overall server health from a single slow consumer.
- Prevents unbounded memory growth.

Tradeoff:
- Some messages (mostly prompts) can be dropped/coalesced under pressure.

## 5) ANSI is semantic, not ad-hoc bytes
Decision:
- Engine emits semantic ANSI events (`SetAnsi`, `ClearScreen`, `ShowAnsiDemo`).
- Renderers decide output bytes.

Why:
- Keeps engine deterministic and testable.
- Avoids escape-code sprawl through gameplay code.

Tradeoff:
- Slightly more plumbing, much cleaner boundaries.

## 6) World is data, validated at load
Decision:
- Rooms/mobs/items are YAML-driven and validated in `WorldLoader`.

Why:
- Fast content iteration without recompilation.
- Strong validation catches broken references at startup.

Tradeoff:
- More loader/validation code up front.

Related choice:
- IDs are namespaced (`zone:id`) to support multi-zone content and cross-zone exits.

## 7) Persistence layering and write-behind coalescing
Decision:
- Keep a repository abstraction with layered implementations:
  - coalescing write-behind (optional)
  - Redis cache (optional)
  - YAML durable store

Why:
- Keeps the hot path free from synchronous disk pressure.
- Allows incremental scale improvements without engine rewrites.

Tradeoff:
- Write-behind introduces a bounded crash-loss window (flush interval).

## 8) Redis is optional, not mandatory
Decision:
- Redis features are opt-in by config.

Why:
- Local development and basic deployments should not require infrastructure.
- Default mode remains fully functional without Redis.

Tradeoff:
- Dual-path logic requires explicit fallback handling.

## 9) Constructor wiring over framework DI
Decision:
- Wire dependencies in `MudServer` using constructor injection.

Why:
- Minimal hidden magic.
- Easy test substitution (clock, repo, buses, metrics).

Tradeoff:
- More bootstrap wiring code.

## 10) Observability built in
Decision:
- Instrument runtime behavior via Micrometer and expose a Prometheus endpoint.

Why:
- Makes tick latency, backpressure, queue behavior, and gameplay throughput visible.
- Supports repeatable load/regression analysis.

Tradeoff:
- Extra instrumentation maintenance.

## 11) Tests as architecture constraints
Decision:
- Treat tests as required contracts for behavior and boundaries.

Why:
- Refactors stay safe as subsystem complexity grows.
- Time-based logic remains deterministic with `MutableClock`.

Tradeoff:
- Higher upfront test effort.

## 12) Incremental scalability plan
Decision:
- Execute scaling in phases while preserving standalone operation.

Current status:
- Phase 1: bus abstraction and session ID factory - complete
- Phase 2: async persistence worker - complete
- Phase 3: Redis cache and Redis buses - complete
- Phase 4: gateway/engine split (gRPC) - planned

Why:
- Maintains deployable stability at each step.
- Avoids high-risk all-at-once rewrites.

Tradeoff:
- Some transitional complexity before final topology arrives.