# V4 Game Client — PixiJS Canvas on v3

> JRPG-style game client built by replacing the xterm terminal in the existing v3 web client with a PixiJS canvas. The canvas becomes the primary game view; the terminal is available as a popout for debugging and features not yet visual. All existing GMCP infrastructure, React panels, and state management are preserved.

## Why Evolve v3 Instead of Rewriting

The v3 client already handles **35+ GMCP packages**, has a complete type system (~285 lines), WebSocket lifecycle management, 6 UI panels, command history, and a minimap. A fresh v4 rewrite would re-implement ~60% of this before touching a single sprite.

By adding PixiJS directly to `web-v3/`, we:

- Skip re-building GMCP parsing, state management, and UI panels
- Ship visual features faster (start with sprites on day one)
- Keep the working client running throughout development
- Maintain the existing CI pipeline (`bun run lint && bun run build`)
- Avoid maintaining two parallel clients during any transition period

## Tech Stack (Additions to v3)

| Addition | Version | Purpose |
|----------|---------|---------|
| **PixiJS** | 8.x | 2D WebGL/WebGPU sprite engine for game canvas |
| **@pixi/react** | (optional) | React wrapper for PixiJS — evaluate during Phase 1 |

Everything else stays: React 19, Vite, TypeScript, xterm.js (retained for popout terminal). No Tauri, no Zustand migration, no Tailwind.

## Architecture

The PixiJS canvas **replaces the xterm terminal** in `PlayPanel` as the primary game view. The terminal moves to a popout (like the existing map/equipment popouts). Surrounding panels (World, Chat, Character) stay in place.

```
┌──────────────────────────────────────────────────────────┐
│                      App Shell                            │
│                                                           │
│  ┌──────────────────────────────┐  ┌──────────────────┐  │
│  │     PixiJS Canvas (NEW)      │  │  React Panels     │  │
│  │  ┌────────────────────────┐  │  │  (unchanged)      │  │
│  │  │     SceneManager       │  │  │                    │  │
│  │  │  ┌────────┐ ┌───────┐ │  │  │  - WorldPanel     │  │
│  │  │  │ World  │ │Battle │ │  │  │  - ChatPanel      │  │
│  │  │  │ Scene  │ │Scene  │ │  │  │  - CharPanel      │  │
│  │  │  └────────┘ └───────┘ │  │  │  - CombatPanel    │  │
│  │  └────────────────────────┘  │  │  - AdminPanel     │  │
│  │                               │  │                    │  │
│  │  [Command Input Bar]         │  └──────────────────┘  │
│  │  [🖥 Terminal] button        │                         │
│  └──────────────────────────────┘                         │
│                                                           │
│  ┌─────────────────────────────────────────────────────┐ │
│  │  Popout Layer (existing)                              │ │
│  │  map | equipment | room | help | terminal (NEW)       │ │
│  └─────────────────────────────────────────────────────┘ │
│                                                           │
│  ┌─────────────────────────────────────────────────────┐ │
│  │          GameStateBridge (NEW)                        │ │
│  │  React useState → shared ref object → PixiJS reads   │ │
│  └─────────────────────────────────────────────────────┘ │
│                                                           │
│  ┌─────────────────────────────────────────────────────┐ │
│  │  Existing: useMudSocket, applyGmcpPackage, types.ts  │ │
│  └─────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────┘
```

### Terminal as Popout

The xterm terminal is not removed — it moves to a new `"terminal"` popout panel (same mechanism as the existing map, equipment, and help popouts in `PopoutLayer`). A button below the canvas (or in the header) opens it. The terminal continues to receive all server text output so it stays in sync. This provides:

- A fallback for any gameplay that doesn't have canvas representation yet
- A debugging view to see raw server output
- Familiar MUD experience for players who prefer text

### State Bridge Pattern

PixiJS code runs outside React's render cycle. Instead of migrating to Zustand, a lightweight bridge exposes current state to the canvas via a mutable ref object:

```typescript
// canvas/GameStateBridge.ts
export interface GameStateSnapshot {
  room: RoomState;
  vitals: Vitals;
  mobs: RoomMob[];
  players: RoomPlayer[];
  combatTarget: CombatTarget | null;
  inCombat: boolean;
  effects: StatusEffect[];
  character: CharacterInfo;
}

// Single ref updated by React on every relevant state change
export const gameStateRef: { current: GameStateSnapshot } = {
  current: { /* defaults */ },
};
```

