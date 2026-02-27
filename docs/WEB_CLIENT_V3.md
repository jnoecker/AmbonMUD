# Web Client v3

*Consolidates: WEB_V3_CURRENT_STATE.md, WEB_V3_FRONTEND_STRUCTURE.md, WEB_V3_GAPS_NEXT_STEPS.md*

Date: 2026-02-27

The v3 web client is a modular React + Vite + TypeScript single-page application served directly by the Ktor backend. It communicates with the server over WebSocket using a GMCP-over-JSON protocol alongside plain MUD text.

---

## High-Level Wiring

The server serves v3 static assets from classpath package `web-v3` at the root path (`/`). Compatibility routes `/v3` and `/v3/` redirect to `/`.

The WebSocket gameplay endpoint is `/ws`.

**Build/packaging contract:**
- Source project: `web-v3/`
- Build output: `src/main/resources/web-v3/` (written by `bun run build`)
- Served by: Ktor static resources at `/`

**Runtime data flow:**
1. Browser connects to `ws(s)://<host>/ws`.
2. Ktor bridge registers the session and emits `InboundEvent.Connected`.
3. WS transport auto-sends `InboundEvent.GmcpReceived(Core.Supports.Set, [...])` for the supported package list.
4. Engine `GmcpEventHandler` stores package support and emits initial snapshots (vitals, room, items, players, mobs, skills, effects, achievements, group).
5. Outbound GMCP is serialized to the browser as a JSON envelope: `{"gmcp":"<Package>","data":<json>}`
6. The v3 frontend parses the envelope and routes by package in `handleGmcp`.

**Key source files for transport/protocol work:**
- Transport boundary: `src/main/kotlin/dev/ambon/transport/KtorWebSocketTransport.kt`
- Engine GMCP publish: `src/main/kotlin/dev/ambon/engine/GmcpEmitter.kt`, `GmcpEventHandler.kt`
- Routing/asset verification: `src/test/kotlin/dev/ambon/transport/KtorWebSocketTransportTest.kt`

---

## Frontend Architecture

The v3 client is modularized around a composition root, hooks, a GMCP handler map, and panel components.

### Composition Root

**`web-v3/src/App.tsx`** (~456 lines) owns top-level app state (vitals, room, players, mobs, effects, skills, inventory/equipment, active tab/popout), wires hooks and panel components together, and handles xterm init/fitting and lifecycle effects.

### Hooks

| Hook | File | Responsibility |
|------|------|----------------|
| `useMudSocket` | `hooks/useMudSocket.ts` | WebSocket connect/disconnect/reconnect/send, inbound message parsing, connection lifecycle |
| `useCommandHistory` | `hooks/useCommandHistory.ts` | Command history persistence, traversal, tab completion state |
| `useMiniMap` | `hooks/useMiniMap.ts` | Visited-room graph, map updates, canvas rendering; exposes `mapCanvasRef`, `drawMap`, `updateMap`, `resetMap` |

### GMCP Handler Map

**`web-v3/src/gmcp/applyGmcpPackage.ts`** — single package-handler map; converts package payloads into React state updates via setter callbacks.

**Packages currently handled:**

| Package | Notes |
|---------|-------|
| `Char.Vitals` | Includes `inCombat` flag |
| `Char.Name` | |
| `Room.Info` | |
| `Char.Items.List` | |
| `Char.Items.Add` | |
| `Char.Items.Remove` | |
| `Room.Players` | |
| `Room.AddPlayer` | |
| `Room.RemovePlayer` | |
| `Room.Mobs` | |
| `Room.AddMob` | |
| `Room.UpdateMob` | |
| `Room.RemoveMob` | |
| `Char.StatusEffects` | |
| `Char.Skills` | Supports refresh, cast with optimistic local cooldown |
| `Comm.Channel` | Social panel; supports `Who` refresh |

Unknown packages are ignored. Package matching in `GameEngine` is prefix-aware (`Char.Items` enables `Char.Items.*`).

