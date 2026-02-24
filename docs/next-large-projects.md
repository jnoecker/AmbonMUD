# AmbonMUD — Next Large Projects

## Current State (February 2026)

AmbonMUD has a mature infrastructure and solid gameplay foundation:

**Infrastructure:** Event-driven tick engine, dual transports (telnet with NAWS/TTYPE/GMCP negotiation, WebSocket with GMCP sidebar panels), event bus abstraction (Local/Redis/gRPC), write-behind coalescing persistence, selectable YAML or PostgreSQL backends, Redis L2 cache with HMAC-signed pub/sub envelopes, gRPC engine/gateway split, zone-based engine sharding with zone instancing and auto-scaling, Prometheus/Grafana observability, Snowflake session IDs, and a swarm load-testing module. 66+ test files.

**Gameplay:** 4 races, 4 classes, 6 primary attributes with mechanical effects, 12 class-specific abilities with mana/cooldowns, 1v1 combat with attribute-based damage scaling, items (equippable + consumable with charges), rich communication (say/tell/gossip/emote/whisper/shout/ooc/pose), individual mob respawn timers, mob wandering AI, HP + mana regen, zone resets, and XP/leveling across 50 levels. 8 zones including tutorial, hub, training, and exploration areas.

**What's missing is depth.** The engine can run the game, but the game needs more _game_. The projects below each add a substantial new dimension.

---

## 1. Status Effect & Buff/Debuff System

**What:** A timed-effect framework that applies modifiers to players and mobs — damage-over-time (DoT), heal-over-time (HoT), stat buffs, stat debuffs, crowd control (stun, root, slow), and immunity windows.

**Why now:** The ability system (12 spells, mana, cooldowns) is implemented but limited to instant effects (`DIRECT_DAMAGE`, `DIRECT_HEAL`). Status effects are the natural next step, and they unblock the majority of interesting spell design space: a Mage's Ignite that burns for 3 ticks, a Cleric's Shield of Faith that reduces incoming damage for 30 seconds, a Rogue's Poison that stacks.

**Key design decisions:**
- Status effects as a first-class data type: `StatusEffect(id, source, target, effectType, magnitude, durationMs, tickIntervalMs, stackable)`
- New `StatusEffectSystem` ticked alongside combat/regen — processes active effects, removes expired ones, broadcasts messages
- Effect types: `DOT`, `HOT`, `STAT_BUFF`, `STAT_DEBUFF`, `STUN` (skip combat round), `ROOT` (prevent movement), `SHIELD` (absorb N damage)
- Stacking rules: same-source refresh duration vs. independent stacks vs. max-stack cap
- Dispel mechanic: `dispel` command or specific counter-spells
- Visual: GMCP `Char.StatusEffects` package for web client display; `effects` command for telnet
- Config-driven: effect definitions in `application.yaml` alongside ability definitions, linked via ability `effect.type` extension

**Scope:** Large. New `StatusEffectSystem`, effect tracking on `PlayerState` and `MobState`, new ability effect types in config, GMCP package, `effects` command, persistence of long-duration buffs across login/logout. Touches `CombatSystem` (damage modifiers, stun handling), `MobSystem` (root prevents movement), `RegenSystem` (HoT integration).

**Depends on:** Nothing (builds on existing AbilitySystem).

---

## 2. NPC Dialogue & Behavior Trees

**What:** Transform mobs from silent wandering combat targets into interactive NPCs with dialogue trees, scripted behaviors, and trigger-based reactions.

**Why now:** NPCs are the prerequisite for quest givers, shopkeepers, trainers, and faction representatives. Without dialogue, the world feels like a combat arena rather than a living place. The mob system already has per-mob timers and the scheduler for delayed actions — behavior trees extend this naturally.

**Key design decisions:**
- Dialogue trees defined in zone YAML: nodes with NPC text, player choices, conditional branches (check quest state, check level, check class)
- `talk <npc>` command initiates dialogue; `1`/`2`/`3` selects options during a conversation
- Conversation state per-session (not persisted — resets on logout)
- Behavior types beyond wandering: `PATROL` (follow a fixed path), `AGGRO_ON_SIGHT` (attack players entering room), `FLEE_LOW_HP` (run when below threshold), `CALL_FOR_HELP` (alert nearby mobs), `STATIONARY` (never wander), `VENDOR` (open shop interface), `TRAINER` (teach abilities)
- Behavior tree YAML schema: conditions → actions, composable and data-driven
- Guard NPCs: attack players who enter without a flag/quest completion

**Scope:** Very large. New `DialogueSystem`, `BehaviorTree` engine, YAML schema extensions, changes to `MobSystem` for richer AI, new commands (`talk`, conversation number selection). Foundation for quests, shops, and trainers.

