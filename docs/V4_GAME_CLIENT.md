# V4 Game Client Architecture

> JRPG-style game client built with Tauri, targeting a rich visual experience over the MUD server's GMCP protocol.

## Tech Stack

| Layer | Technology | Rationale |
|-------|-----------|-----------|
| Shell | **Tauri 2** (Rust) | Native window, small binary, cross-platform, file-system access for settings/logs |
| UI | **React 19** + **TypeScript** | Component model, hooks for state management, large ecosystem |
| Rendering | **PixiJS 8** | 2D WebGL/WebGPU sprite engine; handles tilemaps, animations, particles |
| State | **Zustand** | Minimal boilerplate, works well with frequent GMCP updates |
| Styling | **Tailwind CSS 4** | Utility-first for UI panels; game canvas is PixiJS-rendered |
| Audio | **Howler.js** | Cross-platform audio with sprite support for SFX |
| Build | **Vite 6** | Fast HMR for development, optimized production builds |

## Communication Protocol

The client connects to the MUD server via **WebSocket** and uses **GMCP** (Generic MUD Communication Protocol) for all structured data. Text output is rendered in a scrollback panel; GMCP drives the game UI.

### GMCP Package Dependencies

The client requires these GMCP packages from the server:

| Package | Purpose | Status |
|---------|---------|--------|
| `Char.Vitals` | HP, mana, XP, gold, combat state | Existing |
| `Char.Name` | Name, race, class, level | Existing |
| `Char.Combat` | Current combat target info | Existing |
| `Char.Combat.Event` | Per-hit/dodge/kill combat events | **New** |
| `Char.Stats` | Base + effective stats, derived combat values | **New** |
| `Char.Skills` | Known abilities with cooldowns | Existing |
| `Char.Cooldown` | Individual ability cooldown start events | **New** |
| `Char.StatusEffects` | Active buffs/debuffs | Existing |
| `Char.Items` | Inventory and equipment | Existing |
| `Char.Achievements` | Completed and in-progress achievements | Existing |
| `Char.Gain` | XP/gold/level-up event notifications | **New** |
| `Room.Info` | Room title, description, exits | Existing |
| `Room.Players` | Other players in room | Existing |
| `Room.Mobs` | Mobs in room (id, name, hp) | Existing |
| `Room.MobInfo` | Mob metadata (level, tier, quest/shop/dialogue flags) | **New** |
| `Room.Items` | Items on the ground | Existing |
| `Quest.List` | Full quest log | **New** |
| `Quest.Update` | Single objective progress update | **New** |
| `Quest.Complete` | Quest completion notification | **New** |
| `Group.Info` | Party members (with mana) | Enhanced |
| `Comm.Channel` | Chat messages | Existing |
| `Dialogue` | NPC dialogue trees | Existing |
| `Guild` | Guild info, members, chat | Existing |
| `Friends` | Friends list and online/offline events | Existing |

## Architecture Overview

```
┌──────────────────────────────────────────────────────┐
│                    Tauri Shell (Rust)                 │
│  ┌────────────────────────────────────────────────┐  │
│  │              React Application                  │  │
│  │                                                 │  │
│  │  ┌─────────────┐  ┌──────────────────────────┐ │  │
│  │  │  UI Panels   │  │     PixiJS Canvas        │ │  │
│  │  │  (React)     │  │  ┌──────────────────┐   │ │  │
│  │  │              │  │  │  Scene Manager    │   │ │  │
│  │  │  - Vitals    │  │  │  - World View     │   │ │  │
│  │  │  - Inventory │  │  │  - Battle Scene   │   │ │  │
│  │  │  - Skills    │  │  │  - Dialogue       │   │ │  │
│  │  │  - Chat      │  │  │  - Effects/VFX    │   │ │  │
│  │  │  - Quests    │  │  └──────────────────┘   │ │  │
│  │  │  - Map       │  │                          │ │  │
│  │  └─────────────┘  └──────────────────────────┘ │  │
│  │                                                 │  │
│  │  ┌──────────────────────────────────────────┐  │  │
│  │  │           Zustand Store                   │  │  │
│  │  │  vitals | room | combat | quests | ...    │  │  │
│  │  └──────────────────────────────────────────┘  │  │
│  │                                                 │  │
│  │  ┌──────────────────────────────────────────┐  │  │
│  │  │        GMCP Protocol Layer                │  │  │
│  │  │  WebSocket → parse → dispatch to store    │  │  │
│  │  └──────────────────────────────────────────┘  │  │
│  └────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────┘
```

## Module Structure