In `App.tsx`, a `useEffect` keeps the ref in sync:

```typescript
useEffect(() => {
  gameStateRef.current = { room, vitals, mobs, players, combatTarget, inCombat: vitals.inCombat, effects, character };
});
```

PixiJS reads `gameStateRef.current` on each frame tick — no prop drilling, no coupling.

For events that need push semantics (combat events, gain popups), use a simple event emitter or a ring buffer that PixiJS drains each frame:

```typescript
// canvas/CanvasEventBus.ts
export const canvasEvents = {
  combatEvents: [] as CombatEventData[],
  gainEvents: [] as GainEvent[],
  push(event: CombatEventData | GainEvent) { /* append */ },
  drain(): { combat: CombatEventData[], gains: GainEvent[] } { /* take all, clear */ },
};
```

## New File Structure

All new files go under `web-v3/src/canvas/`. Existing files are modified minimally.

```
web-v3/src/
├── canvas/                          # NEW — all PixiJS code lives here
│   ├── GameStateBridge.ts           # Shared ref for React → PixiJS state
│   ├── CanvasEventBus.ts            # Push events (combat hits, gains)
│   ├── PixiCanvas.tsx               # React component wrapping PixiJS Application
│   ├── SceneManager.ts              # Scene state machine (world ↔ battle ↔ transition)
│   ├── scenes/
│   │   ├── WorldScene.ts            # Tile-based room view, player/NPC sprites, exits
│   │   ├── BattleScene.ts           # JRPG battle view driven by combat events
│   │   ├── TransitionScene.ts       # Room transition animations
│   │   └── DialogueOverlay.ts       # NPC dialogue rendered on canvas
│   ├── sprites/
│   │   ├── CharacterSprite.ts       # Player + NPC animated sprites
│   │   ├── MobSprite.ts             # Enemy sprites with HP bars
│   │   └── EffectSprite.ts          # VFX (hits, heals, particles)
│   └── systems/
│       ├── CombatAnimator.ts        # Queues combat events → sprite animations
│       ├── GainPopup.ts             # Floating XP/gold/level-up numbers
│       └── CooldownOverlay.ts       # Ability cooldown visuals on canvas
├── assets/
│   ├── sprites/                     # NEW — character/mob/item spritesheets
│   ├── tilesets/                    # NEW — world tileset images
│   └── ui/                          # NEW — canvas UI element graphics
│
│  (existing files)
├── App.tsx                          # Modified: swap terminal for canvas, terminal to popout
├── gmcp/applyGmcpPackage.ts        # Modified: also push combat/gain events to CanvasEventBus
├── components/panels/PlayPanel.tsx  # Modified: canvas replaces terminal host div
├── components/PopoutLayer.tsx       # Modified: add "terminal" popout variant
├── types.ts                         # Modified: add "terminal" to PopoutPanel union
├── hooks/...                        # Unchanged
└── ...
```

### Changes to Existing Files

1. **`App.tsx`** — Replace the `terminalHostRef` div in the main layout with `<PixiCanvas />`. Move xterm initialization into the terminal popout. Add `useEffect` to sync `gameStateRef`. The command input bar stays in place below the canvas — it sends commands the same way it does today.

2. **`components/panels/PlayPanel.tsx`** — The `terminalHostRef` div is replaced by the `<PixiCanvas />` component. The room image header, command composer, and action buttons (move, flee, talk, attack, pickup) remain unchanged. Add a small "Terminal" button that opens the terminal popout.

3. **`components/PopoutLayer.tsx`** — Add a `"terminal"` case that renders the xterm terminal at full popout size. The terminal continues receiving all server text via `onTextMessage` regardless of whether the popout is open.

4. **`types.ts`** — Add `"terminal"` to the `PopoutPanel` union type.

5. **`gmcp/applyGmcpPackage.ts`** — In the `Char.Combat.Event` and `Char.Gain` handlers, also push to `canvasEvents` so the canvas can animate them independently of React state.

## GMCP Packages Driving the Canvas

