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

### Phase 6: Production cloud infrastructure

We added a full production deployment story on AWS ECS Fargate:

- **Dockerfile** (multi-stage): fat JAR build → minimal JRE runtime, non-root user. `docker-entrypoint.sh` auto-populates `AMBONMUD_SHARDING_ENGINEID` from the ECS task hostname and `AMBONMUD_SHARDING_ADVERTISEHOST` from the container IP — each Engine task gets a unique identity without code changes.
- **Environment variable config**: Hoplite `EnvironmentVariablesPropertySource` maps `AMBONMUD_*` vars to config keys, so containers need no config file mount.
- **CDK infrastructure** (`infra/`): parameterized by **topology** (standalone vs. ENGINE+GATEWAY split) and **tier** (hobby/moderate/production). Provisions VPC, RDS Postgres, ElastiCache Redis, EFS (world mutations), NLB (telnet), ALB (WebSocket), Cloud Map DNS, ECS Fargate services with CPU/request auto-scaling, CloudWatch alarms, and optional Route 53 + ACM.
- **CI/CD**: ECR push on every `main` merge; CDK deploy workflow with staging auto-deploy and production manual-approval gate, both using OIDC (no long-lived secrets).

**Why it mattered:** the full scaling story is now deployable end-to-end. A single `cdk deploy` command provisions the entire stack; a single redeploy switches topology or tier without data migration.

---

## 5) Current scalability state (what exists today)

### Deployment modes

- **`STANDALONE`**: simplest path, no distributed dependencies required.
- **`ENGINE` + `GATEWAY`**: split mode for horizontal session ingress.

### Load-tested capacity (STANDALONE, February 2026)

Two load test runs were conducted against a single `STANDALONE` instance.

**Run 1 — 70 players (pre-auth-fix baseline)**

| Metric | Result |
|--------|--------|
| Sustained concurrent players | **70** |
| Peak sessions (telnet + WebSocket combined) | **141** |
| Engine tick duration p99 | **< 4 ms** (vs. 100 ms budget) |
| Engine tick overruns | **0** throughout test |
| Outbound frames/sec at peak | ~300 |
| JVM heap at peak | ~40 MB |
| Process CPU at peak | < 1% |

**Run 2 — 150 players (post-auth-fix)**

| Metric | Result |
|--------|--------|
| Sustained concurrent players | **150** |
| Peak sessions (telnet + WebSocket combined) | **153** |
| Engine tick duration p99 at ramp | **~15 ms** (spike during initial 150-player login burst) |
| Engine tick duration p99 steady-state | ~3 ms |
| Engine tick overruns | **~0.02/sec briefly** during ramp; zero thereafter |
| Outbound frames/sec at peak | ~800 |
| JVM heap at peak | ~40 MB |
| Process CPU at peak | ~2% |
| Login capacity disconnects | **0** (auth fix confirmed) |

The auth fix worked: all 150 players logged in cleanly with no "server at max login capacity" rejections. At 150 active players the engine is no longer invisible — tick p99 spiked to ~15 ms during the connection burst and brief overruns appeared, but both resolved once the load stabilised.

### Load-tested capacity (STANDALONE, April 2026)

A third, more aggressive load test was run on the demo EC2 instance (`t4g.medium`, 2 GB ZGC heap) after a batch of targeted scaling changes landed. Bots walked the world, engaged in PvE and PvP combat, crafted, and gossiped — a fuller gameplay mix than the February runs.

**Run 3a — 993 players (pre-improvement baseline on the same hardware)**

| Metric | Result |
|--------|--------|
| Sustained concurrent sessions | **993** (992 telnet + 1 WebSocket) |
| Mobs alive | 106 |
| Rooms occupied | 93 |
| Engine tick duration p99 | **spikes to ~200 ms** (budget-violating) |
| Engine tick overruns | **0.2–0.6/sec**, frequent |
| Engine ticks/sec | wobbling around 9.8 (below the 10 target) |
| Player Saves/sec | ~45 |
| Player repo save duration (max) | ~200 ms |
| JVM GC pause / sec | **2–4 seconds of pause per wall-clock second** (catastrophic — G1 on a small heap) |
| Process CPU | ~0.5–0.75 |

