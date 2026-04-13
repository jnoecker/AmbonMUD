# Web Client

*Consolidated from earlier separate web client documents.*

The web client is a modular React + Vite + TypeScript single-page application served by the Ktor backend. It combines a PixiJS 2D canvas (primary game view) with React side panels, connected to the server over WebSocket using a GMCP-over-JSON protocol alongside plain MUD text.

---

## Visual Progression

Five generations of the client, from the earliest telnet proof-of-concept to the current canvas UI.

### v0 — Telnet (PuTTY)
Plain telnet, no web client. The very first proof of concept running as "QuickMUD".

![v0 telnet](screenshots/v0-telnet.png)

### v0.5 — Telnet with ANSI
Same telnet client with ANSI color support enabled — the first sign of life for the room/look system.

![v0.5 telnet with ANSI](screenshots/v0-5-telnet-ansi.png)

### v1 — First Web Client
A single-page web terminal. Dark background, ASCII art login banner, basic Connected/Reconnect buttons. No panels.

![v1 web client](screenshots/v1-web-client.png)

### v2 — Web Client + Panels
Added a character sidebar (HP/mana/XP bars), a mini-map (dot tracking visited rooms), and a room info panel on the right.

![v2 web client with panels](screenshots/v2-web-panels.png)

### v3 — Surreal Gentle Magic
Full redesign: dark glassmorphism panels, banner artwork, tabbed Play/Character/Social/World layout, GMCP-driven skills and combat view.

![v3 web client](screenshots/v3-web-client.jpg)

### v4 — PixiJS Canvas (Current)
PixiJS canvas replaces the xterm terminal as the primary game view. Terminal moved to popout (available on command input focus). JRPG-style world and battle scenes with sprite-based rendering. Side panels preserved.

![v4 web client](screenshots/webclient-v4.jpeg)

---

## Architecture

### Layout: JRPG Canvas + WoW-Style Panels

The PixiJS canvas occupies the space the terminal previously held — it's the primary game view. Surrounding panels (World, Chat, Character) stay in place as persistent HUD elements. Popouts (map, equipment, room details, terminal) overlay the canvas when opened.

```
┌──────────────────────────────────────────────────────────┐
│                      App Shell                           │
│  ┌──────────────────────────────┐  ┌──────────────────┐ │
│  │     PixiJS Canvas            │  │  React Panels    │ │
│  │  ┌────────────────────────┐  │  │  - WorldPanel    │ │
│  │  │     SceneManager       │  │  │  - ChatPanel     │ │
│  │  │  ┌────────┐ ┌───────┐ │  │  │  - CharPanel     │ │
│  │  │  │ World  │ │Battle │ │  │  │  - CombatPanel   │ │
│  │  │  │ Scene  │ │Scene  │ │  │  │  - AdminPanel    │ │
│  │  │  └────────┘ └───────┘ │  │  │                   │ │
│  │  └────────────────────────┘  │  └──────────────────┘ │
│  │  [Command Input Bar]        │                        │
│  └──────────────────────────────┘                        │
│  ┌─────────────────────────────────────────────────────┐ │
│  │  Popout Layer                                        │ │
│  │  map | equipment | room | help | terminal | spellbook│ │
│  └─────────────────────────────────────────────────────┘ │
│  ┌─────────────────────────────────────────────────────┐ │
│  │  GameStateBridge                                     │ │
│  │  React useState → shared ref object → PixiJS reads  │ │
│  └─────────────────────────────────────────────────────┘ │
│  ┌─────────────────────────────────────────────────────┐ │
│  │  Existing: useMudSocket, applyGmcpPackage, types.ts │ │
│  └─────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────┘
```

### Tech Stack

| Component | Version | Purpose |
|-----------|---------|---------|
| **React** | 19 | UI framework |
| **Vite** | latest | Build tool |
| **TypeScript** | latest | Type safety |
| **PixiJS** | 8.x | 2D WebGL/WebGPU sprite engine for game canvas |
| **xterm.js** | latest | Terminal (retained for popout) |

### Serving & Build

- **Source project:** `web-v3/`
- **Build output:** `src/main/resources/web-v3/` (written by `bun run build`)
- **Served by:** Ktor static resources at `/`
- **Compatibility:** `/v3` and `/v3/` redirect to `/`
- **WebSocket endpoint:** `/ws`

### State Bridge Pattern

PixiJS code runs outside React's render cycle. A lightweight bridge exposes current state to the canvas via a mutable ref object (`GameStateBridge.ts`). React syncs the ref in a `useEffect`; PixiJS reads it each frame tick.

For push events (combat events, gain popups), `CanvasEventBus.ts` provides a ring buffer that PixiJS drains each frame.

### Runtime Data Flow

1. Browser connects to `ws(s)://<host>/ws`
2. Ktor bridge registers the session and emits `InboundEvent.Connected`
3. WS transport auto-sends `InboundEvent.GmcpReceived(Core.Supports.Set, [...])` for supported packages
4. Engine `GmcpEventHandler` stores package support and emits initial snapshots
5. Outbound GMCP is serialized as JSON envelope: `{"gmcp":"<Package>","data":<json>}`
6. Frontend routes by package in `applyGmcpPackage.ts`

---

## File Structure

```
web-v3/src/
├── canvas/                          # PixiJS code
│   ├── GameStateBridge.ts           # Shared ref for React → PixiJS state
│   ├── CanvasEventBus.ts            # Push events (combat hits, gains)
│   ├── PixiCanvas.tsx               # React component wrapping PixiJS Application
│   ├── LoginModal.tsx               # Modal login form (name, password, race/class selection)
│   ├── SceneManager.ts              # Scene state machine (world ↔ battle ↔ transition)
│   ├── scenes/
│   │   ├── WorldScene.ts            # Room view, player/NPC sprites, exits
│   │   └── BattleScene.ts           # JRPG battle view driven by combat events
│   └── systems/
│       ├── CombatAnimator.ts        # Combat event → sprite animations
│       ├── GainPopup.ts             # Floating XP/gold/level-up numbers
│       ├── StatusEffectDisplay.ts   # Buff/debuff icons
│       ├── Minimap.ts               # Canvas-based minimap
│       ├── DialogueOverlay.ts       # NPC dialogue on canvas
│       └── EntityPopout.ts          # Click-to-interact on sprites
├── App.tsx                          # Composition root, state management
├── gmcp/applyGmcpPackage.ts        # GMCP package → state updates
├── components/
│   ├── panels/                      # PlayPanel, WorldPanel, ChatPanel, CharacterPanel
│   ├── PopoutLayer.tsx              # Overlay panels (map, equipment, terminal, spellbook)
│   ├── SpellbookPanel.tsx           # Ability grid with target type filtering
│   └── ...
├── hooks/
│   ├── useMudSocket.ts              # WebSocket lifecycle
│   ├── useCommandHistory.ts         # Command history + tab completion
│   ├── useMiniMap.ts                # Visited-room graph
│   └── useQuickbar.ts              # 9-slot quickbar (localStorage persisted)
├── types.ts                         # Shared TypeScript types
└── styles.css                       # Surreal Gentle Magic design system
```

---

## Validation

Run from `web-v3/`:
```bash
bun run lint
bun run build
```

CI (`.github/workflows/ci.yml`) runs both `bun run lint` and `bun run build` on every push and PR.

---

