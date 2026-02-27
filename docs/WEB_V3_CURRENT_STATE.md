# Web Client v3: Current State Findings

Date: 2026-02-27
Scope: discovery pass of server routing, WS/GMCP protocol bridge, v3 frontend architecture, and existing test coverage.

## What Was Reviewed

- `web-v3/` standalone React + Vite client.
- `src/main/kotlin/dev/ambon/transport/KtorWebSocketTransport.kt`.
- GMCP emit/consume path in engine:
  - `src/main/kotlin/dev/ambon/engine/GmcpEmitter.kt`
  - `src/main/kotlin/dev/ambon/engine/events/GmcpEventHandler.kt`
  - `src/main/kotlin/dev/ambon/engine/GameEngine.kt`
- Transport tests in `src/test/kotlin/dev/ambon/transport/KtorWebSocketTransportTest.kt`.

## High-Level Wiring

1. The server hosts two web clients in parallel:
- Legacy client under `/` from classpath package `web`.
- v3 client under `/v3` from classpath package `web-v3`.

2. The WS endpoint for gameplay is `/ws` and is shared by clients.

3. v3 build pipeline writes static output directly into JVM resources:
- Source project: `web-v3/`
- Build output target: `src/main/resources/web-v3`
- Route served by Ktor: `/v3`

## Build and Packaging Contract

- `web-v3/package.json` scripts:
  - `dev`: `vite`
  - `build`: `tsc -b && vite build`
  - `lint`: `eslint .`
- `web-v3/vite.config.ts`:
  - `base: "/v3/"`
  - `publicDir: false`
  - `outDir: ../src/main/resources/web-v3`

Result: frontend assets are versioned into server resources and served directly by Ktor without extra Gradle copy tasks.

## Runtime Data Flow (WS + GMCP)

1. Browser connects to `ws(s)://<host>/ws`.
2. Ktor bridge registers session with `OutboundRouter` and sends `InboundEvent.Connected`.
3. WS transport auto-sends `InboundEvent.GmcpReceived(Core.Supports.Set, [...])` with supported package list for WS sessions.
4. Engine `GmcpEventHandler` stores package support and emits initial snapshots (vitals, room, items, players, mobs, skills, effects, achievements, group when applicable).
5. Outbound GMCP is serialized to browser as JSON envelope:
- `{"gmcp":"<Package>","data":<json>}`
6. v3 frontend parses envelope and routes by package in `handleGmcp`.

## v3 Frontend Architecture Snapshot

- Main UI and state currently live in a single component file:
  - `web-v3/src/App.tsx` (~1434 lines)
- Key behaviors implemented:
  - xterm-based terminal + command entry.
  - command history in localStorage (`ambonmud_v3_history`).
  - tab completion against static command dictionary.
  - world panel (room info, exits, players, mobs).
  - character panel (identity, vitals, effects, inventory/equipment popouts).
  - local visited-room mini-map graph (client-side inferred layout).
  - reconnect flow and pre-login placeholders.

## GMCP Packages Currently Consumed by v3

Handled in `App.tsx` `handleGmcp` switch:

- `Char.Vitals`
- `Char.Name`
- `Room.Info`
- `Char.Items.List`
- `Char.Items.Add`
- `Char.Items.Remove`
- `Room.Players`
- `Room.AddPlayer`
- `Room.RemovePlayer`
- `Room.Mobs`
- `Room.AddMob`
- `Room.UpdateMob`
- `Room.RemoveMob`
- `Char.StatusEffects`

Unknown packages are ignored by default.

## Server GMCP Capability Snapshot

`GmcpEmitter` can send additional packages not yet rendered by v3 UI:

- `Char.Skills`
- `Comm.Channel`
- `Group.Info`
- `Core.Ping`
- `Char.Achievements`
- plus status vars (`Char.StatusVars`) and current room/player/mob/item/vitals streams.

Note: package matching is prefix-aware in `GameEngine` (`Char.Items` enables `Char.Items.*`).

## Existing Test Coverage Relevant to v3 Wiring

`KtorWebSocketTransportTest` currently covers:

- `/ws` inbound/outbound bridging behavior.
- WS auto `Core.Supports.Set` emission.
- `/v3/` index serving.
- GMCP envelope parser behavior (`tryParseGmcpEnvelope`) across valid/invalid shapes.

## CI Status for Frontend

Current CI (`.github/workflows/ci.yml`) runs only:

- `./gradlew ktlintCheck test`

No v3 frontend lint/build job is wired into CI yet.

## Practical Development Entry Points

- Transport + protocol boundary: `KtorWebSocketTransport.kt`
- Engine GMCP publish points: `GmcpEmitter.kt`, `GmcpEventHandler.kt`
- Frontend data reducer and UI state: `web-v3/src/App.tsx`
- Frontend styling/theming: `web-v3/src/styles.css`
- Routing/asset serving verification: `KtorWebSocketTransportTest.kt`

## Suggested First Refactor Target (before larger feature work)

Split `web-v3/src/App.tsx` into smaller modules:

- ws/connection hook
- GMCP reducer or package handlers
- terminal input/history utility
- map state/renderer utility
- panel components

This reduces risk and makes upcoming feature additions faster and safer.
