# Eryndal: A Base World Plan

This document is the design plan for **Eryndal**, a new base world for AmbonMUD that replaces
the current Ambon-lore-specific zones with a self-contained, D&D-inspired, Diku-style world.
The goal is a showcase demo: every major engine feature is touched by at least one zone, the
world is fully playable at levels 1–10, and it is small and approachable for first-time visitors.

Ambon-specific lore (ambon_hub, noecker_resume) will migrate to a separate lore repository so
the engine itself ships a neutral, genre-standard world.

---

## World Overview

**Name:** The Realm of Eryndal  
**Hub City:** Thornhaven — a frontier city at the crossroads of civilization and wilderness  
**Tone:** Classic heroic fantasy. Think AD&D second edition: taverns, dungeons, merchants, ancient
curses, and the thrill of going from zero to legend. Ambon's Surreal Gentle Magic aesthetic still
applies to art and UI; the lore is just reset to genre convention.

**Player Fantasy:** You arrive in Thornhaven as a nobody. Over ten levels you carve out a name,
earn a guild, find companions, and ultimately face the ancient evil stirring beneath the Celestial
Peak — all in under two hours for a first playthrough.

---

## Classes (5 total)

All classes are config-driven (no code changes required). The four existing classes are kept and
**Ranger** is added as a fifth.

| Key      | Display Name | Primary Stat | HP/lvl | Mana/lvl | Identity |
|----------|-------------|-------------|--------|---------|---------|
| WARRIOR  | Warrior     | STR         | 8      | 4       | Front-line tank; highest HP, taunt, defensive abilities |
| MAGE     | Mage        | INT         | 4      | 16      | Arcane nuker; fragile, powerful AoE and burst damage |
| CLERIC   | Cleric      | WIS         | 6      | 12      | Divine healer; group heals, buffs, turn undead |
| ROGUE    | Rogue       | DEX         | 5      | 8       | Stealth striker; poisons, backstab, mobility abilities |
| RANGER   | Ranger      | DEX         | 6      | 8       | Nature hybrid; ranged damage, animal companion, tracking |

**Starting rooms** need one entry per class in `classStartRooms` config, all pointing to rooms
inside `thornhaven_city` (the new hub). Ranger's start room is the `ranger_lodge` room.

**Trainer locations:** All five class trainers live in a dedicated **Trainers' Hall** wing of
Thornhaven City. The trainer YAML blocks are already working in the engine; we just need new NPC
definitions and rooms.

---

## Races (6 total)

The four existing races are kept; **Half-Orc** and **Gnome** are added.

| Key       | Display Name | Flavor           | Stat Mods (net 0) |
|-----------|-------------|-----------------|------------------|
| HUMAN     | Human       | Versatile        | STR+1, CHA+1 |
| ELF       | Elf         | Magical, graceful| DEX+2, INT+1, STR-1, CON-2 |
| DWARF     | Dwarf       | Tough, stubborn  | STR+1, CON+2, WIS+1, DEX-1, CHA-2 |
| HALFLING  | Halfling    | Quick, charming  | DEX+2, WIS+1, CHA+1, STR-2, CON-1 |
| HALF_ORC  | Half-Orc    | Fierce, resilient| STR+3, CON+2, CHA-2, INT-1, WIS-1 |
| GNOME     | Gnome       | Clever, tiny     | INT+2, DEX+1, WIS+1, STR-2, CON-1 |

Half-Orc and Gnome are config-only additions (no code changes).

---

## Zone Overview (20 Zones)

Zones are divided into five tiers by target level range. The hub is safe at all levels.

### Tier 0 — The Hub (safe, all levels)

#### 1. `thornhaven_city`
**Replaces:** `ambon_hub`, `crafting_workshop`  
**Rooms:** ~40  
**Purpose:** Central hub; every non-combat engine feature lives here.

Key areas and features showcased:
- **Market Square** — general shop (gear/potions), auction house NPC
- **The Tarnished Flagon Inn** — Innkeeper Mira, recall point, set_recall dialogue action
- **Trainers' Hall** — five class trainers (train list / train learn), skill points
- **Thornhaven Bank** — Bank NPC (deposit/withdraw gold and items)
- **Guild Registry** — Guild creation/management NPC, guild roster board
- **Crafting Quarter** — forge, alchemist bench, tailor's table (all crafting station types); recipe merchants
- **Arena District** — PvP dueling zone, scoreboard NPC (leaderboards display), spectator seats
- **Dungeon Finder** — NPC "Old Grimly" who gives lore on instanced dungeons and hands out the entrance key item
- **Post Office** — Mail NPC (list/read/send mail, full dialogue)
- **Trophy Hall** — Hall of Fame display tied to leaderboard data; achievement plaques

The starting hub should feel like a living town: day/night cycle affects which NPCs are present
(innkeeper visible at night, market vendors during day via `active_hours` in mob definitions when
that feature exists, or approximated with descriptive text).

