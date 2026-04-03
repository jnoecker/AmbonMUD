# Eryndal: A Base World Plan

This document is the design plan for **Eryndal**, a new base world for AmbonMUD. All existing
Ambon-lore-specific zones (ambon_hub, noecker_resume, tutorial_glade, low_training_*, celestial_sanctum,
sunken_crypt, demo_ruins, crafting_workshop, labyrinth) will be **deleted entirely** and replaced
with a clean set of 20 new zone files. Nothing is adapted from the old zones — every file starts
from scratch. This avoids carrying forward any lore artifacts, naming quirks, or geographic
incoherence from the previous world.

The goal is a showcase demo: every major engine feature is touched by at least one zone, the world
is fully playable at levels 1–10, and it is small and approachable for first-time visitors.
Ambon's Surreal Gentle Magic aesthetic still applies to art and UI; the lore is reset to genre
convention (D&D-inspired, Diku-style heroic fantasy).

---

## Image Assets

All zones use images from the `demo/` directory at the project root. Before the world files are
written, these images must be copied to `src/main/resources/world/images/demo/` so they are served
at `/images/demo/` by the web transport.

| File | Usage | Background removed? |
|------|-------|---------------------|
| `demo/DefaultMob.png` | Default mob image for all zones | Yes — ready to use |
| `demo/DefaultRoom.png` | Default room image for all zones | No — needs removal before final demo |
| `demo/DefaultObject.png` | Default item image for all zones | No — needs removal before final demo |
| `demo/DefaultPlayer.png` | Player sprite fallback | No — needs removal before final demo |
| `demo/DefaultAbility.png` | Default ability icon | No — needs removal before final demo |

Every zone YAML header will reference these defaults:

```yaml
image:
  room: demo/DefaultRoom.png
  mob: demo/DefaultMob.png
  item: demo/DefaultObject.png
```

Individual mob, item, and NPC entries will **not** override the `image:` field in the initial
implementation — they inherit the zone default. Per-entity images will be sourced from Arcanum
in a later pass once the world structure is stable.

No images from existing zones (the hash-named PNGs in `world/images/`) will be reused.

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

## World Geography

The world is designed so that every zone has a logical physical location. A player walking in one
direction from Thornhaven always ends up somewhere that makes sense relative to where they started.

```
                              [Celestial Peak]
                                    |
                              [Frost Caverns]
                                    |
                    [Barrens Wastes]---[Highland Trails]
                          |                   |
      [Haunted Manor]   [Ruined]        [Old Mines]
            |          [Fortress]             |
  [Thornhaven City]------[Cobblestone Road]---+
       /    |    \
[Cross-] [Sewer] [Thornwood Forest]
[roads]           |             \
  Path          [Goblin Warrens] [Dark Barrows]
                                      |
[Farmer Fields]                 [Shadowmere Fen]
      |
  [Sea Cliffs]
      |
  [Sunken Temple] (offshore ruins, accessible via cliff path)
```

**Compass orientation from Thornhaven:**
- **North**: Crossroads Path (arrivals from the wider world) → eventually Celestial Peak via mountain route
- **Northeast**: Thornwood Forest → Goblin Warrens (beneath)
- **East**: Cobblestone Road → Highland Trails → Old Mines → Frost Caverns → Celestial Peak
- **Southeast**: Farmer Fields → Sea Cliffs → Sunken Temple (coastal ruins)
- **South/West**: Cobblestone Road → Ruined Fortress / Barrens Wastes
- **Northwest**: Haunted Manor (isolated estate, roughly northwest)
- **Far East (deep fens)**: Marsh of Fog → Dark Barrows → Shadowmere Fen
- **Underground**: Thornhaven Sewers beneath the city; Goblin Warrens beneath Thornwood
- **Instanced**: Dungeon of Echoes (entrance portal in Thornhaven; generates on demand)

**Geographic design rules:**
1. Every zone exit leads to an adjacent zone on the map above.
2. No zone is a dead end — at minimum two exits per zone.
3. Zone descriptions reference the surrounding landscape (mountains visible to the north from low-level
   zones; the sea visible from Sea Cliffs and Farmer Fields looking south).
4. Underground zones connect to the surface zone directly above them.

---

## Classes (5 total)

All classes are config-driven (no code changes required). The four existing classes are kept and
**Ranger** is added as a fifth. All five classes will have complete ability definitions in
`application.yaml` — no engine fallbacks are relied upon. The Ranger's full ability set will be
defined in Phase 1 alongside the hub zone, so the trainer has something to offer from day one.

