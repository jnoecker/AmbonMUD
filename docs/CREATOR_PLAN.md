# AmbonMUD Creator — Design Plan

A desktop application that owns and manages a local AmbonMUD installation. You point it at the AmbonMUD project directory and it becomes the single tool for configuring, running, and monitoring the server.

---

## Core Concept

The Creator is not just a YAML editor — it is the **control plane** for AmbonMUD in standalone mode. It:

1. **Reads** all world YAML, application.yaml, and project structure from the AmbonMUD directory
2. **Edits** world content, gameplay config, classes, and races through visual editors
3. **Writes** changes back to the correct locations (it knows the project layout)
4. **Runs** the server via `gradlew run`, streams logs, and manages the process lifecycle
5. **Validates** content both statically (before save) and dynamically (server starts cleanly)

### Scope Boundaries

- **Standalone mode only** — no ENGINE/GATEWAY multi-instance management
- **No embedded player client** — use the browser or telnet separately
- **No remote server management** — local project directory only
- **Future:** a built-in staff player for graceful shutdown via the `shutdown` command

---

## Tech Stack

Matches the existing AmbonMUD-Visualize sibling app:

| Concern | Choice |
|---------|--------|
| Desktop framework | Tauri v2 (tauri-plugin-fs, tauri-plugin-dialog, tauri-plugin-shell) |
| Frontend | React 19, TypeScript 5.8+ |
| Build | Vite 7, Bun |
| State management | React Context + useReducer |
| Graph editor | React Flow |
| YAML parsing | `yaml` npm package (CST mode for format preservation) |
| Styling | CSS (custom) |

---

## Project Model

```typescript
interface Project {
  version: 1;
  name: string;
  mudDir: string;          // root of AmbonMUD project (contains gradlew, src/, etc.)
  openZones: string[];     // which zones are currently open as tabs
  lastOpenTab?: string;
}
```

All paths are derived from `mudDir`:

| What | Derived path |
|------|-------------|
| World zones | `<mudDir>/src/main/resources/world/*.yaml` |
| Application config | `<mudDir>/src/main/resources/application.yaml` |
| Gradle wrapper | `<mudDir>/gradlew.bat` (Windows) or `<mudDir>/gradlew` (Unix) |
| Player saves | `<mudDir>/data/players/` |
| World mutations | `<mudDir>/data/world_mutations.yaml` |
| Login screen | `<mudDir>/src/main/resources/login.txt` |

On project open: scan `mudDir`, validate it looks like an AmbonMUD checkout (has `gradlew`, `src/main/resources/world/`, etc.), read all zone YAMLs + application.yaml into memory.

---

## Server Management

### Process Lifecycle

The Creator spawns and owns the server process:

- **Start:** run `gradlew run` (or `gradlew.bat run` on Windows) as a child process
- **Stop:** send SIGINT/SIGTERM (graceful JVM shutdown). If unresponsive after timeout, force-kill.
- **Restart:** stop then start
- **Save & Reload:** write changed files, stop, start

Future improvement: instead of killing the process, connect as a built-in staff player via localhost telnet and issue the `shutdown` command for graceful engine shutdown.

### Pre-flight Checks

Before starting the server, verify:
- Java 21+ is available on PATH (or JAVA_HOME is set)
- `gradlew`/`gradlew.bat` exists and is executable
- No other process is bound to the configured telnet/web ports (default 4000/8080)

### Log Streaming

Capture stdout/stderr from the child process and display in a Console panel:

- Real-time streaming with auto-scroll
- Pause-on-scroll (manual scroll up stops auto-scroll)
- Filter by log level (DEBUG/INFO/WARN/ERROR)
- Search/filter by text
- Highlight world-loading errors (immediate feedback on bad YAML)
- Clear button
- Timestamp display

### Status Indicator

Top toolbar shows server state:
- **Stopped** — gray, start button enabled
- **Starting** — yellow/spinner, waiting for "Server started" log line
- **Running** — green, stop/restart buttons enabled
- **Stopping** — yellow/spinner
- **Error** — red, show last error from logs

