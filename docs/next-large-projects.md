# AmbonMUD — Next Large Projects

## Current State (February 2026)

AmbonMUD has a mature infrastructure and solid gameplay foundation:

**Infrastructure:** Event-driven tick engine, dual transports (telnet with NAWS/TTYPE/GMCP negotiation, WebSocket with GMCP sidebar panels), event bus abstraction (Local/Redis/gRPC), write-behind coalescing persistence, selectable YAML or PostgreSQL backends, Redis L2 cache with HMAC-signed pub/sub envelopes, gRPC engine/gateway split, zone-based engine sharding with zone instancing and auto-scaling, Prometheus/Grafana observability, Snowflake session IDs, and a swarm load-testing module. 66+ test files.

**Gameplay:** 4 races, 4 classes, 6 primary attributes with mechanical effects, 12 class-specific abilities with mana/cooldowns, status effects (DoT, HoT, STAT_BUFF/DEBUFF, STUN, ROOT, SHIELD with stacking rules), group/party system with N:M multi-combatant combat and threat tables, items (equippable + consumable with charges), gold currency with mob drops and in-room shops (buy/sell/list), rich communication (say/tell/gossip/emote/whisper/shout/ooc/pose), individual mob respawn timers, NPC dialogue trees with conditional branches, behavior tree AI (aggro guards, patrol sentries, cowardly mobs, wandering aggressors), mob-initiated combat, HP + mana regen, zone resets, quest system with multi-step objectives and rewards, achievements with titles and cosmetic rewards, and XP/leveling across 50 levels. 8 zones including tutorial, hub, training, and exploration areas.