| Key      | Display Name | Primary Stat | HP/lvl | Mana/lvl | Identity |
|----------|-------------|-------------|--------|---------|---------|
| WARRIOR  | Warrior     | STR         | 8      | 4       | Front-line tank; highest HP, taunt, defensive abilities |
| MAGE     | Mage        | INT         | 4      | 16      | Arcane nuker; fragile, powerful AoE and burst damage |
| CLERIC   | Cleric      | WIS         | 6      | 12      | Divine healer; group heals, buffs, turn undead |
| ROGUE    | Rogue       | DEX         | 5      | 8       | Stealth striker; poisons, backstab, mobility abilities |
| RANGER   | Ranger      | DEX         | 6      | 8       | Nature hybrid; ranged damage, animal companion, tracking |

**Ranger ability set (to define in application.yaml):**

| Ability Key | Level | Type | Description |
|-------------|-------|------|-------------|
| `arrow_shot` | 1 | DirectDamage | Ranged single-target attack |
| `hunters_mark` | 2 | StatusEffect (WEAKEN on target) | Mark an enemy; increases all damage they take |
| `camouflage` | 3 | StatusEffect (DODGE_BOOST on self) | Blend into surroundings; increased dodge for 2 ticks |
| `volley` | 4 | AoeDamage | Fire a volley of arrows; hits all enemies in the room |
| `track` | 5 | Utility | Reveal hidden mobs in the current room |
| `natures_grasp` | 6 | StatusEffect (ROOT on target) | Roots an enemy in place; they cannot flee for 2 ticks |
| `summon_hawk` | 7 | SummonPet | Summons a hawk companion that fights alongside the ranger |
| `eagle_eye` | 8 | DirectDamage | Precision shot with high crit multiplier |
| `rain_of_arrows` | 9 | AoeDamage | Heavy AoE; long cooldown |
| `nature_bond` | 10 | Heal (AllAllies) | Channel nature magic to heal the entire party |

**Starting rooms:** All five classes start in `thornhaven_city:new_arrivals_hall`. Class-specific
flavor is conveyed via the character creation screen and trainer descriptions, not separate start rooms.

**Trainer locations:** All five class trainers live in a dedicated **Trainers' Hall** wing of
Thornhaven City. Forester Lenna (Ranger trainer) is also present in `thornwood_forest` for players
who find her in the wild.

---

## Races (6 total)

The four existing races are kept; **Half-Orc** and **Gnome** are added. Both are config-only
additions (no code changes).

| Key       | Display Name | Flavor           | Stat Mods (net 0) |
|-----------|-------------|-----------------|------------------|
| HUMAN     | Human       | Versatile        | STR+1, CHA+1 |
| ELF       | Elf         | Magical, graceful| DEX+2, INT+1, STR-1, CON-2 |
| DWARF     | Dwarf       | Tough, stubborn  | STR+1, CON+2, WIS+1, DEX-1, CHA-2 |
| HALFLING  | Halfling    | Quick, charming  | DEX+2, WIS+1, CHA+1, STR-2, CON-1 |
| HALF_ORC  | Half-Orc    | Fierce, resilient| STR+3, CON+2, CHA-2, INT-1, WIS-1 |
| GNOME     | Gnome       | Clever, tiny     | INT+2, DEX+1, WIS+1, STR-2, CON-1 |

---

## Zone Overview (20 Zones)

Zones are divided into five tiers by target level range. The hub is safe at all levels. Every zone
is written entirely from scratch — no YAML content is reused from existing files.

### Tier 0 — The Hub (safe, all levels)

#### 1. `thornhaven_city`
**Rooms:** ~40  
**Purpose:** Central hub; every non-combat engine feature lives here.

Key areas and features showcased:
- **New Arrivals Hall** — where all characters begin; notice board with world overview
- **Market Square** — general shop (gear/potions), auction house NPC
- **The Tarnished Flagon Inn** — Innkeeper Mira, recall point, `set_recall` dialogue action
- **Trainers' Hall** — five class trainers (train list / train learn), skill points, multi-class unlock at level 10
- **Thornhaven Bank** — Bank NPC (deposit/withdraw gold and items)
- **Guild Registry** — Guild creation/management NPC, guild roster board
- **Crafting Quarter** — forge, alchemist bench, tailor's table (all crafting station types); recipe merchants
- **Arena District** — PvP dueling zone, Arena Master NPC (leaderboards display), spectator seats
- **Old Grimly's Study** — Dungeon Finder NPC who gives lore and hands out dungeon entrance
- **Post Office** — Mail NPC (list/read/send mail)
- **Trophy Hall** — Hall of Fame display tied to leaderboard data; achievement plaques

#### 2. `thornhaven_sewers`
**Level range:** 5–8  
**Rooms:** ~15  
**Purpose:** Secret area beneath the city; accessible after completing the Rogue trainer's quest.

Features:
- Hidden doors, container chests with rare items
- Rogue-class flavor: mobs `sewer_rat`, `deserter_rogue`, `bloated_toad`
- One shop NPC ("the Fence") selling stolen goods at a premium
- Connection back to `thornhaven_city:sewer_grate`