This run identified GC as the dominant source of tick jitter and overruns. The engine could nominally hold ~1000 sessions, but the tick was being shredded by stop-the-world pauses.

**Run 3b — 1,437 players (post-improvement final result)**

| Metric | Result |
|--------|--------|
| Sustained concurrent sessions | **1,437** (all telnet bots) |
| Mobs alive | 286 |
| Rooms occupied | 431 |
| Engine tick duration p99 | **~100 ms steady**, occasional blip to ~130 ms |
| Engine tick duration p50/p95 | ~10 ms / ~50 ms |
| Engine tick overruns | **< 0.2/sec**, mostly 0 |
| Engine ticks/sec | **9.95–10.0 held rock-steady** |
| Inbound events/sec | ~350–450 |
| Outbound frames/sec | ~40,000–50,000 |
| Player Saves/sec | **65–100** sustained |
| Player repo save duration (max) | **~30–100 ms** (down from ~200 ms) |
| JVM GC pause / sec | **0.0–0.3** (down from 2–4) |
| JVM heap | 500–1800 MB, healthy sawtooth on 2 GB cap |
| Process CPU | ~0.5 |
| Inbound/outbound backpressure failures | **0** during steady state |
| Mob system tick duration (p95) | ~1 ms (flat despite 2.7× more mobs alive) |
| Combat system tick duration (p95) | ~1 ms |

**Headline result:** ~45% more concurrent sessions on the same hardware, with *better* tick stability and an order-of-magnitude reduction in GC pause time.

### What actually moved the needle (April 2026 changes)

In rough descending order of impact on Run 3b:

