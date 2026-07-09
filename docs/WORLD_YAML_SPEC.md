# World Zone YAML Spec

This document defines the YAML contract loaded by `WorldLoader` (`src/main/kotlin/dev/ambon/domain/world/load/WorldLoader.kt`).
It is written for code generators that need to emit valid zone files.

## Scope

- One YAML document describes one zone file.
- Multiple zone files can be merged into one world.
- YAML files are deserialized into the DTOs in `src/main/kotlin/dev/ambon/domain/world/data/`:
  - `WorldFile` (`zone`, `lifespan`, `startRoom`, `graphical`, `pvpEnabled`, `terrain`, `faction`, `scaling`, `image`, `audio`, `video`, `rooms`, `mobs`, `items`, `shops`, `trainers`, `quests`, `gatheringNodes`, `recipes`, `dungeon`, `puzzles`)
  - `RoomFile` (with `ExitValue`/`DoorFile` for exits and `FeatureFile` for containers/levers/signs)
  - `MobFile` (with `MobSpawnFile`, `MobDropFile`, `MobSpellFile`, `BehaviorFile`, `DialogueNodeFile`, `SpawnConditionFile`)
  - `ItemFile`
  - `ShopFile`
  - `TrainerFile`
  - `QuestFile`
  - `GatheringNodeFile`
  - `RecipeFile`
  - `DungeonFile`
  - `PuzzleFile`
- **Unknown fields are silently ignored** (the loader disables `FAIL_ON_UNKNOWN_PROPERTIES`). A misspelled optional field does not error — it just does nothing. Do not rely on this; emit only fields listed here.

## Top-Level Schema

```yaml
zone: <string, required, non-blank after trim>
lifespan: <integer minutes >= 0, optional>
startRoom: <room-id string, required>
graphical: <boolean, optional, default false>  # true if the zone has custom graphical assets
pvpEnabled: <boolean, optional, default false>  # when true, players can attack each other in this zone
terrain: <string, optional - default terrain for all rooms in this zone; see terrain notes>
faction: <string, optional - controlling faction id; inherited by mobs that don't set their own>
scaling:                # optional dynamic level-scaling config
  mode: <string, optional - one of static|bounded|player (case-insensitive); default static>
  levelRange: [<min>, <max>]  # required for bounded mode; min >= 1, max >= min
image:                  # zone-wide image defaults
  room: <string, optional>
  mob: <string, optional>
  item: <string, optional>
audio:                  # zone-wide audio defaults
  music: <string, optional>
  ambient: <string, optional>
video: <string, optional - relative path under /videos/; zone cinematic that auto-plays
        on a player's first entry to the zone and is replayable from the expanded map>
rooms: <map<string, Room>, required, must be non-empty>
mobs: <map<string, Mob>, optional, default {}>
items: <map<string, Item>, optional, default {}>
shops: <map<string, Shop>, optional, default {}>
trainers: <map<string, Trainer>, optional, default {}>
quests: <map<string, Quest>, optional, default {}>
gatheringNodes: <map<string, GatheringNode>, optional, default {}>
recipes: <map<string, Recipe>, optional, default {}>
dungeon: <Dungeon, optional - one procedural dungeon template per zone; see Dungeon section>
puzzles: <map<string, Puzzle>, optional, default {}>
```

`lifespan` notes:
- Units are minutes.
- `0` is allowed and, in the current engine, effectively disables runtime resets (zones reset only when `lifespan > 0`).

`pvpEnabled` notes:
- When `true`, players in this zone can attack each other with `kill <player>`.
- PvP death respawns the defeated player at the zone's `startRoom` with full HP/mana, no loot loss.
- PvP kills and deaths are tracked on `PlayerRecord` (`pvpKills`/`pvpDeaths`).

`terrain` notes:
- Valid values (case-insensitive): `inside`, `outside`, `forest`, `mountain`, `underground`, `underwater`, `desert`, `swamp`, `urban`, `sky`. Any other value is a load error.
- Rooms without their own `terrain` inherit the zone value; if neither is set, rooms default to `outside`.
- `inside`, `underground`, and `underwater` are treated as sheltered (no weather exposure).

`faction` notes:
- Names a faction id from `application.yaml` `engine.factions.definitions`. When the faction config is available at load, an unknown zone faction logs a **warning** (treated as no controlling faction) — it is not a load error.
- Mobs inherit the zone faction unless they declare their own `faction` (mob-level values are not validated).

`scaling` notes:
- `static` (default): mobs and quests use their authored `level` fields as-is.
- `bounded`: mobs and quests scale to the level of the highest-level player in the zone, clamped to `levelRange`; the range is required for this mode and must be `[min >= 1, max >= min]` with exactly two values.
- `player`: mobs and quests scale directly to the reference player's level with no bounds (tutorial zones, social hubs).
- Declaring conflicting `scaling` blocks for the same zone across files is a load error.

### Required vs optional

- Required top-level fields: `zone`, `startRoom`, `rooms`
- Optional top-level fields: `lifespan`, `graphical`, `pvpEnabled`, `terrain`, `faction`, `scaling`, `image`, `audio`, `video`, `mobs`, `items`, `shops`, `trainers`, `quests`, `gatheringNodes`, `recipes`, `dungeon`, `puzzles`

## Nested Schemas

### `rooms` map

Each key is a room ID (local or fully qualified).
Each value:

```yaml
title: <string, required>
description: <string, required>
exits: <map<string direction, Exit>, optional, default {}> # string or object form; see Exits
features: <map<string feature-id, Feature>, optional, default {}> # containers/levers/signs; see Room features
station: <string, optional - crafting station type id; see `station` notes>
bank: <boolean, optional, default false>
stylist: <boolean, optional, default false>
tavern: <boolean, optional, default false>
dungeon: <boolean, optional, default false>
auction: <boolean, optional, default false>
housingBroker: <boolean, optional, default false>
inn: <boolean, optional, default false>
akathavaeShrine: <boolean, optional, default false>
flightMaster: <boolean, optional, default false>
flightMapX: <number, optional - 0..100, % across the Ambon world map; only meaningful with flightMaster>
flightMapY: <number, optional - 0..100, % down the Ambon world map; only meaningful with flightMaster>
boatDock: <boolean, optional, default false>
boatMapX: <number, optional - 0..100, % across the Ambon world map; only meaningful with boatDock>
boatMapY: <number, optional - 0..100, % down the Ambon world map; only meaningful with boatDock>
boatRoutes: <list of {to, price} objects, optional - see `boatDock` notes>
image: <string, optional - relative path under /images/; falls back to zone image.room>
video: <string, optional - relative path under /videos/, shown as clickable cinematic>
music: <string, optional - overrides zone audio.music>
ambient: <string, optional - overrides zone audio.ambient>
jukebox: <list of song objects, optional - see `jukebox` notes>
musicBox: <single song object, optional - see `musicBox` notes>
terrain: <string, optional - overrides the zone terrain; same valid values>
mapX: <integer, optional - explicit minimap grid column; see map pin notes>
mapY: <integer, optional - explicit minimap grid row; must be given with mapX>
mapZ: <integer, optional, default 0 - minimap floor; requires mapX/mapY>
```

#### Exits

Valid direction keys (case-insensitive):

- `n`, `north`
- `s`, `south`
- `e`, `east`
- `w`, `west`
- `u`, `up`
- `d`, `down`

Each exit value is either a plain target-room-id string, or an object:

```yaml
exits:
  n: hall                        # simple string form
  e:
    to: inner_keep               # required in object form
    door:                        # optional door on this exit
      initialState: locked       # open|closed|locked (case-insensitive), default closed
      keyItemId: iron_key        # optional; normalized; must resolve to an existing merged item
      keyConsumed: false         # default false - whether unlocking consumes the key
      resetWithZone: true        # default true - door reverts on zone reset
      respawnSeconds: 90         # optional, > 0 - independent revert timer; see Timed respawn
      frameImage: door_frame.png # optional web art overrides (resolved under the zone images base)
      leafImage: door_leaf.png
      hinge: right               # left|right, default right
      openAngle: 60              # degrees when open, default ~60
      leafScale: 0.76            # leaf size as fraction of the frame box
      leafOffsetY: 0.09          # vertical leaf placement (fraction; + = down)
    requiresAchievement: academy_boss_slain   # optional per-player gate
    lockedMessage: "The door is sealed until you prove yourself."  # shown when gated
```