---

### Tier 1 — Tutorial & First Steps (levels 1–3)

#### 3. `crossroads_path`
**Rooms:** ~12  
**Purpose:** The first zone a new character can enter. Gently guided tutorial experience.

Layout: A winding road approaching Thornhaven from the north. Characters land at
`crossroads_path:world_gate` and walk south toward town, with quest-giving NPCs at decision points.

Features:
- **Tutorial quest "A Traveler's Welcome"** — four steps leading to Thornhaven and the first trainer visit
- Rich atmospheric room descriptions showcasing the day/night sky and weather descriptions
- Signpost with brief Eryndal lore (one paragraph; no walls of text)
- No mobs harder than tier: weak

#### 4. `thornwood_forest`
**Level range:** 1–4  
**Rooms:** ~18  
**Purpose:** First open-world exploration zone. Nature, wildlife, early combat.

Mobs: `stray_wolf`, `territorial_hare`, `scrappy_fox`, `forest_bandit`, `cave_spider`, `mother_bear`  
Gathering nodes: `wildflower`, `forest_mushroom`, `birch_bark` (herbs)  
NPCs: Forester Lenna (Ranger trainer outpost; gives quest "The Bandit Problem")

Features:
- Behavior trees: wolves patrol in packs and call allies; mother_bear charges when young are present
- Day/night: owls spawn at night; deer visible only at dawn
- Herb gathering nodes feeding the crafting system
- Quest: **"The Bandit Problem"** — clear the bandit camp in `thornwood_forest:bandit_camp`

#### 5. `farmer_fields`
**Level range:** 1–3  
**Rooms:** ~10  
**Purpose:** Gentle quest hub for fresh characters. Low-threat, high narrative density.

Mobs: `field_crow`, `giant_slime`, `barn_rat`, `harvest_sprite`  
NPCs: Farmer Aldous (quest giver), Goodwife Petha (quest giver)

Features:
- Two quest chains: "The Slime Infestation" and "The Missing Chickens"
- First introduction to the `talk` command via Aldous's dialogue tree
- Container chest in the barn showcasing container/search mechanic
- Southern exits lead toward `sea_cliffs`

---

### Tier 2 — Low-Level Wilderness (levels 2–5)

#### 6. `cobblestone_road`
**Level range:** 2–5  
**Rooms:** ~10  
**Purpose:** The trade road west of Thornhaven. Weather and atmosphere showcase.

Mobs: `road_bandit`, `rabid_dog`, `ambush_brigand`  
NPCs: Traveling Merchant Pell (non-hostile; small road-supplies shop, brief dialogue)

Features:
- **Weather showcase** — zone cycles through rain, fog, and clear skies; room descriptions update accordingly
- Roadside inn room with a rest NPC
- Junction rooms connecting to `highland_trails` (north), `marsh_of_fog` (east), and `ruined_fortress` (southwest)

#### 7. `marsh_of_fog`
**Level range:** 3–6  
**Rooms:** ~15  
**Purpose:** Atmosphere-heavy zone with status effects and herbalism.

Mobs: `bog_leech`, `marsh_wraith`, `will_o_wisp`, `fungal_hulk`, `swamp_serpent`  
Gathering nodes: `bog_root`, `nightshade_flower`, `muck_crystal`  
NPCs: Hedge Witch Mossfoot (quest giver, herb buyer, teaches crafting recipes)

Features:
- **Status effects** — bog_leech applies POISON; will_o_wisp applies SLOW; marsh_wraith applies BLIND
- Herbalism gathering; Mossfoot buys herbs and teaches antidote recipe
- Persistent fog in room descriptions (weather flavor)
- Eastern exits lead toward `dark_barrows`
- Quest: **"The Witch's Request"** — gather three rare herbs

#### 8. `highland_trails`
**Level range:** 3–6  
**Rooms:** ~12  
**Purpose:** Scenic mountain foothills with weather variety and melee content.

Mobs: `mountain_goat`, `highland_bandit`, `cave_troll`, `stone_eagle`  
Gathering nodes: `mountain_herb`, `iron_ore`

Features:
- Snow weather in room descriptions at higher elevation rooms
- Gathering nodes for iron ore (shared material type with `old_mines`)
- Cave entrance room linking down into `old_mines`
- Scenic overlook room describing the surrounding landscape (farm fields south, sea glint southeast, mountains north)

#### 9. `old_mines`
**Level range:** 3–6  
**Rooms:** ~15  
**Purpose:** Abandoned silver mine. Primary crafting resource zone; quest chain start.

Mobs: `mine_goblin`, `kobold_digger`, `giant_rat`, `stone_lurker`, `Mine Foreman` (mini-boss)  
Gathering nodes: `silver_ore`, `copper_ore`, `iron_ore`, `raw_gemstone`  
NPCs: Survivor Hadrik (found injured deep in the mine; quest giver)

