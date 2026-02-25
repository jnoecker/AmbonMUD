# Performance Issues Template

Use this template to create GitHub issues for each performance finding. Copy each issue block and create a new GitHub issue with the specified title, labels, and body.

---

## CRITICAL Issues (HIGH Priority - Algorithmic)

### Issue 1: RegenSystem O(n) List Copy Every Tick
**Title:** `perf: RegenSystem copies entire player list every tick`
**Labels:** `performance`, `high-priority`, `engine`
**Body:**
```
## Problem
RegenSystem.tick() creates a full copy of all players and shuffles the entire list, even though only up to 50 are processed per tick.

## Location
`src/main/kotlin/dev/ambon/engine/RegenSystem.kt:45-46`

## Impact
- 1000-10,000 allocations/sec (scales with player count)
- GC pressure increases 5-10x during peak hours
- Tick-time variance increases due to GC pauses

## Expected Improvement
- Eliminate unnecessary list copy
- Use random starting point iterator instead
- Save 500 allocations/sec per 100 concurrent players

## Effort
15 minutes

## References
See PERFORMANCE_ANALYSIS.md: Issue #1
```

---

### Issue 2: flushDirtyGmcpMobs Hidden O(n×p) Loop
**Title:** `perf: flushDirtyGmcpMobs nested loop is O(n²) in room density`
**Labels:** `performance`, `high-priority`, `engine`, `gmcp`
**Body:**
```
## Problem
The nested loop in flushDirtyGmcpMobs calls playersInRoom() for each dirty mob, causing O(dirty_mobs × players_per_room) complexity.

## Location
`src/main/kotlin/dev/ambon/engine/GameEngine.kt:1449-1456`

## Impact
- 500+ player lookups per tick during crowded combat
- 20 players in 1 room + 10 mobs = 5000 iterations/tick
- 100 ticks/sec = 500,000 lookups/sec during intense combat

## Worst Case Scenario
- 50 players in raid zone
- 20 simultaneous mobs taking damage
- 20 × 50 = 1000 player lookups per flush
- Happens every tick during boss fight

## Expected Improvement
- Invert loop: iterate players, check if targeting mob
- Change to O(dirty_mobs + players) complexity
- 90% reduction in lookups during crowded combat

## Effort
20 minutes

## References
See PERFORMANCE_ANALYSIS.md: Issue #2
```

---

### Issue 3: StatusEffectSystem.snapshotEffects O(n²) Deduplication
**Title:** `perf: Status effect snapshots use O(n²) counting for stacks`
**Labels:** `performance`, `high-priority`, `engine`, `effects`
**Body:**
```
## Problem
snapshotEffects() calls countStacks() for each effect, which does a linear search. Also uses distinctBy() for dedup, adding another O(n) pass.

## Location
`src/main/kotlin/dev/ambon/engine/status/StatusEffectSystem.kt:264-279`

## Impact
- 10 effects × 10 iterations = 100+ iterations per player snapshot
- Called every time any effect changes
- 10 dirty players × 25 iterations = 250+ iterations per tick

## Root Cause
- countStacks() iterates entire list for each effect
- distinctBy() adds another O(n) dedup pass
- Could pre-compute stacks when effects are added/removed

## Expected Improvement
- Pre-compute effect stacks in Map<StatusEffectId, Int>
- Track on add/remove events
- Change O(n²) snapshot to O(n) with O(1) lookup

## Effort
1.5 hours (requires StatusEffectSystem refactor)

## References
See PERFORMANCE_ANALYSIS.md: Issue #3
```

---

## CRITICAL Issues (HIGH Priority - Fairness/Architecture)