All packages below are **already parsed by v3**. The canvas reads them via the state bridge — no new server work needed.

| Canvas Feature | GMCP Source | What It Drives |
|---------------|-------------|----------------|
| Room rendering | `Room.Info` | Tile layout, exits, room image |
| Player sprite | `Char.Name` | Sprite selection, position |
| NPCs in room | `Room.Mobs`, `Room.AddMob`, `Room.RemoveMob` | Mob sprites, HP bars |
| Other players | `Room.Players`, `Room.AddPlayer`, `Room.RemovePlayer` | Player name labels |
| Room items | `Room.Items` | Item sprites on ground |
| NPC metadata | `Room.MobInfo` | Quest marker / shop icon / chat bubble overlays |
| Combat start | `Char.Combat` | Transition to battle scene, target highlight |
| Combat actions | `Char.Combat.Event` | Per-hit animations (slash, spell VFX, heal particles, dodge text, death) |
| Combat end | `Char.Vitals` (`inCombat: false`) | Transition back to world scene |
| Damage/heal numbers | `Char.Combat.Event` | Floating damage/heal popups |
| XP/gold popups | `Char.Gain` | Animated reward popups |
| Cooldowns | `Char.Cooldown`, `Char.Skills` | Cooldown overlays on skill sprites |
| Status effects | `Char.StatusEffects` | Buff/debuff icons on player sprite |
| Dialogue | `Dialogue.Node`, `Dialogue.End` | NPC dialogue overlay on canvas |
| Room transitions | `Room.Info` (room change) | Fade/slide animation between rooms |

## Phase Breakdown

### Phase 1: Canvas Replaces Terminal

**Goal:** PixiJS canvas takes over the terminal's space in PlayPanel. Terminal moves to a popout. Existing side panels unchanged.

- Add `pixi.js` dependency to `web-v3/package.json`
- Create `GameStateBridge.ts` and `CanvasEventBus.ts`
- Create `PixiCanvas.tsx` React wrapper (mounts PixiJS `Application`, handles resize)
- Implement `SceneManager` with `WorldScene` as the initial scene
- `WorldScene`: render room title/image as background, player sprite centered, exit indicators (directional arrows or paths), NPC sprites from `Room.Mobs`
- Modify `PlayPanel.tsx`: replace `terminalHostRef` div with `<PixiCanvas />`; keep command input bar and action buttons below canvas
- Add "Terminal" button to open xterm in a popout (via existing `PopoutLayer` mechanism)
- Move xterm setup into popout — terminal stays connected and receives all text even when popout is closed
- Wire `useEffect` in `App.tsx` to sync state bridge
- Pre-login state (before character exists): canvas shows a static scene or the login banner; command input still works for login flow

**Art approach:** Start with simple colored rectangles / placeholder sprites. Art can be swapped in later without code changes.

### Phase 2: Room Transitions + NPC Indicators

**Goal:** Moving between rooms feels fluid. NPCs show their role visually.

- `TransitionScene`: fade or slide animation triggered when `Room.Info` changes
- NPC role overlays from `Room.MobInfo` — quest giver, shop, dialogue icons above mob sprites
- Other players in room rendered as labeled sprites (from `Room.Players`)
- Room items rendered as small sprites on the ground (from `Room.Items`)
- Click-to-interact on NPC/item sprites — sends commands via existing `sendCommand`

### Phase 3: Battle Scene + Combat Animations

**Goal:** Combat is visually represented with a JRPG-style battle view.

- `BattleScene`: triggered when `Char.Combat` provides a target (or `Char.Vitals.inCombat` becomes true)
- **Party on the left, enemies on the right** (Final Fantasy layout). Group members from `Group.Info` shown as stacked sprites on the left if in a party; solo player otherwise
- `CombatAnimator` drains `canvasEvents.combatEvents` each frame and queues animations:
  - `meleeHit` → slash animation + red damage number
  - `abilityHit` → spell VFX (particle burst) + damage number
  - `heal` / `hotTick` → green particles + heal number
  - `dodge` → "DODGE" text popup, sprite sidestep
  - `dotTick` → periodic damage number (purple)
  - `kill` → enemy death animation
  - `death` → player death animation
  - `shieldAbsorb` → shield flash + absorbed number