Features:
- Largest concentration of ore gathering nodes in the world
- Container chests with random gear drops
- Lever-operated collapsed passage (lever opens blocked shortcut room)
- Quest chain start: **"The Lost Expedition"**
- Northern cave exit connects to `highland_trails`; deeper tunnel leads to `goblin_warrens`

---

### Tier 3 — Mid-Level Dungeons (levels 4–7)

#### 10. `goblin_warrens`
**Level range:** 4–7  
**Rooms:** ~18  
**Purpose:** Classic dungeon crawl. Behavior trees, boss encounter.

Mobs: `goblin_scout`, `goblin_warrior`, `goblin_shaman`, `kobold_trapper`, `dire_rat`, `Chieftain Grak` (boss)

Features:
- **Behavior tree showcase**: goblin_scout patrols and calls allies; goblin_shaman buffs nearby goblins; Chieftain Grak flees at 20% HP then returns with reinforcements
- Boss achievement: "Chieftain Slayer" — kill Chieftain Grak
- Connects to `old_mines` (upper tunnels) and `dark_barrows` (secret eastern tunnel)
- Quest step 3 of "The Lost Expedition" — find expedition notes in the chief's chamber

#### 11. `sunken_temple`
**Level range:** 5–7  
**Rooms:** ~15  
**Purpose:** Partially flooded coastal ruins. Puzzle mechanics, undead, artifact quest.

Mobs: `temple_skeleton`, `dark_cultist`, `drowned_acolyte`, `stone_guardian`, `Elder Revenant` (boss)

Features:
- **Lever/door puzzles** — three levers must be pulled to open the inner sanctum (uses door/lever world feature system)
- Container urns and chests with lore items and gear
- Status effects: drowned_acolyte applies WEAKEN; stone_guardian applies STUN
- Accessed via cliff path from `sea_cliffs` — geographically offshore ruins reachable at low tide
- Quest: **"The Stolen Relic"** — recover an artifact from the Elder Revenant

#### 12. `dark_barrows`
**Level range:** 5–8  
**Rooms:** ~15  
**Purpose:** Ancient burial mounds in the deep fens. Powerful undead, necromancer boss.

Mobs: `barrow_wight`, `grave_hound`, `spectral_knight`, `banshee`, `Necromancer Vaelthos` (boss)

Features:
- Pre-combat dialogue on Vaelthos — brief exchange before he attacks
- Group-friendly content: Vaelthos is elite-tier, soloable with good gear or easy with two players
- Achievement: "Barrow Breaker" — kill Necromancer Vaelthos
- Leaderboard: Vaelthos kill time logged to the Trophy Hall
- Connects west to `marsh_of_fog` and east to `shadowmere_fen`
- Quest step 3 of "The Curse of Shadowmere"

#### 13. `ruined_fortress`
**Level range:** 5–8  
**Rooms:** ~18  
**Purpose:** Crumbling keep southwest of Thornhaven. Introduces the reputation/faction system.

Mobs: `iron_order_guard` (faction: Iron Order), `free_sword_rebel` (faction: Free Swords), `gargoyle`, `iron_golem`, `Commander Thane` (boss, Iron Order)

Features:
- **Reputation showcase** — Iron Order and Free Swords are opposing factions. Killing one raises rep with the other. Zone NPCs react to rep tier.
- Iron portcullis doors operated by levers; secret room behind a bookshelf container
- Quest step 1 of "The Celestial Reckoning" — find the ancient seal in Thane's vault

---

### Tier 4 — Higher-Level Content (levels 6–9)

#### 14. `sea_cliffs`
**Level range:** 5–8  
**Rooms:** ~12  
**Purpose:** Dramatic coastal cliffs south of Farmer Fields. Nautical mobs, sea atmosphere, bridge to Sunken Temple.

Mobs: `cliff_harpy`, `sea_raider`, `giant_crab`, `tide_elemental`, `Corsair Captain` (mini-boss)  
Gathering nodes: `sea_kelp`, `pearl_shard`, `salt_crystal` (reagents for alchemist recipes)

Features:
- Dramatic weather: coastal storms (fog + rain combined)
- Sea kelp and pearl shards for mid-tier crafting recipes
- Clifftop overlook room describing the ocean and distant silhouette of Sunken Temple
- Low-tide path exit connecting to `sunken_temple:tidal_approach`
- Quest: **"The Corsair's Bounty"** — defeat the Corsair Captain and recover stolen cargo; reward is a nautical-themed item set

#### 15. `shadowmere_fen`
**Level range:** 6–9  
**Rooms:** ~12  
**Purpose:** Cursed fenland. Faction resolution zone for the Shadowmere quest chain.

Mobs: `shadow_stalker` (faction: Shadowmere Cult), `silver_flame_paladin` (faction: Order of the Silver Flame), `shadow_hulk`, `nightshade_wisp`

