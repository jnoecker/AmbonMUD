# Web Client v3: Current State Findings

Date: 2026-02-27
Status: Updated to reflect the current v3 client implementation (modular panel architecture, social + skills support, and combat skills panel).
Scope: server routing, WS/GMCP protocol bridge, current v3 frontend architecture, and verification status.

## What Was Reviewed

- `web-v3/` standalone React + Vite client.
- `src/main/kotlin/dev/ambon/transport/KtorWebSocketTransport.kt`.
- GMCP emit/consume path in engine:
  - `src/main/kotlin/dev/ambon/engine/GmcpEmitter.kt`
  - `src/main/kotlin/dev/ambon/engine/events/GmcpEventHandler.kt`
  - `src/main/kotlin/dev/ambon/engine/GameEngine.kt`
- Transport tests in `src/test/kotlin/dev/ambon/transport/KtorWebSocketTransportTest.kt`.

## High-Level Wiring

1. The server serves v3 static assets from classpath package `web-v3` at root (`/`).

2. Compatibility routes `/v3` and `/v3/` redirect to `/`.

3. The WS endpoint for gameplay is `/ws`.

4. v3 build pipeline writes static output directly into JVM resources:
- Source project: `web-v3/`
- Build output target: `src/main/resources/web-v3`
- Route served by Ktor static resources: `/` (with `/v3` redirecting to `/`)

## Build and Packaging Contract

- `web-v3/package.json` scripts:
  - `dev`: `vite`
  - `build`: `tsc -b && vite build`
  - `lint`: `eslint .`
- `web-v3/vite.config.ts`:
  - `base: "/"`
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

The v3 client is now modularized.

- `web-v3/src/App.tsx` is a composition root (~456 lines after refactor).
- Connection lifecycle is extracted to:
  - `web-v3/src/hooks/useMudSocket.ts`
- Command history and tab completion are extracted to:
  - `web-v3/src/hooks/useCommandHistory.ts`
- Mini-map graph/render logic is extracted to:
  - `web-v3/src/hooks/useMiniMap.ts`
- GMCP package handling is extracted to:
  - `web-v3/src/gmcp/applyGmcpPackage.ts`
- UI is split into components:
  - `web-v3/src/components/panels/PlayPanel.tsx`
  - `web-v3/src/components/panels/WorldPanel.tsx`
  - `web-v3/src/components/panels/ChatPanel.tsx`
  - `web-v3/src/components/panels/CharacterPanel.tsx`
  - `web-v3/src/components/PopoutLayer.tsx`
  - `web-v3/src/components/MobileTabBar.tsx`
  - shared visual helpers in `web-v3/src/components/Icons.tsx`

Notable current behavior:

- Social panel consumes `Comm.Channel` and supports `Who` refresh.
- Combat-only skills panel is rendered in `WorldPanel` when `Char.Vitals.inCombat` is `true`.
- Skills panel reads `Char.Skills` and supports:
  - refresh via `skills` command
  - cast action with optimistic local cooldown countdowns
  - icon-based cast affordances mapped from `classRestriction` or `targetType`

## GMCP Packages Currently Consumed by v3

Handled in `web-v3/src/gmcp/applyGmcpPackage.ts`:

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
- `Char.Skills`
- `Comm.Channel`

Unknown packages are ignored by default.

## Server GMCP Capability Snapshot

`GmcpEmitter` can still send packages not yet rendered by v3 UI:

- `Group.Info`
- `Core.Ping`
- `Char.Achievements`
- plus status vars (`Char.StatusVars`).

Current skills GMCP payload shape (`Char.Skills`) includes:

- `id`
- `name`
- `description`
- `manaCost`
- `cooldownMs`
- `cooldownRemainingMs`
- `levelRequired`
- `targetType`
- `classRestriction`

Current vitals GMCP payload (`Char.Vitals`) includes combat flag:

- `inCombat`

Note: package matching is prefix-aware in `GameEngine` (`Char.Items` enables `Char.Items.*`).

## Existing Test Coverage Relevant to v3 Wiring

`KtorWebSocketTransportTest` currently covers:

- `/ws` inbound/outbound bridging behavior.
- WS auto `Core.Supports.Set` emission.
- `/v3` and `/v3/` redirect behavior.
- GMCP envelope parser behavior (`tryParseGmcpEnvelope`) across valid/invalid shapes.

PR #253 additionally validated frontend build integrity with:

- `cd web-v3 && bun run lint`
- `cd web-v3 && bun run build`

## CI Status for Frontend

Current CI (`.github/workflows/ci.yml`) runs only:

- `./gradlew ktlintCheck test`

No v3 frontend lint/build job is wired into CI yet.

## Practical Development Entry Points

- Transport + protocol boundary: `KtorWebSocketTransport.kt`
- Engine GMCP publish points: `GmcpEmitter.kt`, `GmcpEventHandler.kt`
- Frontend composition root: `web-v3/src/App.tsx`
- Frontend GMCP package mapping: `web-v3/src/gmcp/applyGmcpPackage.ts`
- Frontend hooks: `web-v3/src/hooks/`
- Frontend panels/components: `web-v3/src/components/`
- Frontend styling/theming: `web-v3/src/styles.css`
- Routing/asset serving verification: `KtorWebSocketTransportTest.kt`