#### 2. `thornhaven_sewers`
**New zone**  
**Level range:** 5–8  
**Rooms:** ~15  
**Purpose:** Secret area beneath the city accessible only once the player has a sewer key (reward from a rogue quest).

Features:
- Hidden doors, container chests with rare items
- Rogue-class flavor; mob `sewer_rat`, `deserter_rogue`, `bloated_toad`
- One shop NPC ("the Fence") for selling stolen goods at a premium
- Connection back to thornhaven_city via `thornhaven_city:sewer_grate`

---

### Tier 1 — Tutorial & First Steps (levels 1–3)

#### 3. `crossroads_path`
**Replaces:** `tutorial_glade` (concept)  
**Rooms:** ~12  
**Purpose:** The first zone a new character enters. A gently guided tutorial experience.

Layout: A winding road from the world-gate into Thornhaven. Characters start at `crossroads_path:world_gate` and walk toward town, with quest-giving NPCs placed at natural decision points.

Tutorial features:
- **Tutorial quest chain "A Traveler's Welcome"** — four steps: (1) read the signpost, (2) speak to the waypost guard, (3) fight a `stray_dog` (first combat), (4) arrive at Thornhaven and set recall
- Rooms have rich atmospheric descriptions showing the day/night sky, weather effects
- Signpost with world lore blurb (introduces the player to Eryndal's setting without walls of text)
- No mobs harder than tier: weak

Class start rooms: all five classes start in different flavor rooms in thornhaven_city; `crossroads_path` is for players who want to experience the tutorial intro (linked from a signpost in thornhaven_city).

#### 4. `thornwood_forest`
**Partly replaces:** `tutorial_glade` (content/mobs, new lore)  
**Level range:** 1–4  
**Rooms:** ~18  
**Purpose:** First open-world exploration zone. Nature, wildlife, early combat.

Mobs: `stray_wolf`, `territorial_hare`, `scrappy_fox`, `forest_bandit`, `cave_spider`, `mother_bear`  
Items: `wolf_pelt`, `rabbit_fur`, `spider_silk`, `wildflower` (gathering nodes for herbs)  
Shops: none; items feed the crafting system  
NPCs: Forester Lenna (Ranger trainer also located here; gives quest "The Bandit Problem")  
Features:
- **Gathering nodes** for herbalism (wildflowers, mushrooms, bark)
- Behavior trees: wolves patrol in packs; mother bear charges when young are nearby
- Day/night: owls and foxes spawn at night; deer visible only at dawn

Quest: **"The Bandit Problem"** (levels 1–3) — Forester Lenna asks the player to clear out a bandit
camp in `thornwood_forest:bandit_camp`. Rewards: 200 XP, basic armor piece.

#### 5. `farmer_fields`
**New zone**  
**Level range:** 1–3  
**Rooms:** ~10  
**Purpose:** A gentle quest hub for fresh characters. Low-threat, high narrative density.

Mobs: `field_crow`, `giant_slime`, `barn_rat`, `harvest_sprite`  
NPCs: Farmer Aldous (quest giver), Goodwife Petha (quest giver)  
Features:
- **Two quest chains**: (1) "The Slime Infestation" — clear slimes from the barn; (2) "The Missing Chickens" — find the fox den in thornwood_forest
- First introduction to `talk` command via Aldous's dialogue tree
- Simple container (chest in the barn) showcasing the container/search mechanic

---

### Tier 2 — Low-Level Wilderness (levels 2–5)

#### 6. `cobblestone_road`
**New zone**  
**Level range:** 2–5  
**Rooms:** ~10  
**Purpose:** The open trade road between Thornhaven and the wider world. Weather and atmosphere showcase.

Mobs: `road_bandit`, `travelling_merchant` (non-hostile, gives lore), `rabid_dog`, `ambush_brigand`  
Features:
- **Weather showcase** — this zone cycles through rain, fog, and clear skies, with description text changes
- Traveling merchant NPC with brief dialogue and a small shop (road supplies)
- One roadside inn room with a short-rest NPC
- Connection point to `highland_trails` (north) and `marsh_of_fog` (east)

#### 7. `marsh_of_fog`
**Replaces:** `low_training_marsh`  
**Level range:** 3–6  
**Rooms:** ~15  
**Purpose:** Atmosphere-heavy zone with status effects and herbalism.

Mobs: `bog_leech`, `marsh_wraith`, `will_o_wisp`, `fungal_hulk`, `swamp_serpent`  
Gathering nodes: `bog_root`, `nightshade_flower`, `muck_crystal`  
NPCs: Hedge Witch Mossfoot (quest giver, herbalism recipes)  
Features:
- **Status effects showcase** — bog_leech applies `POISON`; will_o_wisp applies `SLOW`; marsh_wraith applies `BLIND`
- **Herbalism gathering** — Mossfoot buys gathered herbs for gold and teaches crafting recipes
- Weather: persistent fog aesthetic in room descriptions
- Quest: **"The Witch's Request"** (levels 3–5) — gather three rare herbs; rewards a craftable antidote recipe

#### 8. `highland_trails`
**Replaces:** `low_training_highlands`  
**Level range:** 3–6  
**Rooms:** ~12  
**Purpose:** Scenic mountain foothills with weather variety and straightforward melee content.

Mobs: `mountain_goat`, `highland_bandit`, `cave_troll`, `stone_eagle`  
Features:
- **Snow weather** — room descriptions shift with weather system (blizzard condition)
- Gathering nodes: `mountain_herb`, `iron_ore` (shared with old_mines type)
- A short sub-dungeon (cave entrance) that links into `old_mines`
- Scenic overlook room with a flavor description of the whole world map

#### 9. `old_mines`
**Replaces:** `low_training_mines`  
**Level range:** 3–6  
**Rooms:** ~15  
**Purpose:** Abandoned silver mine. Crafting resources and the start of the mid-level quest chain.

Mobs: `mine_goblin`, `kobold_digger`, `giant_rat`, `stone_lurker`, `mine_foreman` (mini-boss)  
Gathering nodes: `silver_ore`, `copper_ore`, `iron_ore`, `raw_gemstone`  
NPCs: Survivor Hadrik (quest giver, survived a goblin attack)  
Features:
- **Crafting resources** — largest concentration of ore gathering nodes in the world
- **Quest chain start**: "The Lost Expedition" (levels 3–6) — four-step chain leading through old_mines into goblin_warrens
- Container chests with random gear drops (uses container/search mechanic)
- Partially collapsed rooms (flavor; some exits blocked, others require finding a lever to open)

---

### Tier 3 — Mid-Level Dungeons (levels 4–7)

#### 10. `goblin_warrens`
**New zone**  
**Level range:** 4–7  
**Rooms:** ~18  
**Purpose:** Classic dungeon crawl. Behavior trees, traps, boss encounter.

Mobs: `goblin_scout`, `goblin_warrior`, `goblin_shaman`, `kobold_trapper`, `dire_rat`, `Chieftain Grak` (boss)  
Features:
- **Behavior tree showcase** — goblin_scout patrols and calls allies if it spots a player; goblin_shaman casts buffs on nearby goblins; Chieftain Grak flees at 20% HP then returns with reinforcements
- **Trap mechanic** — kobold_trapper rooms have floor traps that deal damage when moving through (described in room descriptions; skill check to avoid via future mechanic or just flavor text for now)
- **Boss achievement** — killing Chieftain Grak awards the achievement "Chieftain Slayer"
- Connects to `old_mines` (upper level) and `dark_barrows` (secret tunnel)
- Quest: completes step 3 of "The Lost Expedition" (find the expedition notes in the chief's room)

#### 11. `sunken_temple`
**Replaces/adapts:** `sunken_crypt`  
**Level range:** 5–7  
**Rooms:** ~15  
**Purpose:** Partially flooded ancient temple. Puzzle mechanics, undead, artifact quest.

Mobs: `temple_skeleton`, `dark_cultist`, `drowned_acolyte`, `stone_guardian`, `Elder Revenant` (boss)  
Features:
- **Lever/door puzzles** — three levers must be pulled in correct order to open the inner sanctum (described via room text, uses the door/lever world feature system)
- **Containers** — ancient chests and urns with lore items and gear
- **Status effects** — drowned_acolyte applies `WEAKEN`; stone_guardian applies `STUN`
- Quest: **"The Stolen Relic"** (levels 5–7) — recover a stolen artifact from the Elder Revenant; rewards a class-specific weapon piece

#### 12. `dark_barrows`
**New zone**  
**Level range:** 5–8  
**Rooms:** ~15  
**Purpose:** Ancient burial mounds. Powerful undead, necromancer boss, main quest chapter two.

Mobs: `barrow_wight`, `grave_hound`, `spectral_knight`, `banshee`, `Necromancer Vaelthos` (boss)  
Features:
- **Boss encounter with dialogue** — Vaelthos has a short pre-combat dialogue tree ("You dare disturb my work?") before triggering combat
- **Group content** — Vaelthos is elite-tier and intended for 2–3 players, but soloable with good gear
- **Achievement**: "Barrow Breaker" — kill Necromancer Vaelthos
- **Leaderboard**: Vaelthos kill time logged to the Hall of Fame board in thornhaven_city
- Connects to `goblin_warrens` (secret tunnel) and `shadowmere_fen` (eastern passage)
- Quest: Step 3 of "The Curse of Shadowmere" chain (find Vaelthos's research notes)

#### 13. `ruined_fortress`
**Replaces/adapts:** `demo_ruins`  
**Level range:** 5–8  
**Rooms:** ~18  
**Purpose:** Crumbling keep with mixed enemy factions. Introduces the reputation system.

Mobs: `fortress_guard` (faction: Iron Order), `rebel_soldier` (faction: Free Swords), `gargoyle`, `iron_golem`, `Commander Thane` (boss, Iron Order)  
Features:
- **Reputation system showcase** — Iron Order and Free Swords are opposing factions. Killing Iron Order guards raises Free Swords rep and vice versa. NPCs react differently based on rep tier.
- **World features** — iron portcullis doors operated by levers; secret room behind a bookshelf container
- Quest: Step 1 of "The Celestial Reckoning" — find the ancient order's seal in the commander's vault

---

### Tier 4 — Higher-Level Content (levels 6–9)

#### 14. `shadowmere_fen`
**New zone**  
**Level range:** 6–9  
**Rooms:** ~12  
**Purpose:** Cursed fenland. Faction reputation, rare drops, dark atmosphere.

Mobs: `shadow_stalker` (faction: Shadowmere Cult), `silver_flame_paladin` (faction: Order of the Silver Flame), `shadow_hulk`, `nightshade_wisp`  
Features:
- **Dual factions** — this is the resolution zone for the "Curse of Shadowmere" quest; player must choose side by killing enough mobs of one faction to unlock the final boss route
- Rare item drops: `shadowmere_crystal` used in high-tier crafting recipes
- Quest: Final step of "The Curse of Shadowmere" — purify or embrace the shadow depending on faction choice

#### 15. `frost_caverns`
**New zone**  
**Level range:** 7–9  
**Rooms:** ~14  
**Purpose:** Ice caves in the mountains. Group-oriented, rare crafting materials.

Mobs: `frost_imp`, `ice_golem`, `yeti`, `frozen_revenant`, `Ice Wyrm` (boss)  
Gathering nodes: `frost_crystal` (tier-3 crafting material), `glacial_ore`  
Features:
- **Group content** — Ice Wyrm is scaled for a full group; uses multi-target AoE ability type
- **Rare crafting** — frost_crystal is required for the highest-tier weapon and armor recipes
- Weather: permanent blizzard (day/night cycle still applies; "the blizzard rages" vs "the blizzard calms slightly at dawn")
- Quest: Step 3 of "The Celestial Reckoning" — gather three frost_crystals for the ancient ritual

#### 16. `haunted_manor`
**New zone**  
**Level range:** 7–9  
**Rooms:** ~14  
**Purpose:** Cursed noble manor. Ghost NPCs, full dialogue trees, pet companion unlock.

Mobs: `poltergeist`, `manor_specter`, `animated_armor`, `howling_shade`, `Warden of the Manor` (boss)  
NPCs: **Ghost of Lady Veyra** — full multi-branch dialogue tree; she is not hostile and gives lore about the manor's curse, the Celestial Peak, and the final quest
Features:
- **Ghost companion unlock** — completing Lady Veyra's dialogue tree and defeating the Warden unlocks the `spectral_wisp` pet via a SUMMON_PET ability
- **NPC dialogue showcase** — Lady Veyra has 6+ dialogue nodes with branching choices; she provides exposition for the end-game quest
- **Status effect**: animated_armor applies `SLOW`; howling_shade applies `FEAR` (new effect, or mapped to existing STUN)
- Quest: Step 2 of "The Celestial Reckoning" — learn the ancient rite from Lady Veyra

#### 17. `barrens_wastes`
**Replaces:** `low_training_barrens`  
**Level range:** 7–10  
**Rooms:** ~12  
**Purpose:** Blasted wasteland. High-level open-world combat, bounty hunts, PvP flavor.

Mobs: `wasteland_raider`, `dust_elemental`, `scavenging_wyvern`, `marauder_captain` (elite)  
Features:
- **Bounty quest board** — a board in one room that offers rotating kill-count quests (kill 10 raiders for gold/XP); these feed the achievement system ("Bounty Hunter" achievement)
- **PvP-adjacent flavor** — the Barrens is flagged in room descriptions as a "dangerous frontier where travelers sometimes duel for sport"; the in-game duel system works anywhere so this is just narrative
- Connects to `frost_caverns` (northern pass) and `celestial_peak` (via a mountain trail)

---

### Tier 5 — End-Game (levels 8–10)

#### 18. `celestial_peak`
**Replaces:** `celestial_sanctum`  
**Level range:** 8–10  
**Rooms:** ~16  
**Purpose:** Summit of the highest mountain. End-game combat, legendary gear, final boss.

Mobs: `celestial_guardian`, `storm_elemental`, `divine_construct`, `fallen_angel`, `Elder Dragon Auranthos` (final boss)  
Features:
- **Final boss encounter** — Auranthos has a full pre-combat dialogue and multiple combat phases (phase 2 triggered at 50% HP via behavior tree)
- **Achievement**: "Dragonslayer" — kill Elder Dragon Auranthos; also awards a title
- **Leaderboard**: Auranthos kill time and party composition logged to thornhaven_city Trophy Hall
- Rare gear drops: best-in-slot items at level 10
- Quest: Final step of "The Celestial Reckoning"

#### 19. `the_labyrinth`
**Keeps:** `labyrinth.yaml` concept, new lore wrapper  
**Level range:** 5–10 (scales)  
**Rooms:** ~20  
**Purpose:** Endless navigational challenge. Leaderboard and achievement showcase.

Mobs: Labyrinth sentinels at levels 2–9 (already exist in labyrinth.yaml; relabel with Eryndal names)  
Features:
- **Navigation challenge** — the labyrinth exists to test spatial awareness and map-reading
- **Achievement**: "Maze Runner" — reach the center of the labyrinth without dying
- **Leaderboard**: fastest labyrinth solve time
- Accessed via a hidden door in thornhaven_sewers (flavor: "the city was built over an older structure")

#### 20. `dungeon_of_echoes`
**New zone (instanced)**  
**Level range:** 3–10 (scales to party)  
**Rooms:** generated  
**Purpose:** The procedural instanced dungeon. Showcases all dungeon system features.

Features:
- Uses the existing `DungeonManager` / `DungeonGenerator` engine systems
- Template: `echoes_standard` (standard difficulty template) and `echoes_hard` (hard mode)
- Scales mob level to the initiating player's level (±1)
- Entrance via the Dungeon Finder NPC ("Old Grimly") in thornhaven_city
- Completion achievement: "Echo Diver" — complete a dungeon run
- Contains a boss room at the end with a randomized boss from a pool of 4 templates
- Drops include crafting materials and level-appropriate gear

---

## Quest Chains

### Chain 1: "A Traveler's Welcome" (levels 1–3, tutorial)
A hand-holding introduction for brand-new players.

| Step | Zone | Objective | Reward |
|------|------|-----------|--------|
| 1 | crossroads_path | Read the signpost; speak to the Waypost Guard | 50 XP |
| 2 | thornhaven_city | Set a recall point at the inn | 100 XP + 10 gold |
| 3 | thornwood_forest | Kill 3 stray wolves | 200 XP + basic weapon |
| 4 | thornhaven_city | Visit a class trainer and spend a skill point | 300 XP + "First Steps" title |

### Chain 2: "The Lost Expedition" (levels 3–6)
A group of scholars went into the Old Mines and never returned.

| Step | Zone | Objective | Reward |
|------|------|-----------|--------|
| 1 | old_mines | Find Survivor Hadrik | 300 XP |
| 2 | old_mines | Retrieve the expedition's supply chest | 400 XP + crafting recipe |
| 3 | goblin_warrens | Recover the lead scholar's journal from the Chieftain's room | 600 XP + Chieftain Slayer achievement |
| 4 | thornhaven_city | Return journal to the scholar's guild contact | 800 XP + rare item |

### Chain 3: "The Curse of Shadowmere" (levels 5–8)
The fens to the east grow darker. An ancient darkness is stirring in the barrows.

| Step | Zone | Objective | Reward |
|------|------|-----------|--------|
| 1 | marsh_of_fog | Speak to Hedge Witch Mossfoot; gather three rare herbs | 400 XP + antidote recipe |
| 2 | dark_barrows | Find Vaelthos's research notes | 600 XP |
| 3 | dark_barrows | Kill Necromancer Vaelthos | 1000 XP + Barrow Breaker achievement |
| 4 | shadowmere_fen | Choose a faction and complete the purification/corruption ritual | 1500 XP + faction title |

### Chain 4: "The Celestial Reckoning" (levels 8–10, main story arc)
An ancient evil at the summit threatens to consume the realm.

| Step | Zone | Objective | Reward |
|------|------|-----------|--------|
| 1 | ruined_fortress | Find the ancient order's seal in Commander Thane's vault | 800 XP |
| 2 | haunted_manor | Learn the ancient rite from Ghost of Lady Veyra | 1000 XP + ghost companion pet |
| 3 | frost_caverns | Gather 3 frost_crystals for the sealing ritual | 1200 XP |
| 4 | celestial_peak | Kill Elder Dragon Auranthos | 3000 XP + Dragonslayer achievement + title |

---

## Key NPCs

| Name | Zone | Purpose | Engine Feature |
|------|------|---------|---------------|
| Innkeeper Mira | thornhaven_city | Recall point, inn lore, housing stub | set_recall dialogue action |
| Captain Varek | thornhaven_city | Warrior trainer, lost expedition quest | train list/learn, quest give |
| Archmage Solen | thornhaven_city | Mage trainer, world lore | train list/learn |
| High Priest Aldric | thornhaven_city | Cleric trainer, hints at growing darkness | train list/learn |
| Shadow "Shade" | thornhaven_city | Rogue trainer, sewer key quest | train list/learn, gate content |
| Forester Lenna | thornwood_forest | Ranger trainer, bandit quest | train list/learn, quest give |
| Banker Theron | thornhaven_city | Bank deposits/withdrawals | bank dialogue actions |
| Old Grimly | thornhaven_city | Dungeon finder, lore guide | dungeon_enter trigger |
| Mail Clerk Oswin | thornhaven_city | Mail send/read | mail command context |
| Hedge Witch Mossfoot | marsh_of_fog | Herb buyer, curse quest giver | shop, quest give |
| Survivor Hadrik | old_mines | Expedition quest giver | quest give |
| Ghost of Lady Veyra | haunted_manor | End-game lore, ghost pet unlock | multi-branch dialogue tree |
| Necromancer Vaelthos | dark_barrows | Boss; pre-combat dialogue | dialogue then combat |
| Elder Dragon Auranthos | celestial_peak | Final boss; multi-phase combat | behavior tree (phase trigger) |
| Arena Master | thornhaven_city | PvP duel rules, leaderboard query | leaderboard display |
| Guild Registrar | thornhaven_city | Guild creation/management | guild dialogue actions |

---

## Feature Showcase Matrix

The table below maps each major engine feature to the zone(s) that demonstrate it.

| Feature | Primary Zone(s) |
|---------|----------------|
| Basic combat | thornwood_forest, farmer_fields |
| NPC dialogue trees | thornhaven_city (all trainers + innkeeper + banker) |
| Multi-branch dialogue with actions | thornhaven_city (Mira's recall), haunted_manor (Lady Veyra) |
| Quest system | all zones; full chain in each tier |
| Class trainers (skill points) | thornhaven_city |
| Bank NPC | thornhaven_city |
| Inn / recall point | thornhaven_city |
| Shops (gear + potions) | thornhaven_city, marsh_of_fog (herb shop) |
| Auction house | thornhaven_city |
| Mail system | thornhaven_city |
| Crafting stations | thornhaven_city (crafting quarter) |
| Gathering nodes (herbs) | thornwood_forest, marsh_of_fog |
| Gathering nodes (ore) | old_mines, frost_caverns, highland_trails |
| Crafting recipes | thornhaven_city (learned from NPCs and drops) |
| Recipe discovery | old_mines (drop), marsh_of_fog (NPC) |
| Status effects (poison/slow/blind) | marsh_of_fog |
| Status effects (stun/weaken) | sunken_temple |
| Status effects (fear/slow) | haunted_manor |
| Weather system | cobblestone_road (rain/fog), highland_trails (snow), frost_caverns (blizzard) |
| Day/night cycle | thornwood_forest, thornhaven_city (NPC schedules via description text) |
| Behavior trees (patrol/call allies) | goblin_warrens |
| Behavior trees (flee + return) | goblin_warrens (Chieftain Grak) |
| Behavior trees (multi-phase boss) | celestial_peak (Auranthos phase 2) |
| Door/lever puzzles | sunken_temple, ruined_fortress |
| Container / search mechanic | farmer_fields, old_mines, thornhaven_sewers |
| Group content | goblin_warrens, dark_barrows, frost_caverns, dungeon_of_echoes |
| Instanced dungeons | dungeon_of_echoes |
| Pet / companion system | haunted_manor (ghost pet unlock) |
| PvP dueling | thornhaven_city (arena district) |
| Guild system | thornhaven_city (guild registry) |
| Reputation / faction system | ruined_fortress + shadowmere_fen |
| Achievements | all zones (10+ distinct achievements) |
| Leaderboards / Hall of Fame | thornhaven_city (Trophy Hall), celestial_peak, the_labyrinth |
| Titles | chains 1 and 4 (First Steps, Dragonslayer) |
| Sprite progression | earned through tier advancement (automatic) |
| GMCP / minimap | all zones (room descriptions drive map generation) |
| Seasonal events (day/night/weather) | all outdoor zones |
| Multi-classing | thornhaven_city (trainer, level 10+ unlock) |

---

## Achievement List (Initial Set)

| ID | Name | Trigger | Zone |
|----|------|---------|------|
| first_blood | First Blood | Kill any mob for the first time | any |
| wolf_hunter | Wolf Hunter | Kill 10 stray_wolves | thornwood_forest |
| chieftain_slayer | Chieftain Slayer | Kill Chieftain Grak | goblin_warrens |
| barrow_breaker | Barrow Breaker | Kill Necromancer Vaelthos | dark_barrows |
| dragon_slayer | Dragonslayer | Kill Elder Dragon Auranthos | celestial_peak |
| maze_runner | Maze Runner | Reach the labyrinth center without dying | the_labyrinth |
| echo_diver | Echo Diver | Complete a dungeon_of_echoes run | dungeon_of_echoes |
| master_crafter | Master Crafter | Craft 20 items | any |
| herb_collector | Hedge Witch's Friend | Gather 10 herbs | marsh_of_fog |
| ore_miner | Deep Delver | Gather 10 ore | old_mines |
| full_party | Strength in Numbers | Complete a dungeon with a full group | dungeon_of_echoes |
| bounty_hunter | Bounty Hunter | Complete 5 bounty quests | barrens_wastes |
| loyal_friend | Loyal Companion | Unlock the ghost pet | haunted_manor |
| guild_founder | Guild Founder | Create a guild | thornhaven_city |
| max_level | Level Ten | Reach level 10 | any |

---

## Content Progression (Levels 1–10)

| Level | Target Zones | Key Activities |
|-------|-------------|---------------|
| 1 | crossroads_path, thornhaven_city, thornwood_forest | Tutorial, set recall, first combat |
| 2 | thornwood_forest, farmer_fields | Quest completion, first gear |
| 3 | cobblestone_road, old_mines (entrance) | Road travel, first mine mobs, Lost Expedition start |
| 4 | old_mines, marsh_of_fog, highland_trails | Full mine, status effects, herbs |
| 5 | goblin_warrens, sunken_temple | Boss fight, puzzle dungeon |
| 6 | dark_barrows, ruined_fortress | Group content, reputation intro |
| 7 | shadowmere_fen, thornhaven_sewers, haunted_manor (entrance) | Faction choice, secret area |
| 8 | haunted_manor, barrens_wastes | Ghost dialogue, bounty hunts |
| 9 | frost_caverns, celestial_peak (approach) | Rare crafting, group boss |
| 10 | celestial_peak, dungeon_of_echoes, the_labyrinth | Final boss, endgame content |

---

## Item Economy

### Starter Gear (Levels 1–2)
Dropped in thornwood_forest and farmer_fields; no gold required:
- `worn_sword`, `battered_staff`, `hunting_bow`, `rusty_dagger` (class-appropriate)
- `leather_cap`, `padded_vest`

### Purchased Gear (Levels 2–5)
Thornhaven Market; affordable with quest rewards:
- `iron_sword`, `oak_staff`, `short_bow`, `silver_dagger`
- `iron_helm`, `chainmail_vest`, `leather_bracers`
- Potions: `minor_healing_potion` (50 gold), `clarity_potion` (75 gold)

### Crafted Gear (Levels 3–8)
Recipes available through NPCs and drops; materials from gathering:
- `steel_sword` (iron_ore ×3 + coal ×1)
- `mage_focus` (silver_ore ×2 + wildflower ×1)
- `healing_salve` (bog_root ×1 + wildflower ×2) — consumable, equivalent to minor_healing_potion

### Rare/Endgame Gear (Levels 7–10)
Boss drops and frost_caverns materials:
- `glacial_blade` (glacial_ore ×2 + frost_crystal ×1)
- `dragon_scale_vest` (dropped by Auranthos)
- `shadow_mantle` (shadowmere_crystal ×3)

---

## Migration Plan

### Files to Replace
| Old File | Action | New File |
|----------|--------|---------|
| `ambon_hub.yaml` | Replace | `thornhaven_city.yaml` |
| `tutorial_glade.yaml` | Replace | `crossroads_path.yaml` + (content absorbed into `thornwood_forest.yaml`) |
| `crafting_workshop.yaml` | Merge into hub | (crafting section of `thornhaven_city.yaml`) |
| `demo_ruins.yaml` | Adapt | `ruined_fortress.yaml` (strip Ambon lore, add Iron Order/Free Swords faction mobs) |
| `sunken_crypt.yaml` | Adapt | `sunken_temple.yaml` (lore rewrite + add lever puzzle rooms) |
| `low_training_marsh.yaml` | Adapt | `marsh_of_fog.yaml` |
| `low_training_highlands.yaml` | Adapt | `highland_trails.yaml` |
| `low_training_mines.yaml` | Adapt | `old_mines.yaml` |
| `low_training_barrens.yaml` | Adapt | `barrens_wastes.yaml` |
| `labyrinth.yaml` | Keep + rename | `the_labyrinth.yaml` (update zone key, update lore text) |
| `celestial_sanctum.yaml` | Replace | `celestial_peak.yaml` |
| `noecker_resume.yaml` | Remove | (move to Ambon lore repository; not part of base world) |

### Files to Keep Unchanged
- `achievements.yaml` — update achievement IDs to match new zone names
- `sprites.yaml`, `player_sprites.yaml` — no changes needed

### Config Changes Required
1. **Add RANGER class** to `application.yaml` classes block (same format as existing classes)
2. **Add HALF_ORC and GNOME races** to `application.yaml` races block
3. **Update `classStartRooms`** to point all five classes to rooms in `thornhaven_city`
4. **Update `startRoom`** for zones that are renamed/replaced
5. **Update zone list** in any config that enumerates zones (search for `worldZones` or `zoneFiles`)
6. **Update abilities config** to reference new trainer NPC IDs for RANGER class abilities

### New Dungeon Template Required
Add a `dungeon_of_echoes` entry to the dungeon templates section of `application.yaml`.

---

## Implementation Order

Suggested phase-by-phase order that lets the demo be testable early:

### Phase 1: Hub + Tutorial (MVP demo)
1. `thornhaven_city.yaml` — hub with all trainers, inn, bank, shops, guild, crafting quarter
2. `crossroads_path.yaml` — tutorial path
3. Config: add RANGER class, HALF_ORC + GNOME races, update classStartRooms

**Demo checkpoint:** All 5 classes can log in, visit trainers, set recall, visit the shop. Every trainer-related feature works.

### Phase 2: Early Wilderness
4. `thornwood_forest.yaml` — levels 1–4, gathering nodes, Ranger trainer here
5. `farmer_fields.yaml` — levels 1–3, two quest chains
6. `cobblestone_road.yaml` — levels 2–5, weather showcase

**Demo checkpoint:** Tutorial chain "A Traveler's Welcome" fully completable.

### Phase 3: Low-Level Dungeons (adapt existing)
7. `old_mines.yaml` — adapt from `low_training_mines`
8. `marsh_of_fog.yaml` — adapt from `low_training_marsh`
9. `highland_trails.yaml` — adapt from `low_training_highlands`
10. `barrens_wastes.yaml` — adapt from `low_training_barrens`

**Demo checkpoint:** Levels 1–5 fully playable; "The Lost Expedition" chain completable.

### Phase 4: Mid-Level Dungeons
11. `goblin_warrens.yaml` — new
12. `sunken_temple.yaml` — adapt from `sunken_crypt`
13. `dark_barrows.yaml` — new

**Demo checkpoint:** Levels 4–7 content; "The Curse of Shadowmere" chain completable.

### Phase 5: Factions + Secret Areas
14. `ruined_fortress.yaml` — adapt from `demo_ruins`
15. `shadowmere_fen.yaml` — new
16. `thornhaven_sewers.yaml` — new

**Demo checkpoint:** Reputation system showcase fully working.

### Phase 6: High-Level Content
17. `frost_caverns.yaml` — new
18. `haunted_manor.yaml` — new

**Demo checkpoint:** Levels 7–9 content; ghost pet, rare crafting.

### Phase 7: End-Game + Config Cleanup
19. `celestial_peak.yaml` — replace `celestial_sanctum`
20. `the_labyrinth.yaml` — adapt from `labyrinth`
21. Dungeon template: `dungeon_of_echoes` (config + new template YAML)
22. Update `achievements.yaml` for new zone names/IDs
23. Remove `ambon_hub.yaml`, `noecker_resume.yaml`, `tutorial_glade.yaml`

**Demo checkpoint:** Full levels 1–10 playable; all four quest chains completable; every feature showcased.

### Phase 8: Polish + Demo Integration
- Update `application.yaml` `classStartRooms` and default start zone
- Update the web demo's landing copy to reference Eryndal
- Update `README.md` demo section with new world overview and features list
- Smoke-test the full new player experience end-to-end
- Archive/link old Ambon-lore zones in a comment or separate branch

---

## Open Questions for Discussion

1. **Ranger class image** — Does an appropriate sprite already exist in the ability images? If not,
   is the placeholder pattern (reuse an existing image hash) acceptable for the initial PR?

2. **Faction system integration** — The reputation/faction system exists in config
   (`ambonMUD.engine.reputation`?). Do we need to verify the faction keys used in ruined_fortress
   and shadowmere_fen match the config format, or define them fresh?

3. **Zone file naming** — Should the new zone files use the same snake_case convention as existing
   files? (Yes, assumed above.) Should the zone YAML `zone:` key match the filename exactly?
   (Yes, convention from all existing zones.)

4. **Ranger abilities** — The ability definitions are in `application.yaml`. Should Ranger's
   initial ability set be defined as part of Phase 1 (so the trainer has something to offer
   immediately), or as a follow-up task?

5. **Mob images** — New zones will initially use zone default images (set in zone YAML header).
   Is there a preferred placeholder image hash to use for new mobs without dedicated sprites,
   or should we source new images from the existing `src/main/resources/world/images/` directory?

6. **Keep labyrinth as-is?** — The current `labyrinth.yaml` has 20×20 grid rooms. Should we
   keep that structure and just update lore text, or rework it to be a smaller, more curated maze?

7. **Phase scope per task** — Should each Phase above become one GitHub issue + one PR, or should
   individual zones within a phase be separate tasks? For large zones like thornhaven_city (~40 rooms),
   a single-zone PR might be most reviewable.