Features:
- Dual-faction zone: player's previous kill balance from dark_barrows determines which faction is hostile
- Rare drops: `shadowmere_crystal` used in high-tier crafting
- Quest: final step of "The Curse of Shadowmere" — purify or embrace the shadow

#### 16. `frost_caverns`
**Level range:** 7–9  
**Rooms:** ~14  
**Purpose:** Ice caves deep in the mountains above the Highland Trails.

Mobs: `frost_imp`, `ice_golem`, `yeti`, `frozen_revenant`, `Ice Wyrm` (boss)  
Gathering nodes: `frost_crystal` (tier-3 crafting material), `glacial_ore`

Features:
- Group content: Ice Wyrm is a multi-target AoE encounter intended for 2–3 players
- Rare crafting: frost_crystal required for endgame weapon/armor recipes
- Weather: permanent blizzard condition
- Quest step 3 of "The Celestial Reckoning" — gather frost_crystals

#### 17. `haunted_manor`
**Level range:** 7–9  
**Rooms:** ~14  
**Purpose:** Cursed noble estate northwest of Thornhaven. Ghost NPCs, full dialogue trees, pet companion unlock.

Mobs: `poltergeist`, `manor_specter`, `animated_armor`, `howling_shade`, `Warden of the Manor` (boss)  
NPCs: **Ghost of Lady Veyra** — non-hostile; 6+ node dialogue tree providing end-game exposition

Features:
- Multi-branch dialogue with Lady Veyra; her story explains the Celestial Peak threat
- Ghost companion: defeating the Warden after completing Veyra's dialogue awards a SUMMON_PET ability (`spectral_wisp`)
- Status effects: animated_armor applies SLOW; howling_shade applies STUN (representing fear)
- Quest step 2 of "The Celestial Reckoning"

#### 18. `barrens_wastes`
**Level range:** 7–10  
**Rooms:** ~12  
**Purpose:** Blasted wasteland far west of the road. High-level overworld, bounty hunts, PvP flavor.

Mobs: `wasteland_raider`, `dust_elemental`, `scavenging_wyvern`, `marauder_captain` (elite)

Features:
- Bounty quest board (rotating kill-count quests for gold/XP → feeds "Bounty Hunter" achievement)
- Open PvP narrative: room descriptions note the Barrens are lawless; the duel system works here like anywhere
- Connects north to `frost_caverns` approach and east toward `celestial_peak` via mountain trail

---

### Tier 5 — End-Game (levels 8–10)

#### 19. `celestial_peak`
**Level range:** 8–10  
**Rooms:** ~16  
**Purpose:** Summit of Eryndal's highest mountain. End-game combat, legendary gear, final boss.

Mobs: `celestial_guardian`, `storm_elemental`, `divine_construct`, `fallen_angel`, `Elder Dragon Auranthos` (final boss)

Features:
- Final boss: Auranthos has pre-combat dialogue and a two-phase behavior tree (phase 2 triggers at 50% HP)
- Achievement: "Dragonslayer" — kill Auranthos; also awards a title
- Leaderboard: Auranthos kill time logged to Trophy Hall
- Best-in-slot gear drops
- Quest: final step of "The Celestial Reckoning"

#### 20. `dungeon_of_echoes`
**Level range:** 3–10 (scales to party)  
**Rooms:** generated  
**Purpose:** Procedural instanced dungeon. Showcases the entire dungeon system.

Features:
- Uses `DungeonManager` / `DungeonGenerator` engine systems
- Two templates: `echoes_standard` and `echoes_hard`
- Mob level scales to initiating player's level ±1
- Entrance via Old Grimly in `thornhaven_city`
- Achievement: "Echo Diver" — complete a run
- Boss room at end with a pool of 4 randomized boss templates
- Crafting material and level-appropriate gear drops

---

## Quest Chains

### Chain 1: "A Traveler's Welcome" (levels 1–3, tutorial)

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
| 3 | goblin_warrens | Recover the lead scholar's journal from Chieftain Grak's chamber | 600 XP + Chieftain Slayer achievement |
| 4 | thornhaven_city | Return journal to the scholar's guild contact | 800 XP + rare item |

### Chain 3: "The Curse of Shadowmere" (levels 5–8)
The fens grow darker. Something in the barrows is stirring.

| Step | Zone | Objective | Reward |
|------|------|-----------|--------|
| 1 | marsh_of_fog | Speak to Hedge Witch Mossfoot; gather three rare herbs | 400 XP + antidote recipe |
| 2 | dark_barrows | Find Vaelthos's research notes | 600 XP |
| 3 | dark_barrows | Kill Necromancer Vaelthos | 1000 XP + Barrow Breaker achievement |
| 4 | shadowmere_fen | Choose a faction and complete the ritual | 1500 XP + faction title |

