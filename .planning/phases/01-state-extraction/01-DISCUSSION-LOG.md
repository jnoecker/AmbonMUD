# Phase 1: State Extraction - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md -- this log preserves the alternatives considered.

**Date:** 2026-04-05
**Phase:** 01-State Extraction
**Areas discussed:** State grouping, State library, GMCP dispatch, Migration scope

---

## State Grouping

### Organization Strategy

| Option | Description | Selected |
|--------|-------------|----------|
| Domain-split contexts | 5-8 focused contexts (CharContext, RoomContext, CombatContext, etc.). Each manages its own slice. Components subscribe only to what they need -- fewer re-renders. | ✓ |
| Single monolithic context | One GameStateProvider with one big state object + reducer. Simpler wiring but every state change re-renders all subscribers. | |
| Hybrid (domain + UI) | Domain-split for game state plus separate UIContext for transient UI state. | |

**User's choice:** Domain-split contexts
**Notes:** None

### Granularity

| Option | Description | Selected |
|--------|-------------|----------|
| ~6 broad contexts | Core, Combat, Inventory, Social, World, UI | |
| ~10 fine-grained contexts | Separate context per major system | |
| You decide | Claude picks during implementation | ✓ |

**User's choice:** You decide
**Notes:** Optimize for minimal re-renders and manageable file sizes

### Dispatch Pattern

| Option | Description | Selected |
|--------|-------------|----------|
| useReducer + typed actions | Each context has a reducer with discriminated action union. Predictable, testable, works well with GMCP dispatch. | ✓ |
| useState with grouped setters | Each context wraps related useState calls and exposes setter functions. Less boilerplate. | |
| You decide | Claude picks per context based on complexity. | |

**User's choice:** useReducer + typed actions
**Notes:** None

### Bridge Sync

| Option | Description | Selected |
|--------|-------------|----------|
| Single sync component | Dedicated GameStateBridgeSync subscribes to all contexts and writes to gameStateRef in one place. | ✓ |
| Per-context sync | Each context provider updates its slice of gameStateRef in its own useEffect. | |
| You decide | Claude picks based on 60fps reliability. | |

**User's choice:** Single sync component
**Notes:** None

---

## State Library

| Option | Description | Selected |
|--------|-------------|----------|
| Pure React Context + useReducer | No new dependencies. Domain-split avoids re-render problem. React 19 improved context performance. | ✓ |
| Zustand | Lightweight (~1KB), selector-based subscriptions. Adds dependency and different mental model. | |
| Jotai | Atomic state model. 88-state-to-atoms migration is more work. | |

**User's choice:** Pure React Context + useReducer
**Notes:** None

---

## GMCP Dispatch

### Dispatch Strategy

| Option | Description | Selected |
|--------|-------------|----------|
| Dispatch map | applyGmcpPackage receives { core: coreDispatch, combat: combatDispatch, ... } and fires typed actions. | ✓ |
| Central event bus | GMCP emits domain events to event bus. Each context subscribes. More decoupled but adds indirection. | |
| Single unified dispatch | One top-level dispatch routes GMCP updates internally. | |

**User's choice:** Dispatch map
**Notes:** None

### API Migration

| Option | Description | Selected |
|--------|-------------|----------|
| Refactor signature directly | Change applyGmcpPackage to accept dispatch map instead of 80+ setters. Clean break. | ✓ |
| Adapter layer | Keep current setter-based signature with adapter translating to dispatches. | |
| You decide | Claude picks based on migration approach. | |

**User's choice:** Refactor signature directly
**Notes:** None

---

## Migration Scope

### Strategy

| Option | Description | Selected |
|--------|-------------|----------|
| Big-bang refactor | Extract all 88 useState at once in a single PR. No hybrid state. | ✓ |
| Incremental by domain | Move one domain at a time. 5-8 PRs with mixed state. | |
| You decide | Claude picks based on risk. | |

**User's choice:** Big-bang refactor
**Notes:** None

### File Layout

| Option | Description | Selected |
|--------|-------------|----------|
| web-v3/src/state/ | New top-level directory for context files. Clean separation from components and hooks. | ✓ |
| web-v3/src/contexts/ | More conventional React naming. | |
| You decide | Claude picks during implementation. | |

**User's choice:** web-v3/src/state/
**Notes:** None

---

## Claude's Discretion

- Exact number and naming of domain contexts
- Internal reducer action naming conventions
- canvasCallbacks bridge handling
- Test strategy for verifying no regressions

## Deferred Ideas

None -- discussion stayed within phase scope