### Issue 4: Engine Tick Fairness - Inbound Drain Monopolization
**Title:** `perf: Inbound event drain can monopolize entire tick budget`
**Labels:** `performance`, `high-priority`, `engine`, `fairness`
**Body:**
```
## Problem
The inbound event drain phase has no time budget. It can consume the entire 100ms tick, starving simulation phases (combat, regen, GMCP flushes).

## Location
`src/main/kotlin/dev/ambon/engine/GameEngine.kt:380-395`

## Impact
- Worst-case tick latency: 100+ ms
- Login bursts cause server-wide lag for all players
- Manifests as "server feels laggy but CPU is low" (10-30% utilization)
- Tick variance: some ticks 5ms, others 95ms

## Worst Case Scenario
- 100 players attempt login simultaneously
- 100 × 200ms (auth per login) = 20 seconds of simulation stall
- All 50 online players see 200 ticks worth of latency spike

## Solution
Implement time-budgeted inbound drain:
- Reserve 30ms for inbound events
- Guarantee remaining 70ms for simulation
- Accept losing events on overflow (queue them for next tick)

## Effort
1-2 hours

## Phase 0
This is Phase 0: Latency/Fairness Guardrails (implement first)

## References
See PERFORMANCE_ANALYSIS.md: Issue #4
```

---

### Issue 5: Authoritative Loop Stall - Login/Auth Blocking Simulation
**Title:** `perf: Login/auth suspends engine loop, freezing all players`
**Labels:** `performance`, `high-priority`, `engine`, `login`, `fairness`
**Body:**
```
## Problem
The login sequence executes synchronously on the engine thread. Although it uses Dispatchers.IO, the coroutine suspends, blocking the authoritative loop from proceeding.

## Location
`src/main/kotlin/dev/ambon/engine/GameEngine.kt:1100-1250` (login sequence)

## Impact
- Per-login: 100-500ms suspension
- During burst: 10 logins × 200ms = 2000ms server freeze
- All 50 online players see ~20 ticks skipped
- Manifests as: "Server froze then resumed" (catastrophic UX)

## Why It's Critical
- Hardest to diagnose (low CPU during stall, high latency)
- Worst UX impact: entire server appears unresponsive
- Happens during peak hours (most visible)

## Solution
Decouple auth/login completion from authoritative loop:
- Launch auth/DB work on Dispatchers.IO without awaiting
- Send `LoginCompleted` event when ready
- Engine immediately proceeds with simulation
- Handle login completion asynchronously

## Effort
3-4 hours (requires InboundEvent hierarchy changes)

## Phase 0
This is Phase 0: Latency/Fairness Guardrails (implement first)

## References
See PERFORMANCE_ANALYSIS.md: Issue #5
```

---

### Issue 6: Scheduler Overload - O(n) Recount When Backlogged
**Title:** `perf: Scheduler amplifies overload by scanning queue at worst time`
**Labels:** `performance`, `high-priority`, `scheduler`, `fairness`
**Body:**
```
## Problem
When scheduler detects backlog, it scans the entire queue for overrun accounting. This O(n) work happens exactly when the server is already stressed, worsening the situation.

## Location
`src/main/kotlin/dev/ambon/engine/scheduler/Scheduler.kt:runDue()` (overload path)

## Impact
- Spell spam triggers 1000+ action backlog
- Each tick: 50 actions run + 1000 queue scans = latency increases
- Backlog grows → worse performance → spiral
- Players experience "jank" during ability spam

## Pathological Behavior
- Server is fine under normal load
- Player spams abilities → queue fills → scheduler scans
- Scanning makes things worse → queue grows → worse scanning
- Cascading failure instead of graceful degradation

## Solution
Replace O(n) recount with O(1) amortized tracking:
- Track queue age and overdue count as items enter/leave
- Record metrics on backlog detection (don't scan)
- Expose queue age metrics for observability

## Effort
1-2 hours

## Phase 0
This is Phase 0: Latency/Fairness Guardrails (implement first)

## References
See PERFORMANCE_ANALYSIS.md: Issue #6
```

---

## MAJOR Issues (MEDIUM Priority - Algorithmic Efficiency)