- A door becomes a room feature with id `<room-id>/<dir-abbrev>` (e.g. `crypt:entry/n`).
- `requiresAchievement` gates the exit per player: only characters whose unlocked achievements include the id can pass. `lockedMessage` is the refusal text.
- **Exit targets are not hard-validated.** An exit pointing to a room that doesn't exist in the merged world logs a warning and is presented in-game as an unreachable, shimmering way (useful for exits into zones not loaded on this engine). It does not fail the load.

#### Room features

Non-exit interactive features (exit doors are declared inside `exits`, above). Each key is a local feature id; the runtime id becomes `<room-id>/<feature-id>`.

```yaml
features:
  old_chest:
    type: CONTAINER              # required - CONTAINER | LEVER | SIGN (case-insensitive)
    displayName: "an oak chest"  # required, non-blank
    keyword: chest               # required, non-blank - what players type
    initialState: closed         # CONTAINER: open|closed|locked (default closed); LEVER: up|down (default up); ignored for SIGN
    keyItemId: brass_key         # CONTAINER only; normalized; must resolve to an existing merged item
    keyConsumed: false           # default false
    resetWithZone: true          # default true
    respawnSeconds: 120          # optional, > 0; rejected on SIGN (signs have no state)
    items: [healing_potion]      # CONTAINER only - initial contents; each must resolve to an existing merged item
    text: "Beware the lower crypt." # SIGN only - required for SIGN
    backgroundImage: chest_bg.png   # optional backdrop art for the web features modal (all types)
    plateImage: lever_plate.png     # LEVER only - static base art
    handleImage: lever_handle.png   # LEVER only - rotating handle art
    leverPivot: { x: 0.5, y: 0.85 } # LEVER only - handle pivot as sprite fractions
    upAngle: -28                    # LEVER only - handle rotation (degrees) when up
    downAngle: 28                   # LEVER only - handle rotation (degrees) when down
```

- Container refills on zone reset / `respawnSeconds` replace whatever is inside, including player-stored items.
- Sequence puzzles reference features by `keyword` (see Puzzles).

Map pin notes (`mapX`/`mapY`/`mapZ`):
- By default the loader assigns every room a minimap cell by BFS from the zone's start room (N/S/E/W step one cell; up/down starts a new floor). `mapX`/`mapY` **pin** a room to an exact grid cell instead, and `mapZ` picks its floor (0 = ground, positive = up). `+x` is east, `+y` is south, in the zone's own frame — absolute values don't matter, only relative placement.
- Pinned rooms are seated first, exactly where the author put them; unpinned rooms are BFS-placed around them (relative to their pinned neighbours where possible). Arcanum's zone editor "Save map layout" writes pins for every room; hand-editing is fine too.
- Loading fails if `mapX`/`mapY` is given without the other, if `mapZ` appears without both, or if two rooms in the same zone are pinned to the same cell of the same floor.

`bank` notes:
- When `true`, enables bank commands (`deposit`, `withdraw`, `bank`) in this room.
- Bank commands: `deposit`, `withdraw`, `bank`. Configurable via `ambonMUD.engine.bank` in `application.yaml`.

`stylist` notes:
- When `true`, enables stylist commands (`stylist`, `changerace <race>`) in this room.
- The stylist charges a configurable gold fee (default 500, see `ambonMUD.engine.stylist.feeGold`) to swap a character's race.
- The swap applies the new race's stat modifiers as a delta against the old race's modifiers, then recomputes derived HP/mana caps. Stats gained from levelling, prestige, or equipment are preserved.
- Racial abilities are **not** currently transferred — see GH issue #993.
- Shows a Stylist badge on the web client via the standard panel drawer.

`tavern` notes:
- When `true`, enables gambling commands (`gamble`, `dice`) and lottery ticket purchases (`lottery buy`) in this room. (`lottery` info works anywhere.)
- Also shows Lottery and Dice badges on the web client canvas.

`auction` notes:
- When `true`, marks this room as an auction house: shows the Auction badge + kiosk panel on the web client.

`housingBroker` notes:
- When `true`, marks this room as a housing broker's office: shows the Housing badge + kiosk panel on the web client.

`flightMaster` notes:
- When `true`, this room hosts a flight master: `flights` lists the flight points the player can travel to, `fly <name|#>` pays gold to fast-travel there.
- The network is **per-player and discovery-gated** — visiting a `flightMaster` room records it; from any flight master you can fly to any point you've personally discovered. Mark every roost in the network with `flightMaster: true`.
- The fare scales with travel distance (BFS hops between the current room and the destination), clamped to a min/max. Flying is blocked in combat; otherwise gold is the only gate. Configurable via `ambonMUD.engine.flight` in `application.yaml`.
- Shows a Flight badge + destination panel on the web client canvas.
- `flightMapX`/`flightMapY` pin this roost on the painted Ambon world map (`flight_map` global asset) in the web kiosk: percentages of the map image (`0` = left/top edge, `100` = right/bottom). They drive a clickable griffin hotspot — the same percentage convention as equipment paper-doll slots, understood by both Arcanum and the engine. Omit them and the roost still works, just listed textually under the map rather than pinned. Loading fails if either value is outside `0..100`.

`boatDock` notes:
- When `true`, this room is a boat dock: `voyages` lists the routes the player can sail from here, `sail <name|#>` pays a flat fare to travel there.
- Unlike the flight master, boats are **authored, not discovered**: every route is listed in this room's `boatRoutes`, available immediately, with a fixed **author-set price** paid on **every** trip (no distance scaling, no ownership, no persistence). Sailing is blocked in combat; otherwise gold is the only gate.
- `boatRoutes` is a list of `{ to: <zone:room>, price: <gold> }`. `to` may be local (`room`) or cross-zone (`other:room`), like an exit target; `price` must be `>= 0`. A route whose `to` room isn't loaded on this engine is silently skipped (mirrors the flight kiosk's tolerance for unloaded zones), so a typo'd destination simply won't appear.
- `boatMapX`/`boatMapY` pin this dock on the painted Ambon world map (`boat_map` global asset — by default the same uploaded art as the flight kiosk) and double as the map pin for any route whose destination is this room. They drive a clickable anchor hotspot (`boat_dock` marker asset); omit them and the dock still works, with routes listed textually instead of pinned. Loading fails if either value is outside `0..100`. Message strings are configurable via `ambonMUD.engine.boat` in `application.yaml`.
- A room may be both a `flightMaster` and a `boatDock` — a transit hub shows both badges and both kiosks.

`jukebox` notes:
- A non-empty `jukebox` list turns this room into a jukebox: players pay gold to play a song that becomes the room's music for everyone present, locked for the song's `durationSeconds`, then the room reverts to its default `music`.
- Commands: `jukebox` / `jb` lists the playlist; `jukebox play <n>` / `jb play <n>` pays for the n-th song. Shows a Jukebox badge + picker panel on the web client.
- Globally gated by `ambonMUD.engine.jukebox.enabled`; `maxSongDurationSeconds` is a sanity bound.
- Each song object:
  ```yaml
  jukebox:
    - title: "Tavern Reel"              # required
      file: jukebox/tavern_reel.mp3     # required - resolved under the zone audio base, like `music`
      durationSeconds: 90               # required, > 0 - how long it locks the room (≈ the track length)
      cost: 5                           # optional, gold (default 5); >= 0
      artist: "The Wandering Bards"      # optional
      description: "A foot-stomping reel about a barkeep's lost cat."  # optional lore flavour
      lyrics:                           # optional - lines broadcast to the room, spread over the duration
        - "Oh the barkeep's cat ran out the door"
        - "She chased a rat across the floor"
  ```
- Text-only flavour (players without audio): the `description` is broadcast to the room when the song starts, each `lyrics` line is broadcast as `♪ line ♪` spread evenly across `durationSeconds` (it need not match the actual sung pacing), and the room is told when the song ends. Lyrics are capped at one line per 3 seconds of duration; lines must be non-blank.

