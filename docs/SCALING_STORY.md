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

### Load-tested capacity (STANDALONE, February 2026)

Load testing confirmed the following benchmarks on a single `STANDALONE` instance:

| Metric | Result |
|--------|--------|
| Sustained concurrent players | **70** |
| Peak sessions (telnet + WebSocket combined) | **141** |
| Engine tick duration p50 | < 1 ms |
| Engine tick duration p99 | **< 4 ms** (vs. 100 ms budget) |
| Engine tick overruns | **0** throughout test |
| JVM heap at peak | ~40 MB |
| Process CPU at peak | < 1% |

The engine tick is not the bottleneck. At 141 sessions the game loop is using less than 4% of its time budget, leaving enormous headroom before tick starvation becomes a concern.

### Auth funnel: the real login-throughput ceiling

The login path has two independently tunable limits:

- **`login.maxConcurrentLogins`** (default: `150`) — maximum sessions simultaneously in the name-lookup → BCrypt → world-entry funnel. Sessions beyond this receive an immediate "server busy" message rather than silently timing out.
- **`login.authThreads`** (default: `8`) — dedicated thread pool for BCrypt hashing, isolated from `Dispatchers.IO` to avoid starving socket I/O.

BCrypt at cost-10 takes roughly 100–300 ms per operation. With `authThreads: 8`, the sustained login throughput cap is approximately **30–80 logins/sec**. At that rate, 150 simultaneous new connections clear the funnel in 2–5 seconds — well within any reasonable bot or player timeout.

**Symptom to watch for:** if bots time out in their initial state (before submitting a name) during a high-concurrency ramp, the cause is almost always the login semaphore being saturated, not the engine. Increase `maxConcurrentLogins` and/or `authThreads` in lock-step with your CPU count.

### Observed subsystem pressure at 70 players

From the February 2026 load test, in order of observed pressure:

1. **Regen tick max latency** — average stays near 0 but max spikes to ~4 ms at 70 active players as the per-player regen loop grows. First subsystem to feel pressure at higher player counts.
2. **Outbound backpressure** — small cluster of disconnects (~0.4/sec) during peak combat activity. Expected and correct behavior (slow WebSocket clients being shed).
3. **Persistence batch latency** — Player repo save/load max hits ~150 ms when the coalescing worker flushes a large batch. Not on the critical path; acceptable and configurable via `flushIntervalMs`.

### Throughput and safety mechanisms already present

- **Single-threaded authoritative tick** for gameplay consistency.
- **Backpressure-aware outbound routing** (slow sessions can be disconnected instead of allowing unbounded memory growth).
- **Prompt coalescing** to reduce unnecessary output churn.
- **Write-coalescing persistence** to compress many state changes into fewer durable writes.
- **Backend selection** (`YAML` or `POSTGRES`) behind one `PlayerRepository` abstraction.
- **Optional Redis cache/pub-sub** with graceful degradation.
- **Metrics pipeline** via Micrometer/Prometheus, including standalone metrics endpoint support in split deployments.
- **Isolated BCrypt thread pool** to prevent auth load from starving socket I/O on `Dispatchers.IO`.

### Sharding status

- Zone-based engine sharding is fully implemented (Phase 5): zone registry, inter-engine messaging, player handoff protocol with ACK-based rollback, Redis player location index, gateway multi-engine session routing, and zone instancing with auto-scaling.
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

1. **Login funnel throughput**: the BCrypt thread pool (`authThreads`) is the primary ceiling for concurrent logins. At default settings (8 threads, cost-10 BCrypt), sustained throughput is ~30–80 new logins/sec. Tunable independently of the engine. High-concurrency test ramps will see bots timeout with “stuck in WAIT_NAME” errors if `maxConcurrentLogins` is too low relative to the ramp rate — this is the semaphore being exhausted, not an engine issue.
2. **Per-shard tick budget** (authoritative loop): command routing, combat, mob updates, and world events share the budget per engine shard. Load testing shows the single-threaded engine comfortably handles 70 active players with p99 tick < 4 ms. With sharding, this scales horizontally.
3. **Telnet transport thread pressure**: `BlockingSocketTransport` uses `Dispatchers.IO` with one blocking `read()` per connection. At very high telnet session counts (hundreds), `Dispatchers.IO` thread overhead becomes meaningful. Virtual threads (JDK 21 feature, tracked as #301) are the planned remedy — each session's blocking read uses a virtual thread instead of a platform thread, enabling thousands of concurrent telnet sessions with minimal overhead. WebSocket transport is unaffected (already non-blocking via Ktor/Netty).
4. **Cross-zone coordination latency**: handoffs and cross-engine messaging add one Redis pub/sub hop (~1–5 ms). Zone instancing adds instance-selection overhead.
5. **Operational observability depth**: core metrics exist per-engine, but saturation-oriented dashboards/alerts and cross-engine correlation can still be expanded.
6. **Persistence durability vs. latency tuning**: flush interval and backend choices are workload-dependent.

---

## 8) Near-term scaling roadmap (credible next steps)

The highest-value next steps are:

1. **Virtual threads for telnet transport (#301)**: migrate `BlockingSocketTransport` from `Dispatchers.IO` platform threads to JDK 21 virtual threads. Unlocks thousands of concurrent telnet sessions without thread pool pressure. WebSocket transport already uses Ktor's non-blocking I/O and does not require this change.
2. Add queue depth/capacity gauges across inbound/outbound buses and per-session buffers.
3. Improve error taxonomy metrics for auth, handoff, and Redis fallback reasons — particularly a counter for `maxConcurrentLogins` saturation events to make the auth ceiling visible in Grafana.
4. Codify alert rules and dashboards as versioned infra artifacts.
5. Add structured logging + correlation IDs across gateway/engine/session flows.
6. Strengthen telemetry contract tests so instrumentation regressions are caught in CI.

This positions the project for confident load testing and safer multi-engine operation.

---

## 9) 90-second interview version

> “We scaled AmbonMUD by separating concerns: gameplay remains deterministic in a single authoritative tick loop, while transports and I/O are abstracted and distributed. First, we introduced bus interfaces so engine code stopped depending on local channels. Next, we moved persistence writes off the tick using a coalescing worker. Then we added Redis as optional cache and pub/sub with HMAC-signed envelopes and graceful degradation. We split runtime into engine and gateway roles over gRPC for horizontal session ingress, with Snowflake IDs and gateway leasing for distributed session safety. Then we implemented zone-based engine sharding — partitioning the game world across multiple engine processes, with a player handoff protocol for cross-zone movement, an inter-engine bus for global commands, and a Redis player location index for O(1) tell routing. We also added zone instancing with auto-scaling for hot-zone load distribution. Load testing validated 70 sustained concurrent players with p99 engine tick under 4 ms against a 100 ms budget — the engine is not the bottleneck. The actual ceiling at high concurrency is the BCrypt auth funnel, which we tuned with an isolated thread pool. Next up is virtual threads for the telnet transport to push beyond hundreds of concurrent sessions.”

---

## 10) Optional deep-dive prompts (if interviewer asks)

- **Consistency model:** “Authoritative in-engine state; Redis/cache layers are non-authoritative accelerators.”
- **Failure mode:** “Redis loss degrades features but should not crash gameplay.”
- **Data integrity:** “Repository abstraction keeps YAML/Postgres swappable while preserving player progression invariants.”
- **Why not fully distributed immediately?** “We optimized for correctness and deployable increments over speculative distributed complexity.”