**Depends on:** Nothing.

---

## 3. Quest System

**What:** A data-driven quest framework with multi-step objectives, state tracking, prerequisite chains, and rewards.

**Why now:** Quests provide structured progression and narrative purpose. With abilities, classes, and training zones already in place, players need goals beyond "grind mobs for XP." The quest system gives the world meaning.

**Key design decisions:**
- Quest definitions in YAML (`quests/` directory or embedded in zone YAML)
- Objective types: `KILL_MOB(templateId, count)`, `VISIT_ROOM(roomId)`, `COLLECT_ITEM(itemKeyword, count)`, `TALK_TO_NPC(mobTemplateId)`, `REACH_LEVEL(level)`, `USE_ABILITY(abilityId, count)`
- Quest state on `PlayerRecord`: `Map<QuestId, QuestProgress>` — persisted through all three persistence layers
- Quest lifecycle: `AVAILABLE` → `ACTIVE` → `COMPLETED` (or `FAILED`); repeatable quests reset to `AVAILABLE`
- Prerequisites: quest B requires quest A completed; level requirements; class restrictions
- Rewards: XP, gold (if economy exists), items, unlocking new zones/areas, title grants
- `quest` / `journal` commands to view active/completed quests
- GMCP `Quest.List` and `Quest.Update` packages for web client
- Trigger hooks: `onMobKill`, `onRoomEnter`, `onItemPickup`, `onDialogueChoice` — quest system subscribes to engine events