---

## Data Types

The Creator's TypeScript types mirror these Kotlin DTOs exactly:

### World Content

| Kotlin DTO | Key fields |
|-----------|-----------|
| `WorldFile` | zone, lifespan, startRoom, image, audio, rooms, mobs, items, shops, quests, gatheringNodes, recipes |
| `RoomFile` | title, description, exits (map of direction to ExitValue), features, station, image, video, music, ambient |
| `ExitValue` | to (RoomId string), optional door (DoorFile) |
| `MobFile` | name, room, tier, level, stat overrides, drops, behavior, dialogue, quests |
| `ItemFile` | displayName, slot, stats (str/dex/con/int/wis/cha), damage, armor, consumable, onUse, basePrice |
| `ShopFile` | name, room, items list |
| `QuestFile` | objectives (type/targetKey/count), rewards (xp/gold) |
| `BehaviorFile` | template + params (patrolRoute, fleeHpPercent, aggroMessage, maxWanderDistance) |
| `DialogueNodeFile` | text, choices (text, next, minLevel, requiredClass, action) |
| `FeatureFile` | type (CONTAINER/LEVER/SIGN), state, key, items, text |
| `GatheringNodeFile` | skill, yields, respawn |
| `RecipeFile` | skill, materials, output, station |

### Config (application.yaml sections)

| Section | Key fields |
|---------|-----------|
| `engine.abilities.definitions` | 104 abilities with id, displayName, manaCost, cooldownMs, damage, healing, statusEffectId, requiredClass, requiredLevel, targetType, image |
| `engine.statusEffects.definitions` | 27 effects with id, name, type, durationMs, tickMs, value, maxStacks, statMods |
| `engine.combat` | strDivisor, dexDodgePerPoint, maxDodgePercent, intSpellDivisor, feedback toggles |
| `engine.mob.tiers` | 4 tiers (weak/standard/elite/boss) with HP/damage/armor/XP/gold formulas |
| `progression` | XP curve (baseXp, exponent, linearXp), level rewards (hpPerLevel, manaPerLevel, fullHealOnLevelUp) |
| `engine.economy` | buyMultiplier, sellMultiplier |
| `engine.regen` | HP and mana regen intervals, amounts, stat divisors |
| `engine.classes.definitions` | Class defs: id, displayName, hpPerLevel, manaPerLevel, description, selectable, primaryStat, startRoom |
| `engine.races.definitions` | Race defs: id, displayName, description, statMods |

---

## App Architecture

```
App.tsx
  ProjectContext (project state, all zones, config, server process)
    UndoContext (undo/redo stack, max 100 actions)
      AppShell
        Toolbar (save, start/stop/restart, validate, undo/redo, server status badge)
        Sidebar (zone tree navigator, entity lists)
        TabBar (open zone/config/console tabs)
        MainArea
          ZoneEditor (when zone tab active)
            MapCanvas (React Flow graph)
              RoomNode (custom node)
              ExitEdge (custom edge)
            RoomPanel (property editor for selected room)
            MobEditor / ItemEditor / ShopEditor (entity forms)
          ConfigEditor (when config tab active)
            AbilityList + AbilityEditor
            StatusEffectList + StatusEffectEditor
            CombatConfig / MobTierConfig / ProgressionConfig
            EconomyConfig / RegenConfig
          ClassDesigner (when classes tab active)
            ClassList + ClassEditor
          RaceDesigner (when races tab active)
            RaceList + RaceEditor
          Console (when console tab active)
            LogStream (filterable, searchable, auto-scroll)
        StatusBar (validation errors, dirty indicator, server state, player count)
```

---

## Module Details

### Zone/Room Map Editor (React Flow)

