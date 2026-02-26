# GameEngine Modularization Refactor Plan (Draft)

This plan proposes decomposing `GameEngine` into focused event-handler modules while preserving the current architecture contracts:

- Single-threaded engine loop
- Semantic event boundaries (`InboundEvent`/`OutboundEvent`)
- No blocking I/O in engine handlers
- Existing prompt/backpressure semantics via outbound routing

It is intentionally modeled after the CommandRouter refactor style (extract by concern, keep a thin orchestration layer, and preserve behavior with characterization tests).

---

## Clarifying Questions (to resolve before implementation)

1. **Scope boundary:** Should this refactor be strictly structural (no behavior change), or can we include low-risk behavior cleanups if tests are unchanged? It's acceptable to include low-risk behavior cleanups.
2. **Migration strategy:** Do you prefer a phased rollout with a compatibility adapter (old + new paths temporarily), or a single cutover PR once tests pass? Single cutover PR.
3. **Module granularity:** Do you want coarse modules (e.g., `SessionEvents`, `LoginEvents`, `GameplayEvents`) or finer-grained modules per subsystem (`MovementEvents`, `CombatEvents`, `DialogueEvents`, etc.)? Finer-grained modules per subsystem.
4. **Ownership of dependencies:** Should each handler receive only the exact dependencies it needs, or do you prefer a shared `EngineContext` object passed to all handlers? Unless you think otherwise, let's pass the full engine context to avoid changes later if we decide we need more dependencies in a given system, and to reduce duplication.
5. **Event registration pattern:** Preferred dispatch style:
   - *map/registry of `KClass<out InboundEvent>` to handler function* go with the registry approach here
6. **Unknown/unsupported events:** Should unknown events remain silently ignored, logged at debug, or emitted as metric counters? debug log + metrics
7. **Testing baseline:** Do you want strict no-diff characterization tests first (lock behavior), then refactor, or refactor + tests together in one PR? refactor+tests is fine.
8. **File organization:** Should event handlers live under `engine/events/` or split under existing subsystem folders (e.g., `engine/combat`, `engine/status`, `engine/social`)? subsystem folders
9. **Performance constraints:** Is there any concern about additional indirection/allocation in dispatch, or is maintainability the primary goal? maintainability is the primary goal
10. **Telemetry:** Should this refactor include per-event-type timing/counters in `GameMetrics`, or defer observability changes to a follow-up PR? include telemetry changes

---

## Proposed Target Design

### 1) Keep `GameEngine` as orchestration shell

`GameEngine` responsibilities after refactor:
- tick loop lifecycle
- inbound polling/draining
- calling event dispatcher
- periodic systems/scheduler hooks
- top-level error isolation/logging

`GameEngine` should no longer contain long event `when` branches.

### 2) Introduce event dispatch abstraction

Create:
- `EngineEventDispatcher` (interface)
- `DefaultEngineEventDispatcher` (implementation)

Dispatcher performs top-level routing from `InboundEvent` to typed handlers.

### 3) Extract concern-oriented handler modules

Initial module split (balanced granularity):
- `SessionEventHandler` (connect/disconnect/session-level state)
- `InputEventHandler` (line input entry + parser boundary)
- `LoginEventHandler` (login/create/account FSM interactions)
- `PlayerActionEventHandler` (movement/look/say/combat command-triggered actions that are currently direct engine events)
- `SystemEventHandler` (timers/admin/system-triggered inbound events)

Each handler:
- owns only its event subset
- exposes `suspend fun handle(event, ctx): Boolean` (`true` = handled)
- remains deterministic and non-blocking

### 4) Explicit `EngineHandlerContext`

Introduce a focused context object containing shared mutable registries/services currently used across event branches:
- registries/state accessors
- command router/parser access
- combat/mob/status/ability systems
- scheduler/clock/metrics hooks
- outbound emitter utilities

Prefer explicit typed fields over service-locator patterns.

### 5) Dispatcher strategy

Use central explicit dispatch first (simpler, debuggable):
- `when (event)` in dispatcher only
- each branch delegates to exactly one handler

If needed later, evolve to registry-based mapping.

### 6) Preserve behavior via characterization tests

Before moving logic, add/expand tests that assert:
- identical outputs for representative events
- prompt emission/coalescing invariants
- disconnect/close edge cases
- login + gameplay transition flows

Then refactor with tests green at each phase.

---

## Step-by-Step Implementation Plan

### Phase 0 — Baseline and safety net
1. Capture current event types handled in `GameEngine` (inventory list).
2. Add characterization tests for high-risk flows not already covered.
3. Add temporary trace logging (debug-only) around event dispatch to compare behavior if needed.

**Exit criteria:** full test suite green with stronger baseline.

### Phase 1 — Introduce abstractions with no behavior change
1. Add `EngineEventDispatcher` and `EngineHandlerContext`.
2. Move existing monolithic `when` body into `DefaultEngineEventDispatcher` mostly verbatim.
3. Update `GameEngine` to call dispatcher.

**Exit criteria:** no behavior changes, diff mostly mechanical, tests green.

### Phase 2 — Extract first handler (lowest risk)
1. Extract `SessionEventHandler` from dispatcher.
2. Keep event ownership explicit and small.
3. Verify with focused tests.

**Exit criteria:** dispatcher shrinks, behavior unchanged.

### Phase 3 — Extract login/input handlers
1. Extract `InputEventHandler` and `LoginEventHandler`.
2. Keep parser/command boundaries unchanged.
3. Verify prompt and error path parity.

**Exit criteria:** connection + login flows stable.

### Phase 4 — Extract gameplay/system handlers
1. Extract remaining event families by cohesive concern.
2. Remove dead utility methods from `GameEngine`.
3. Normalize helper methods (outbound send, room broadcasts, etc.) into shared utilities if needed.

**Exit criteria:** `GameEngine` is orchestration-only.

### Phase 5 — Cleanup and hardening
1. Remove temporary tracing and migration scaffolding.
2. Add short architecture note in docs/onboarding.
3. Ensure naming/package consistency with existing engine conventions.

**Exit criteria:** maintainable module structure with documented boundaries.

---

## Risk & Mitigation

- **Risk: subtle behavior drift** during extraction.
  - **Mitigation:** characterization tests + small phased PRs.
- **Risk: duplicated helper logic** across handlers.
  - **Mitigation:** shared utility methods in context or dedicated helper class.
- **Risk: hidden coupling** via broad context.
  - **Mitigation:** constructor-level dependency narrowing per handler.
- **Risk: debugging complexity** from indirection.
  - **Mitigation:** explicit dispatcher branches and structured logs.

---

## Suggested PR Breakdown (similar spirit to CommandRouter refactor)

1. **PR A:** tests + dispatcher/context scaffolding (no logic moves)
2. **PR B:** session/input/login extraction
3. **PR C:** gameplay/system extraction + cleanup
4. **PR D (optional):** dispatcher polish, metrics, and docs

This sequence keeps each PR reviewable and behavior-safe.