```
src/
├── main.tsx                    # Tauri + React entry point
├── App.tsx                     # Root layout (canvas + panels)
├── connection/
│   ├── WebSocketManager.ts     # WebSocket lifecycle, reconnect
│   ├── GmcpParser.ts           # Parse GMCP packages from WS frames
│   └── GmcpDispatcher.ts       # Route GMCP data → Zustand slices
├── store/
│   ├── index.ts                # Combined Zustand store
│   ├── vitalsSlice.ts          # HP, mana, XP, gold
│   ├── roomSlice.ts            # Room info, exits, players, mobs, items
│   ├── combatSlice.ts          # Combat target, combat events queue
│   ├── statsSlice.ts           # Base + effective stats
│   ├── inventorySlice.ts       # Items, equipment
│   ├── skillsSlice.ts          # Abilities, cooldowns
│   ├── questSlice.ts           # Active quests, objectives
│   ├── chatSlice.ts            # Chat channels, messages
│   ├── groupSlice.ts           # Party info
│   └── ...                     # Additional slices as needed
├── canvas/
│   ├── PixiApp.tsx             # PixiJS application wrapper
│   ├── SceneManager.ts         # Scene state machine
│   ├── scenes/
│   │   ├── WorldScene.ts       # Overworld tilemap + player sprite
│   │   ├── BattleScene.ts      # JRPG battle view (driven by Char.Combat.Event)
│   │   ├── DialogueScene.ts    # NPC dialogue overlay
│   │   └── TransitionScene.ts  # Room transition animations
│   ├── sprites/
│   │   ├── CharacterSprite.ts  # Player + NPC animated sprites
│   │   ├── MobSprite.ts        # Enemy sprites with HP bars
│   │   └── EffectSprite.ts     # VFX (hits, heals, particles)
│   └── systems/
│       ├── CombatAnimator.ts   # Queues Char.Combat.Event → animations
│       ├── GainPopup.ts        # Animated XP/gold/level-up popups
│       └── CooldownOverlay.ts  # Ability cooldown visuals
├── panels/
│   ├── VitalsPanel.tsx         # HP/mana/XP bars
│   ├── InventoryPanel.tsx      # Bag + equipment grid
│   ├── SkillBar.tsx            # Ability hotbar with cooldowns
│   ├── QuestPanel.tsx          # Quest log with objective tracking
│   ├── ChatPanel.tsx           # Scrollback + channel tabs
│   ├── MiniMap.tsx             # Auto-mapped zone minimap
│   ├── PartyPanel.tsx          # Group member vitals (HP + mana)
│   └── StatsPanel.tsx          # Character stats sheet
├── audio/
│   ├── AudioManager.ts         # Howler.js wrapper
│   ├── sfx.ts                  # SFX sprite definitions
│   └── music.ts                # BGM management
└── assets/
    ├── sprites/                # Character/mob/item spritesheets
    ├── tilesets/               # World tileset images
    ├── ui/                     # UI element graphics
    └── audio/                  # SFX + music files
```

## Phase Breakdown

### Phase 1: Foundation (connection + state + basic panels)

- Tauri project scaffold with React + Vite
- WebSocket connection to MUD server
- GMCP parser and dispatcher
- Zustand store with core slices (vitals, room, character)
- Basic UI panels: vitals bars, room info, scrollback text
- Login flow

### Phase 2: PixiJS Canvas + World View

- PixiJS application integration
- Scene manager state machine
- World scene with tile-based room rendering
- Player sprite on current room tile
- Room transition animations on movement
- Exits rendered as directional indicators

### Phase 3: Combat System

- Battle scene triggered by `Char.Combat` (target acquired)
- `Char.Combat.Event` drives per-action animations:
  - `MeleeHit` → slash animation + damage number
  - `AbilityHit` → spell VFX + damage number
  - `Heal` → green particles + heal number
  - `Dodge` → "DODGE" text popup
  - `Kill` → death animation + XP/gold popup (from `Char.Gain`)
  - `Death` → player death animation
  - `ShieldAbsorb` → shield flash + absorbed number
  - `DotTick` / `HotTick` → periodic damage/heal numbers
- `Char.Cooldown` → real-time cooldown bars on skill bar
- Ability hotbar with click-to-cast

### Phase 4: Inventory, Equipment, Stats

- Inventory grid panel (drag-and-drop)
- Equipment paper-doll display
- `Char.Stats` panel showing base/effective stats + combat values
- Item tooltips with stat comparison

### Phase 5: Quests, Dialogue, NPCs

- Quest panel with `Quest.List` / `Quest.Update` / `Quest.Complete`
- Objective tracking HUD
- NPC dialogue overlay (from `Dialogue.Node` / `Dialogue.End`)
- `Room.MobInfo` drives NPC icons (quest marker, shop icon, chat bubble)

### Phase 6: Social + Polish

- Party panel with HP + mana bars (from enhanced `Group.Info`)
- Chat panel with channel tabs
- Friends list with online status
- Guild panel
- Minimap with auto-mapping
- Audio system (BGM per zone, SFX for combat events)
- Settings panel (audio, display, keybindings)

## Key Design Decisions

### Combat Event Animation Queue

`Char.Combat.Event` packets arrive as individual events (not batched). The `CombatAnimator` maintains a queue:

1. Event arrives → pushed to animation queue
2. Queue processes events sequentially with timing
3. Each event type maps to a specific animation + sound
4. Animations are non-blocking (next event can start before previous finishes if timing allows)

This ensures combat feels responsive even at high tick rates.

### State Architecture

GMCP packages map 1:1 to Zustand store slices. The dispatcher routes each package to the appropriate slice update. React components subscribe to specific slices for minimal re-renders.

```
WebSocket frame
  → GmcpParser.parse(frame)
  → GmcpDispatcher.dispatch(package, data)
  → store.getState().updateVitals(data)  // example
  → React re-renders subscribed components
  → PixiJS reads store for canvas updates
```

### Canvas/React Split

- **React** handles all UI panels (vitals, inventory, chat, quests) — these are standard DOM elements styled with Tailwind
- **PixiJS** handles the game canvas (world view, battle scene, VFX) — these are WebGL-rendered sprites
- Both read from the same Zustand store
- Canvas is a single `<canvas>` element managed by PixiJS; React panels overlay/surround it

### Offline-First Settings

Tauri's file-system access stores user preferences locally:
- Keybindings
- Audio volumes
- Display preferences
- Server connection history

These persist across sessions without server-side storage.
