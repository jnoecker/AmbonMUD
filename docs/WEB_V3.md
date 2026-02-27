# Web Client v3

This document consolidates the previous v3 state, structure, and gap documents.

Replaces:
- `WEB_V3_CURRENT_STATE.md`
- `WEB_V3_FRONTEND_STRUCTURE.md`

Roadmap items for v3 are tracked in [ROADMAP.md](./ROADMAP.md).

## Scope

- Runtime integration between Ktor WebSocket transport and GMCP engine events
- Frontend module structure in `web-v3/`
- Current package coverage and known delivery gaps

## Runtime wiring

Server integration points:
- `src/main/kotlin/dev/ambon/transport/KtorWebSocketTransport.kt`
- `src/main/kotlin/dev/ambon/engine/GmcpEmitter.kt`
- `src/main/kotlin/dev/ambon/engine/events/GmcpEventHandler.kt`
- `src/main/kotlin/dev/ambon/engine/GameEngine.kt`

Routing behavior:
- Static assets served from classpath package `web-v3` at `/`
- Compatibility redirects: `/v3`, `/v3/` -> `/`
- Gameplay WebSocket endpoint: `/ws`

## Build and packaging contract

Frontend project: `web-v3/`

Commands:

```bash
bun install
bun run dev
bun run lint
bun run build
```

Build output contract:
- `vite` writes production assets directly into `src/main/resources/web-v3`
- Ktor serves those assets directly from classpath at runtime

## Frontend module structure

Composition root:
- `web-v3/src/App.tsx`

Hooks:
- `web-v3/src/hooks/useMudSocket.ts`
- `web-v3/src/hooks/useCommandHistory.ts`
- `web-v3/src/hooks/useMiniMap.ts`

GMCP mapping layer:
- `web-v3/src/gmcp/applyGmcpPackage.ts`

Panels/components:
- `web-v3/src/components/panels/PlayPanel.tsx`
- `web-v3/src/components/panels/WorldPanel.tsx`
- `web-v3/src/components/panels/ChatPanel.tsx`
- `web-v3/src/components/panels/CharacterPanel.tsx`
- `web-v3/src/components/PopoutLayer.tsx`
- `web-v3/src/components/MobileTabBar.tsx`
- `web-v3/src/components/Icons.tsx`

## GMCP flow

1. Browser connects to `ws(s)://<host>/ws`.
2. Transport emits `InboundEvent.Connected`.
3. WS transport sends `Core.Supports.Set` support declaration.
4. Engine records capabilities and emits initial snapshots.
5. Outbound GMCP is serialized as `{"gmcp":"<package>","data":...}`.
6. Frontend routes package payloads through `applyGmcpPackage`.

## GMCP coverage snapshot

Currently consumed in frontend handler:
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

Known server packages not yet rendered in v3 UI:
- `Group.Info`
- `Char.Achievements`
- `Char.StatusVars`
- `Core.Ping`

## Current behavior notes

- Social panel consumes `Comm.Channel` and supports `who` refresh.
- World panel swaps to a combat-focused skills panel when `Char.Vitals.inCombat` is true.
- Skills panel uses `Char.Skills` with refresh and cast actions.

## Validation and tests

Backend-side coverage exists in:
- `src/test/kotlin/dev/ambon/transport/KtorWebSocketTransportTest.kt`

Covered areas include:
- `/ws` bridging
- GMCP envelope parsing
- `/v3` redirect behavior
- WS auto-support message path

Frontend CI coverage status:
- Root CI currently runs Gradle checks only.
- Frontend lint/build checks are not yet wired in `.github/workflows/ci.yml`.

## Development guidance

When adding v3 features:

1. Add GMCP package mapping in `applyGmcpPackage.ts`.
2. Extend app state in `App.tsx` only as needed.
3. Render through existing panel modules or add panel components.
4. Keep socket semantics centralized in `useMudSocket.ts`.
5. Add automated test coverage for new package handling paths.
