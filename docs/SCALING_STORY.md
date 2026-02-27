# AmbonMUD Scaling Story (Interview Talk Track)

This document is a narrative you can use in interviews to explain how AmbonMUD scales, what tradeoffs were made, what is already implemented, and what comes next.

---

## 1) One-sentence framing

> “I designed AmbonMUD to scale by **keeping gameplay authoritative and deterministic in a single-threaded engine**, while **scaling connection handling and I/O at the edges** using gateway processes, async persistence, and optional Redis-based coordination.”

---

## 2) Business/problem framing (why scaling mattered)

AmbonMUD started as a classic single-process MUD, which was fast to build and easy to reason about. As requirements grew, we needed to support:

- more simultaneous network sessions (telnet + WebSocket),
- safer persistence under higher write frequency,
- deployment flexibility (single process for dev/small, split services for larger loads), and
- a path to multi-engine sharding without rewriting core gameplay.

The key principle was **incremental scalability**: keep `STANDALONE` working at every step while adding the seams needed for distributed modes.

---

## 3) Architectural principle: scale the edge, protect the core

The core design decision was to **not** parallelize game logic directly. Instead:

- The engine tick loop stays authoritative and single-threaded for consistent state transitions.
- Transport, buffering, persistence, and inter-process delivery are where concurrency and horizontal scaling happen.

Why this is interview-worthy:

- It reduces race-condition risk in gameplay.
- It makes correctness easier to test.
- It still gives practical horizontal scale via gateway fan-out and optional sharding.

---

## 4) Evolution story (phased delivery)

### Phase 1: Event bus abstraction

We introduced `InboundBus`/`OutboundBus` interfaces and local implementations so engine systems stop depending on raw channels.

**Why it mattered:** this created a clean seam where local transport could later be replaced with remote transport (Redis/gRPC) without rewriting game systems.

### Phase 2: Async persistence worker

We added write-coalescing + background flush for player saves. Reads remain synchronous for login/authority paths; writes are coalesced and flushed off the game tick.

**Why it mattered:** engine tick latency stopped being coupled to disk/database write frequency.

### Phase 3: Redis integration (optional)

We added Redis as:

- L2 cache for persistence,
- pub/sub transport for bus events.

Redis is explicitly non-authoritative; if unavailable, the system degrades without taking down the engine.

**Why it mattered:** this unlocked multi-process routing and reduced repeated persistence lookup pressure.

### Phase 4: gRPC engine/gateway split

We split deployment into:

- `ENGINE` mode: authoritative game loop + gRPC server,
- `GATEWAY` mode: transport termination + gRPC client bridge,
- `STANDALONE` mode: all-in-one for simplicity.

Gateways reconnect with backoff, and session IDs are globally safe via Snowflake-style allocation + leased gateway IDs.

**Why it mattered:** connection scale becomes mostly an edge concern; the core remains deterministic.

### Phase 5: Zone-based engine sharding

We partitioned the game world across multiple engine processes by zone:

- Zone registry (Static or Redis-backed) maps zones to owning engines.
- Inter-engine bus (Local or Redis) handles cross-zone messaging (tell, gossip, who, handoff).
- Player handoff protocol with serialized state transfer, ACK-based timeout, and rollback on failure.
- Redis-backed player location index for O(1) cross-engine `tell` routing.
- Gateway multi-engine support with session routing and `SessionRedirect` handling.
- Zone instancing (layering) with load-balanced instance selection and auto-scaling based on capacity thresholds.

**Why it mattered:** the engine is no longer a single-process bottleneck. Each shard handles its zones independently, and the system scales horizontally by adding more engine shards.

---

## 5) Current scalability state (what exists today)

### Deployment modes

- **`STANDALONE`**: simplest path, no distributed dependencies required.
- **`ENGINE` + `GATEWAY`**: split mode for horizontal session ingress.

### Throughput and safety mechanisms already present

- **Single-threaded authoritative tick** for gameplay consistency.
- **Backpressure-aware outbound routing** (slow sessions can be disconnected instead of unbounded queue growth).
- **Prompt coalescing** to reduce unnecessary output churn.
- **Write-coalescing persistence** to compress many state changes into fewer durable writes.
- **Backend selection** (`YAML` or `POSTGRES`) behind one `PlayerRepository` abstraction.
- **Optional Redis cache/pub-sub** with graceful degradation.
- **Metrics pipeline** via Micrometer/Prometheus, including standalone metrics endpoint support in split deployments.

