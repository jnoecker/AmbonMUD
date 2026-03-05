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

### GMCP Package Reference

All packages listed below are **implemented** in both the server (`GmcpEmitter.kt`) and the web-v3 client (`applyGmcpPackage.ts`).

#### Character Packages

| Package | Purpose | Payload Fields |
|---------|---------|----------------|
| `Char.StatusVars` | Label mapping for vitals fields | `hp`, `maxHp`, `mana`, `maxMana`, `level`, `xp` |
| `Char.Vitals` | HP, mana, XP, gold, combat state | `hp`, `maxHp`, `mana`, `maxMana`, `level`, `xp`, `xpIntoLevel`, `xpToNextLevel`, `gold`, `inCombat` |
| `Char.Name` | Identity, appearance | `name`, `gender`, `race`, `class`, `level`, `sprite` |
| `Char.Stats` | Base + effective stats, derived combat values | `strength`..`charisma`, `effectiveStrength`..`effectiveCharisma`, `baseDamageMin`, `baseDamageMax`, `armor`, `dodgePercent` |
| `Char.Skills` | Known abilities with cooldowns | `id`, `name`, `description`, `manaCost`, `cooldownMs`, `cooldownRemainingMs`, `levelRequired`, `targetType`, `classRestriction` |
| `Char.Cooldown` | Individual ability cooldown start event | `abilityId`, `cooldownMs` |
| `Char.StatusEffects` | Active buffs/debuffs | Array of `{ id, name, type, remainingMs, stacks }` |
| `Char.Items.List` | Full inventory and equipment | `inventory[]`, `equipment{}` — each item: `id`, `name`, `keyword`, `slot`, `damage`, `armor`, `basePrice`, `image` |
| `Char.Items.Add` | Item added to inventory | Single item payload (same fields as above) |
| `Char.Items.Remove` | Item removed from inventory | `id`, `name` |
| `Char.Achievements` | Completed and in-progress achievements | `completed[]` (`id`, `name`, `title`), `inProgress[]` (`id`, `name`, `current`, `required`) |
| `Char.Gain` | XP/gold/level-up event notifications | `type`, `amount`, `source?`, `newLevel?`, `hpGained?`, `manaGained?` |

#### Combat Packages

| Package | Purpose | Payload Fields |
|---------|---------|----------------|
| `Char.Combat` | Current combat target info | `targetId`, `targetName`, `targetHp`, `targetMaxHp`, `targetImage` |
| `Char.Combat.Event` | Per-hit/dodge/kill combat events | `type` (`meleeHit`, `abilityHit`, `heal`, `dodge`, `dotTick`, `hotTick`, `kill`, `death`, `shieldAbsorb`), plus type-specific fields: `targetName`, `targetId`, `damage`, `amount`, `sourceIsPlayer`, `abilityId`, `abilityName`, `effectName`, `xpGained`, `goldGained`, `killerName`, `killerIsPlayer`, `attackerName`, `absorbed`, `remaining` |

#### Room Packages

| Package | Purpose | Payload Fields |
|---------|---------|----------------|
| `Room.Info` | Room title, description, exits, image | `id`, `title`, `description`, `zone`, `exits{}`, `image?` |
| `Room.Players` | Other players in room (full list) | Array of `{ name, level }` |
| `Room.AddPlayer` | Delta: player entered room | `name`, `level` |
| `Room.RemovePlayer` | Delta: player left room | `name` |
| `Room.Mobs` | Mobs in room (full list) | Array of `{ id, name, hp, maxHp, image? }` |
| `Room.AddMob` | Delta: mob spawned/entered room | `id`, `name`, `hp`, `maxHp`, `image?` |
| `Room.UpdateMob` | Delta: mob HP/state changed | `id`, `name`, `hp`, `maxHp`, `image?` |
| `Room.RemoveMob` | Delta: mob died/left room | `id` |
| `Room.MobInfo` | Mob metadata (level, tier, NPC flags) | Array of `{ id, level, tier, questGiver, shopKeeper, dialogue }` |
| `Room.Items` | Items on the ground (full list) | Array of `{ id, name, image? }` |

#### Quest Packages

| Package | Purpose | Payload Fields |
|---------|---------|----------------|
| `Quest.List` | Full quest log | Array of `{ id, name, description, objectives[] }` — each objective: `{ description, current, required }` |
| `Quest.Update` | Single objective progress update | `questId`, `objectiveIndex`, `current`, `required` |
| `Quest.Complete` | Quest completion notification | `questId`, `questName` |

#### Social Packages

| Package | Purpose | Payload Fields |
|---------|---------|----------------|
| `Comm.Channel` | Chat messages (say, tell, gossip, shout, ooc, gtell) | `channel`, `sender`, `message` |
| `Group.Info` | Party members with full vitals | `leader`, `members[]` (`name`, `level`, `hp`, `maxHp`, `mana`, `maxMana`, `class`) |
| `Guild.Info` | Guild summary | `name`, `tag`, `rank`, `motd`, `memberCount`, `maxSize` |
| `Guild.Members` | Guild roster | Array of `{ name, rank, online, level? }` |
| `Guild.Chat` | Guild chat message | `sender`, `message` |
| `Friends.List` | Friends list with status | Array of `{ name, online, level?, zone? }` |
| `Friends.Online` | Friend came online | `name`, `level` |
| `Friends.Offline` | Friend went offline | `name` |

#### Dialogue Packages

| Package | Purpose | Payload Fields |
|---------|---------|----------------|
| `Dialogue.Node` | NPC dialogue with choices | `mobName`, `text`, `choices[]` (`index`, `text`) |
| `Dialogue.End` | Dialogue ended | `mobName`, `reason` |

#### Shop Packages

| Package | Purpose | Payload Fields |
|---------|---------|----------------|
| `Shop.List` | Shop inventory opened | `name`, `sellMultiplier`, `items[]` (`id`, `name`, `keyword`, `description`, `slot?`, `damage`, `armor`, `buyPrice`, `basePrice`, `consumable`, `image?`) |
| `Shop.Close` | Shop closed | `{}` |

#### System Packages

| Package | Purpose | Payload Fields |
|---------|---------|----------------|
| `Core.Ping` | Keep-alive ping | `{}` |

### Full Character Sync

On login (and when a client negotiates GMCP support), the server sends a full sync in this order:

1. `Char.StatusVars`
2. `Char.Vitals`
3. `Char.Name`
4. `Char.Items.List`
5. `Char.Skills`
6. `Char.StatusEffects`
7. `Char.Achievements`
8. `Group.Info`
9. `Guild.Info` (if in a guild)

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
  - `meleeHit` → slash animation + damage number
  - `abilityHit` → spell VFX + damage number
  - `heal` → green particles + heal number
  - `dodge` → "DODGE" text popup
  - `kill` → death animation + XP/gold popup (from `Char.Gain`)
  - `death` → player death animation
  - `shieldAbsorb` → shield flash + absorbed number
  - `dotTick` / `hotTick` → periodic damage/heal numbers
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

- Party panel with HP + mana bars (from `Group.Info`)
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