- `GainPopup` shows floating "+X XP" / "+X Gold" / "Level Up!" from `Char.Gain` events
- Enemy HP bar on the battle scene (from `Char.Combat` target HP)
- Transition back to `WorldScene` when combat ends

### Phase 4: Dialogue + Status Overlays

**Goal:** NPC conversations and character status are rendered on canvas.

- `DialogueOverlay`: renders `Dialogue.Node` as a text box with clickable choices over the world/battle scene
- Status effect icons on player sprite (buff = blue border, debuff = red border, with stack count)
- `CooldownOverlay`: if abilities are shown on canvas (optional hotbar), render cooldown sweep animations driven by `Char.Cooldown`

### Phase 5: Polish + Sprite System

**Goal:** Swap placeholder art for real sprites. Improve visual fidelity.

- Spritesheet loading system (texture atlases for characters, mobs, items)
- Animated sprite states (idle, walk, attack, cast, hit, death)
- Particle system for spell effects, environmental ambiance
- Improved room rendering (tileset-based if art is available, otherwise styled backgrounds)
- Canvas-based minimap as alternative to the current `useMiniMap` canvas

## Key Design Decisions

### Layout Vision: JRPG Canvas + WoW-Style Panels

The overall feel is a **JRPG game view** (canvas fills the main area) surrounded by **WoW-style panels** that can be opened, closed, and rearranged:

- **Canvas** occupies the space the terminal previously held — it's the primary game view
- **Side panels** (World, Chat, Character) stay in their current positions as persistent HUD elements
- **Popouts** (map, equipment, room details, terminal) overlay the canvas when opened — like WoW's bag/character/quest windows
- Over time, more game features move from side panels into the canvas or into popouts, trending toward a clean JRPG view with on-demand HUD panels

The existing v3 panel infrastructure already supports this pattern. The `PopoutLayer` component handles overlay rendering, and the `activePopout` state machine manages which one is visible.

### Terminal Popout Behavior

The xterm terminal is always mounted (even when the popout is closed) so it stays in sync with server output. When the popout opens, the terminal is reparented into the popout container and `fitAddon.fit()` is called. This means:

- No messages are lost when the popout is closed
- Opening the terminal shows full scrollback history
- Players can check raw server output any time without losing their place
- The terminal popout is useful as a debug tool during canvas development — features not yet represented visually are still accessible via text

### Combat Event Animation Queue

`Char.Combat.Event` packets arrive individually. The `CombatAnimator` maintains a FIFO queue:

1. `CanvasEventBus` receives event → pushed to queue
2. Each frame, `CombatAnimator` checks if the current animation slot is free
3. If free, dequeue next event → map to animation + timing
4. Animations can overlap (damage numbers float independently of sprite animations)

This keeps combat responsive even at the 100ms server tick rate.

### Click-to-Interact

Canvas sprites are clickable. Clicking an NPC/item sends the same commands the existing panels send:

- Click mob → `kill <mob>` (or `talk <mob>` if quest giver / dialogue NPC)
- Click item → `get <item>`
- Click exit → movement command

All commands go through the existing `sendCommand` callback — the canvas never touches the WebSocket directly.

### Sprite Resolution

The server already sends `image` fields on rooms, mobs, and items (nullable strings in GMCP payloads). The `sprite` field on `Char.Name` identifies the player sprite. These are the hooks for mapping game entities to sprite assets. When no image is specified, fall back to a default sprite per entity type.

## What This Plan Does NOT Include

- **Tauri / native desktop wrapper** — browser-served from Ktor is sufficient. Can revisit if distribution needs change.
- **Audio (Howler.js)** — deferred. Can be added as a standalone enhancement later without architectural impact.
- **State management migration (Zustand)** — the ref bridge pattern works. If the bridge becomes unwieldy at scale, Zustand is a straightforward incremental migration from the bridge pattern.
- **New GMCP packages** — everything the canvas needs is already emitted by the server. No backend changes required for any phase.

## Validation

Same commands as v3, run from `web-v3/`:

```bash
bun run lint
bun run build
```

PixiJS adds ~150 kB to the bundle (tree-shaken). Monitor bundle size during development — the v3 client is already >500 kB, so lazy-loading the canvas module is recommended.