### Chain 4: "The Celestial Reckoning" (levels 8–10)
An ancient evil at the summit threatens to consume the realm.

| Step | Zone | Objective | Reward |
|------|------|-----------|--------|
| 1 | ruined_fortress | Find the ancient order's seal in Thane's vault | 800 XP |
| 2 | haunted_manor | Learn the ancient rite from Ghost of Lady Veyra | 1000 XP + spectral_wisp pet |
| 3 | frost_caverns | Gather 3 frost_crystals | 1200 XP |
| 4 | celestial_peak | Kill Elder Dragon Auranthos | 3000 XP + Dragonslayer achievement + title |

---

## Key NPCs

| Name | Zone | Purpose | Engine Feature |
|------|------|---------|---------------|
| Innkeeper Mira | thornhaven_city | Recall point, inn lore | set_recall dialogue action |
| Captain Varek | thornhaven_city | Warrior trainer, lost expedition quest | train list/learn, quest give |
| Archmage Solen | thornhaven_city | Mage trainer | train list/learn |
| High Priest Aldric | thornhaven_city | Cleric trainer | train list/learn |
| Shadow "Shade" | thornhaven_city | Rogue trainer, sewer key quest | train list/learn, gate content |
| Forester Lenna | thornwood_forest + thornhaven_city | Ranger trainer, bandit quest | train list/learn, quest give |
| Banker Theron | thornhaven_city | Bank deposits/withdrawals | bank dialogue actions |
| Old Grimly | thornhaven_city | Dungeon finder | dungeon_enter trigger |
| Mail Clerk Oswin | thornhaven_city | Mail send/read | mail commands |
| Hedge Witch Mossfoot | marsh_of_fog | Herb buyer, curse quest giver | shop, quest give |
| Survivor Hadrik | old_mines | Expedition quest giver | quest give |
| Traveling Merchant Pell | cobblestone_road | Road supplies shop, lore | shop, dialogue |
| Corsair Captain | sea_cliffs | Mini-boss, bounty quest target | combat, achievement trigger |
| Ghost of Lady Veyra | haunted_manor | End-game lore, ghost pet unlock | multi-branch dialogue tree |
| Necromancer Vaelthos | dark_barrows | Boss; pre-combat dialogue | dialogue then combat |
| Commander Thane | ruined_fortress | Faction boss | faction kill, achievement |
| Elder Dragon Auranthos | celestial_peak | Final boss; multi-phase | behavior tree phase trigger |
| Arena Master | thornhaven_city | PvP duel rules, leaderboard | leaderboard display |
| Guild Registrar | thornhaven_city | Guild creation/management | guild dialogue actions |

---

## Feature Showcase Matrix

| Feature | Primary Zone(s) |
|---------|----------------|
| Basic combat | thornwood_forest, farmer_fields |
| NPC dialogue trees | thornhaven_city (all trainers + innkeeper + banker) |
| Multi-branch dialogue with actions | thornhaven_city (Mira recall), haunted_manor (Lady Veyra) |
| Quest system | all zones; full chain in each tier |
| Class trainers (skill points) | thornhaven_city, thornwood_forest (Ranger) |
| Bank NPC | thornhaven_city |
| Inn / recall point | thornhaven_city |
| Shops (gear + potions) | thornhaven_city, cobblestone_road, marsh_of_fog |
| Auction house | thornhaven_city |
| Mail system | thornhaven_city |
| Crafting stations | thornhaven_city (crafting quarter) |
| Gathering nodes (herbs) | thornwood_forest, marsh_of_fog, sea_cliffs |
| Gathering nodes (ore) | old_mines, highland_trails, frost_caverns |
| Crafting recipes | thornhaven_city (NPCs/drops); marsh_of_fog (Mossfoot) |
| Recipe discovery | old_mines (drop), sea_cliffs (NPC) |
| Status effects (poison/slow/blind) | marsh_of_fog |
| Status effects (stun/weaken) | sunken_temple |
| Status effects (stun/slow) | haunted_manor |
| Weather system (rain/fog) | cobblestone_road, sea_cliffs |
| Weather system (snow/blizzard) | highland_trails, frost_caverns |
| Day/night cycle | thornwood_forest, crossroads_path, thornhaven_city |
| Behavior trees (patrol/call allies) | goblin_warrens |
| Behavior trees (flee + return) | goblin_warrens (Chieftain Grak) |
| Behavior trees (multi-phase boss) | celestial_peak (Auranthos phase 2) |
| Pre-combat dialogue | dark_barrows (Vaelthos), celestial_peak (Auranthos) |
| Door/lever puzzles | sunken_temple, ruined_fortress |
| Container / search mechanic | farmer_fields, old_mines, thornhaven_sewers |
| Group content | goblin_warrens, dark_barrows, frost_caverns, dungeon_of_echoes |
| Instanced dungeons | dungeon_of_echoes |
| Pet / companion system | haunted_manor (ghost pet), thornwood_forest (Ranger hawk) |
| PvP dueling | thornhaven_city (arena district) |
| Guild system | thornhaven_city (guild registry) |
| Reputation / faction system | ruined_fortress + shadowmere_fen |
| Achievements | all zones (15 distinct achievements) |
| Leaderboards / Hall of Fame | thornhaven_city (Trophy Hall), celestial_peak, dark_barrows |
| Titles | chains 1 and 4; faction choice in chain 3 |
| Sprite progression | tier advancement (automatic) |
| GMCP / minimap | all zones |
| Day/night/weather atmosphere | all outdoor zones |
| Multi-classing | thornhaven_city (trainer, level 10+) |

