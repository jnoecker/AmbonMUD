# AmbonMUD — Next Big Projects Brainstorm

## Where We Are

The infrastructure story is complete: event-driven engine, dual transports (telnet + WebSocket), event bus abstraction, write-behind persistence, Redis caching/pub-sub, PostgreSQL backend, gRPC gateway split, Prometheus metrics, zone resets, and a 49+ test suite.

Gameplay has the fundamentals: movement, look, communication (say/tell/gossip/emote), 1v1 combat vs mobs, items (get/drop/wear/remove with 3 equipment slots), XP/leveling (50 levels, quadratic curve), mob AI (random wandering), HP regen, and zone resets. Three zones with ~63 rooms, 20 mobs, and 39 items.

What's notably absent is _depth_. The projects below would each add a meaningful gameplay or tooling dimension.

---

## 1. Quest System

**What:** A data-driven quest framework — quest definitions in YAML, objectives (kill N mobs, visit room, collect item, talk to NPC), state tracking per player, rewards (XP, items, unlocking areas).

**Why this is high-impact:** Quests give players purpose beyond grinding. They create narrative arcs, guide exploration, and provide structured progression. Every successful MUD has some form of quest or task system.

**Key design questions:**
- Quest state lives on `PlayerRecord` (persisted) — a map of quest ID to progress
- Quest definitions in YAML alongside zone files, or a separate `quests/` directory?
- How to trigger quests: NPC dialogue? Entering a room? Picking up an item?
- Multi-step vs single-objective quests
- Prerequisite chains (quest B requires completing quest A)

**Scope:** Large. Touches persistence (`PlayerRecord` gains quest state), engine (new `QuestSystem` subsystem tracking objective completion), command parser (new `quest`/`journal` commands), world YAML (quest definitions + NPC quest-giver data), and the world loader.

---

## 2. NPC Dialogue & Scripting

**What:** Give mobs/NPCs the ability to speak, respond to player interaction, and execute scripted behaviors — dialogue trees, trigger-based reactions, patrol routes, aggro conditions.

**Why this is high-impact:** Right now mobs are combat targets that wander randomly. NPCs with dialogue transform the world from a combat arena into a living place. This is also a prerequisite for quest givers, shopkeepers, and trainers.