- **Rooms as nodes:** Custom `RoomNode` shows title, mob count badge, item count badge, shop/station icons. Color-coded by zone.
- **Exits as edges:** Custom `ExitEdge` shows direction label (n/s/e/w/u/d), door icon if door present. Cross-zone exits in different color.
- **Auto-layout:** Dagre algorithm positions nodes on first load using exit topology (N=up, S=down, E=right, W=left). Positions saved to `.creator-layout.json` per zone.
- **Interactions:**
  - Double-click node: open room panel
  - Drag between nodes: create exit (direction prompted or inferred from position)
  - Click canvas: create room
  - Right-click: context menu (delete, duplicate, add mob/item)
  - Ctrl+Z/Y: undo/redo

### Mob/Item/Shop Editors

Form-based editors in a right-side panel when entity is selected:

- **Mob tier preview:** Shows computed HP/damage/armor/XP/gold from tier+level formulas, with override toggles
- **Drop editor:** Repeatable rows with item ID dropdown + chance slider (0-100%)
- **Behavior selector:** Template dropdown with conditional param fields
- **Dialogue tree:** Collapsible tree view with inline text editing
- **Item slot preview:** Visual equipment slot indicator
- **Shop item picker:** Multi-select with search from all items across zones

### Config Editor

Tabbed UI for application.yaml sections — structured forms, not raw YAML:

| Tab | Controls |
|-----|---------|
| Abilities | Searchable/filterable grid. Detail form with dynamic fields by effect type. |
| Status Effects | Grid + detail form. Effect type determines which fields shown. |
| Combat | Sliders for divisors and percentages. Toggles for feedback. |
| Mob Tiers | 4-tier table with all numeric fields editable inline. |
| Progression | XP curve inputs + visual graph showing XP-per-level. Level rewards config. |
| Economy | Buy/sell multiplier sliders with computed price preview. |
| Regen | HP and Mana regen: base interval, min interval, amount, stat divisor. |

### Class/Race Designer

- **Class list:** Cards or table with search. Supports 20+ entries.
- **Class editor:** ID (auto-uppercase), displayName, description, hpPerLevel, manaPerLevel, primaryStat dropdown, selectable toggle, startRoom (room picker). HP/Mana curve graph for levels 1-50.
- **Race list:** Same pattern.
- **Race editor:** ID, displayName, description, stat mod inputs (6 fields with +/- steppers). Net-zero indicator. Effective base stats preview (10 + mod).

### Console / Log Viewer