### Issue 7: CombatSystem - Repeated Equipment Lookups
**Title:** `perf: Combat repeatedly looks up equipment for stat bonuses`
**Labels:** `performance`, `medium-priority`, `engine`, `combat`
**Body:**
```
## Problem
CombatSystem calls items.equipment(sessionId) multiple times per attack for different stat calculations (damage, dodge, strength bonus).

## Location
`src/main/kotlin/dev/ambon/engine/CombatSystem.kt:515-532`

## Impact
- 150+ equipment lookups per combat tick
- 1500+ lookups/sec during combat
- Each creates temporary Map object

## Solution
Cache equipment bonuses during combat session:
- Pre-calculate damage/defense bonuses at combat start
- Invalidate cache on equip/unequip commands
- Avoid repeated lookups within same combat round

## Effort
45 minutes

## Phase 1
This is Phase 1: Critical Fixes (medium priority)

## References
See PERFORMANCE_ANALYSIS.md: Issue #7
```

---

### Issue 8: GMCP - String Interpolation + O(n) jsonEscape() Per Field
**Title:** `perf: GMCP serialization uses inefficient 5-chain jsonEscape()`
**Labels:** `performance`, `medium-priority`, `engine`, `gmcp`
**Body:**
```
## Problem
jsonEscape() does 5 sequential string.replace() calls, each scanning the entire string. Called per GMCP field.

## Location
`src/main/kotlin/dev/ambon/engine/GmcpEmitter.kt:27-269` (especially lines 263-269)

## Impact
- 3.75M character comparisons/sec during combat
- 2500+ allocations/sec (5 replace calls per escape)
- Visible as tick-time spikes during GMCP burst

## Solution
Replace with single-pass character-by-character scan:
- Scan string once, build StringBuilder with escapes
- Or use regex with compiled pattern
- Reduce allocations from 5 to 1 per escape

## Effort
30 minutes

## Phase 1
This is Phase 1: Critical Fixes

## References
See PERFORMANCE_ANALYSIS.md: Issue #8
```

---

### Issue 9: OutboundRouter - Multiple HashMap Lookups Per Frame
**Title:** `perf: OutboundRouter does multiple hash map lookups per message`
**Labels:** `performance`, `medium-priority`, `transport`
**Body:**
```
## Problem
Every sendLine()/sendPrompt() does a ConcurrentHashMap lookup on sinks. With 100+ messages/tick, this adds overhead.

## Location
`src/main/kotlin/dev/ambon/transport/OutboundRouter.kt:156-177`

## Impact
- 100+ HashMap lookups per tick
- ConcurrentHashMap has higher overhead than HashMap
- Not a bottleneck alone, but combined with GMCP burst makes ticks spiky

## Solution
Could batch rendering into sink structures or cache local SessionId → Sink reference.

## Effort
2 hours (may require buffering refactor)

## Phase 2
This is Phase 2: Combat Efficiency (lower priority)

## References
See PERFORMANCE_ANALYSIS.md: Issue #9
```

---

### Issue 10: Transport - GMCP Frame Bypass Batch Flush
**Title:** `perf: GMCP frames bypass network session batch flushing`
**Labels:** `performance`, `medium-priority`, `transport`, `gmcp`
**Body:**
```
## Problem
GMCP frames return false from writeFrame(), preventing batch optimization. Results in 100+ separate synchronized() calls instead of 1 per batch.

## Location
`src/main/kotlin/dev/ambon/transport/NetworkSession.kt:278, 236-258`

## Impact
- 100x increase in synchronized() contention per tick
- GMCP frames don't get batched with text frames
- More efficient transport possible

## Solution
Make GMCP frames batchable:
- Buffer GMCP frames in batch queue
- Return true from writeFrame() for batching
- Flush entire batch in single synchronized call

## Effort
45 minutes

## Phase 3
This is Phase 3: Transport Optimization

## References
See PERFORMANCE_ANALYSIS.md: Issue #10
```

---

### Issue 11: WebSocket Transport - O(n) Line Parsing + Double Scan
**Title:** `perf: WebSocket input does two-pass character scanning`
**Labels:** `performance`, `medium-priority`, `transport`, `websocket`
**Body:**
```
## Problem
splitIncomingLines() and sanitizeIncomingLines() both scan every character. Could be combined into single pass.

## Location
`src/main/kotlin/dev/ambon/transport/KtorWebSocketTransport.kt:214-309`

## Impact
- 200,000+ character iterations/sec at moderate typing rate
- Unnecessary allocations from double scan
- Not tick-blocking but adds transport latency

## Solution
Combine into single pass:
- Scan once for line boundaries AND length/printable validation
- Allocate substring only if valid
- Reduce scans from 2 to 1

## Effort
30 minutes

## Phase 3
This is Phase 3: Transport Optimization

## References
See PERFORMANCE_ANALYSIS.md: Issue #11
```

