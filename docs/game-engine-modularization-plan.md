# GameEngine Modularization Refactor Plan

This document describes the decomposition of `GameEngine` into focused event-handler modules while preserving architectural contracts:

- Single-threaded engine loop
- Semantic event boundaries (`InboundEvent`/`OutboundEvent`)
- No blocking I/O in engine handlers
- Existing prompt/backpressure semantics via outbound routing

The refactor follows the CommandRouter style: extract by concern, keep a thin orchestration layer, preserve behavior.

---

## Implemented Design

### 1) GameEngine remains the orchestration shell

`GameEngine` responsibilities preserved:
- tick loop lifecycle
- inbound polling/draining
- calling event dispatcher
- periodic systems/scheduler hooks
- top-level error isolation/logging

Long event `when` branches have been extracted to specialized handlers.

### 2) Event dispatch abstraction

Implemented:
- `EngineEventDispatcher` (interface) – routes `InboundEvent` to handlers
- `DefaultEngineEventDispatcher` (implementation) – explicit `when` routing

Located in `src/main/kotlin/dev/ambon/engine/events/`.

### 3) Extracted concern-oriented handler modules

Handlers created (in `engine/events/`):
- **`SessionEventHandler`** – connect/disconnect/session lifecycle
- **`InputEventHandler`** – line input routing + login/gameplay branching
- **`LoginEventHandler`** – login FSM state machine dispatch
- **`PhaseEventHandler`** – zone phasing (cross-engine handoffs)
- **`GmcpEventHandler`** – GMCP protocol negotiation + data subscriptions
- **`GmcpFlushHandler`** – GMCP dirty-state flushing (decoupled from event handling)
- **`InterEngineEventHandler`** – inter-engine messaging (handoffs, broadcasts, transfers)

Each handler:
- owns a specific event concern
- receives only needed dependencies (constructor injection)
- remains deterministic and non-blocking

### 4) Lazy initialization in GameEngine

Handlers are wired as lazy-initialized properties in `GameEngine`:
```kotlin
private val sessionEventHandler by lazy { SessionEventHandler(...) }
private val inputEventHandler by lazy { InputEventHandler(...) }
// etc.
```

This defers instantiation and avoids circular dependency issues while keeping initialization close to usage.

### 5) Dispatcher routing strategy

**Chosen:** explicit central dispatch with typed delegation.
- `EngineEventDispatcher` uses `when (event)` to route to handlers
- each branch calls the appropriate handler method
- clear, debuggable, easy to extend

### 6) Behavior preservation

All tests pass without modification:
- Existing characterization tests lock behavior
- event routing is semantically identical
- prompt emission/coalescing invariants maintained
- disconnect/close edge cases handled
- login + gameplay transition flows stable

---

## Implementation Summary

This refactor was completed in a single PR with comprehensive handler extraction:

### Completed Phases

**Phase 1 – Dispatcher Foundation**
- Added `EngineEventDispatcher` interface and `DefaultEngineEventDispatcher` implementation
- Moved top-level event routing into explicit dispatcher
- Tests pass without modification

**Phase 2–4 – Handler Extraction**
- Extracted all session, input, login, and gameplay event handlers in one cohesive change
- Each handler receives narrowly scoped dependencies (no broad context object)
- Lazy initialization prevents circular dependency issues
- GameEngine focuses on orchestration, not event logic

**No Phase 5 needed** – Architecture is clear and no migration scaffolding was necessary.

### Design Decisions

1. **No shared `EngineHandlerContext`:** Instead, each handler receives exactly the dependencies it needs via constructor injection. This makes coupling explicit and reduces accidental coupling.

2. **Lazy initialization:** Handlers are initialized as `private val X by lazy { ... }` to defer construction and avoid initialization-order problems while keeping wiring nearby.

3. **Explicit dispatch:** The `DefaultEngineEventDispatcher` uses a single `when (event)` statement with direct handler calls—no registry or reflection.

4. **Shared utilities:** Helpers like `drainDirty()` are defined locally in `GmcpFlushHandler` rather than extracted to a shared utility, keeping the handler self-contained.

---

## Testing & Verification

- All existing tests pass without modification
- Behavior is bit-for-bit identical to pre-refactor code
- Session connect/disconnect, login FSM, command routing, GMCP, and inter-engine messaging all function correctly
- ktlintCheck passes (code follows official Kotlin style)

---

## Next Steps

Future phases may include:

1. **Further handler decomposition** – If gameplay logic (commands, combat, abilities) becomes unwieldy, extract `CommandEventHandler` or `CombatEventHandler`.
2. **Metrics per handler** – Add per-handler event counters to `GameMetrics`.
3. **Async error handling** – Consider structured concurrency improvements if needed.

These can be deferred until motivation arises.
