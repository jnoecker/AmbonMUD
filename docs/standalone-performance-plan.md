# STANDALONE / Per-Node Performance Analysis & Optimization Plan

## Scope and motivation
This report focuses on runtime throughput and responsiveness in `STANDALONE` mode, where the game loop is intentionally single-threaded and therefore sensitive to queueing/latency amplification long before host CPU/RAM saturation is obvious.

## Current architecture signals that explain "low resource usage but degraded performance"

1. **The core engine work is serialized on one dispatcher thread per process.**
   `MudServer` wires the engine dispatcher as a `newSingleThreadExecutor`, and the tick loop runs all major simulation phases serially each tick. This creates an Amdahl's-law ceiling for per-node throughput. Under load, latency rises as queue delay, not necessarily as host CPU saturation.  
   - `src/main/kotlin/dev/ambon/MudServer.kt` (single-thread engine dispatcher)
   - `src/main/kotlin/dev/ambon/engine/GameEngine.kt` (single tick loop with phased processing)

2. **Inbound processing is explicitly time-budgeted (`inboundBudgetMs`) and event-capped (`maxInboundEventsPerTick`).**
   This protects simulation fairness but can accumulate inbound backlog if arrival rate exceeds drain rate, producing "laggy" behavior while OS metrics still look moderate.  
   - `GameEngine.run()` inbound drain budget and caps
   - `application.yaml` defaults: `tickMillis=100`, `inboundBudgetMs=30`, `maxInboundEventsPerTick=1000`

3. **Several hot paths allocate/copy collections each tick.**
   - `PlayerRegistry.allPlayers()` returns `players.values.toList()`.
   - `MobRegistry.all()` returns `mobs.values.toList()`.
   - `BehaviorTreeSystem.tick()` calls `mobs.all().filter(...).toMutableList().shuffle(...)` each tick.
   - `RegenSystem.tick()` calls `players.allPlayers()` each tick and iterates from a random offset.

   Under medium/high concurrency this can become a GC/allocator pressure source and add jitter to the single engine thread.

4. **World/zone reset paths perform repeated whole-collection filtering.**
   `resetZone()` repeatedly filters world mobs/items/rooms and active mobs by zone. This is acceptable at low frequency but can spike tick time if many entities exist.

5. **Outbound backpressure handling can induce disconnect churn for slow clients.**
   `OutboundRouter` uses non-blocking `trySend` into per-session queues; on full queue it disconnects non-GMCP frame senders. In heavy broadcast periods, this can drive repeated session churn and extra work on the single engine thread.

6. **Current metrics are good but do not yet expose enough causal detail for automated adaptation.**
   The code already tracks phase/tick metrics and overrun indicators, but lacks adaptive control loops (dynamic inbound budget, dynamic per-system caps, etc.).

---

## Highest-ROI opportunities (prioritized)

### P0: Protect tick latency first (fast wins)

1. **Add adaptive tick budgeting (closed-loop control).**
   Replace fixed `inboundBudgetMs` with a dynamic target range based on recent p95 phase times and inbound queue depth. Example:
   - Keep simulation+scheduler reserve budget.
   - Allow inbound budget to grow when queue is deep and simulation is under budget.
   - Shrink inbound budget when tick overruns persist.

2. **Introduce per-phase hard caps + debt accounting where missing.**
   Some systems already have caps (`maxCombatsPerTick`, `maxPlayersPerTick`, scheduler cap). Extend the pattern consistently and report carried debt so operators can see which subsystem is starved.

3. **Reduce avoidable per-tick allocations in registries and AI iteration.**
   - Add allocation-light iterators/snapshots for player/mob registries (reused buffers or callback iteration).
   - Avoid full `filter + shuffle` each tick for behavior trees. Keep a stable rotating index, randomize incrementally, and only reevaluate active mob set on spawn/despawn/change.

### P1: Increase effective throughput without breaking engine/transport contract

4. **Move expensive non-stateful work off engine thread where safe.**
   Keep gameplay state mutation on engine thread, but offload pure/isolated work:
   - Parsing/serialization prep for GMCP payloads.
   - Potentially expensive string construction for repeated messages.
   - Optional precomputation for room/mob render fragments.

5. **Batch/coalesce outbound operations further.**
   Existing prompt coalescing is good. Extend coalescing to repeated room-wide redundant lines and high-frequency GMCP updates (e.g., micro-batching a few ms per session).