---

## Achievement List

| ID | Name | Trigger | Zone |
|----|------|---------|------|
| first_blood | First Blood | Kill any mob for the first time | any |
| wolf_hunter | Wolf Hunter | Kill 10 stray_wolves | thornwood_forest |
| chieftain_slayer | Chieftain Slayer | Kill Chieftain Grak | goblin_warrens |
| corsair_hunter | Corsair Hunter | Kill the Corsair Captain | sea_cliffs |
| barrow_breaker | Barrow Breaker | Kill Necromancer Vaelthos | dark_barrows |
| dragon_slayer | Dragonslayer | Kill Elder Dragon Auranthos | celestial_peak |
| echo_diver | Echo Diver | Complete a dungeon_of_echoes run | dungeon_of_echoes |
| master_crafter | Master Crafter | Craft 20 items | any |
| herb_collector | Hedge Witch's Friend | Gather 10 herbs | marsh_of_fog |
| ore_miner | Deep Delver | Gather 10 ore | old_mines |
| full_party | Strength in Numbers | Complete a dungeon with a full group | dungeon_of_echoes |
| bounty_hunter | Bounty Hunter | Complete 5 bounty quests | barrens_wastes |
| loyal_companion | Loyal Companion | Unlock the ghost pet | haunted_manor |
| guild_founder | Guild Founder | Create a guild | thornhaven_city |
| max_level | Level Ten | Reach level 10 | any |

---

## Content Progression (Levels 1–10)

| Level | Target Zones | Key Activities |
|-------|-------------|---------------|
| 1 | crossroads_path, thornhaven_city, thornwood_forest | Tutorial, set recall, first combat |
| 2 | thornwood_forest, farmer_fields | Quest completion, first gear |
| 3 | cobblestone_road, old_mines (entrance) | Road travel, mine mobs, Lost Expedition start |
| 4 | old_mines, marsh_of_fog, highland_trails | Full mine, status effects, herbs |
| 5 | goblin_warrens, sea_cliffs, sunken_temple (approach) | Boss fight, coastal content |
| 6 | dark_barrows, ruined_fortress, sunken_temple | Group content, puzzles, faction intro |
| 7 | shadowmere_fen, thornhaven_sewers, haunted_manor (entrance) | Faction choice, secret area |
| 8 | haunted_manor, barrens_wastes | Ghost dialogue, bounty hunts |
| 9 | frost_caverns, celestial_peak (approach) | Rare crafting, group boss |
| 10 | celestial_peak, dungeon_of_echoes | Final boss, endgame content |

---

## Item Economy

### Starter Gear (Levels 1–2)
Dropped in thornwood_forest and farmer_fields; no gold required:
- `worn_sword`, `battered_staff`, `hunting_bow`, `rusty_dagger` (class-appropriate; Ranger uses bow)
- `leather_cap`, `padded_vest`

### Purchased Gear (Levels 2–5)
Thornhaven Market; affordable with quest rewards:
- `iron_sword`, `oak_staff`, `short_bow`, `silver_dagger`
- `iron_helm`, `chainmail_vest`, `leather_bracers`
- `minor_healing_potion` (50 gold), `clarity_potion` (75 gold)

### Crafted Gear (Levels 3–8)
Materials from gathering; recipes from NPCs and drops:
- `steel_sword` (iron_ore ×3 + coal ×1)
- `mage_focus` (silver_ore ×2 + wildflower ×1)
- `healing_salve` (bog_root ×1 + wildflower ×2) — consumable, same tier as minor_healing_potion
- `pearl_ring` (pearl_shard ×2 + silver_ore ×1) — accessory with CHA bonus

### Rare/Endgame Gear (Levels 7–10)
Boss drops and frost_caverns materials:
- `glacial_blade` (glacial_ore ×2 + frost_crystal ×1)
- `dragon_scale_vest` (dropped by Auranthos)
- `shadow_mantle` (shadowmere_crystal ×3)

---

## Old Zone Cleanup

All existing world zone files will be deleted as part of Phase 1. None of their content is reused.