**Recent completions (Feb 2026):** Group/party system with threat mechanics (#5), quest system with objective tracking (#3), achievement & title system (#11), web-based admin dashboard (#14).

**What's missing is depth and breadth.** The engine can run complex gameplay, but the game needs more replayable content and world interactivity. The projects below each add a substantial new dimension.

---

## 1. Status Effect & Buff/Debuff System — IMPLEMENTED

**Status:** Fully implemented. `StatusEffectSystem` ticked in the engine loop. Effect types: `DOT`, `HOT`, `STAT_BUFF`, `STAT_DEBUFF`, `STUN` (skips combat round), `ROOT` (prevents movement), `SHIELD` (absorbs incoming damage). Configurable stacking rules (`REFRESH`, `STACK`, `MAX_STACKS`). Player and mob targets. `CombatSystem` applies stat mods and stun handling; `MobSystem` respects ROOT. `effects`/`buffs`/`debuffs` command for telnet. `Char.StatusEffects` GMCP package for web client. Effect definitions are config-driven in `application.yaml` alongside ability definitions.

**Remaining opportunities:**
- Dispel mechanic (`dispel` command or counter-spell ability type)
- Immunity/resistance windows after crowd control expires
- Persistence of long-duration buffs across login/logout

---

## 2. NPC Dialogue & Behavior Trees — IMPLEMENTED

**Status:** Fully implemented in two phases.

**Phase 1 — NPC Dialogue (complete):** `DialogueSystem` with YAML-defined dialogue trees. `talk <npc>` command initiates conversation; `1`/`2`/`3` selects options. Conditional branches check level, class, and flags. Conversation state per-session (resets on logout). GMCP `Dialogue.*` packages for web client.

**Phase 2 — Behavior Trees (complete):** `BehaviorTreeSystem` with composable BT nodes. Predefined YAML templates: `aggro_guard`, `stationary_aggro`, `patrol`, `patrol_aggro`, `wander`, `wander_aggro`, `coward`. Node types: `SequenceNode`, `SelectorNode`, `InverterNode`, `CooldownNode` (composites/decorators); `IsPlayerInRoom`, `IsInCombat`, `IsHpBelow` (conditions); `AggroAction`, `FleeAction`, `WanderAction`, `PatrolAction`, `SayAction`, `StationaryAction` (actions). Mob-initiated combat via `CombatSystem.startMobCombat()`. Flee mechanic via `CombatSystem.fleeMob()` (disengage + move). Per-mob memory for patrol indices and cooldown timestamps. `MobSystem` guards BT mobs from random wandering. Time-gated execution (2-5s ticks, configurable).

**Remaining opportunities:**
- `CALL_FOR_HELP` behavior (alert nearby mobs to join combat)
- `VENDOR` and `TRAINER` behavior types (open shop/training interface automatically)
- Guard NPCs gated by quest flags or faction standing
- Inline composable BT YAML (full tree definition instead of templates)
- Conditional aggro (only attack certain classes/levels)

**Depends on:** Nothing.

---

## 3. Quest System — IMPLEMENTED

**Status:** Phase 1 complete. `QuestSystem` ticked in the engine loop. Quest definitions loaded from YAML. Quest state on `PlayerRecord`: `Map<QuestId, QuestProgress>` persisted through all three persistence layers. Quest lifecycle: `AVAILABLE` → `ACTIVE` → `COMPLETED`; repeatable quests reset to `AVAILABLE`. Prerequisites: quest B requires quest A completed; level requirements; class restrictions. Objective types: `KILL_MOB(templateId, count)`, `VISIT_ROOM(roomId)`, `COLLECT_ITEM(itemKeyword, count)`, `TALK_TO_NPC(mobTemplateId)`, `REACH_LEVEL(level)`, `USE_ABILITY(abilityId, count)`. Rewards: XP, gold, items. Commands: `quest accept <id>`, `quest list`, `quest journal`, `quest complete <id>` (when conditions met). GMCP `Quest.*` packages for web client. Trigger hooks: quest progress tracked on mob kills, room visits, item collection, dialogue choices.

**Remaining opportunities:**
- `CRAFT_ITEM` objective type for crafting system integration (#7)
- Quest chains with branching paths and alternate endings
- Optional bonus objectives for extra rewards
- Time-limited quests with failure states
- Dynamic quest scaling (difficulty based on party level)
- Quest-givers that move/wander (currently static)

**Depends on:** Nothing. Enhanced by NPC Dialogue (#2) for interactive quest-giver conversations and Crafting (#7) for craft objectives.

---

## 4. Economy, Currency & Shops — IMPLEMENTED (core)

**Status:** Core economy is implemented. `PlayerRecord.gold` is persisted through all backends. Mob gold drops use `goldMin`/`goldMax` from the tier formula (per-mob override supported in zone YAML). Items have a `basePrice` field; shops are defined per-zone in the `shops` YAML map. Commands `buy`/`sell`/`list`/`gold` are live. Buy/sell price multipliers are configurable via `engine.economy.*`.

**Remaining opportunities:**
- Player-to-player trading (`trade <player>` with confirmation flow)
- Gold sinks beyond shop purchases (ability training fees, fast-travel costs, item repair)
- Vendor inventory refresh on zone reset (currently static)
- GMCP `Char.Vitals` extension with gold balance for web client sidebar
- Shop UI panel in the web client (currently only the in-game `list` command)

---

## 5. Group/Party System & Multi-Combatant Combat — IMPLEMENTED

**Status:** Fully implemented. Players form groups via `group invite <player>`, `group accept`, `group leave`, `group list`, `group kick <player>`. Group state: leader, member list, max size (configurable, default 5). Aggro/threat table per mob: tracks cumulative threat from each attacker (damage dealt + threat from healing + taunt mechanics). Mob target selection: attacks highest-threat target in the threat table; can switch targets mid-fight. N:M multi-target combat: each mob picks its own target; each player contributes damage to one mob at a time. Combat rounds run in parallel for independent mob-vs-player matchups. XP distribution: configurable split among group members in the room (equal split or proportional to damage). Loot distribution: configurable per-drop (round-robin, random, or leader-assigns). Cross-instance grouping: group members must be on the same zone instance (enforced). `CombatSystem` fully reworked: `fightsByPlayer` now maps each player to a list of mobs; threat tables replace 1:1 fight pairs. Commands: `group *`, `gtell <message>` (group chat), `group disband`. GMCP `Group.*` packages for web client group management panel.

**Remaining opportunities:**
- Threat scaling based on class (tanks generate more threat, healers less)
- Rend/bleed abilities that scale with group DPS
- Area-of-effect abilities that hit multiple mobs (requires Status Effects #1)
- Taunts with cooldowns and threat multipliers
- Cross-shard group support (groups spanning multiple engines in sharded deployments)
- Raid-size groups (20+ players) with role-based targeting

**Depends on:** Nothing. Enhanced by Status Effects (#1) for area effects and group utility spells.

---

## 6. Procedural Dungeon Instances

**What:** On-demand, procedurally generated dungeon zones that a player or group enters, completes, and which are destroyed afterward. Each run is unique — randomized room layouts, mob placements, item drops, and a boss encounter at the end.

**Why now:** The zone instancing infrastructure already exists (`InstanceSelector`, `ThresholdInstanceScaler`). Procedural dungeons extend this from "multiple copies of the same zone" to "unique generated zones per party." This creates replayability without hand-crafting hundreds of rooms.

**Key design decisions:**
- Dungeon templates: define room archetypes, mob pools, item pools, layout rules (linear, branching, hub-and-spoke) in YAML
- Generator: `DungeonGenerator` produces a `World` fragment (rooms + mobs + items) from a template + RNG seed
- Entry mechanic: portal rooms in the overworld; `enter <dungeon>` command; creates a private zone instance
- Party scoping: only the entering group can see/access the instance; instance destroyed when all players leave or time expires
- Difficulty scaling: mob levels and counts scale to party size and average level
- Boss encounter: final room has a boss mob with special abilities (requires Status Effects #1)
- Loot tables: dungeon-specific drops with rarity tiers; boss drops are guaranteed
- Leaderboard: fastest clear time, tracked per-dungeon-template

**Scope:** Very large. New `DungeonGenerator`, dungeon template YAML schema, runtime zone creation/destruction, private instance management, difficulty scaling logic. Benefits from Group Combat (#5) and Status Effects (#1).

**Depends on:** Zone instancing (already implemented). Enhanced by Group Combat (#5).

---

## 7. Crafting & Gathering System

**What:** Players gather raw materials from the world and combine them into equipment, consumables, and utility items via recipes. Introduces non-combat progression and creates economic depth.

**Key design decisions:**
- Gathering nodes in rooms: `mine`, `harvest`, `fish`, `chop` commands; nodes respawn on per-node timers (like mob respawns)
- Material items: new item category (`material: true`), non-equippable, used as crafting inputs
- Recipe definitions in YAML: `inputs: [{keyword: "iron_ore", count: 3}, {keyword: "coal", count: 1}]`, `output: "iron_sword"`, optional `station: "forge"` (requires being in a room with a forge)
- Crafting skill levels per player: `crafting.mining`, `crafting.smithing`, `crafting.alchemy`, etc. — persisted on `PlayerRecord`
- Success chance scales with skill level; critical successes produce higher-quality variants
- Commands: `craft <recipe>`, `recipes`, `gather`, `mine`, `harvest`
- Quality tiers: Normal → Fine → Superior → Masterwork — affect item stats
- Interaction with economy: crafted items sellable; materials tradeable

**Scope:** Medium-large. New `CraftingSystem`, `GatheringNode` world data, recipe YAML, skill tracking on `PlayerRecord` (+ migration), new commands. Self-contained but richer with Economy (#4).

**Depends on:** Nothing. Enhanced by Economy (#4).

---

## 8. Online Creation (OLC) / World Builder

**What:** In-game staff commands to create, edit, and delete rooms, exits, mobs, items, and zones in real time — no YAML editing, no restart.

**Why now:** The world currently has 8 zones but needs many more to sustain player interest. Hand-editing YAML, restarting, and walking to the room is slow. OLC lets builders iterate in seconds. This is the traditional MUD "builder" toolset and dramatically accelerates content creation.

**Key design decisions:**
- Command families: `redit` (room edit), `medit` (mob edit), `iedit` (item edit), `zedit` (zone edit)
  - `redit create <id>` / `redit title <text>` / `redit desc <text>` / `redit exit <dir> <target>` / `redit remove exit <dir>`
  - `medit create <template>` / `medit name <text>` / `medit tier <weak|standard|elite|boss>` / `medit room <roomId>`
  - `iedit create <keyword>` / `iedit name <text>` / `iedit slot <slot>` / `iedit damage <n>`
  - `zedit create <name>` / `zedit lifespan <minutes>` / `zedit startroom <roomId>`
- Permission model: new `isBuilder` flag on `PlayerRecord` (separate from `isStaff` — builders can create content but not kick/smite/shutdown)
- Persistence: changes written to a `world-overrides/` directory as YAML patches; loaded on top of base world files
- Validation: same rules as `WorldLoader` — enforced on every save; rejects broken exits, missing rooms, etc.
- Undo: `redit undo` reverts last change per-room (single-level undo stack)
- Zone resets: builder edits survive resets; builder can manually trigger `zedit reset`
- Runtime world mutation: `World` becomes mutable (or replaced atomically on each builder save)

**Scope:** Very large. New command families, mutable world model, override persistence layer, builder permissions, validation on write. The biggest single project on this list.

**Depends on:** Nothing.

---

## 9. Persistent World State & Server Events

**What:** World state that survives restarts and zone resets — doors, levers, containers, global flags, and server-wide timed events (invasions, seasonal content, world bosses).

**Key design decisions:**
- Stateful room features: doors (locked/unlocked/open/closed), containers (items inside, locked), levers/switches (toggle state), signs (readable text)
- Door keys: specific items unlock specific doors; key consumed or reusable (configurable per door)
- Persistence: `WorldState` stored in Postgres or YAML overlay; loaded on startup; updated on state change
- Zone reset interaction: configurable per-feature — some state resets with the zone (containers refill), some persists (quest-unlocked doors stay open)
- Server events: `ServerEventScheduler` runs timed world events defined in YAML
  - Example: "Every Saturday 8 PM, spawn a world boss in `ambon_hub:plaza` for 2 hours"
  - Example: "During December, replace zone descriptions with winter-themed variants"
- Global flags: `worldFlags: Map<String, Boolean>` — set by quest completions, boss kills, or admin commands; checked by dialogue conditions and room descriptions
- Commands: `open <door>`, `close <door>`, `unlock <door>`, `lock <door>`, `search <container>`, `put <item> <container>`

**Scope:** Medium-large. New `WorldStateSystem`, room feature definitions in zone YAML, persistence layer, event scheduler, new commands. Foundation for richer world design.

**Depends on:** Nothing.

---

## 10. Auto-Map & Enhanced Web Client

**What:** A real-time auto-map in the web client that renders the player's explored world as a navigable graph, plus enhanced UI panels for combat, abilities, and social features.

**Why now:** The web client already has GMCP-driven sidebar panels (character, room, inventory, equipment, skills, room players) with clickable exits. The next step is a spatial map — the single most requested feature in browser-based MUD clients.

**Key design decisions:**
- Auto-map algorithm: as the player moves, the client builds a graph of visited rooms and their exits; render as a 2D grid or force-directed graph
- GMCP data: `Room.Info` already provides room ID, title, zone, and exits — sufficient for client-side map building
- Map rendering: HTML5 Canvas or SVG; current room highlighted; clickable rooms send movement commands
- Persistent map state: store explored rooms in `localStorage` per character
- Fog of war: unexplored rooms shown as grey placeholders based on known exits
- Mini-map vs. full-map: collapsible mini-map always visible; full-map in a modal/panel
- Combat UI: ability buttons with cooldown timers, target selection, HP/mana bars for target
- Chat panel: tabbed channels (say, gossip, tell, group, ooc) with message history
- Mobile layout: responsive design; swipe for map, tap for exits, collapsible panels

**Scope:** Medium (mostly frontend). No engine changes required — all data flows through existing GMCP packages. Could add a few new GMCP packages (`Map.Explored` for server-side map persistence across devices).

**Depends on:** Nothing (GMCP infrastructure exists).

---

## 11. Achievement & Title System — IMPLEMENTED

**Status:** Fully implemented. Achievement definitions in YAML with `id`, `displayName`, `description`, `criteria` (same types as quest objectives: `KILL_MOB`, `VISIT_ROOM`, `COLLECT_ITEM`, `REACH_LEVEL`, `USE_ABILITY`, `COMPLETE_QUEST`), and `reward` (title, XP, item, gold). Tracking on `PlayerRecord`: `achievements: Set<AchievementId>` (completed) + progress counters for in-progress achievements persisted through all three persistence layers. Title system: players choose a display title from earned titles via `title <titleId>` or `title clear`; shown in `who` list and `look` at player. Commands: `achievements` (list all with progress and descriptions), `achievement info <id>`, `title <titleId>`, `title clear`. Categories: Combat, Exploration, Social, Class-specific. Hidden achievements: not shown until completed (discovery reward). Server-wide first: "First player to X" achievements auto-announce when completed. GMCP `Char.Achievements` package for web client achievements panel with progress bars.

**Remaining opportunities:**
- Stat bonuses (+1% crit, +5 health per achievement) — currently titles are cosmetic only
- Leaderboards for specific achievements (fastest kill time, most explored rooms)
- Achievement tiers (bronze/silver/gold) with escalating difficulty
- Community events tied to achievements (e.g., "10% of players reach level 30" triggers a server event)

**Depends on:** Nothing. Synergizes well with Quest System (#3) for quest-based achievements.

---

## 12. Player Housing & Persistent Spaces

**What:** Personal rooms or houses that players can own, furnish, and invite others to visit. A persistent personal space in the world.

**Key design decisions:**
- Housing zone: a dedicated zone (`player_housing`) with dynamically created rooms per player
- Acquisition: purchased with gold (requires Economy #4), or granted as quest reward, or a fixed number of free rooms
- Customization: `house title <text>`, `house desc <text>`, `house lock` / `house unlock`, `house invite <player>`
- Furniture items: decorative items placed in housing rooms; new `furniture` item type
- Storage: items stored in housing rooms persist across zone resets and server restarts (uses WorldState persistence from #9 or a dedicated housing persistence layer)
- Access control: owner always enters; invited players can enter; locked houses block strangers
- Address system: `visit <player>` command teleports to a player's house entrance
- Eviction: houses of inactive players (no login for N days) are recycled

**Scope:** Medium-large. Dynamic room creation, housing persistence, access control, new commands. Benefits from Economy (#4) for purchasing and Persistent World State (#9) for item storage.

**Depends on:** Economy (#4) for gold-based purchasing. Enhanced by Persistent World State (#9).

---

## 13. Social Systems: Guilds, Friends & Mail

**What:** Persistent social structures — guilds/clans with ranks and shared resources, a friends list with online status, and an in-game mail system for offline messaging.

**Key design decisions:**
- **Guilds:** `guild create <name>`, `guild invite <player>`, `guild promote/demote <player>`, `guild chat <msg>`, `guild leave`, `guild disband`
  - Guild data: name, leader, officer list, member list, message of the day, guild bank (gold + items)
  - Persisted in Postgres (new `guilds` table + `guild_members` table) or YAML
  - Cross-engine: guild chat routed via `InterEngineBus` in sharded deployments
- **Friends:** `friend add <player>`, `friend remove <player>`, `friend list` (shows online/offline status)
  - Stored on `PlayerRecord`: `friends: Set<String>` (player names)
  - Online status: check `PlayerRegistry` (local) or `PlayerLocationIndex` (sharded)
- **Mail:** `mail send <player> <message>`, `mail read`, `mail delete <id>`
  - Stored in Postgres (`mail` table: sender, recipient, subject, body, read flag, timestamp)
  - Notification on login: "You have 3 unread messages."
  - Optional item attachments (mail an item to an offline player)

**Scope:** Large. Three subsystems with persistence, cross-engine routing, new commands, GMCP packages. Can be phased: friends first (small), then mail (medium), then guilds (large).

**Depends on:** Nothing. Guilds enhanced by Economy (#4) for guild bank.

---

## 14. Comprehensive Admin Dashboard — PARTIALLY IMPLEMENTED

**Status:** Basic HTTP server and dashboard structure implemented. Served on a configurable admin port (default 9091), opt-in via `ambonMUD.admin.enabled: true` in config. Current features:
- **Player lookup:** search by name, view basic stats
- **Server metrics:** session count, player count, zone distribution
- **Basic admin controls:** optional early implementation; can ban/unban players, grant roles
- **Live metrics:** basic tick health and event counts
- **Tech stack:** Ktor HTTP server with server-rendered HTML (not a full SPA)

**Implemented panels:**
- Dashboard home with server status and quick stats
- Player browser and lookup
- Basic metrics visualization

**Remaining opportunities:**
- **Live metrics visualization:** embed Grafana dashboards or add custom charts (session count, tick latency, combat activity)
- **Advanced world inspector:** browse zones/rooms/mobs/items in a tree view; see current player positions on a zone map
- **Event log viewer:** real-time feed of login/logout, combat kills, level-ups, admin actions, errors
- **Shard health page:** per-engine status (zones owned, player count, tick budget usage, instance count) for sharded deployments
- **Config editor:** view and hot-reload select config values without restart
- **Upgrade to SPA:** migrate to React/Vue for better interactivity and offline-capable UI
- **Advanced player management:** view/edit quest state, inventory, achievement state, modify attributes
- **Audit log:** persistent record of all admin actions with timestamps and user attribution

**Depends on:** Nothing. Primarily additive — reads from existing metrics, registries, and persistence.

---

## Suggested Priority & Sequencing

### Phase A — Deepen the combat/ability system
✅ **COMPLETE** — All projects in this phase are implemented.

| # | Project | Status | Unlocks |
|---|---------|--------|---------|
| 1 | Status Effects & Buffs | ✅ Done | Dispel mechanic and CC immunity remain |
| 5 | Group/Party Combat | ✅ Done | Multiplayer cooperation, class roles matter, harder content |

### Phase B — Build a living world
✅ **COMPLETE** — All projects in this phase are implemented.

| # | Project | Status | Unlocks |
|---|---------|--------|---------|
| 2 | NPC Dialogue & Behaviors | ✅ Done | CALL_FOR_HELP, VENDOR/TRAINER behaviors, quest-gated aggro remain |
| 3 | Quest System | ✅ Done (Phase 1) | Structured progression, narrative arcs, achievement triggers |
| 4 | Economy & Shops | ✅ Done (core) | Player-to-player trading, gold sinks, GMCP gold panel remain |

### Phase C — Endgame & replayability
⏳ **IN PROGRESS** — Ready for development.

| # | Project | Status | Effort | Unlocks |
|---|---------|--------|--------|---------|
| 6 | Procedural Dungeons | ⏳ Pending | Very large | Infinite replayable content, group challenges |
| 7 | Crafting & Gathering | ⏳ Pending | Medium-large | Non-combat progression, economic depth |
| 11 | Achievements & Titles | ✅ Done | Medium | Collection goals, cosmetic rewards ✓ |

### Phase D — Community & polish
⏳ **IN PROGRESS** — Ready for development.

| # | Project | Status | Effort | Unlocks |
|---|---------|--------|--------|---------|
| 10 | Auto-Map & Enhanced Web Client | ⏳ Pending | Medium | Player accessibility, modern UI |
| 13 | Social Systems (Guilds/Friends/Mail) | ⏳ Pending | Large | Community building, offline interaction |
| 12 | Player Housing | ⏳ Pending | Medium-large | Personal investment, long-term retention |

### Phase E — Builder & operator tooling
⏳ **IN PROGRESS** — Ready for development.

| # | Project | Status | Effort | Unlocks |
|---|---------|--------|--------|---------|
| 8 | OLC / World Builder | ⏳ Pending | Very large | Rapid content creation, builder community |
| 9 | Persistent World State & Events | ⏳ Pending | Medium-large | Dynamic world, seasonal content |
| 14 | Admin Dashboard | ✅ Partial | Large | Operational visibility, player support (basic version live) |

---

## Dependency Graph

```
Status Effects (#1) ──→ Procedural Dungeons (#6) (boss mechanics)
                    ──→ Group Combat (#5) (area effects, group utility)

NPC Dialogue (#2) ──→ Quest System (#3) (quest givers)
                  ──→ Economy & Shops (#4) (vendor NPCs)

Economy (#4) ──→ Crafting (#7) (sell crafted items)
             ──→ Player Housing (#12) (purchase houses)
             ──→ Social: Guilds (#13) (guild bank)

Quest System (#3) ──→ Achievements (#11) (quest-based achievements)

Persistent World State (#9) ──→ Player Housing (#12) (item storage)

Everything else is independent and can start at any time.
```