6. **Pre-index zone content for resets.**
   Build immutable indices at world load:
   - `zone -> roomIds`
   - `zone -> mobSpawns`
   - `zone -> itemSpawns`
   This turns reset scans into O(k_zone) and removes repeated global filters.

### P2: Raise per-node ceiling and operational predictability

7. **Optional multi-lane architecture inside STANDALONE process.**
   Keep one authoritative gameplay thread, but split into lanes with bounded mailboxes:
   - Lane A: engine simulation.
   - Lane B: outbound rendering/transcoding.
   - Lane C: persistence flushing.
   Most of this exists partially; formalizing and enforcing boundaries can smooth tail latency.

8. **Add an auto-tuner profile for STANDALONE defaults.**
   For instance, expose profile presets (`small`, `medium`, `large`) to tune:
   - tick interval
   - inbound budget
   - per-system caps
   - queue capacities
   based on connected sessions/entity counts.

9. **Load-test and profile gate for regressions.**
   Add repeatable performance CI smoke for `:swarm` scenarios and publish p50/p95/p99 tick and command latency baselines.

---

## Concrete implementation plan

## Phase 1 (1–2 weeks): Instrument + quick wins
- [ ] Add metrics:
  - inbound queue depth histogram
  - per-phase p95/p99
  - per-system debt/backlog counters
  - GC pause time export in metrics endpoint docs
- [ ] Implement allocation-light registry iteration APIs and swap Regen/BehaviorTree hot loops.
- [ ] Add zone reset indices computed once at world load.
- [ ] Add runtime config toggles for new behavior (safe default off where risky).

**Exit criteria**
- 20–40% reduction in engine-thread allocation rate under synthetic load.
- Lower p95 tick duration variance in soak tests.

## Phase 2 (1–2 weeks): Adaptive scheduling and caps
- [ ] Implement adaptive inbound budget controller with guardrails.
- [ ] Add debt-aware fairness across systems (combat/status/regen/scheduler).
- [ ] Add operator-visible "degradation mode" state (e.g., when overrun sustained > N seconds).

**Exit criteria**
- Fewer sustained tick overruns at same load.
- Improved command round-trip p95 under bursty chat/combat.

## Phase 3 (2–4 weeks): Throughput scaling path
- [ ] Outbound render micro-batching and GMCP coalescing.
- [ ] Optional multi-lane dispatcher structure in STANDALONE (feature-flagged).
- [ ] Profile-guided tuning of default config for common deployment sizes.

**Exit criteria**
- Higher max concurrent sessions before p95 command latency crosses SLO.
- Better slow-client isolation without disconnect storms.

---

## Suggested benchmark matrix (must be repeatable)

1. **Baseline scenarios** (using `:swarm`):
   - Idle connected users
   - Movement/chat spam
   - Combat-heavy rooms
   - Mixed chat + combat + shops + GMCP

2. **Metrics to track for each scenario**:
   - Tick duration p50/p95/p99
   - Inbound queue depth p95/p99
   - Command end-to-end latency p95/p99
   - Outbound queue depth distribution
   - Disconnect rate due to backpressure
   - GC pause totals and max pause

3. **Pass/fail gates**:
   - No sustained tick overrun at target concurrency.
   - No monotonic inbound backlog growth in steady state.
   - No >X% regressions in p95 latency vs baseline.

---

## Configuration tuning guidance for STANDALONE operators (interim)

Until adaptive controls are implemented:
- Keep `tickMillis` fixed (100ms is reasonable), but tune `inboundBudgetMs` upward carefully if simulation is light.
- Increase system caps (`maxCombatsPerTick`, `regen.maxPlayersPerTick`, `scheduler.maxActionsPerTick`) only with measurements, because each increases single-thread work.
- Validate queue capacities against expected burst patterns to avoid avoidable disconnect churn.
- Prefer reducing per-event work (allocations/string building) over simply increasing caps.

---

## Risks and mitigations

- **Risk:** Adaptive budgeting starves simulation.  
  **Mitigation:** hard minimum simulation reserve + overrun fail-safe rollback.

- **Risk:** Collection-iteration refactors introduce consistency bugs.  
  **Mitigation:** retain existing API, add new fast paths, and cover with regression tests.

- **Risk:** More batching increases perceived latency.  
  **Mitigation:** keep micro-batches tiny (e.g., 5–15ms) and bypass for prompts/errors.

---

## Recommended order of execution
1. Hot-loop allocation fixes + metrics expansion.
2. Adaptive inbound budget + debt visibility.
3. Outbound/GMCP coalescing and optional multi-lane enhancements.
4. Update default STANDALONE tuning profile from measured data.