### Sharding status

- Zone-based engine sharding is fully implemented (Phase 5): zone registry, inter-engine messaging, player handoff protocol with ACK-based rollback, Redis player location index, gateway multi-engine session routing, and zone instancing with auto-scaling.
- The system supports deployment from single-process (`STANDALONE`) all the way to multi-engine sharded with zone instancing for hot-zone load distribution.
- The system supports deployment from single-process (`STANDALONE`) all the way to multi-engine sharded with zone instancing for hot-zone load distribution.

---

## 6) Scaling tradeoffs to discuss explicitly in interviews

### Tradeoff A: Determinism over raw parallelism

- **Choice:** keep game logic single-threaded.
- **Benefit:** far fewer synchronization bugs and clearer ordering semantics.
- **Cost:** one engine instance has a finite tick budget; vertical scaling and careful scheduling matter.

### Tradeoff B: Eventual persistence over sync-on-every-change

- **Choice:** write-behind with periodic flush.
- **Benefit:** lower tick jitter and better throughput.
- **Cost:** bounded durability window (up to flush interval) on abrupt crash.

### Tradeoff C: Optional infrastructure dependencies

- **Choice:** Redis/Postgres are feature flags, not hard requirements.
- **Benefit:** local development and small deployments stay lightweight.
- **Cost:** more conditional wiring paths and more configuration/testing matrix.

### Tradeoff D: Edge scaling first, core scaling second

- **Choice:** scale gateways first; shard engine state later.
- **Benefit:** simpler early wins for connection fan-in and operational resilience.
- **Cost:** engine CPU/tick loop was the fundamental gameplay ceiling until sharding was operationalized.
- **Update:** Zone-based sharding (Phase 5) is now implemented, removing the single-engine bottleneck. Zone instancing further distributes load within hot zones.

---

## 7) “How I would explain bottlenecks today”

If asked “what limits scale right now?”, a strong answer is:

1. **Per-shard tick budget** (authoritative loop): command routing, combat, mob updates, and world events share the budget per engine shard. With sharding, this scales horizontally.
2. **Cross-zone coordination latency**: handoffs and cross-engine messaging add one Redis pub/sub hop (~1-5ms). Zone instancing adds instance-selection overhead.
3. **Operational observability depth**: core metrics exist per-engine, but saturation-oriented dashboards/alerts and cross-engine correlation can still be expanded.
4. **Persistence durability vs. latency tuning**: flush interval and backend choices are workload-dependent.

---

## 8) Near-term scaling roadmap (credible next steps)

The highest-value next steps are:

1. Add queue depth/capacity gauges across inbound/outbound buses and per-session buffers.
2. Improve error taxonomy metrics for auth, handoff, and Redis fallback reasons.
3. Codify alert rules and dashboards as versioned infra artifacts.
4. Add structured logging + correlation IDs across gateway/engine/session flows.
5. Strengthen telemetry contract tests so instrumentation regressions are caught in CI.

This positions the project for confident load testing and safer multi-engine operation.

---

## 9) 90-second interview version

> “We scaled AmbonMUD by separating concerns: gameplay remains deterministic in a single authoritative tick loop, while transports and I/O are abstracted and distributed. First, we introduced bus interfaces so engine code stopped depending on local channels. Next, we moved persistence writes off the tick using a coalescing worker. Then we added Redis as optional cache and pub/sub with HMAC-signed envelopes and graceful degradation. We split runtime into engine and gateway roles over gRPC for horizontal session ingress, with Snowflake IDs and gateway leasing for distributed session safety. Then we implemented zone-based engine sharding — partitioning the game world across multiple engine processes, with a player handoff protocol for cross-zone movement, an inter-engine bus for global commands, and a Redis player location index for O(1) tell routing. We also added zone instancing with auto-scaling for hot-zone load distribution. Today, the system scales from single-process to multi-engine sharded deployments, with observability hardening as the next major scaling multiplier.”

---

## 10) Optional deep-dive prompts (if interviewer asks)

- **Consistency model:** “Authoritative in-engine state; Redis/cache layers are non-authoritative accelerators.”
- **Failure mode:** “Redis loss degrades features but should not crash gameplay.”
- **Data integrity:** “Repository abstraction keeps YAML/Postgres swappable while preserving player progression invariants.”
- **Why not fully distributed immediately?** “We optimized for correctness and deployable increments over speculative distributed complexity.”