- Streams stdout/stderr from the `gradlew run` child process
- Color-coded by log level
- Filterable (level checkboxes, text search)
- Auto-scroll with pause-on-scroll
- Clear button
- Persists across tab switches (doesn't lose history when viewing other tabs)

---

## YAML Round-Trip Strategy

Exported YAML should produce minimal diffs from hand-authored YAML:

1. Use `yaml` package in CST (Concrete Syntax Tree) mode to preserve comments, key ordering, and formatting when possible.
2. For zone files: read as CST, edit in UI, serialize back using CST patching for existing files, clean serialization for new files.
3. For application.yaml: read full file as CST, patch only managed sections (abilities, status effects, combat, mob, progression, economy, regen, classes, races), preserve everything else untouched.
4. Key ordering in zone YAML: `zone`, `lifespan`, `startRoom`, `image`, `audio`, `rooms`, `mobs`, `items`, `shops`, `quests`, `gatheringNodes`, `recipes` (matching existing convention).
5. Block scalars for multi-line descriptions. Flow style for simple inline objects.

---

## Validation Engine

Client-side validation mirrors `WorldLoader.kt` rules:

**Zone-level:**
- Zone name non-blank
- startRoom exists in rooms
- No duplicate IDs within zone
- All exit targets resolve (within zone or cross-zone)
- All mob/item/shop room refs resolve
- All drop itemIds resolve
- Direction strings valid (n/s/e/w/u/d)
- Behavior templates valid
- Patrol routes reference valid rooms

**Config-level:**
- Ability `requiredClass` references a defined class
- Ability `statusEffectId` references a defined status effect
- Numeric ranges valid (minDamage > 0, maxDamage >= minDamage, etc.)

**Cross-validation:**
- Class startRooms reference valid rooms
- Quest giver references valid mob

Validation runs on change (debounced 300ms). Errors shown inline in editors and summarized in status bar.

---

## Undo/Redo

All mutations are dispatched as typed actions:

- `undoStack: Action[]` (max 100)
- `redoStack: Action[]` (cleared on new action)
- Ctrl+Z / Ctrl+Y keyboard shortcuts
- Actions are granular (e.g., "change room title", "add exit", "delete mob") — not full-state snapshots

---

## Implementation Phases

### Phase 1: Project Shell + Server Management

1. Initialize Tauri v2 project (React, TS, Vite, Bun)
2. Project model: open directory picker, validate AmbonMUD checkout, derive all paths
3. Layout shell: toolbar, sidebar, tab bar, main area, status bar
4. Server process management: start/stop/restart via gradlew
5. Console tab with log streaming, filtering, search
6. Server status indicator in toolbar
7. Pre-flight checks (Java version, port availability)

### Phase 2: Zone Map Editor

1. Read zone YAMLs into typed state
2. React Flow integration: RoomNode, ExitEdge, dagre auto-layout
3. Room property panel (title, description, exits, station, image)
4. Create/delete rooms and exits via canvas interaction
5. YAML export: serialize zones back to files
6. Undo/redo for map operations
7. Save & Reload workflow (write files, restart server)

### Phase 3: Entity Editors (Mobs, Items, Shops, Quests)

1. Mob editor form with tier preview + stat override
2. Drop editor, behavior selector, dialogue tree view
3. Item editor with slot/stat/consumable fields
4. Shop editor with item picker
5. Quest editor with objectives and rewards
6. Gathering node and recipe editors
7. Zone-level validation

### Phase 4: Config Editor

1. Parse application.yaml into typed config state
2. Tabbed config UI shell
3. Ability list + detail form (104 abilities, filterable)
4. Status effect list + detail form
5. Combat, mob tier, progression, economy, regen panels
6. Config validation
7. CST-preserving application.yaml write-back

### Phase 5: Class/Race Designer

1. Class list with search
2. Class editor form + HP/Mana curve graph
3. Race list + editor with stat mods
4. Cross-references: ability-to-class, class start rooms

### Phase 6: Polish

1. YAML preview panel (toggle raw YAML alongside forms)
2. Diff view before save
3. Global search across zones
4. Bulk rename (refactor IDs across references)
5. Keyboard shortcuts documentation
6. Window state persistence

### Future

- **Graceful shutdown:** built-in staff player connects via localhost, issues `shutdown` command
- **Asset generation:** port image/prompt generation from AmbonMUD-Visualize into Creator
- **Live monitoring:** query admin API for player count, active sessions while server runs
- **Multi-instance support:** ENGINE/GATEWAY profile management

---

## Key Risks

| Risk | Mitigation |
|------|-----------|
| YAML round-trip loses comments/formatting | Use `yaml` CST mode; test round-trip on all existing zone files before first release |
| React Flow performance on huge zones (labyrinth is 478KB YAML) | React Flow has built-in virtualization; test early with 200+ nodes; lazy-load large zones |
| application.yaml partial patching corrupts unmanaged sections | CST-level patching; read full file, modify only managed nodes; backup before write |
| Type drift between Kotlin DTOs and TS types | Consider codegen later; for now, manually sync and add round-trip test (export, reimport, diff) |
| Child process management edge cases (zombie processes, port conflicts) | PID tracking, timeout-based force kill, port check before start |
| Platform differences (Windows vs Mac vs Linux) | Tauri abstracts most of this; test gradlew invocation on Windows (`.bat`) and Unix |

---

## Verification Strategy

- **Round-trip test:** import all zone files, export, diff against originals (should be minimal/zero diff)
- **Validation test:** import existing zones, verify zero validation errors
- **Server management test:** start, verify log streaming, stop, verify process cleaned up
- **Manual:** create a new zone with rooms/mobs/items, export, start server, verify it loads