1. **ZGC + 2 GB heap** (PRs [#1009](https://github.com/jnoecker/AmbonMUD/pull/1009), [#1010](https://github.com/jnoecker/AmbonMUD/pull/1010)). Switching from the default G1 collector to ZGC and giving it a heap it could actually breathe in collapsed GC pause time from 2–4 s/s to 0–0.3 s/s. This was the single biggest win and directly explains the engine now holding 10 ticks/sec cleanly. The larger heap (`JAVA_OPTS` tuning) stops ZGC from churning on a too-small working set.
2. **Mob behavior-tree gating to populated zones** ([PR #1015](https://github.com/jnoecker/AmbonMUD/pull/1015)). Mob BTs only execute in zones where at least one player is present. Run 3b had 286 mobs alive across 431 occupied rooms, and mob system tick duration stayed flat at ~1 ms — the same as Run 3a with 106 mobs. This decouples mob count from per-tick cost, which matters enormously as the world grows.
3. **Postgres backend on an EC2 sidecar** ([PR #1011](https://github.com/jnoecker/AmbonMUD/pull/1011)). Moving off YAML persistence onto Postgres dropped max save latency from ~200 ms to ~30–100 ms even while save throughput roughly doubled (45 → ~80/sec). The coalescing worker now flushes to a backend that actually wants concurrent writes.
4. **GMCP broadcast payload serialize-once** ([PR #1013](https://github.com/jnoecker/AmbonMUD/pull/1013)). When N sessions receive the same broadcast, the JSON payload is now serialized once and referenced N times instead of re-serialized per recipient. At ~50k outbound frames/sec this materially reduces allocation pressure (which also feeds back into GC behavior).
5. **`PlayerRegistry.allPlayers` snapshot cache** ([PR #1014](https://github.com/jnoecker/AmbonMUD/pull/1014)). Callers that iterate all players (who, gossip, broadcasts) now hit a cached snapshot invalidated on mutation rather than rebuilding the collection on every access. At 1,400+ sessions this hot path stops being an O(N) allocation on every tick.
6. **Reduced GMCP vitals churn** ([PR #1009](https://github.com/jnoecker/AmbonMUD/pull/1009)). Fewer redundant `Char.Vitals` sends per player per second, which cascades into lower outbound queue depth and lower allocation pressure.

### Auth funnel: the real login-throughput ceiling

The login path has two independently tunable limits:

- **`login.maxConcurrentLogins`** (default: `150`) — maximum sessions simultaneously in the name-lookup → BCrypt → world-entry funnel. Sessions beyond this receive an immediate "server busy" message rather than silently timing out.
- **`login.authThreads`** (default: `8`) — dedicated thread pool for BCrypt hashing, isolated from `Dispatchers.IO` to avoid starving socket I/O.

BCrypt at cost-10 takes roughly 100–300 ms per operation. With `authThreads: 8`, the sustained login throughput cap is approximately **30–80 logins/sec**. At that rate, 150 simultaneous new connections clear the funnel in 2–5 seconds — well within any reasonable bot or player timeout.

**Symptom to watch for:** if bots time out in their initial state (before submitting a name) during a high-concurrency ramp, the cause is almost always the login semaphore being saturated, not the engine. Increase `maxConcurrentLogins` and/or `authThreads` in lock-step with your CPU count.

### Observed subsystem pressure (April 2026, updated)

Post Run 3b, ranked by observed pressure:

1. **Tick p99 at 100 ms budget edge.** Steady-state p99 is now pinned near the 100 ms tick budget at 1,400+ sessions. Headroom is thinner than the raw session count suggests — the next scaling push needs to shift work off the authoritative tick (regen cap, scheduler batching) or add a second engine shard.
2. **Regen subsystem.** Max regen tick duration is ~30–40 ms — still unbounded, still O(N) across all players. This remains the top actionable change for extending single-engine capacity.
3. **Outbound frame throughput (~50k frames/sec).** Serialize-once helps, but at this frame rate the outbound router is the most allocation-intensive path in the process. Further wins likely come from per-frame batching rather than per-payload deduping.
4. **Postgres save latency tail.** Most saves complete in < 30 ms, but occasional max spikes to ~100 ms persist. Acceptable today; worth watching if save rate climbs past ~150/sec.
5. **GC behavior (resolved-but-watch).** ZGC holds steady at 1,400 sessions on a 2 GB heap. Heap usage oscillates 500–1800 MB; we have room but not a lot. A heap cap bump would be the first lever if memory pressure returns.
6. **Login burst.** Not re-exercised in Run 3 (bots connected gradually). The February Run 2 finding that the login semaphore is the actual ceiling on rapid ramps still stands.

### Throughput and safety mechanisms already present

- **Single-threaded authoritative tick** for gameplay consistency.
- **Backpressure-aware outbound routing** (slow sessions can be disconnected instead of allowing unbounded memory growth).
- **Prompt coalescing** to reduce unnecessary output churn.
- **Broadcast payload serialize-once** to compress fan-out allocation cost.
- **Write-coalescing persistence** to compress many state changes into fewer durable writes.
- **Backend selection** (`YAML` or `POSTGRES`) behind one `PlayerRepository` abstraction.
- **Optional Redis cache/pub-sub** with graceful degradation.
- **Mob behavior-tree gating to populated zones** so mob CPU cost tracks active play area, not total world size.
- **Cached `PlayerRegistry.allPlayers` snapshot** to keep whole-world iteration off the hot allocation path.
- **ZGC with tuned heap** for sub-second GC pauses under sustained 1,400+ session load.
- **Metrics pipeline** via Micrometer/Prometheus, including standalone metrics endpoint support in split deployments.
- **Isolated BCrypt thread pool** to prevent auth load from starving socket I/O on `Dispatchers.IO`.

### Sharding status

- Zone-based engine sharding is fully implemented (Phase 5): zone registry, inter-engine messaging, player handoff protocol with ACK-based rollback, Redis player location index, gateway multi-engine session routing, and zone instancing with auto-scaling.
- The system supports deployment from single-process (`STANDALONE`) all the way to multi-engine sharded with zone instancing for hot-zone load distribution.
- Phase 6 (Production AWS infrastructure) is also complete — see `docs/DEPLOYMENT.md`. The CDK project provisions the full stack (VPC, RDS, Redis, EFS, NLB, ALB, Cloud Map, ECS Fargate, CloudWatch) parameterized by `topology` and `tier`.

---

## 6) Scaling tradeoffs to discuss explicitly in interviews

### Tradeoff A: Determinism over raw parallelism

- **Choice:** keep game logic single-threaded.
- **Benefit:** far fewer synchronization bugs and clearer ordering semantics.
- **Cost:** one engine instance has a finite tick budget; vertical scaling and careful scheduling matter. Confirmed empirically: at ~1,400 sessions the single-engine tick p99 sits at the 100 ms budget edge.

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

### Tradeoff E: Big hammer (ZGC) before fine-grained tick optimization

- **Choice:** switch the GC and enlarge the heap before trying to micro-optimize individual allocating call sites.
- **Benefit:** one config change recovered the tick loop across the board — higher leverage than any single code fix would have had at 993 sessions.
- **Cost:** ZGC prefers a larger heap than G1, so minimum deployment size went up. Small-footprint deployments now need a conscious downgrade back to G1.

---

## 7) “How I would explain bottlenecks today”

If asked “what limits scale right now?”, a strong answer is:

1. **Single-engine tick budget at ~1,400 sessions.** Steady-state p99 is pinned to the 100 ms tick budget. The system holds 10 TPS cleanly today, but further single-engine headroom comes from shifting work off the authoritative loop — the obvious next move is a `maxPlayersPerRegenTick` cap so regen stops being an unbounded per-tick sweep.
2. **Login funnel throughput** (BCrypt thread pool). At default settings sustained throughput is ~30–80 new logins/sec. Not a concern at steady state; it is the ceiling on rapid ramps. The semaphore is independent of the engine, and saturation manifests as bots timing out in `WAIT_NAME` — not as tick overruns.
3. **Outbound allocation rate at ~50k frames/sec.** Broadcast payload serialize-once (#1013) closed the biggest hole; the next wins are per-frame batching and possibly a pooled buffer strategy for the hottest GMCP packages.
4. ~~**Telnet transport thread pressure**~~: resolved — `BlockingSocketTransport` now uses JDK 21 virtual threads (PR #313).
5. ~~**GC-induced tick shredding**~~: resolved — ZGC + 2 GB heap (#1009/#1010) collapsed pause time by ~10×.
6. **Cross-zone coordination latency**: handoffs and cross-engine messaging add one Redis pub/sub hop (~1–5 ms). Zone instancing adds instance-selection overhead.
7. **Operational observability depth**: core metrics exist per-engine; the Run 3 Grafana dashboard is rich, but saturation-oriented alerts and cross-engine correlation still have headroom.
8. **Persistence durability vs. latency tuning**: flush interval and backend choices are workload-dependent. Postgres is the current production-path recommendation; YAML remains the lightweight default.

---

## 8) Near-term scaling roadmap (credible next steps)

Reordered after Run 3 findings:

1. **Regen tick cap** (`maxPlayersPerRegenTick`, mirroring `maxCombatsPerTick`). Now the clearest single-engine scaling lever — with mob ticks gated and GC contained, regen is the remaining unbounded per-tick sweep.
2. **Outbound frame batching.** At 50k frames/sec the per-frame overhead dominates. Batching contiguous frames to the same session (especially GMCP update bursts) should cut both syscall count and allocation.
3. **Multi-shard load test.** The Run 3b result (1,437 sessions on a single engine) establishes a strong per-shard baseline. The next credible capacity claim requires an end-to-end multi-engine run exercising handoff, cross-zone tell, and gateway redirect under load.
4. **Scale-in graceful drain**: add an ECS lifecycle hook or Lambda that triggers zone migration (via `HandoffManager`) before an Engine task is terminated by ECS scale-in or deployment.
5. Add queue depth/capacity gauges across inbound/outbound buses and per-session buffers.
6. Improve error taxonomy metrics for auth, handoff, and Redis fallback reasons — particularly a counter for `maxConcurrentLogins` saturation events to make the auth ceiling visible in Grafana.
7. Add structured logging + correlation IDs across gateway/engine/session flows.
8. Strengthen telemetry contract tests so instrumentation regressions are caught in CI.
9. *(done)* ~~**Virtual threads for telnet transport (#301)**~~.
10. *(done)* ~~Codify alert rules and dashboards as versioned infra artifacts~~.
11. *(done)* ~~ZGC + heap tuning~~ — shipped in #1009/#1010, validated in Run 3b.
12. *(done)* ~~Mob behavior tick gating to populated zones~~ — shipped in #1015, validated in Run 3b (286 mobs at ~1 ms p95).
13. *(done)* ~~Postgres production path on demo~~ — shipped in #1011, validated in Run 3b (65–100 saves/sec, max ~100 ms).

---

## 9) 90-second interview version

> “We scaled AmbonMUD by separating concerns: gameplay remains deterministic in a single authoritative tick loop, while transports and I/O are abstracted and distributed. First, we introduced bus interfaces so engine code stopped depending on local channels. Next, we moved persistence writes off the tick using a coalescing worker. Then we added Redis as optional cache and pub/sub with HMAC-signed envelopes and graceful degradation. We split runtime into engine and gateway roles over gRPC for horizontal session ingress, with Snowflake IDs and gateway leasing for distributed session safety. Then we implemented zone-based engine sharding — partitioning the world across engine processes with player handoff, inter-engine bus, Redis-backed O(1) tell routing, and zone instancing with auto-scaling for hot zones. In April 2026 we ran a sustained load test on a `t4g.medium` and pushed a single `STANDALONE` instance to 1,437 concurrent sessions with the engine holding 10 TPS and p99 tick around 100 ms — up from about 1,000 sessions with the tick getting shredded by GC before we intervened. The wins were targeted: switching to ZGC with a 2 GB heap collapsed pause time from 2–4 seconds per wall-clock second to near zero; gating mob behavior ticks to populated zones kept mob CPU cost flat even as world occupancy tripled; moving persistence to Postgres and serializing broadcast payloads once before fanout cut allocation pressure on the hottest paths. Finally, we containerized the server with a multi-stage Dockerfile and a full CDK infrastructure on AWS ECS Fargate — parameterized by topology and tier, with NLB+ALB, Cloud Map, EFS-backed world mutations, and CloudWatch alarms — so the whole stack deploys with a single `cdk deploy` command and scales from ~$30/month to full HA multi-engine production.”

---

## 10) Optional deep-dive prompts (if interviewer asks)

- **Consistency model:** “Authoritative in-engine state; Redis/cache layers are non-authoritative accelerators.”
- **Failure mode:** “Redis loss degrades features but should not crash gameplay.”
- **Data integrity:** “Repository abstraction keeps YAML/Postgres swappable while preserving player progression invariants.”
- **Why not fully distributed immediately?** “We optimized for correctness and deployable increments over speculative distributed complexity.”
- **What surprised you in the April 2026 load test?** “The biggest single-engine win was a config change, not a code change — ZGC with a properly sized heap. It's a reminder that before reaching for architecture, confirm the JVM itself isn't the bottleneck. Our per-tick subsystem costs were already fine; the tick loop was being interrupted by stop-the-world pauses, which no amount of in-engine optimization would have fixed.”