**Files to delete:**
- `ambon_hub.yaml`
- `tutorial_glade.yaml`
- `crafting_workshop.yaml`
- `demo_ruins.yaml`
- `sunken_crypt.yaml`
- `low_training_marsh.yaml`
- `low_training_highlands.yaml`
- `low_training_mines.yaml`
- `low_training_barrens.yaml`
- `labyrinth.yaml`
- `celestial_sanctum.yaml`
- `noecker_resume.yaml`

**Files to keep (data definitions, not world zones):**
- `achievements.yaml` — will be rewritten for Eryndal achievement IDs
- `sprites.yaml`, `player_sprites.yaml` — unchanged; sprite system is world-agnostic

**Config changes required:**
1. Add `RANGER` class block to `application.yaml` with full ability definitions (see above)
2. Add `HALF_ORC` and `GNOME` race blocks to `application.yaml`
3. Add full Ranger ability set to `application.yaml` abilities section
4. Update `classStartRooms` — all five classes point to `thornhaven_city:new_arrivals_hall`
5. Remove all references to deleted zone names from config
6. Add `dungeon_of_echoes` dungeon templates to the dungeon config section
7. Add faction definitions for Iron Order, Free Swords, Shadowmere Cult, Order of the Silver Flame
8. Copy `demo/Default*.png` files to `src/main/resources/world/images/demo/`

---

## Implementation Order

One GitHub issue and one PR per phase. Each phase is self-contained and leaves the server in a
runnable state with a testable demo checkpoint.

### Phase 1: Hub + Config Foundation
**Scope:** `thornhaven_city.yaml` + `crossroads_path.yaml` + all config changes

Deliverables:
- Write both zone YAML files from scratch
- Add RANGER class + all 10 ranger abilities to `application.yaml`
- Add HALF_ORC and GNOME to `application.yaml`
- Update classStartRooms
- Add dungeon template stubs for `dungeon_of_echoes`
- Delete all old zone files listed above
- Copy `demo/Default*.png` to `src/main/resources/world/images/demo/`
- Update `achievements.yaml` with the 15 Eryndal achievement IDs (stubs; triggers filled in per zone)

**Demo checkpoint:** All 5 classes and 6 races can be selected. All trainers accessible. Inn, bank,
shop, mail, guild, crafting quarter, arena, and dungeon finder all reachable and functional.

### Phase 2: Early Wilderness
**Scope:** `thornwood_forest.yaml` + `farmer_fields.yaml` + `cobblestone_road.yaml`

**Demo checkpoint:** Tutorial chain "A Traveler's Welcome" fully completable. Levels 1–3 playable.

### Phase 3: Low-Level Wilderness
**Scope:** `highland_trails.yaml` + `old_mines.yaml` + `marsh_of_fog.yaml`

**Demo checkpoint:** Levels 3–5 playable. "The Lost Expedition" chain steps 1–2 completable.
Status effects (poison, slow, blind) working. Herb and ore gathering nodes functional.

### Phase 4: Mid-Level Dungeons
**Scope:** `goblin_warrens.yaml` + `dark_barrows.yaml`

**Demo checkpoint:** Levels 4–7 content. "The Lost Expedition" fully completable. Behavior trees
(patrol, call allies, flee-return) demonstrated. Chieftain Grak and Vaelthos boss fights working.

### Phase 5: Coastal + Temple Content
**Scope:** `sea_cliffs.yaml` + `sunken_temple.yaml`

**Demo checkpoint:** Levels 5–8 coastal content. Lever/door puzzle in sunken_temple working.
Coastal weather (storm) and tidal path mechanic functional.

### Phase 6: Factions + Secret Areas
**Scope:** `ruined_fortress.yaml` + `shadowmere_fen.yaml` + `thornhaven_sewers.yaml`

**Demo checkpoint:** Reputation/faction system fully demonstrated. "The Curse of Shadowmere"
chain completable. Sewer secret area accessible after Rogue quest.

### Phase 7: High-Level Content
**Scope:** `haunted_manor.yaml` + `barrens_wastes.yaml` + `frost_caverns.yaml`

**Demo checkpoint:** Levels 7–9 content. Ghost pet unlock working. Bounty board functional.
Rare crafting materials (frost_crystal) available.

### Phase 8: End-Game + Polish
**Scope:** `celestial_peak.yaml` + `dungeon_of_echoes` dungeon template

Deliverables:
- `celestial_peak.yaml` with two-phase Auranthos boss
- Full dungeon template YAML for `dungeon_of_echoes`
- Final `achievements.yaml` with all 15 achievement triggers wired to correct zone/mob IDs
- Update README demo section with Eryndal world overview
- Smoke-test full levels 1–10 playthrough

**Demo checkpoint:** Complete world playable end-to-end. All four quest chains completable.
Every feature in the showcase matrix demonstrated. Demo live.
