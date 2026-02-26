# STANDALONE / Per-Node Performance Analysis and Plan

## Scope
This plan focuses on throughput/latency bottlenecks where the game appears to degrade before CPU/RAM saturation, which usually indicates head-of-line blocking in the single-threaded engine loop.

## Observed architecture constraints
- The main engine loop is single-threaded and executes four phases in one coroutine: inbound drain, simulation, GMCP flush, and scheduler/zone reset. Any long phase delays all others. See `GameEngine.run()`. 
- Inbound processing has a fixed time budget (`inboundBudgetMs`) and fixed event cap (`maxInboundEventsPerTick`), which can either starve simulation (if too high) or increase input latency (if too low) under load.
- Several subsystems perform per-tick list cloning/shuffling/scanning, even when capped by work limits.

## High-probability bottlenecks

### 1) Inbound starvation and tick debt growth under bursty traffic
`GameEngine.run()` drains inbound events until a fixed deadline and then runs simulation. In overload conditions, the engine can oscillate between queue growth and simulation starvation because the budget is static and not adaptive to debt/backlog. 

**Why this matches your symptom:** system CPU can remain moderate while the single event loop falls behind due to unfair scheduling between phases.

### 2) High allocation + O(n) shuffles each tick in AI/combat/regen
- `BehaviorTreeSystem.tick()` creates a filtered list of all mobs with behavior trees and shuffles it every tick.
- `CombatSystem.tick()` clones/shuffles `playerTarget.entries` and `activeMobs.values` every tick.
- `RegenSystem.tick()` copies all players each tick (`players.allPlayers()` returns `toList()`), then scans up to cap.

This creates steady GC pressure and wastes engine time on selection logic instead of simulation work.

### 3) Repeated expensive per-action stat/equipment aggregation
Inside combat/regen paths, equipment bonuses are recomputed repeatedly (`items.equipmentBonuses(...)`, repeated stat sums), even when unchanged between ticks.

### 4) Tick clock reads and cross-cutting overhead inside hot loops
`clock.millis()` is called frequently inside nested loops and phase boundaries. Not the biggest bottleneck alone, but avoidable overhead in a very hot single-thread loop.

### 5) Backpressure/disconnect behavior may amplify jitter
Outbound queue saturation leads to disconnects for slow clients, but enqueue failures still consume loop work and can produce bursty churn during stress if capacity tuning is off.

## Prioritized optimization roadmap

## Phase 0 — Measurement first (1-2 days)
1. Add/verify per-phase p50/p95/p99 timing dashboards (already instrumented by `recordTickPhase`).
2. Add counters for per-tick candidate-set sizes before capping:
   - AI candidates scanned
   - player attacks considered
   - mob attacks considered
   - regen candidates scanned
3. Add gauges for queue depths and tick debt trend alarms.

**Exit criteria:** Can attribute >80% of tick time to specific phase + subsystem under load.

## Phase 1 — Low-risk hot-path wins (2-4 days)
1. **Replace per-tick shuffle/copy with rotating cursors (round-robin):**
   - Maintain stable indexed collections for active combatants, AI mobs, and regen players.
   - Advance a cursor each tick and process up to configured cap.
   - Preserve fairness without allocating temporary lists.
2. **Incremental membership tracking:**
   - Track "AI-enabled mob ids" incrementally on spawn/despawn instead of filtering all mobs each tick.
   - Track "regen-eligible players" (hp/mana below max) and remove once full.
3. **Cache derived combat stats:**
   - Recompute attack/defense/stat composites only on equipment/status change events, not per swing.
4. **Micro-optimizations in loop:**
   - Capture `now` once per phase when semantically acceptable.
   - Reduce map lookups and lowercasing in hot paths.

**Expected impact:** lower per-tick CPU time and GC churn; smoother p95 tick duration.

## Phase 2 — Fairness controls in single-thread loop (2-3 days)
1. **Adaptive inbound budgeting:**
   - Dynamically shrink inbound budget when tick debt rises.
   - Dynamically expand when debt is zero and inbound queue age rises.
2. **Work quotas per subsystem based on debt/backlog:**
   - Auto-tune `maxCombatsPerTick`, AI actions, regen cap using bounded controllers.
3. **Budget guardrails:**
   - Hard reserve minimum simulation slice each tick (e.g., >=40%).

**Expected impact:** avoids collapse mode where inputs are drained but world simulation lags.

## Phase 3 — Parallelize non-authoritative work (3-6 days)
Keep authoritative state mutations on engine thread, but offload compute-heavy prep:
1. Precompute AI intent candidates asynchronously, apply chosen intents on engine thread.
2. Precompute path/target query indexes off-thread snapshots.
3. Optional: parallel encode/batch of outbound payloads (text framing/GMCP JSON prep), then enqueue results.

**Expected impact:** better per-node throughput without violating engine consistency model.

## Phase 4 — STANDALONE tuning profile + load tests (2-4 days)
1. Ship a documented "standalone-performance" preset:
   - `tickMillis`, `inboundBudgetMs`, queue capacities, subsystem caps.
2. Add repeatable swarm scenario with pass/fail SLOs:
   - tick debt ceiling
   - p95 inbound queue age
   - disconnect rate from outbound backpressure
3. Capacity envelope outputs:
   - max stable concurrent sessions
   - max command rate before sustained debt.

## Suggested default tuning experiments (safe to trial)
- Reduce `inboundBudgetMs` from 30ms to ~20ms when simulation lag is primary symptom.
- Increase subsystem caps only after Phase 1 allocation cuts.
- Keep `tickMillis=100` initially; changing tick size before fairness/allocation fixes can mask root cause.

## Implementation checklist
- [ ] Add candidate-size metrics (AI/combat/regen).
- [ ] Refactor AI tick selection to cursor-based iteration.
- [ ] Refactor combat tick ordering to cursor-based iteration.
- [ ] Refactor regen scanning to eligibility set + cursor.
- [ ] Add derived-stat cache invalidation hooks (equipment/status changes).
- [ ] Introduce adaptive inbound budget + min simulation reservation.
- [ ] Add standalone load-test profile and CI-adjacent benchmark script.
- [ ] Document operational playbook for tuning + dashboards.

## Risks / tradeoffs
- Cursor-based iteration changes encounter ordering characteristics; needs gameplay regression tests.
- Adaptive budgets can cause oscillations if not damped; use bounded step changes and hysteresis.
- Derived stat caches need strict invalidation events to avoid stale combat math.

## Recommended order to maximize delivery value
1. Phase 0 instrumentation
2. Phase 1 allocation/scanning reductions
3. Phase 2 adaptive fairness in loop
4. Phase 4 tuning + load characterization
5. Phase 3 selective parallelization (if needed after above)