**Scope:** Large. New `QuestSystem`, `PlayerRecord` extension (+ Flyway migration), YAML loader changes, new commands, GMCP packages. Benefits enormously from NPC Dialogue (#2) for quest givers.

**Depends on:** Ideally NPC Dialogue (#2) for quest-giver NPCs, but can be built standalone with room-triggered quests and item-based quest starts.

---

## 4. Economy, Currency & Shops

**What:** A gold currency system, NPC shopkeepers, loot-table gold drops, and player-to-player trading.

**Why now:** Items currently have no tangible value — you pick them up, equip them, and that's it. An economy creates demand, enables shops as discovery mechanisms, and provides a gold sink/faucet loop that gives grinding purpose beyond XP.

**Key design decisions:**
- `gold: Long` field on `PlayerRecord` (persisted) — new Flyway migration
- Mob loot tables: `goldMin` / `goldMax` per mob template (in zone YAML); gold drops on kill
- Shop NPCs: `vendor` behavior type (from #2), or simpler room-based shops with `ShopDefinition` in zone YAML
- Commands: `buy <item>`, `sell <item>`, `list` (when in a shop room/talking to vendor), `trade <player>` with confirmation flow
- Gold sinks: consumable item purchases, ability training fees, fast-travel costs, item repairs
- Gold sources: mob drops, quest rewards, selling loot to vendors (at reduced price)
- Economic balance: configurable buy/sell price ratios; vendor inventory refresh on zone reset
- GMCP: `Char.Vitals` extended with gold; shop UI in web client

**Scope:** Medium. New `ShopSystem`, `PlayerRecord.gold` field (+ migration), loot-table extension in zone YAML, new commands. Self-contained but richer with NPC Dialogue (#2).

**Depends on:** Nothing (can use room-based shops without NPC Dialogue).

---

## 5. Group/Party System & Multi-Combatant Combat

**What:** Players form groups, share XP, and fight together. Combat expands from 1v1 player-vs-mob to N-players-vs-M-mobs with aggro/threat mechanics.

**Why now:** The class system (tank, healer, DPS, utility) is implemented but meaningless without grouping. Warriors should hold aggro; Clerics should heal the party; Mages should deal area damage. Group combat makes classes matter and makes the game social.

**Key design decisions:**
- Commands: `group invite <player>`, `group accept`, `group leave`, `group list`, `group kick <player>`, `gtell <message>` (group chat)
- Group state: leader, member list, max size (configurable, default 5)
- Aggro/threat table per mob: tracks cumulative threat from each attacker (damage dealt + healing threat + taunt)
- Mob target selection: attacks highest-threat target; can switch targets mid-fight
- Multi-target combat rounds: each mob in a fight picks a target independently; each player contributes damage to one mob at a time
- XP distribution: split among group members in the room (configurable: equal split vs. proportional to damage)
- Loot distribution: round-robin, random, or leader-assigns
- Cross-instance grouping: group members on different zone instances? (Probably not — require same instance)
- `CombatSystem` rework: `fightsByPlayer` becomes N:M instead of 1:1; threat tables replace simple fight pairs

**Scope:** Very large. Major `CombatSystem` rework (threat tables, multi-target, area abilities), new group state tracking, XP distribution overhaul, new commands, GMCP `Group.*` packages.

**Depends on:** Nothing, but significantly enhanced by Status Effects (#1) for area spells and group utility.

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

## 11. Achievement & Title System

**What:** Tracked accomplishments (kill 100 goblins, visit every room in a zone, reach level 25, complete all tutorial quests) that award titles, cosmetic rewards, and minor stat bonuses.

**Key design decisions:**
- Achievement definitions in YAML: `id`, `displayName`, `description`, `criteria` (same types as quest objectives), `reward` (title, XP, item, gold, stat bonus)
- Tracking on `PlayerRecord`: `achievements: Set<AchievementId>` (completed) + progress counters for in-progress achievements
- Title system: players choose a display title from earned titles; shown in `who` list and `look` at player; stored on `PlayerRecord`
- Commands: `achievements` (list all with progress), `title <titleId>` (set display title), `title clear`
- Categories: Combat, Exploration, Social, Crafting, Class-specific
- Hidden achievements: not shown until completed (discovery reward)
- Server-wide first: "First player to kill the Dragon" — unique title, announced server-wide
- GMCP: `Char.Achievements` package for web client panel

**Scope:** Medium. New `AchievementSystem`, `PlayerRecord` extension (+ migration), YAML definitions, new commands, GMCP package. Self-contained.

**Depends on:** Nothing. Richer with Quest System (#3) for quest-based achievements.

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

## 14. Comprehensive Admin Dashboard

**What:** A web-based admin dashboard for server management — player lookup, live metrics visualization, world state inspection, event log viewer, and operational controls — separate from the in-game staff commands.

**Key design decisions:**
- Separate web app served on a configurable admin port (e.g., 9091), protected by authentication
- Panels:
  - **Player management:** search by name, view stats/inventory/quest state, grant staff/builder, ban/unban, edit attributes
  - **Live metrics:** embedded Grafana dashboards or custom charts (session count, tick latency, combat activity, shard health)
  - **World inspector:** browse zones/rooms/mobs/items in a tree view; see current player positions on a zone map
  - **Event log:** real-time feed of login/logout, combat kills, level-ups, admin actions, errors
  - **Shard health:** per-engine status (zones owned, player count, tick budget usage, instance count) for sharded deployments
  - **Config editor:** view and hot-reload select config values without restart
- Tech stack: Ktor serving a small SPA (could be React, Vue, or server-rendered HTML)
- API layer: REST endpoints backed by engine queries (read-only for most; write for admin actions)
- Authentication: simple password or token-based; configurable in `application.yaml`

**Scope:** Large. New web application, REST API layer, frontend SPA, authentication. Primarily additive — reads from existing metrics, registries, and persistence. No engine changes needed.

**Depends on:** Nothing.

---

## Suggested Priority & Sequencing

### Phase A — Deepen the combat/ability system
These build on the existing ability infrastructure and make combat tactically interesting:

| # | Project | Effort | Unlocks |
|---|---------|--------|---------|
| 1 | Status Effects & Buffs | Large | Rich spell design, interesting boss fights, group utility |
| 5 | Group/Party Combat | Very large | Multiplayer cooperation, class roles matter, harder content |

### Phase B — Build a living world
These transform the world from a combat arena into a narrative experience:

| # | Project | Effort | Unlocks |
|---|---------|--------|---------|
| 2 | NPC Dialogue & Behaviors | Very large | Quest givers, shops, trainers, world flavor |
| 3 | Quest System | Large | Structured progression, narrative arcs, achievement triggers |
| 4 | Economy & Shops | Medium | Item value, gold loop, crafting demand |

### Phase C — Endgame & replayability
These create reasons to keep playing after reaching max level:

| # | Project | Effort | Unlocks |
|---|---------|--------|---------|
| 6 | Procedural Dungeons | Very large | Infinite replayable content, group challenges |
| 7 | Crafting & Gathering | Medium-large | Non-combat progression, economic depth |
| 11 | Achievements & Titles | Medium | Collection goals, cosmetic rewards |

### Phase D — Community & polish
These make the game social and accessible:

| # | Project | Effort | Unlocks |
|---|---------|--------|---------|
| 10 | Auto-Map & Enhanced Web Client | Medium | Player accessibility, modern UI |
| 13 | Social Systems (Guilds/Friends/Mail) | Large | Community building, offline interaction |
| 12 | Player Housing | Medium-large | Personal investment, long-term retention |

### Phase E — Builder & operator tooling
These accelerate content creation and operations:

| # | Project | Effort | Unlocks |
|---|---------|--------|---------|
| 8 | OLC / World Builder | Very large | Rapid content creation, builder community |
| 9 | Persistent World State & Events | Medium-large | Dynamic world, seasonal content |
| 14 | Admin Dashboard | Large | Operational visibility, player support |

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
