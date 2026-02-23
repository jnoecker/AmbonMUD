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

- The codebase includes engine-sharding design and implementation hooks (zone claims/registry, inter-engine bus, zone handoff flow), plus a follow-up plan for replicated entry zones.
- Practically, this means the architecture has moved beyond “single node only,” but the highest-confidence production posture remains “authoritative engine + scalable gateways,” with sharding as the advanced path.

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
- **Cost:** engine CPU/tick loop is still the fundamental gameplay ceiling until sharding is fully operationalized.

---

## 7) “How I would explain bottlenecks today”

If asked “what limits scale right now?”, a strong answer is:

1. **Engine tick budget** (authoritative loop): command routing, combat, mob updates, and world events all share this budget.
2. **Cross-zone coordination complexity** once sharding/handoffs increase.
3. **Operational observability depth**: core metrics exist, but saturation-oriented dashboards/alerts can still be expanded.
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

> “We scaled AmbonMUD by separating concerns: gameplay remains deterministic in a single authoritative tick loop, while transports and I/O are abstracted and distributed. First, we introduced bus interfaces so engine code stopped depending on local channels. Next, we moved persistence writes off the tick using a coalescing worker. Then we added Redis as optional cache and pub/sub, with graceful degradation. Finally, we split runtime into engine and gateway roles over gRPC, so we can scale session ingress horizontally without compromising game-state correctness. We also added Snowflake IDs and gateway leasing for distributed session safety. Today, the system is production-friendly in standalone and split edge-scaling modes, with sharding infrastructure in place and observability hardening as the next major scaling multiplier.”

---

## 10) Optional deep-dive prompts (if interviewer asks)

- **Consistency model:** “Authoritative in-engine state; Redis/cache layers are non-authoritative accelerators.”
- **Failure mode:** “Redis loss degrades features but should not crash gameplay.”
- **Data integrity:** “Repository abstraction keeps YAML/Postgres swappable while preserving player progression invariants.”
- **Why not fully distributed immediately?** “We optimized for correctness and deployable increments over speculative distributed complexity.”

