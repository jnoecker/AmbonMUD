# Phase 1: State Extraction - Context

**Gathered:** 2026-04-05
**Status:** Ready for planning

<domain>
## Phase Boundary

Decompose App.tsx's 88 `useState` calls (1,655 lines) into a structured state management system using React Context + useReducer. Every downstream component can consume game state via context without prop drilling. Canvas continues reading state via mutable refs at 60fps. No new features, no layout changes -- pure refactoring.

</domain>

<decisions>
## Implementation Decisions

### State Grouping
- **D-01:** Domain-split contexts -- 5-8 focused contexts (e.g., CoreContext, CombatContext, InventoryContext, SocialContext, WorldContext, UIContext) rather than one monolithic context
- **D-02:** Each context uses `useReducer` with a typed discriminated action union (e.g., `{ type: 'SET_VITALS', vitals } | { type: 'SET_ROOM', room }`)
- **D-03:** A dedicated `GameStateBridgeSync` component subscribes to all contexts and writes to `gameStateRef` in one place -- single sync layer, not per-context

### State Library
- **D-04:** Pure React Context + `useReducer` -- no external state libraries (Zustand, Jotai, etc.). Zero new dependencies. React 19's improved context performance is sufficient with domain-split architecture.

### GMCP Dispatch Integration
- **D-05:** `applyGmcpPackage` receives a dispatch map (`{ core: coreDispatch, combat: combatDispatch, ... }`) and fires typed actions directly to the appropriate domain context
- **D-06:** Refactor `applyGmcpPackage` signature directly -- no adapter layer, no temporary setter compatibility. Clean break in one pass.

### Migration Strategy
- **D-07:** Big-bang refactor -- extract all 88 `useState` calls at once into the new context system in a single PR. No hybrid state, no temporary adapters.
- **D-08:** New context files live in `web-v3/src/state/` directory (e.g., `state/CoreContext.tsx`, `state/CombatContext.tsx`, `state/GameStateProvider.tsx` as composition root)

### Claude's Discretion
- Exact number and naming of domain contexts (user said "you decide" on granularity -- optimize for minimal re-renders and manageable file sizes)
- Internal reducer action naming conventions
- How to handle the `canvasCallbacks` bridge (keep as-is or fold into a context)
- Test strategy for verifying no regressions

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### State management targets
- `web-v3/src/App.tsx` -- The 1,655-line monolith being decomposed; contains all 88 useState calls
- `web-v3/src/canvas/GameStateBridge.ts` -- Mutable ref pattern for 60fps canvas reads; must be preserved (STATE-02)
- `web-v3/src/gmcp/applyGmcpPackage.ts` -- GMCP handler that currently receives 80+ setter functions; signature will be refactored to dispatch map

### Existing hooks (must continue working)
- `web-v3/src/hooks/useCommandHistory.ts`
- `web-v3/src/hooks/useMiniMap.ts`
- `web-v3/src/hooks/useAudioEngine.ts`
- `web-v3/src/hooks/useQuickbar.ts`

### Canvas consumers of GameStateBridge
- `web-v3/src/canvas/SceneManager.ts`
- `web-v3/src/canvas/scenes/WorldScene.ts`
- `web-v3/src/canvas/scenes/BattleScene.ts`
- `web-v3/src/canvas/systems/EntityPopout.ts`
- `web-v3/src/canvas/systems/DialogueOverlay.ts`
- `web-v3/src/canvas/systems/Minimap.ts`

### Requirements
- `.planning/REQUIREMENTS.md` -- STATE-01 (GameStateProvider context + reducer), STATE-02 (preserve GameStateBridge ref pattern)

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `GameStateBridge.ts` already defines `GameStateSnapshot` interface -- can serve as the contract for the sync component
- `canvasCallbacks` object in GameStateBridge -- action bridge from React to canvas (sendCommand, openShop, etc.)
- `useMudSocket` hook -- manages WebSocket connection; will need to receive dispatch map instead of individual setters
- Constants file with `EMPTY_CHAR`, `EMPTY_ROOM`, `EMPTY_VITALS` -- good initial state values for reducers

### Established Patterns
- Component imports from `./components/panels/` -- each panel is already a separate component, ready to consume contexts
- GMCP handler (`applyGmcpPackage`) is a pure function that receives state setters -- clean seam for refactoring to dispatch
- Custom hooks (`useCommandHistory`, `useQuickbar`) are already extracted -- pattern to follow

### Integration Points
- `App.tsx` renders all panels and passes state as props -- every panel import site will change to useContext consumption
- `useMudSocket` creates the WebSocket and wires `applyGmcpPackage` -- this is where the dispatch map gets constructed
- `canvasEvents` (CanvasEventBus) -- separate from state; canvas-to-React communication stays unchanged

</code_context>

<specifics>
## Specific Ideas

No specific requirements -- open to standard approaches

</specifics>

<deferred>
## Deferred Ideas

None -- discussion stayed within phase scope

</deferred>

---

*Phase: 01-state-extraction*
*Context gathered: 2026-04-05*