**Server packages not yet rendered by v3 UI:** `Group.Info`, `Char.Achievements`, `Core.Ping`, `Char.StatusVars`.

### `Char.Skills` Payload Shape

```json
{
  "id": "...",
  "name": "...",
  "description": "...",
  "manaCost": 20,
  "cooldownMs": 5000,
  "cooldownRemainingMs": 0,
  "levelRequired": 10,
  "targetType": "ENEMY",
  "classRestriction": "MAGE"
}
```

### Panel Components

| Component | File |
|-----------|------|
| Play panel | `components/panels/PlayPanel.tsx` |
| World panel | `components/panels/WorldPanel.tsx` |
| Chat panel | `components/panels/ChatPanel.tsx` |
| Character panel | `components/panels/CharacterPanel.tsx` |
| Popout layer | `components/PopoutLayer.tsx` |
| Mobile tab bar | `components/MobileTabBar.tsx` |
| Shared icons | `components/Icons.tsx` |
| Direction helper | `components/isDirection.ts` |

**Notable behavior:**
- Social panel consumes `Comm.Channel` and supports `Who` refresh.
- `WorldPanel` swaps entity lists for a combat-only skills subpanel when `Char.Vitals.inCombat` is `true`.
- Skills panel: refresh via `skills` command; cast with optimistic local cooldown countdown; icon affordances mapped from `classRestriction` / `targetType`.

### Feature Placement Guide

- **New GMCP package:** add mapping in `gmcp/applyGmcpPackage.ts`; extend app state in `App.tsx` if needed; render in an existing or new panel under `components/panels/`.
- **New socket-level behavior:** start in `hooks/useMudSocket.ts` (do not scatter WebSocket code in panels).
- **New input/history/completion behavior:** add in `hooks/useCommandHistory.ts`.
- **Mini-map behavior changes:** add in `hooks/useMiniMap.ts`.

### Validation Commands

Run from `web-v3/`:
```bash
bun run lint
bun run build
```

---

## CI Status

Current CI (`.github/workflows/ci.yml`) runs backend verification with `./gradlew ktlintCheck test integrationTest` and a separate frontend job for `bun install`, `bun run lint`, and `bun run build`.

---

## Known Gaps & Next Steps

### Known Gaps

1. **Unused GMCP surface** — Server emits `Group.Info`, `Char.Achievements`, `Core.Ping`, `Char.StatusVars`; none are rendered in v3 yet.
2. **No frontend CI** — Frontend breakage can merge undetected.
3. **Local dev ergonomics** — v3 dev server setup does not document a WS proxy to backend `/ws`; local iteration may require manual setup.
4. **Limited frontend test coverage** — UI and GMCP reducer regressions rely on manual testing; no dedicated automated tests for v3 React behavior.
5. **Heuristic client map** — Map graph is inferred from observed exits/room IDs; no persistence or reconciliation across reconnects.
6. **Large frontend bundle** — Main JS chunk exceeds 500 kB; initial load can degrade on slower clients.

### Recommended Next Steps (Prioritized)

1. **`Group.Info` party panel** — Best first GMCP feature: compact party widget with reducer support and manual test checklist.
2. **Frontend tests for GMCP handling** — Unit tests for parser + package mapping (`applyGmcpPackage`); smoke tests for key render states.
3. **Wire frontend into CI** — Add a CI step in `web-v3/`: install deps → lint → typecheck/build.
4. **Local dev workflow docs** — Document exact commands and topology for running backend + v3 dev server together; document how `/ws` is reached during dev.
5. **Expand GMCP package support** — `Char.Achievements`, then `Core.Ping` telemetry.
6. **Bundle size reduction** — Route/panel-level lazy loading; manual chunking for terminal-heavy dependencies.

### Definition of Done for New v3 Features

- GMCP package contract documented in code comments.
- UI behavior covered by at least one automated test.
- Manual acceptance steps written in PR notes.
- No regression in `/v3` serving or `/ws` protocol behavior.