**Key design questions:**
- Dialogue defined in YAML (tree of nodes with player choices and NPC responses)
- New `talk <npc>` command to initiate dialogue
- Scripted behaviors: patrol paths, aggro-on-sight, flee-at-low-HP, call-for-help
- Keep it data-driven (YAML) vs introduce a lightweight scripting language?
- How dialogue interacts with combat state (can't talk to hostile mobs)

**Scope:** Large. New `DialogueSystem`, changes to `MobSystem` for richer AI, YAML schema extensions for dialogue trees and behavior scripts, new commands.

---

## 3. Spell & Skill System (Character Abilities)

**What:** Player abilities beyond basic attack — healing spells, offensive magic, buffs/debuffs, passive skills. Possibly tied to a class or skill-tree system.

**Why this is high-impact:** Combat is currently "kill <mob>" and wait. Abilities add tactical depth, resource management (mana/cooldowns), and build diversity. This is the single biggest gameplay depth multiplier.

**Key design questions:**
- Resource model: mana pool (regen like HP), cooldowns, or both?
- Skill acquisition: level-based unlocks, trainers (NPCs), skill points?
- Effect types: direct damage, heal, DoT, HoT, buff (stat boost), debuff (stat reduction), crowd control
- Status effect system needed as a foundation (timed buffs/debuffs on players and mobs)
- `cast <spell> [target]` command, `skills`/`spells` list command
- How spells interact with the tick system (DoTs tick every N engine ticks)

**Scope:** Very large. New `SpellSystem`, `StatusEffectSystem`, mana/cooldown tracking on `PlayerState`, spell definitions in YAML or config, changes to `CombatSystem` for ability-based combat rounds.

---

## 4. Player Classes & Races

**What:** Character archetypes (Warrior, Mage, Rogue, Cleric) with distinct stat distributions, abilities, and equipment restrictions. Optionally, races (Human, Elf, Dwarf) for flavor and minor stat bonuses.

**Why this is high-impact:** Classes create replayability and distinct play styles. They make grouping interesting (tank + healer + DPS). Combined with the spell/skill system, this defines what "your character" means.

**Key design questions:**
- Class selection during character creation (extend the login flow)
- Stat differences: base HP, mana, damage scaling, armor proficiency
- Class-specific abilities (requires spell/skill system first, or build together)
- Can players change class? Dual-class?
- Races: cosmetic + minor stat bonuses, or meaningful mechanical differences?
- Persisted on `PlayerRecord`

**Scope:** Medium-large. Extends character creation flow, `PlayerRecord`, `PlayerProgression`, and the combat/ability systems. Best paired with project #3.

---

## 5. Economy & Shops

**What:** A currency system (gold), NPC shopkeepers who buy/sell items, and player-to-player trading.

**Why this is high-impact:** An economy gives items tangible value, creates a gold sink/faucet loop, and provides a reason to grind beyond XP. Shops also serve as a natural "what items exist" discovery mechanism.

**Key design questions:**
- Gold on `PlayerRecord` (persisted) and on mob loot tables
- Shop NPCs defined in zone YAML with inventory and prices
- `buy <item>`, `sell <item>`, `list` commands when in a shop room
- Player trading: `trade <player>` with confirmation flow, or simple `give <item> <player>`?
- Economic balance: gold sinks (repairs? consumables? fast travel?) vs gold sources (mob drops, quest rewards, selling loot)

**Scope:** Medium. New `ShopSystem`, currency field on `PlayerRecord`, shop data in zone YAML, new commands. Relatively self-contained.

---

## 6. Group/Party System & Multi-Combatant Combat

**What:** Players can form groups, share XP, and fight together. Combat expands from 1v1 to N-players-vs-M-mobs with aggro/threat mechanics.

**Why this is high-impact:** Grouping is the social core of MUDs. It makes combat more interesting (tank holds aggro, healer keeps party alive, DPS burns targets), encourages cooperation, and enables harder content (group-required boss encounters).

**Key design questions:**
- `group <player>`, `ungroup`, `group list` commands
- XP splitting formula (equal? proportional to damage dealt?)
- Aggro/threat table per mob: who does it attack? `CombatSystem` rework needed
- Multi-target combat round: each mob picks a target from its threat table
- Group communication: `gtell` (group tell) command
- Party size limits?

**Scope:** Large. Significant `CombatSystem` rework (threat tables, multi-target), new group state tracking, XP distribution changes, new commands.

---

## 7. Online Creation (OLC) / World Builder

**What:** In-game staff commands to create and edit rooms, exits, mobs, items, and zones in real time — without editing YAML files and restarting.

**Why this is high-impact:** Dramatically accelerates world-building. Instead of editing YAML, restarting, and walking to the room, builders can create on the fly. This is the traditional MUD "builder" toolset.

**Key design questions:**
- `redit` (room edit), `medit` (mob edit), `iedit` (item edit), `zedit` (zone edit) command families
- Changes stored where? In-memory hot-reload + persist to YAML? Or directly to DB?
- Permissions: separate "builder" flag vs the existing `isStaff`?
- Undo/history for builder mistakes
- Validation (same rules as `WorldLoader`) enforced on save
- How to handle zone resets while a builder is editing

**Scope:** Very large. New command families, runtime world mutation (currently world is immutable after load), persistence of world changes, builder permissions.

---

## 8. Rich Web Client

**What:** Evolve the web client beyond a plain xterm.js terminal. Add a graphical map panel, character stats sidebar, inventory/equipment UI, clickable exits, and possibly a chat panel.

**Why this is high-impact:** Lowers the barrier to entry massively. New players can see a map, click to move, and understand the game without memorizing commands. The terminal stays available for power users.

**Key design questions:**
- Architecture: structured data channel (JSON over WebSocket) alongside the text stream, or parse ANSI output client-side?
- Tech stack: vanilla JS (current), or introduce React/Vue/Svelte?
- Map rendering: auto-map from room data sent by server, or hand-drawn zone maps?
- Mobile-friendly layout?
- New `OutboundEvent` variants for structured data (room info, inventory, combat status) vs keeping the engine protocol unchanged and parsing on the client

**Scope:** Large. Primarily frontend work, but may require new outbound event types or a parallel structured-data channel. Could be phased: map first, then inventory panel, then clickable commands.

---

## 9. Crafting System

**What:** Players gather materials and combine them into new items — weapons, armor, potions, food.

**Why this is high-impact:** Adds a non-combat progression path, creates demand for specific items/materials (economic depth), and gives players agency in itemization beyond what drops from mobs.

**Key design questions:**
- Recipe definitions in YAML (input items → output item)
- Crafting stations in specific rooms, or craft anywhere?
- Material items: new item type (non-equippable, used as crafting input)
- `craft <recipe>`, `recipes` commands
- Skill-based crafting (chance of failure, quality tiers)?
- Interaction with economy (crafted items sellable to shops)

**Scope:** Medium. New `CraftingSystem`, recipe YAML, material items in zone definitions, new commands. Self-contained but more interesting when combined with an economy.

---

## 10. Persistent World State & Events

**What:** World state that survives restarts — doors that stay locked, levers that stay pulled, boss kills tracked globally, server-wide events (invasions, seasonal content).

**Why this is high-impact:** Currently the world resets fully on zone lifespan expiry and on server restart. Persistent state creates a sense of consequence — the world changes because of player actions.

**Key design questions:**
- What persists: door states, container contents, placed items, triggered flags?
- Storage: extend zone YAML with runtime state overlay? Separate world-state persistence layer?
- Global event system: timed server events (weekend boss spawns, holiday content)
- `Scheduler` already exists — extend it for persistent/recurring world events
- Interaction with zone resets (some state survives reset, some doesn't)

**Scope:** Medium-large. New persistence layer for world state, engine changes for stateful room features (doors, containers, switches), event scheduling.

---

## Suggested Priority Tiers

### Tier 1 — Foundation for everything else
These enable the most downstream projects:

| # | Project | Unlocks |
|---|---------|---------|
| 2 | NPC Dialogue & Scripting | Quest givers, shopkeepers, trainers, world flavor |
| 3 | Spell & Skill System | Tactical combat, class differentiation, group roles |
| 5 | Economy & Shops | Item value, gold loop, crafting demand |

### Tier 2 — Major gameplay depth
Build on Tier 1 foundations:

| # | Project | Depends on |
|---|---------|------------|
| 1 | Quest System | NPC Dialogue (#2) |
| 4 | Classes & Races | Spells/Skills (#3) |
| 6 | Group Combat | Spells/Skills (#3) |

### Tier 3 — Polish & expansion
High value but less foundational:

| # | Project | Notes |
|---|---------|-------|
| 8 | Rich Web Client | Independent; can start anytime |
| 9 | Crafting | Benefits from Economy (#5) |
| 7 | OLC / World Builder | Independent; high effort |
| 10 | Persistent World State | Independent; medium effort |

---

## Dependency Graph

```
NPC Dialogue (#2) ──→ Quest System (#1)
                  ──→ Economy & Shops (#5) ──→ Crafting (#9)

Spells & Skills (#3) ──→ Classes & Races (#4)
                     ──→ Group Combat (#6)

Rich Web Client (#8)         ← independent
OLC / World Builder (#7)     ← independent
Persistent World State (#10) ← independent
```
