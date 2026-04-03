# AmbonMUD Release Architecture Review

_Date: 2026-04-03_

## Scope

This review focuses on release readiness and architecture-level blockers/opportunities across:

- Runtime composition (`STANDALONE`, `ENGINE`, `GATEWAY`)
- Engine loop and tick-phase boundaries
- Transport adapters and backpressure behavior
- Persistence durability and consistency characteristics
- Redis/gRPC sharding/event-bus boundaries
- Admin surface and operational security posture

## Executive Summary

The codebase has strong architectural foundations (clear semantic event boundaries, robust config validation, bounded tick processing), but there are several release-relevant concerns—mostly around operational defaults and lifecycle hardening.

### Top release blockers

1. ENGINE default port conflict between gRPC and metrics HTTP.
2. Standalone shutdown path can call `stop()` twice.
3. Default write-coalescing flush interval creates a non-trivial crash-loss window.

## Findings

## 1) ENGINE mode default port conflict (Release Blocker)

### Evidence

- `GrpcServerConfig.port` default is `9090`.
- `ObservabilityConfig.metricsHttpPort` default is also `9090`.
- `EngineServer.start()` starts both gRPC and metrics HTTP listeners.

### Impact

With default settings in ENGINE mode, startup can fail due to bind collision.

### Recommendation

- Move one default port (e.g., metrics to `9092`) **and**
- Add config validation that gRPC and metrics ports cannot match in ENGINE mode.

## 2) Standalone shutdown path is not strictly idempotent (High)

### Evidence

- A JVM shutdown hook invokes `stop()`.
- The STANDALONE main flow also calls `stop()` after `awaitShutdown()`.

### Impact

Duplicate stop invocation can mask shutdown-order defects and make cleanup behavior nondeterministic under signal races.

### Recommendation

- Make `stop()` explicitly idempotent with an atomic guard and return-early semantics.
- Ensure only one path owns shutdown completion signaling.

## 3) Persistence crash-loss window from default write coalescing (High)

### Evidence

- Persistence worker is enabled by default.
- Flush interval defaults to `5000ms`.
- Coalescing repository defers physical writes and flushes dirty records asynchronously.

### Impact

An abrupt process crash can drop up to ~5 seconds of in-memory dirty player state.

### Recommendation

- For release profile, reduce flush interval.
- Add immediate-flush triggers for critical state transitions (economy transactions, guild mutations, milestone progression).

## 4) Admin auth hardening relies on deployment perimeter (Medium)

### Evidence

- Admin auth helper uses Basic auth token comparison.
- No built-in TLS guarantee in the server layer.

### Impact

If exposed directly (without TLS/reverse proxy controls), credentials are at risk and brute-force posture is weak.

### Recommendation

- Document production requirement: TLS termination + private network boundary.
- Add optional rate limiting / lockout for auth failures.

## 5) `GameEngine` has high orchestration density (Medium)

### Evidence

- `GameEngine` owns many systems/handlers and a long single-class tick pipeline.

### Impact

Increases change coupling and regression blast radius as features expand.

### Recommendation

- Extract tick phases into dedicated coordinators (`InboundPhase`, `SimulationPhase`, `GmcpFlushPhase`, `SchedulerPhase`) with phase contract tests.

## 6) Gateway lifecycle waiting strategy is minimal (Low/Medium)

### Evidence

- GATEWAY mode blocks the main thread with `Thread.currentThread().join()`.

### Impact

Works functionally, but constrains graceful orchestration semantics and makes lifecycle intent less explicit.

### Recommendation

- Introduce a gateway shutdown signal (parity with other modes) and structured await semantics.

## Architectural strengths worth preserving

- Clear engine/transport semantic boundary via `InboundEvent`/`OutboundEvent`.
- Robust and broad config validation.
- Tick-loop fairness controls: bounded inbound drain + per-system caps.
- Redis bus integrity checks with HMAC signature validation.

## Suggested launch hardening checklist

1. Resolve ENGINE default port collision + add validation gate.
2. Make server shutdown idempotent.
3. Define launch durability profile (flush policy + critical immediate flushes).
4. Publish production deployment baseline (TLS/proxy/admin constraints).
5. Add a pre-release startup smoke test matrix for all three modes.