---

### Issue 12: GMCP Envelope Parsing - String Search Chain
**Title:** `perf: GMCP envelope parsing does 5-6 sequential string searches`
**Labels:** `performance`, `medium-priority`, `transport`, `gmcp`
**Body:**
```
## Problem
tryParseGmcpEnvelope() does multiple indexOf() calls to find JSON structure. Each is O(n) string search.

## Location
`src/main/kotlin/dev/ambon/transport/KtorWebSocketTransport.kt:328-364`

## Impact
- 1250 character comparisons per GMCP message
- 125,000+ comparisons/sec during ability spam
- Not engine-blocking (runs on Dispatchers.IO) but still wasteful

## Solution
Could use regex or structured JSON parsing instead of manual string search.

## Effort
45 minutes

## Phase 3
This is Phase 3: Transport Optimization

## References
See PERFORMANCE_ANALYSIS.md: Issue #12
```

---

## MINOR Issues (LOW Priority)

### Issue 13: drainDirty() - Set-to-List Conversions
**Title:** `perf: drainDirty() allocates list for no reason`
**Labels:** `performance`, `low-priority`, `engine`, `optimization`
**Body:**
```
## Problem
drainDirty() converts Set to List then clears. Could iterate Set directly.

## Location
`src/main/kotlin/dev/ambon/engine/GameEngine.kt:1430, 1436, 1443, 1450, 1459`

## Impact
- 200 allocations/sec
- Quick win with minimal effort

## Solution
Use set.iterator() pattern or forEach instead of toList().

## Effort
10 minutes

## References
See PERFORMANCE_ANALYSIS.md: Issue #13
```

---

### Issue 14: AnsiRenderer - String Concatenation Per Frame
**Title:** `perf: AnsiRenderer uses string concatenation instead of StringBuilder`
**Labels:** `performance`, `low-priority`, `transport`, `optimization`
**Body:**
```
## Problem
renderLine() concatenates strings 3 times per frame. Should use StringBuilder or joinToString().

## Location
`src/main/kotlin/dev/ambon/transport/AnsiRenderer.kt:10-21`

## Impact
- 150-300 allocations/sec
- Likely optimized by Kotlin compiler, but explicit is better

## Effort
15 minutes

## References
See PERFORMANCE_ANALYSIS.md: Issue #14
```

---

### Issue 15: ThreatTable - Filter Allocation Per Mob Action
**Title:** `perf: ThreatTable.topThreatInRoom filters instead of using predicate`
**Labels:** `performance`, `low-priority`, `engine`, `combat`, `optimization`
**Body:**
```
## Problem
topThreatInRoom() allocates filtered map instead of using maxByOrNull with predicate.

## Location
`src/main/kotlin/dev/ambon/engine/ThreatTable.kt:37-44`

## Impact
- 1000+ allocations/sec during combat
- Easy to optimize

## Solution
Use maxByOrNull { ... } with inline predicate instead of filter().

## Effort
10 minutes

## References
See PERFORMANCE_ANALYSIS.md: Issue #15
```

---

## OBSERVABILITY Issues

### Issue 16: Add Queue Latency Metrics (Phase 0)
**Title:** `observability: Add queue age and latency metrics to GameEngine`
**Labels:** `observability`, `high-priority`, `metrics`, `phase-0`
**Body:**
```
## Problem
Current metrics can't diagnose "laggy but low CPU" scenarios. Missing queue delay visibility.

## Missing Metrics
- Inbound queue age (time from enqueue → processing, p50/p95/p99)
- Outbound queue age per session (time from enqueue → flush)
- Queue depth over time

## Why Important
- Root cause diagnosis for "server feels slow" when CPU is 20%
- Essential for detecting inbound backlog from auth stalls
- Prerequisite for effective alerting

## Implementation
Add to GameMetrics:
```kotlin
fun recordInboundLatency(ageMs: Long) { ... }
fun recordOutboundLatency(sessionId: SessionId, ageMs: Long) { ... }
```

## Phase 0
Critical for Phase 0 diagnostic capability

## References
See PERFORMANCE_ANALYSIS.md: Observability Gaps to Close
```