`musicBox` notes:
- A `musicBox` block turns this room into a music box: a one-song miniature of the jukebox (a miniaturization of Tessikar's musical device). Unlike the room-wide, paid jukebox, the music box is **free** and **player-scoped** — winding it up starts the song for *you* only, and it follows you out of the room until it ends or you stop it.
- Commands: `musicbox` / `mb` opens the device (shows the song + lyrics); `musicbox play` / `mb play` winds it up; `musicbox stop` / `mb stop` closes it. Shows a Music Box kiosk badge + device panel on the web client; the badge stays in the rail wherever the player goes while their song is still playing.
- The song object mirrors a jukebox song minus `cost` (it is always free):
  ```yaml
  musicBox:
    title: "Tessikar of Kaerinlith"      # required
    file: musicbox/lullaby.mp3           # required - resolved under the zone audio base, like `music`
    durationSeconds: 243                 # required, > 0 - the play length
    artist: "TRADITIONAL"                # optional
    description: "A palm-sized walnut-and-brass music box on the desk."  # optional lore flavour
    image: items/lyric-sheet.png         # optional - keepsake art, resolved under the zone images base
    lyrics:                              # optional - shown on the device, spread over the duration
      - "Born beyond the veil"
      - "Where brass sang slow"
  ```
- Lyric timing is computed on the client from the song's start, `durationSeconds`, and line count (lines spread evenly; pacing need not match the sung pacing). On the open device the current line is lit and scrolls; while the device is closed each line pops as a brief 🎵 toast so an exploring player can keep up. The personal song plays over the room's jukebox/default music for the player who started it. Lyrics are capped at one line per 3 seconds of duration; lines must be non-blank.
- The first time a player winds up a given music box, a collectible **lyric-sheet keepsake** is minted into their inventory (a bound souvenir holding the title, artist, and full lyrics; replays are quiet). `image` sets that keepsake's picture — an optional filename resolved under the zone images base, exactly like room/item art. When omitted, the keepsake falls back to the web client's generic item default.

`dungeon` notes:
- When `true`, shows a Dungeon badge on the web client canvas that opens the dungeon kiosk panel.

`inn` notes:
- When `true`, enables the `rest` command in this room. Resting sets the player's recall point to this room.
- Shows an Inn badge on the web client canvas; the popout displays the player's current recall point and offers a "Rest & Set Recall Here" button.
- The recall point is the destination of the `recall` command. Rest is blocked while in combat.

`akathavaeShrine` notes:
- When `true`, enables the `pledge` and `renounce` commands in this room.
- Pledging is free and converts the player into the AKATHAVAE class (their former class is stashed and restored on renounce): combat and multiclassing are forbidden, vitals rescale to the Akathavae curve, and the player levels by illuminating the world (recording rooms, creatures, and items in their Arcanum journal).
- Renouncing at a shrine costs gold (`ambonMUD.engine.akathavae.renounceCostGold`, default 2500), restores the former class, and starts a re-pledge cooldown (`repledgeCooldownMs`, default 24h).
- **Content guideline — quest collect items:** every item targeted by a quest `collect` objective should be obtainable through at least one non-kill channel: a room/ground spawn, a shop, a gathering node, or a mob drop. Mob drops are reachable on the pacifist path because illuminating a creature grants a drop the player still needs for an active collect objective (once per living instance, capped at the remaining count). An item that only exists as loot from a mob no Akathavae can illuminate would make the quest impossible for the pledged.

`station` notes:
- Designates the room as a crafting station of the given type.
- Station type ids are **data-driven** via `ambonMUD.engine.crafting.stationTypes` in `application.yaml` (shipped defaults: `forge`, `alchemy_table`, `workbench`, `enchanting_table`). The loader lowercases the value and only requires it to be non-blank — it does **not** validate against the configured set, so an unconfigured id loads but never matches a recipe.
- Recipes that specify a matching `station` type receive a bonus when crafted in this room.
- Visible in room descriptions as "Crafting station: Forge" (etc.).

### `mobs` map

Each key is a mob ID (local or fully qualified).
Each value:

```yaml
name:           <string, required>
description:    <string, optional, default "">
room:           <room-id string - legacy single-spawn shorthand; see `spawns` notes>
spawns:         <list<Spawn>, optional - preferred placement form; see `spawns` notes>
role:           <string, optional - one of combat|vendor|quest_giver|dialog|prop (case-insensitive); default combat>
tier:           <string, optional - one of weak|standard|elite|boss (case-insensitive); default standard>
level:          <integer >= 1, optional; default 1>
hp:             <integer >= 1, optional - overrides tier-computed hp>
minDamage:      <integer >= 0, optional - overrides tier-computed minDamage; 0 allows harmless mobs (e.g. a training dummy)>
maxDamage:      <integer >= minDamage, optional - overrides tier-computed maxDamage>
armor:          <integer >= 0, optional - overrides tier baseArmor (flat damage reduction, no level scaling)>
xpReward:       <long >= 0, optional - overrides tier-computed xpReward>
goldMin:        <long >= 0, optional - overrides tier-computed goldMin>
goldMax:        <long >= goldMin, optional - overrides tier-computed goldMax>
drops:          <list<Drop>, optional, default []>
behavior:       <Behavior, optional - assigns a behavior tree to this mob; see Behavior section>
respawnSeconds: <long > 0, optional - seconds after death before this mob respawns in its origin room;
                omit to rely on zone-wide reset only>
faction:        <string, optional - overrides the zone `faction`; not validated>
category:       <string, optional - one of humanoid|beast|undead|elemental|construct|aberration; default humanoid;
                drives default sprite selection>
image:          <string, optional - relative path under /images/; falls back to zone image.mob>
video:          <string, optional - relative path under /videos/, shown in context menu>
rareVariants:   <bool, optional, default true - whether the server may spawn rare cosmetic variants of this mob>
condition:      <SpawnCondition, optional - gates when this mob appears; see "Conditional spawning" below>
dialogue:       <map<node-id, DialogueNode>, optional - conversation tree; see Dialogue section>
quests:         <list<quest-id>, optional - quest ids attached to this NPC (normalized)>
spells:         <map<spell-id, Spell>, optional - combat spells; see Mob spells section>
defaultAttack:  <string, optional - spell key that replaces the default melee attack; must reference a key in `spells`>
```

`spawns` / `room` notes:
- Exactly one of `room` or `spawns` must be present (declaring both is a load error; declaring neither is a load error).
- `room: <id>` is the legacy shorthand for one instance: equivalent to `spawns: [{ room: <id> }]`. Prefer `spawns` for new content.
- Each spawn entry is `{ room: <room-id, required>, count: <int >= 1, default 1> }`. Total instance count across entries must be >= 1.
- A template with one total instance keeps its id; multi-instance templates get runtime ids `<templateId>#0`, `<templateId>#1`, … All instances share the template's stats.
- Every spawn `room` must resolve to an existing merged room.
- **Quest-giver and turn-in NPCs must have exactly one spawn instance** (see Quests) — multi-spawn quest NPCs are a load error.

`role` notes:
- `combat` (default): attackable, fights back, awards XP and drops.
- `vendor`, `quest_giver`, `dialog`: social NPCs — they refuse attack commands.
- `prop`: examine-only set dressing (statues, totems).

`respawnSeconds` notes:
- When set, the mob is scheduled to respawn independently of any zone-wide reset.
- The respawn is silently cancelled if the zone resets first (the mob is already back in the registry).
- If the origin room no longer exists at respawn time the respawn is silently skipped.
- Players in the origin room see an arrival message when the mob reappears.
- Ignored for condition-gated mobs (see below): their respawn is governed entirely by their condition.

`Drop` entry:

```yaml
itemId: <item-id string, required>
chance: <double in [0.0, 1.0], required>
```

#### Conditional spawning (`condition`)

A mob may be gated to only appear under specific world conditions — the classic
"only seen at night", "only during a storm", or "only in winter" creature. When
`condition` is present (and gates anything), the mob is **not** placed at world
start; instead its entire lifecycle is owned by the conditional spawn handler:

```yaml
condition:
  time:    [NIGHT]            # any of DAWN, DAY, DUSK, NIGHT; omit for any time
  weather: [STORM, RAIN]      # any of the configured weather ids; omit for any weather
  seasons: [WINTER]           # any of SPRING, SUMMER, AUTUMN, WINTER; omit for any season
  events:  [blood_moon]       # any one of these world-event flags must be active; omit for none
  chance:  0.25               # per-opportunity appearance probability (0.0–1.0); default 1.0
```

Semantics:
- **Facets are AND-ed; values within a facet are OR-ed.** The example means
  "at night, during a storm or rain, in winter".
- An omitted facet means "any". A `condition` whose facets are all empty and
  whose `chance` is `1.0` behaves like no condition at all.
- `time` and `seasons` values are validated against the enums above (case-insensitive); unknown values are a load error. `weather` values are uppercased but validated only at runtime against the configured weather ids (`ambonMUD.engine.weather.types`; shipped defaults: `CLEAR`, `RAIN`, `STORM`, `FOG`, `SNOW`, `WIND`).
- While the gates hold, the handler rolls `chance` periodically; on success the
  mob appears (with an arrival message, and a zone-wide shout for the sighting).
- When any gate stops holding, the mob **fades out** at the next check — but
  never while it is fighting a player.
- Weather-gated mobs naturally only appear where players are present, because
  per-zone weather only advances in zones that have occupants.
- Event flags come from `ambonMUD.engine.worldEvents` definitions (config, not
  zone YAML). An event may be bounded by real-world dates and/or carry a
  `recurrence` window (e.g. ten minutes of every hour) so gated mobs appear
  during normal play; activations broadcast the event's start/end messages and
  update the `world` command + `World.Events` GMCP.

#### Rare cosmetic variants (`rareVariants`)

Independently of `condition`, the server may spawn any COMBAT mob as a rare
cosmetic **variant** — a tinted/overlaid version with a flavor name prefix
(e.g. *Shadow-touched giant rat*) and a modest HP/XP/loot bump. This guarantees
explorers always have richer sightings to find even when no rare mob was
hand-authored. Variants roll on every spawn — cold start (so a freshly-booted
world is already seeded with a few to discover), zone reset, post-death respawn,
and conditional spawn. Spawns into an occupied world announce with reach scaled
by rarity; cold-start variants spawn silently, since nobody is connected yet.

Set `rareVariants: false` to opt a mob out — appropriate for unique named bosses
or strictly-themed creatures whose appearance should never be altered. The
archetype palette and base chance are operator-configurable under
`ambonMUD.engine.mobVariants` in `application.yaml`.

#### Behavior

```yaml
behavior:
  template: <string - one of the predefined behavior templates>
  params:   <BehaviorParams, optional, default {}>
# — or an inline tree instead of a template —
behavior:
  tree:
    type: selector
    children:
      - type: is_in_combat
      - type: sequence
        children:
          - type: is_player_in_room
          - type: say
            message: "Halt!"
          - type: aggro
      - type: stationary
```

A `behavior` block must contain `template` or `tree` — declaring neither is a load error; when both are present, `tree` wins.

`BehaviorParams` entry (template form only):

```yaml
patrolRoute:       <list<string>, optional, default [] - room IDs for patrol waypoints>
fleeHpPercent:     <integer, optional, default 20 - HP percentage threshold for fleeing>
aggroMessage:      <string, optional - message the mob says before attacking>
fleeMessage:       <string, optional - message the mob says before fleeing>
maxWanderDistance: <integer, optional, default 3 - max rooms from origin for wander templates>
```

Available behavior templates:

| Template | Description |
|----------|------------|
| `aggro_guard` | Stays in place, attacks any player in the room on sight |
| `patrol` | Cycles through `patrolRoute` rooms; pauses during combat |
| `patrol_aggro` | Patrols and attacks players on sight |
| `wander` | Moves randomly between adjacent rooms; pauses during combat |
| `wander_aggro` | Wanders and attacks players on sight |
| `coward` | Wanders randomly, flees when HP drops below `fleeHpPercent` |

Inline tree node types (`type:` values):

- Composites: `selector`, `sequence` (with `children:`)
- Decorators: `inverter` (one child), `cooldown` (`cooldownMs`, `key`, one child)
- Conditions: `is_in_combat`, `is_player_in_room`, `is_hp_below` (`percent`, default 20)
- Actions: `stationary`, `say` (`message`), `aggro`, `flee`, `patrol` (`route`), `wander` (`maxDistance`, default 3)

Behavior validation rules:
- Unknown template names and unknown inline node types cause a load error.
- `patrolRoute` / `route` room IDs follow standard ID normalization (prefixed with `<zone>:` when unqualified).
- Templates requiring `patrolRoute` (`patrol`, `patrol_aggro`) should have a non-empty route.
- A mob's `aggressive` display flag is derived from the template name containing `aggro`; inline trees are never flagged aggressive, even if they contain an `aggro` action.

#### Dialogue

An NPC conversation tree, keyed by node id. Players open it with `talk <mob>`.

```yaml
dialogue:
  root:                              # a node with key `root` is required
    text: "Welcome to the Academy."
    choices:                         # optional; a node without choices ends the conversation
      - text: "Who are you?"
        next: about                  # optional - id of the next node; must exist in this map
        minLevel: 5                  # optional - hide this choice below the level
        requiredClass: MAGE          # optional - hide this choice from other classes
        action: "unlock_flag:met_headmaster"  # optional - side effect when chosen
  about:
    text: "I am the headmaster."
```

- The `root` node is required; a `next` referencing a missing node is a load error.
- Recognized `action` values: `unlock_flag:<name>` (adds a dialogue flag, used by quest `requiresDialogueFlag` gates), `accept_quest:<quest-id>`, `turn_in_quest:<quest-id>` (bare ids resolve against the player's current zone), `set_recall` (inn ledger), `enter_house` (housing broker).

#### Mob spells

Combat spells for a `combat`-role mob. Keyed by spell id.

```yaml
spells:
  firebolt:
    displayName: "Firebolt"                # required, non-blank
    message: "hurls a crackling firebolt at you"   # required, non-blank - second-person text
    roomMessage: "hurls a firebolt at %s"  # optional - text shown to bystanders
    minDamage: 3                           # optional; if either damage bound is set: min >= 1, max >= min
    maxDamage: 6                           #   (min defaults to 1, max defaults to min)
    healMin: 0                             # optional self-heal range; if used: healMin >= 1, healMax >= healMin
    healMax: 0
    statusEffectId: burning                # optional status effect applied on hit (not validated at load)
    cooldownMs: 6000                       # optional, >= 0, default 0
    weight: 2                              # optional, >= 1, default 1 - relative selection weight
```

- Each spell must have at least one of: damage, heal, or `statusEffectId`.
- `defaultAttack: <spell-key>` (on the mob) replaces the mob's basic melee attack with that spell; the key must exist in `spells`.

#### Tier formula

For tier `T` and level `L` (where `L` defaults to 1):

```text
hp         = floor(T.baseHp        * T.hpScalingRate    ^ (L-1))
minDamage  = floor(T.baseMinDamage * T.damageScalingRate ^ (L-1))
maxDamage  = floor(T.baseMaxDamage * T.damageScalingRate ^ (L-1))
armor      = T.baseArmor
xpReward   = floor(T.baseXpReward  * T.xpScalingRate    ^ (L-1))
goldMin    = floor(T.baseGoldMin   * T.goldScalingRate  ^ (L-1))
goldMax    = floor(T.baseGoldMax   * T.goldScalingRate  ^ (L-1))
```

Each scaling rate is a `Double` `>= 1.0`; `1.0` means no growth. Any explicit per-mob field overrides the computed value from the tier formula.

Tier values are operator-configurable via `application.yaml` under `ambonMUD.engine.mob.tiers`.
The values shipped in `application.yaml` are:

| Tier     | baseHp | hpScalingRate | baseMinDmg | baseMaxDmg | damageScalingRate | baseArmor | baseXp | xpScalingRate | baseGoldMin | baseGoldMax | goldScalingRate |
|----------|--------|---------------|------------|------------|-------------------|-----------|--------|---------------|-------------|-------------|-----------------|
| weak     | 5      | 1.10          | 1          | 2          | 1.30              | 0         | 15     | 1.09          | 1           | 3           | 1.19            |
| standard | 12     | 1.10          | 2          | 4          | 1.30              | 1         | 30     | 1.08          | 3           | 8           | 1.19            |
| elite    | 28     | 1.09          | 3          | 6          | 1.30              | 2         | 75     | 1.08          | 10          | 25          | 1.19            |
| boss     | 55     | 1.09          | 4          | 9          | 1.30              | 3         | 200    | 1.07          | 50          | 100         | 1.19            |

(The Kotlin fallback defaults in `MobTiersConfig` differ slightly — they only apply if a deployment strips the `tiers` block from its config.)

Mob armor applies as flat damage reduction: `effectiveDamage = max(1, playerRoll - mob.armor)`.

### `items` map

Each key is an item ID (local or fully qualified).
Each value:

```yaml
displayName: <string, required, non-blank after trim>
description: <string, optional, default "">
keyword: <string, optional, if present must be non-blank after trim>
slot: <string, optional - equipment slot id; see `slot` notes>
classes: <list<string>, optional - class-restriction list; accepted but currently ignored by the engine>
damage: <integer, optional, default 0, must be >= 0>
armor: <integer, optional, default 0, must be >= 0>
stats: <map<stat-id, integer>, optional, default {} - stat bonuses, e.g. { STR: 1, DEX: -1 }; see `stats` notes>
consumable: <boolean, optional, default false>
charges: <integer, optional, must be > 0 when present>
onUse: <OnUse, optional>
room: <room-id string, optional>
respawnSeconds: <long > 0, optional - requires room placement; see "Timed respawn" below>
matchByKey: <boolean, optional, default false>
basePrice: <integer, optional, default 0, must be >= 0>
image: <string, optional - relative path under /images/; falls back to zone image.item>
video: <string, optional - relative path under /videos/, shown in context menu>
itemType: <string, optional - one of equipment|consumable|quest|treasure|keepsake|misc; inferred when omitted>
questItem: <boolean, optional, default false>
takeable: <boolean, optional, default true>
# Arcanum design-time metadata - accepted for round-trip preservation, ignored by the server:
level: <integer, optional>
tier: <string, optional>
archetype: <string, optional>
primaryStat: <string, optional>
secondaryStat: <string, optional>
tertiaryStat: <string, optional>
```

`slot` notes:
- Slot ids are **data-driven** via `application.yaml` `engine.equipment.slots` (shipped defaults: `head`, `neck`, `body`, `hands`, `weapon`, `offhand`, `feet`). The loader lowercases the value and only rejects blank strings — an unconfigured slot id loads but can never be equipped.

`stats` notes:
- Keys are stat ids, uppercased by the loader. Stat ids are data-driven via `engine.stats.definitions` (shipped defaults: `STR`, `DEX`, `CON`, `INT`, `WIS`, `CHA`). Keys are not validated at load — an unknown key is inert.
- Negative values are allowed: builders use them for cursed / trade-off items (e.g. +STR / -DEX rings).
- There is **no** top-level `constitution:` field — a stat bonus goes in `stats:` (e.g. `stats: { CON: 1 }`). A stray top-level `constitution:` is silently ignored.

`itemType` notes:
- `quest` and `keepsake` items are **bound**: they cannot be dropped, sold, given, traded, banked, or mailed. `questItem: true` is a separate flag consulted for quest bookkeeping.
- When omitted, a type is inferred from the item's other properties (equipment slot → `equipment`, `consumable` → `consumable`, etc.).

`takeable` notes:
- When `false`, players cannot `get` the item and auto-loot skips it. Useful for room scenery (statues, fountains, signs) that should appear in the `Items here:` line but stay put.

`basePrice` notes:
- Determines the item's value in the shop economy.
- `0` (or omitted) means the item cannot be bought or sold.
- Actual buy/sell prices are computed by applying global multipliers from `application.yaml`:
  - Buy price = `basePrice * engine.economy.buyMultiplier` (default 1.0)
  - Sell price = `basePrice * engine.economy.sellMultiplier` (default 0.5)

`matchByKey` is optional (default `false`). When `true`, players must type the exact keyword; substring-based fallback on `displayName` and `description` is disabled.

`OnUse` entry:

```yaml
healHp: <integer, optional, default 0, must be >= 0>
healMana: <integer, optional, default 0, must be >= 0>
grantXp: <long, optional, default 0, must be >= 0>
```

If `onUse` is present, at least one effect must be positive (`healHp > 0`, `healMana > 0`, or `grantXp > 0`).

Charge/consumption notes:
- If `charges` is set, one charge is spent per use.
- If `consumable: true`, the item is removed when charges are exhausted (or immediately after use when `charges` is unset).

Location rules for items:

- `room` may be omitted (item starts unplaced).
- `mob` placement is deprecated and rejected by the loader (use `mobs.<id>.drops` for mob loot).

### Timed respawn (`respawnSeconds` on items, doors, and features)

Without `respawnSeconds`, ground items and stateful features (doors, levers,
containers) only return to their authored state on a **zone reset** (`lifespan`).
Setting `respawnSeconds` gives an individual spawn or feature its own timer:

- **Item** (`items.<id>.respawnSeconds`): the item is re-placed in its `room`
  that many seconds after it is observed missing from it. Requires `room`
  placement; must be > 0.
- **Door** (`exits.<dir>.door.respawnSeconds`): the door reverts to its
  `initialState` (re-closing/re-locking, or re-opening) that many seconds after
  it leaves it.
- **Lever** (`features.<id>.respawnSeconds`): the lever snaps back to its
  `initialState`.
- **Container** (`features.<id>.respawnSeconds`): the container reverts to its
  `initialState` *and refills its `items` list* that many seconds after either
  its state or its contents differ from the authored initial condition. Note
  that — like a `resetWithZone` zone reset — refilling replaces whatever is in
  the container, including player-stored items.
- `SIGN` features have no state; the loader rejects `respawnSeconds` on them.

Timers are independent of, and reset by, zone resets: whenever the item or
feature returns to its initial condition by any means, the timer disarms.

### `shops` map

Each key is a shop ID; the loader qualifies it with the zone (`<zone>:<key>`).
Each value:

```yaml
name:  <string, required, non-blank after trim>
room:  <room-id string, required - the room where the shop NPC is located>
items: <list<string>, optional, default [] - item IDs available for purchase>
image: <string, optional - shopkeeper portrait image filename>
requiredReputation:      # optional reputation gate on browse/buy
  faction: <string, required - must be a defined faction id when the faction config is present (load error otherwise)>
  min: <integer, optional>
  max: <integer, optional - when both set, min <= max>
```

Shop notes:
- A room can have at most one shop. If multiple shops reference the same room, the last one wins.
- `items` lists item IDs (local or fully qualified) that the shop sells. Each must resolve to an existing merged item.
- Players use `list`/`shop` to see inventory, `buy <keyword>` to purchase, and `sell <keyword>` to sell back.
- Items sold to shops are destroyed (not added to shop inventory).
- Selling requires being in a shop room. The item must have `basePrice > 0`.

Shop ID normalization:
- `room` follows the same normalization rules as other room references (prefixed with `<zone>:` when unqualified).
- `items` entries follow the same normalization rules as item references.

### `trainers` map

Each key is a trainer ID; the loader qualifies it with the zone (`<zone>:<key>`).
Each value:

```yaml
name:    <string, required, non-blank after trim>
class:   <string, optional - single class ID; legacy single-class form>
classes: <list<string>, optional - one or more class IDs; preferred for multi-class trainers>
room:    <room-id string, required - the room where the trainer NPC is located>
image:   <string, optional - trainer portrait image filename>
```

Either `class:` (single string) or `classes:` (non-empty list) must be present.
If both are set, `classes:` takes precedence and `class:` is ignored.
Class IDs are normalized to uppercase and de-duplicated by the loader. The canonical class list is data-driven via `application.yaml` `engine.classes` (shipped: `WARRIOR`, `MAGE`, `CLERIC`, `ROGUE`, `RANGER`); the loader does **not** reject unknown class ids — an unknown class simply has no abilities to teach at runtime.

Trainer notes:
- A room can have at most one trainer. If multiple trainers reference the same room, the last one wins.
- A trainer with multiple `classes:` entries teaches abilities from all of them. The web client shows one tab per class. The text command `train list` renders a section per class.
- Players unlock each class individually via `train unlock <class>` (the class argument is required for multi-class trainers; optional for single-class trainers).
- `train learn <ability>` searches the trainer's unlocked classes for a matching ability — players don't need to specify which class an ability belongs to.
- Abilities with a matching `requiredClass` in `application.yaml` are shown at this trainer.
- The trainer NPC must be added separately in the `mobs:` section with a matching `room` — the `trainers:` entry is the registry binding, not the mob definition.

Trainer ID normalization:
- `room` follows the same normalization rules as other room references (prefixed with `<zone>:` when unqualified).

Example — single-class trainers (legacy form, still supported):

```yaml
trainers:
  warrior_trainer:
    name: "Captain Varek"
    class: WARRIOR
    room: warrior_training_hall
  mage_trainer:
    name: "Archmage Solvara"
    class: MAGE
    room: mage_library
```

Example — a multi-class "academy master" who teaches three classes from one room:

```yaml
trainers:
  combat_instructor:
    name: "Master Grizelda"
    classes: [WARRIOR, ROGUE, RANGER]
    room: training_yard
```

### `quests` map

Each key is a quest ID; the loader qualifies it with the zone (`<zone>:<key>`).
Each value:

```yaml
name: <string, required, non-blank after trim>
description: <string, optional, default "">
giver: <mob-id string, required - the NPC who offers the quest; normalized>
turnInMob: <mob-id string, optional - overrides the NPC that accepts turn-ins; defaults to `giver`>
completionType: <string, optional - auto | npc_turn_in (case-insensitive); default npc_turn_in>
objectives:                       # optional list; see zero-objective quests below
  - type: <string, required - kill | collect (built-in; extensible via ObjectiveHandler registry)>
    targetKey: <string, required - mob id (kill) or item id (collect); normalized>
    count: <integer >= 1, optional, default 1>
    description: <string, optional - auto-generated ("<type> <target> x<count>") when blank>
rewards:                          # optional
  xp: <long, optional, default 0>
  gold: <long, optional, default 0>
  currencies: <map<string, long>, optional - e.g. { quest_points: 10, honor: 5 }>
  items:                          # optional fixed-item rewards
    - itemId: <string, required - must resolve to an existing merged item>
      count: <integer >= 1, required to be >= 1; default 1>
requiredReputation:               # optional reputation gate
  faction: <string, required - must be a defined faction id when the faction config is present>
  min: <integer, optional - below this the giver hints to grind reputation>
  max: <integer, optional - above this the quest disappears; when both set, min <= max>
level: <integer, optional - intended player level; drives XP diminishing returns on completion>
difficulty: <string, optional - trivial|easy|standard|hard|epic (case-insensitive); when set, the engine
            computes XP from quest level × tier multiplier instead of using `rewards.xp` as-is>
requiresDialogueFlag: <string, optional - hides the quest until the player has this dialogue flag
                      (granted by a dialogue choice `action: unlock_flag:<name>`)>
```

Quest notes:
- `giver` and `turnInMob` must reference known mob templates with **exactly one spawn instance** — a missing template or a multi-spawn NPC is a load error.
- `completionType: auto` completes the quest the moment its objectives are done; `npc_turn_in` requires walking to the resolved turn-in NPC. Unknown completion/objective types load, but no built-in handler will ever advance or complete them.
- Reward `items` are spawned into the player's inventory at the moment of quest completion (turn-in or auto-complete) and surfaced through GMCP `Quest.Available` (on offer) and `Quest.Complete` (on turn-in) so clients can preview and celebrate them. Unknown template ids are skipped with a `[Quest]` warning to the player.
- Reward `currencies` keys should match currency ids defined in `application.yaml` under `engine.currencies.definitions` (not validated at load). See `CurrencySystem` for runtime handling.
- Listing the quest id in the giver mob's `quests:` list attaches the offer to that NPC.

#### Zero-objective "visit" quests

A quest with `objectives: []` is allowed only when `completionType: npc_turn_in`. It models a pure delivery / "go see this other NPC" quest: it has no tracked progress, is ready to turn in the moment the player accepts it, and completes by walking up to the resolved turn-in NPC (`turnInMob` if set, otherwise `giver`).

```yaml
quests:
  message_for_alric:
    name: "A Message for Alric"
    description: "Take word to Alric in the next village."
    giver: village_elder
    turnInMob: alric         # the override NPC who accepts the hand-in
    completionType: npc_turn_in
    objectives: []           # nothing to track — accepting is the whole task
    rewards:
      xp: 25
```

The loader rejects empty objectives with any other completion type (e.g. `auto`), since that would auto-complete the quest the instant the player accepts it.

### `gatheringNodes` map

Each key is a gathering node ID; the loader qualifies it with the zone.
Each value:

```yaml
displayName:    <string, required, non-blank after trim>
keyword:        <string, optional - defaults to the map key (text after the last `:`); non-blank when present>
image:          <string, optional - node art, resolved under the zone images base>
skill:          <string, required - a gathering skill id; see notes>
skillRequired:  <integer >= 1, optional, default 1>
yields:         <list<Yield>, required, must be non-empty>
rareYields:     <list<RareYield>, optional, default []>
respawnSeconds: <integer, optional, default 60>
xpReward:       <integer, optional, default 10>
room:           <room-id string, required>
```

`Yield` entry:

```yaml
itemId:      <item-id string, required - must resolve to an existing item>
minQuantity: <integer >= 1, optional, default 1>
maxQuantity: <integer >= minQuantity, optional, default 1>
```

`RareYield` entry (bonus roll on top of the normal yield):

```yaml
itemId:     <item-id string, required - normalized; existence not validated at load>
quantity:   <integer >= 1, optional, default 1>
dropChance: <double in (0.0, 1.0], optional, default 0.1>
```

Gathering node notes:
- Skill ids are **data-driven** via `application.yaml` `engine.crafting.skills` (shipped defaults: `mining` and `herbalism` are gathering-type; `smithing`, `alchemy`, and `enchanting` are crafting-type). The loader lowercases the value and only rejects blanks — it does not verify the id is a configured gathering skill, so use one, or the node will be ungatherable.
- Nodes are visible in the room when players use the `look` command ("Resources: a copper ore vein, ...").
- After a player gathers from a node, it becomes depleted for `respawnSeconds` before becoming available again.
- Players must have a skill level >= `skillRequired` to gather from the node.
- There is a configurable cooldown between gather attempts (`crafting.gatherCooldownMs`, default 3000ms).

Commands:
- `gather <keyword>` / `harvest <keyword>` / `mine <keyword>` — gather from a node in the current room.
- `craftskills` / `professions` / `prof` — view your gathering and crafting skill levels.

### `recipes` map

Each key is a recipe ID; the loader qualifies it with the zone.
Each value:

```yaml
displayName:   <string, required, non-blank after trim>
skill:         <string, required - a crafting skill id; see notes>
skillRequired: <integer >= 1, optional, default 1>
levelRequired: <integer, optional, default 1>
materials:     <list<Material>, required, must be non-empty>
outputItemId:  <item-id string, required - must resolve to an existing item>
outputQuantity: <integer >= 1, optional, default 1>
station:       <string, optional - station type id (shipped: forge|alchemy_table|workbench|enchanting_table)>
stationBonus:  <integer, optional, default 0 - extra output quantity when crafted at a matching station>
xpReward:      <integer, optional, default 25>
image:         <string, optional - recipe/output art filename>
```

`Material` entry:

```yaml
itemId:   <item-id string, required - must resolve to an existing item>
quantity: <integer >= 1, optional, default 1>
```

Recipe notes:
- Skill ids are data-driven (see gathering notes above); use a crafting-type skill (`smithing`, `alchemy`, `enchanting` by default). The loader lowercases the value but does not verify the type.
- Station type ids are likewise data-driven and lowercased without validation — use a configured id or the bonus can never trigger.
- `materials` are consumed from the player's inventory when crafting.
- If `station` is set and the player is in a room with a matching `station` type, extra output is produced: `stationBonus` when > 0, otherwise the global `crafting.stationBonusQuantity` (default 1).
- `levelRequired` is the player's character level (not skill level).
- Crafting stations are visible in the room description ("Crafting station: Forge").

Commands:
- `craft <keyword>` / `make <keyword>` / `create <keyword>` — craft a recipe.
- `recipes [filter]` — list all recipes, optionally filtered by skill name or recipe name.
- `craftskills` / `professions` / `prof` — view your gathering and crafting skill levels.

### `dungeon` block

At most one per zone file (a single object, not a map). The template's id becomes `<zone>:dungeon`.

```yaml
dungeon:
  name: <string, required>
  description: <string, optional, default "">
  image: <string, optional - dungeon art, resolved under the zone images base>
  minLevel: <integer, optional, default 1 - minimum player level to enter>
  roomCountMin: <integer, optional, default 20 - must be >= 3 and <= roomCountMax>
  roomCountMax: <integer, optional, default 25>
  roomTemplates:            # required, must have at least one entry; keys case-insensitive
    entrance:               # room types: entrance | corridor | chamber | treasure | boss
      - title: <string, required, non-blank>
        description: <string, optional, default "">
        image: <string, optional>
    corridor: [ ... ]
    boss: [ ... ]
  mobPools:
    common: <list<mob-id>, optional>   # NOT normalized or existence-checked at load - use fully qualified ids
    elite: <list<mob-id>, optional>
    boss: <list<mob-id>, required non-empty>
  lootTables:               # keyed by difficulty: lore | normal | hard | heroic (case-insensitive)
    normal:
      mobDrops: <list<item-id>, optional>          # normalized; existence not checked at load
      completionRewards: <list<item-id>, optional> # normalized; existence not checked at load
  portalRoom: <room-id string, optional - NOT normalized at load; use a fully qualified id>
```

Dungeon notes:
- Unknown room-type or difficulty keys are a load error.
- Rooms are generated procedurally per run by drawing titles/descriptions from the type pools; mobs are drawn from `mobPools` and scaled by difficulty (`lore` 0.5×hp/dmg with no XP/loot, `normal` 1×, `hard` 1.5×, `heroic` 2×).
- Mark the lobby room with the room-level `dungeon: true` flag so the web client shows the kiosk.

### `puzzles` map

Each key is a puzzle ID; the loader qualifies it with the zone.
Each value:

```yaml
puzzles:
  <puzzle-id>:
    type: <string, required - one of riddle|sequence (case-insensitive)>
    roomId: <room-id string, required - room where the puzzle is>
    mobId: <mob-id string, optional - the NPC that poses the riddle (riddle type)>
    # For riddle type:
    question: <string, optional - the riddle text>
    answer: <string, optional - the canonical answer>
    acceptableAnswers: <list<string>, optional - additional accepted answers>
    # For sequence type:
    steps:
      - { feature: <feature keyword>, action: <string, e.g. "pull"> }   # both required non-blank
    resetOnFail: <boolean, optional, default true>
    # Common:
    reward:
      type: <string, required - one of unlock_exit|give_item|give_gold|give_xp>
      exitDirection: <direction, required for unlock_exit>
      targetRoom: <room-id, required for unlock_exit>
      itemId: <item-id, required for give_item>
      gold: <long > 0, required for give_gold>
      xp: <long > 0, required for give_xp>
    failMessage: <string, optional, default "That doesn't seem right.">
    successMessage: <string, optional, default "Success!">
    cooldownMs: <long, optional, default 0 - 0 means one-time per session>
    backgroundImage: <string, optional - parchment backdrop art for the web puzzle panel>
```

Puzzle notes:
- Puzzles are session-scoped: solved state resets when the player disconnects.
- The `answer` command is used to submit a riddle answer (`answer <text>`).
- For `riddle` type: at least one answer must be present across `answer` + `acceptableAnswers` (all are lowercased and trimmed for matching). The `mobId` optionally ties the riddle to an NPC in the room.
- For `sequence` type: `steps` defines an ordered list of feature interactions the player must complete (matched by feature `keyword` + action verb, both lowercased). `resetOnFail` (default `true`) resets progress if the player performs the wrong step.
- Reward types:
  - `unlock_exit` — reveals a hidden exit in `exitDirection` leading to `targetRoom`.
  - `give_item` — grants the item specified by `itemId`.
  - `give_gold` — awards `gold` gold (must be > 0).
  - `give_xp` — awards `xp` XP (must be > 0).
- `cooldownMs` of `0` (default) means the puzzle can only be solved once per session.

Puzzle ID normalization:
- `roomId` follows the same normalization rules as other room references (prefixed with `<zone>:` when unqualified).
- `mobId` follows the same normalization rules as mob references.
- Reward `targetRoom` and `itemId` follow standard normalization.

## ID Normalization Rules

The loader normalizes IDs with this logic:

1. Trim whitespace.
2. Reject blank strings.
3. If the string contains `:`, use it as-is.
4. Otherwise prefix with `<zone>:` from the current file.

This applies to:

- `startRoom`
- `rooms` keys
- room exit targets (`exits.<dir>` string form and `exits.<dir>.to`)
- `exits.<dir>.door.keyItemId`
- `features.<id>.keyItemId` and `features.<id>.items` entries
- `mobs` keys, `mobs.*.room`, and `mobs.*.spawns.*.room`
- `mobs.*.drops.*.itemId`
- `mobs.*.quests` entries
- `mobs.*.behavior` patrol route room IDs
- `quests` keys, `quests.*.giver`, `quests.*.turnInMob`, `quests.*.objectives.*.targetKey`
- `quests.*.rewards.items.*.itemId`
- `items` keys and `items.*.room`
- `shops` keys, `shops.*.room`, and `shops.*.items` entries
- `trainers` keys and `trainers.*.room`
- `gatheringNodes` keys, `gatheringNodes.*.room`, `gatheringNodes.*.yields.*.itemId`, `gatheringNodes.*.rareYields.*.itemId`
- `recipes` keys, `recipes.*.materials.*.itemId`, `recipes.*.outputItemId`
- `puzzles` keys, `puzzles.*.roomId`, `puzzles.*.mobId`, `puzzles.*.reward.targetRoom`, `puzzles.*.reward.itemId`
- `rooms.*.boatRoutes.*.to`
- `dungeon.lootTables.*.mobDrops` / `completionRewards` entries

**Not** normalized (emit fully qualified ids): `dungeon.mobPools.*` entries and `dungeon.portalRoom`.

Examples with `zone: swamp`:

- `edge` -> `swamp:edge`
- `forest:trailhead` -> `forest:trailhead`

## Validation Rules

### Per-file validation

Each individual file must satisfy:

1. `zone` is non-blank after trim.
2. If `lifespan` is present, it is `>= 0` (minutes).
3. `rooms` is not empty.
4. `startRoom` (after normalization) exists among that same file's normalized room IDs.

### Cross-file (merged world) validation

When loading multiple files:

1. At least one file must be provided.
2. The world start room is taken from the first file in the list (unless overridden by config):
   - `world.startRoom = normalize(firstFile.zone, firstFile.startRoom)` — and it must exist in the merged world.
3. Duplicate normalized IDs are rejected globally:
   - room IDs must be unique across all files
   - mob IDs must be unique across all files
   - item IDs must be unique across all files
4. Exit targets are **soft-validated**: an exit to a missing room logs a warning and renders as an unreachable "shimmering" way; it is not a load error. (Exits into zones excluded by a zone filter are likewise treated as remote.)
5. Every mob spawn `room` must resolve to an existing merged room.
6. Every item `room` (if set) must resolve to an existing merged room.
7. Every mob drop `itemId` must resolve to an existing merged item.
8. Every door and container `keyItemId`, and every container `items` entry, must resolve to an existing merged item.
9. Every quest `giver` and `turnInMob` must reference a known mob template with exactly one spawn instance.
10. Every shop `room` must resolve to an existing merged room.
11. Every shop `items` entry must resolve to an existing merged item.
12. Every trainer `room` must resolve to an existing merged room.
13. Every gathering node `room` must resolve to an existing merged room.
14. Every gathering node `yields.*.itemId` must resolve to an existing merged item (`rareYields` item ids are normalized but not existence-checked).
15. Every recipe `materials.*.itemId` must resolve to an existing merged item.
16. Every recipe `outputItemId` must resolve to an existing merged item.
17. For repeated `zone` names across files, `lifespan` merge rule is:
    - if only one file sets `lifespan`, that value is used
    - if multiple files set it, all non-null values must match
    - conflicting non-null values are rejected
18. For repeated `zone` names across files, conflicting `scaling` blocks are rejected.

## Item Keyword Resolution

If `items.<id>.keyword` is omitted, keyword is derived from the raw item map key:

- Take the text after the last `:`
- Example: key `silver_coin` -> keyword `silver_coin`
- Example: key `swamp:silver_coin` -> keyword `silver_coin`

If `keyword` is provided, it is trimmed and must be non-blank.

The same rule applies to `gatheringNodes.<id>.keyword`.

## Minimap Layout

Minimap coordinates are assigned automatically at load — nothing to author. Each zone is laid out by BFS from its start room; up/down exits split rooms into floors. Two rooms whose horizontal exits imply the same grid cell (non-euclidean topology) are displaced to the nearest free cell with a load-time **warning**, and that exit draws diagonally on the map. Generators can keep maps clean by laying out each floor so its N/S/E/W exits are euclidean-consistent.

## Generator Checklist

For each file your tool emits:

1. Emit required top-level fields: `zone`, `startRoom`, `rooms`.
2. Ensure `rooms` has at least one entry.
3. Ensure `startRoom` points to a room in that same file (after normalization).
4. Restrict exit direction keys to the allowed set; in object form always include `to`.
5. Use only non-negative integers for `lifespan`, `damage`, `armor`.
6. For every item, use `room` or omit placement entirely (unplaced). Do not use `mob`.
7. If `onUse` is present, include at least one positive effect (`healHp`, `healMana`, or `grantXp`).
8. If `charges` is present, it must be > 0.
9. Ensure all local/qualified references resolve in the merged set of files (exit targets to unloaded zones are tolerated, but everything else in the cross-file validation list is a hard error).
10. Ensure normalized room/mob/item IDs are globally unique across files.
11. If splitting one zone across files, keep `lifespan` (and `scaling`) consistent when repeated.
12. If `respawnSeconds` is present (mob, item, door, or feature), it must be > 0; never put it on a SIGN feature.
13. If `basePrice` is present, it must be >= 0.
14. If `goldMin` or `goldMax` is present, both must be >= 0 and `goldMax` >= `goldMin`.
15. Give every mob exactly one of `room:` or `spawns:` (with every `count >= 1`), and give quest-giver / turn-in NPCs exactly one spawn instance.
16. For every shop, ensure `room` resolves to an existing room and all `items` resolve to existing items; shop `name` must be non-blank.
17. If `behavior` is present, include `template` (a known template) or an inline `tree` (known node types).
18. If using `patrol` or `patrol_aggro` templates, `patrolRoute` must be non-empty and all room IDs must resolve.
19. If a mob declares `spells`, each spell needs `displayName`, `message`, and at least one of damage/heal/status; `defaultAttack` must reference a declared spell key.
20. If a mob declares `dialogue`, include a `root` node and make every `next` reference an existing node.
21. Each gathering node must have a non-empty `yields` list; all yield `itemId` references must resolve to existing items; `room` must resolve.
22. Each recipe must have a non-empty `materials` list; all `itemId` references (materials and output) must resolve to existing items.
23. Use configured skill ids (`mining`/`herbalism` for nodes, `smithing`/`alchemy`/`enchanting` for recipes) and station ids (`forge`/`alchemy_table`/`workbench`/`enchanting_table`) — the loader won't catch a typo here, it will just never work in game.
24. If `puzzles` is present, each puzzle must have a valid `type` (`riddle` or `sequence`); riddles need at least one answer, sequences need non-empty `steps` with non-blank `feature`/`action`.
25. Puzzle rewards: `unlock_exit` needs `exitDirection` + `targetRoom`; `give_item` needs `itemId`; `give_gold` needs `gold > 0`; `give_xp` needs `xp > 0`.
26. If `dungeon` is present: non-empty `roomTemplates`, non-empty `mobPools.boss`, `3 <= roomCountMin <= roomCountMax`, and fully qualified ids in `mobPools`/`portalRoom`.
27. For every quest, ensure `name` and `giver` are non-blank, objective `targetKey`s are non-blank with `count >= 1`, and reward item ids resolve with `count >= 1`.
28. If `condition` is present on a mob, keep `chance` in `[0.0, 1.0]` and use valid `time`/`seasons` values.
29. If `flightMapX/Y` or `boatMapX/Y` are present, keep them in `0..100`.

## Minimal Valid Example

```yaml
zone: crypt
startRoom: entry
rooms:
  entry:
    title: "Crypt Entry"
    description: "Cold air drifts from below."
```

## Full-Feature Example

```yaml
zone: crypt
lifespan: 30 # minutes
startRoom: entry

mobs:
  rat:
    name: "a cave rat"
    room: hall               # legacy shorthand for spawns: [{ room: hall }]
    respawnSeconds: 30       # reappears 30 s after being killed
    drops:
      - itemId: fang
        chance: 1.0
  sentinel:
    name: "a stone sentinel"
    spawns:
      - room: entry          # preferred placement form
    tier: elite
    level: 3
    goldMin: 15              # explicit gold override (ignores tier formula)
    goldMax: 30
    quests: [cull_the_rats]
    behavior:
      template: aggro_guard
      params:
        aggroMessage: "The sentinel's eyes glow red!"
    # no respawnSeconds — relies on zone-wide reset

quests:
  cull_the_rats:
    name: "Cull the Rats"
    description: "The sentinel wants the crypt cleared of vermin."
    giver: sentinel
    completionType: npc_turn_in
    objectives:
      - type: kill
        targetKey: rat
        count: 3
    rewards:
      xp: 50
      gold: 10

items:
  helm:
    displayName: "a dented helm"
    description: "Old iron, still useful."
    slot: head
    armor: 1
    stats:
      CON: 1
    room: entry
    basePrice: 12
  fang:
    displayName: "a rat fang"
    basePrice: 2
  iron_ore:
    displayName: "a chunk of iron ore"
    basePrice: 12
  brass_key:
    displayName: "a small brass key"
    # basePrice 0 (default) — cannot be bought or sold
  health_potion:
    displayName: "a small health potion"
    keyword: "potion"
    consumable: true
    onUse:
      healHp: 8
    basePrice: 20

shops:
  crypt_vendor:
    name: "Crypt Keeper's Wares"
    room: entry
    items:
      - helm
      - health_potion

trainers:
  warrior_trainer:
    name: "Sergeant Crag"
    class: WARRIOR
    room: training_yard

rooms:
  entry:
    title: "Entry"
    description: "A cracked stair descends."
    exits:
      n: hall
      w: forge
      e:
        to: training_yard
        door:
          initialState: locked
          keyItemId: brass_key
    features:
      plaque:
        type: SIGN
        displayName: "a bronze plaque"
        keyword: plaque
        text: "Abandon hope, all ye who dungeon-crawl here."
  hall:
    title: "Hall"
    description: "Pillars vanish into shadow."
    exits:
      south: entry
      east: overworld:graveyard   # fine even if 'overworld' isn't loaded — shimmers as unreachable
  forge:
    title: "The Forge"
    description: "A sweltering room with a roaring forge."
    station: forge
    exits:
      e: entry
  training_yard:
    title: "Training Yard"
    description: "Straw dummies stand in ragged rows."
    exits:
      w: entry

gatheringNodes:
  iron_vein:
    displayName: "an iron ore vein"
    keyword: iron
    skill: mining
    skillRequired: 1
    yields:
      - itemId: iron_ore
        minQuantity: 1
        maxQuantity: 2
    respawnSeconds: 30
    xpReward: 15
    room: hall

recipes:
  iron_helm:
    displayName: "Iron Helm"
    skill: smithing
    skillRequired: 5
    materials:
      - itemId: iron_ore
        quantity: 3
    outputItemId: helm
    station: forge
    stationBonus: 0
    xpReward: 25
```

## Notes For Robust Generators

- Keep IDs stable and slug-like (for example `snake_case`) even though loader checks are minimal.
- Prefer local IDs within the same file; use qualified IDs only for cross-zone references.
- Unknown fields are silently ignored by the loader — a typo in an optional field name will not error, it will just be dropped. Validate your output against this spec rather than relying on load failures.
