# Web Client v3 Frontend Structure

Date: 2026-02-27
Status: Baseline structure established in PR #253.

## Goal

This document captures the post-refactor module layout for `web-v3` so new features can be added without growing `App.tsx` back into a monolith.

## Current Module Map

### Composition Root

- `web-v3/src/App.tsx`
  - Owns top-level app state (vitals, room, players, mobs, effects, inventory/equipment, active tab/popout).
  - Wires hooks and panel components together.
  - Handles xterm init/fitting and high-level lifecycle effects.

### Hooks

- `web-v3/src/hooks/useMudSocket.ts`
  - Encapsulates WebSocket connect/disconnect/reconnect/send and inbound message parsing.
  - Exposes callbacks for text vs GMCP events and connection lifecycle hooks.

- `web-v3/src/hooks/useCommandHistory.ts`
  - Encapsulates command history persistence and traversal.
  - Handles tab completion state for terminal and composer input flows.

- `web-v3/src/hooks/useMiniMap.ts`
  - Encapsulates visited-room graph, map updates, and canvas rendering.
  - Exposes `mapCanvasRef`, `drawMap`, `updateMap`, `resetMap`.

### GMCP Handling

- `web-v3/src/gmcp/applyGmcpPackage.ts`
  - Single package-handler map for supported GMCP payloads.
  - Converts package payloads into React state updates via setter callbacks.

### Components

- `web-v3/src/components/panels/PlayPanel.tsx`
- `web-v3/src/components/panels/WorldPanel.tsx`
- `web-v3/src/components/panels/CharacterPanel.tsx`
- `web-v3/src/components/PopoutLayer.tsx`
- `web-v3/src/components/MobileTabBar.tsx`
- `web-v3/src/components/Icons.tsx`
- `web-v3/src/components/isDirection.ts`

## Feature Placement Guidance

- New GMCP package support:
  - Add package mapping in `gmcp/applyGmcpPackage.ts`.
  - Add/extend app state in `App.tsx` if needed.
  - Render in an existing panel or a new panel component under `components/panels/`.

- New socket-level behavior:
  - Start in `hooks/useMudSocket.ts`.
  - Keep transport semantics centralized there (do not scatter ws code in panels).

- New input behavior/history/completion:
  - Add in `hooks/useCommandHistory.ts`.

- Mini-map behavior changes:
  - Add in `hooks/useMiniMap.ts`.

## Validation Commands

Run from `web-v3/`:

- `bun run lint`
- `bun run build`