---

### Issue 17: Add Per-Phase Tick Attribution (Phase 0)
**Title:** `observability: Add per-phase tick breakdown metrics`
**Labels:** `observability`, `high-priority`, `metrics`, `phase-0`
**Body:**
```
## Problem
Knowing "tick took 120ms" doesn't tell you where time went. Need phase breakdown.

## Missing Metrics
- Time spent in inbound drain phase
- Time spent in simulation phase
- Time spent in GMCP flush phase
- Time spent in outbound flush phase

## Why Important
- Identify which phase is starving others
- Essential for debugging fairness issues
- Enables targeted optimization

## Implementation
Add to GameEngine.run():
```kotlin
val inboundTimer = Timer.start()
// ... drain inbound
metrics.recordTickPhase("inbound", inboundTimer.stop())

val simulationTimer = Timer.start()
// ... tick
metrics.recordTickPhase("simulation", simulationTimer.stop())
```

## Phase 0
Critical for Phase 0 diagnostic capability

## References
See PERFORMANCE_ANALYSIS.md: Observability Gaps to Close
```

---

### Issue 18: Add Phase Debt Counters (Phase 0)
**Title:** `observability: Add phase budget overrun detection and metrics`
**Labels:** `observability`, `high-priority`, `metrics`, `phase-0`
**Body:**
```
## Problem
Fairness issues (inbound starving simulation) are invisible without debt tracking. Need to detect phase budget overruns.

## Missing Metrics
- Counter: How often inbound drain exceeds budget
- Counter: How often simulation is compressed/skipped
- Gauge: Current tick debt (over-budget milliseconds)
- Queue depth when overruns happen

## Why Important
- Phase budget overruns are first symptom of fairness degradation
- Detection is prerequisite for diagnosis and alerting
- Enables proactive response to degradation

## Implementation
Add to GameEngine.run():
```kotlin
if (inboundMs > INBOUND_BUDGET) {
    metrics.incrementCounter("tick.phase.overrun.inbound")
    metrics.gauge("tick.inbound.debt", inboundMs - INBOUND_BUDGET)
}
```

## Phase 0
Critical for Phase 0 diagnostic capability

## References
See PERFORMANCE_ANALYSIS.md: Observability Gaps to Close
```

---

## Quick Reference: Priority Order

### Implement First (Phase 0 - Fairness/Observability)
- Issue #4: Inbound drain time budget
- Issue #5: Async login/auth decoupling
- Issue #6: Scheduler overload tracking
- Issue #16: Queue latency metrics
- Issue #17: Per-phase tick breakdown
- Issue #18: Phase debt counters

### Implement Second (Phase 1 - Critical Algorithmic Fixes)
- Issue #1: RegenSystem list copy
- Issue #2: flushDirtyGmcpMobs O(n²)
- Issue #3: StatusEffectSystem O(n²)
- Issue #8: GMCP jsonEscape() efficiency

### Implement Third (Phase 2 - Combat Efficiency)
- Issue #7: Equipment lookup caching

### Implement Fourth (Phase 3 - Transport Optimization)
- Issue #10: GMCP frame batching
- Issue #11: WebSocket double-scan
- Issue #12: GMCP envelope parsing

### Nice-to-Have (Phase 4 - Low Priority)
- Issue #13: drainDirty() conversion
- Issue #14: AnsiRenderer concatenation
- Issue #15: ThreatTable filter allocation

---

## Labels to Create (if needed)
- `phase-0` - Fairness/observability guardrails
- `phase-1` - Critical algorithmic fixes
- `phase-2` - Combat efficiency
- `phase-3` - Transport optimization
- `fairness` - Scheduling fairness and latency variance
- `observability` - Metrics and diagnostics
