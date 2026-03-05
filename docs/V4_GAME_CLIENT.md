# V4 Game Client — PixiJS Canvas on v3

> JRPG-style visual layer added incrementally to the existing v3 web client. No rewrite — PixiJS renders alongside existing React panels, sharing the same state and GMCP infrastructure.

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

Everything else stays: React 19, Vite, TypeScript, xterm.js. No Tauri, no Zustand migration, no Tailwind.

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│                 Existing v3 App Shell                     │
│                                                           │
│  ┌────────────────┐  ┌─────────────────────────────────┐ │
│  │  React Panels   │  │       PixiJS Canvas (NEW)       │ │
│  │  (unchanged)    │  │  ┌───────────────────────────┐  │ │
│  │                 │  │  │     SceneManager          │  │ │
│  │  - PlayPanel    │  │  │  ┌─────────┐ ┌─────────┐ │  │ │
│  │  - WorldPanel   │  │  │  │ World   │ │ Battle  │ │  │ │
│  │  - ChatPanel    │  │  │  │ Scene   │ │ Scene   │ │  │ │
│  │  - CharPanel    │  │  │  └─────────┘ └─────────┘ │  │ │
│  │  - CombatPanel  │  │  └───────────────────────────┘  │ │
│  │  - AdminPanel   │  │                                  │ │
│  └────────────────┘  └─────────────────────────────────┘ │
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

All new files go under `web-v3/src/canvas/`. Existing files are untouched unless noted.

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
│  (existing files — unchanged)
├── App.tsx                          # Modified: add GameStateBridge sync + PixiCanvas
├── gmcp/applyGmcpPackage.ts        # Modified: also push combat/gain events to CanvasEventBus
├── components/panels/...            # Unchanged
├── hooks/...                        # Unchanged
├── types.ts                         # Unchanged (canvas code imports existing types)
└── ...
```

### Changes to Existing Files

Only two files need modification:

1. **`App.tsx`** — Add `<PixiCanvas />` component in the layout, add `useEffect` to sync `gameStateRef`, conditionally show/hide canvas vs terminal based on a toggle or game state.

2. **`gmcp/applyGmcpPackage.ts`** — In the `Char.Combat.Event` and `Char.Gain` handlers, also push to `canvasEvents` so the canvas can animate them independently of React state.

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

### Phase 1: PixiJS Foundation + World Scene

**Goal:** PixiJS canvas renders the current room with the player sprite. Existing panels continue working alongside it.

- Add `pixi.js` dependency to `web-v3/package.json`
- Create `GameStateBridge.ts` and `CanvasEventBus.ts`
- Create `PixiCanvas.tsx` React wrapper (mounts PixiJS `Application`, handles resize)
- Implement `SceneManager` with `WorldScene` as the initial scene
- `WorldScene`: render room title/image as background, player sprite centered, exit indicators (directional arrows or paths), NPC sprites from `Room.Mobs`
- Add `<PixiCanvas />` to `App.tsx` layout — initially as an optional view alongside the terminal
- Add toggle or layout mode to switch between terminal-primary and canvas-primary views
- Wire `useEffect` in `App.tsx` to sync state bridge

**Art approach:** Start with simple colored rectangles / placeholder sprites. Art can be swapped in later without code changes.

### Phase 2: Room Transitions + NPC Indicators

**Goal:** Moving between rooms feels fluid. NPCs show their role visually.

- `TransitionScene`: fade or slide animation triggered when `Room.Info` changes
- NPC role overlays from `Room.MobInfo` — quest giver (❗), shop (🛒), dialogue (💬) icons above mob sprites
- Other players in room rendered as labeled sprites (from `Room.Players`)
- Room items rendered as small sprites on the ground (from `Room.Items`)
- Click-to-interact on NPC/item sprites → sends commands via existing `sendCommand`

### Phase 3: Battle Scene + Combat Animations

**Goal:** Combat is visually represented with a JRPG-style battle view.

- `BattleScene`: triggered when `Char.Combat` provides a target (or `Char.Vitals.inCombat` becomes true)
- Player sprite on one side, enemy sprite(s) on the other
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

### Canvas Placement in Layout

The PixiJS canvas is **not a replacement** for the terminal. It's an additional view. Options:

- **Side-by-side**: Canvas on one side, terminal + panels on the other (desktop)
- **Tabbed**: Canvas as a new tab alongside Play/World/Chat/Character (mobile)
- **Overlay**: Canvas behind the terminal, with terminal having a semi-transparent background

The exact layout is a UI decision that can be iterated on. Phase 1 starts with the simplest option (a new panel/tab) and refines from there.

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
