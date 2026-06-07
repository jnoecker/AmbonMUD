# World Zone YAML Spec

This document defines the YAML contract loaded by `WorldLoader` (`src/main/kotlin/dev/ambon/domain/world/load/WorldLoader.kt`).
It is written for code generators that need to emit valid zone files.

## Scope

- One YAML document describes one zone file.
- Multiple zone files can be merged into one world.
- YAML files are deserialized into:
  - `WorldFile` (`zone`, `lifespan`, `startRoom`, `rooms`, `mobs`, `items`, `shops`, `trainers`, `gatheringNodes`, `recipes`, `puzzles`, `dungeon`)
  - `RoomFile`
  - `MobFile`
  - `MobDropFile`
  - `ItemFile`
  - `ShopFile`
  - `TrainerFile`
  - `GatheringNodeFile`
  - `RecipeFile`
  - `DungeonFile` 

## Top-Level Schema

```yaml
zone: <string, required, non-blank after trim>
lifespan: <integer minutes >= 0, optional>
startRoom: <room-id string, required>
graphical: <boolean, optional, default false>  # true if the zone has custom graphical assets
pvpEnabled: <boolean, optional, default false>  # when true, players can attack each other in this zone
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
gatheringNodes: <map<string, GatheringNode>, optional, default {}>
recipes: <map<string, Recipe>, optional, default {}>
puzzles: <map<string, Puzzle>, optional, default {}>
```

`lifespan` notes:
- Units are minutes.
- `0` is allowed and, in the current engine, effectively disables runtime resets (zones reset only when `lifespan > 0`).

`pvpEnabled` notes:
- When `true`, players in this zone can attack each other with `kill <player>`.
- PvP death respawns the defeated player at the zone's `startRoom` with full HP/mana, no loot loss.
- PvP kills and deaths are tracked on `PlayerRecord` (`pvpKills`/`pvpDeaths`).

### Required vs optional

- Required top-level fields: `zone`, `startRoom`, `rooms`
- Optional top-level fields: `lifespan`, `graphical`, `pvpEnabled`, `image`, `audio`, `mobs`, `items`, `shops`, `trainers`, `gatheringNodes`, `recipes`, `puzzles`

## Nested Schemas

### `rooms` map

Each key is a room ID (local or fully qualified).
Each value:

```yaml
title: <string, required>
description: <string, required>
exits: <map<string direction, string target-room-id>, optional, default {}>
station: <string, optional - one of FORGE|ALCHEMY_TABLE|WORKBENCH (case-insensitive)>
bank: <boolean, optional, default false>
stylist: <boolean, optional, default false>
tavern: <boolean, optional, default false>
dungeon: <boolean, optional, default false>
inn: <boolean, optional, default false>
image: <string, optional - relative path under /images/>
video: <string, optional - relative path under /videos/, shown as clickable cinematic>
music: <string, optional - overrides zone audio.music>
ambient: <string, optional - overrides zone audio.ambient>
```

`bank` notes:
- When `true`, enables bank commands (`deposit`, `withdraw`, `bank`) in this room.
- Bank commands: `deposit`, `withdraw`, `bank`. Configurable via `ambonmud.engine.bank` in `application.yaml`.

`stylist` notes:
- When `true`, enables stylist commands (`stylist`, `changerace <race>`) in this room.
- The stylist charges a configurable gold fee (default 500, see `ambonMUD.engine.stylist.feeGold`) to swap a character's race.
- The swap applies the new race's stat modifiers as a delta against the old race's modifiers, then recomputes derived HP/mana caps. Stats gained from levelling, prestige, or equipment are preserved.
- Racial abilities are **not** currently transferred — see GH issue #993.
- Shows a Stylist badge on the web client via the standard panel drawer.

`tavern` notes:
- When `true`, enables gambling commands (`gamble`, `dice`) and lottery ticket purchases (`lottery buy`) in this room. (`lottery` info works anywhere.)
- Also shows Lottery and Dice badges on the web client canvas.

`dungeon` notes:
- When `true`, shows a Dungeon badge on the web client canvas that opens the dungeon kiosk panel.

`inn` notes:
- When `true`, enables the `rest` command in this room. Resting sets the player's recall point to this room.
- Shows an Inn badge on the web client canvas; the popout displays the player's current recall point and offers a "Rest & Set Recall Here" button.
- The recall point is the destination of the `recall` command. Rest is blocked while in combat.

`station` notes:
- Designates the room as a crafting station of the given type.
- Recipes that specify a matching `station` type receive a bonus when crafted in this room.
- Visible in room descriptions as "Crafting station: Forge" (etc.).

Valid direction keys (case-insensitive):

- `n`, `north`
- `s`, `south`
- `e`, `east`
- `w`, `west`
- `u`, `up`
- `d`, `down`

### `mobs` map

Each key is a mob ID (local or fully qualified).
Each value:

```yaml
name:           <string, required>
room:           <room-id string, required>
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
image:          <string, optional - relative path under /images/>
video:          <string, optional - relative path under /videos/, shown in context menu>
```

`respawnSeconds` notes:
- When set, the mob is scheduled to respawn independently of any zone-wide reset.
- The respawn is silently cancelled if the zone resets first (the mob is already back in the registry).
- If the origin room no longer exists at respawn time the respawn is silently skipped.
- Players in the origin room see an arrival message when the mob reappears.

`Drop` entry:

```yaml
itemId: <item-id string, required>
chance: <double in [0.0, 1.0], required>
```

`Behavior` entry:

```yaml
template: <string, required - one of the predefined behavior templates>
params:   <BehaviorParams, optional, default {}>
```

`BehaviorParams` entry:

```yaml
patrolRoute:    <list<string>, optional, default [] - room IDs for patrol waypoints>
fleeHpPercent:  <integer, optional, default 20 - HP percentage threshold for fleeing>
aggroMessage:   <string, optional - message the mob says before attacking>
fleeMessage:    <string, optional - message the mob says before fleeing>
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

Behavior validation rules:
- `behavior` and `stationary: true` are mutually exclusive (load error).
- `patrolRoute` room IDs follow standard ID normalization (prefixed with `<zone>:` when unqualified).
- Unknown template names cause a load error.
- Templates requiring `patrolRoute` (`patrol`, `patrol_aggro`) should have a non-empty route.

Tier formula (for tier `T` and level `L`, where `L` defaults to 1):

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

Tier default values are operator-configurable via `application.yaml` under `ambonmud.engine.mob.tiers`.
The built-in defaults are:

| Tier     | baseHp | hpScalingRate | baseMinDmg | baseMaxDmg | damageScalingRate | baseArmor | baseXp | xpScalingRate | baseGoldMin | baseGoldMax | goldScalingRate |
|----------|--------|---------------|------------|------------|-------------------|-----------|--------|---------------|-------------|-------------|-----------------|
| weak     | 5      | 1.10          | 1          | 2          | 1.06              | 0         | 15     | 1.09          | 1           | 3           | 1.19            |
| standard | 12     | 1.10          | 2          | 4          | 1.07              | 1         | 30     | 1.08          | 3           | 8           | 1.19            |
| elite    | 28     | 1.09          | 3          | 6          | 1.07              | 2         | 75     | 1.08          | 10          | 25          | 1.19            |
| boss     | 55     | 1.09          | 4          | 9          | 1.07              | 3         | 200    | 1.07          | 50          | 100         | 1.19            |

Mob armor applies as flat damage reduction: `effectiveDamage = max(1, playerRoll - mob.armor)`.

### `items` map

Each key is an item ID (local or fully qualified).
Each value:

```yaml
displayName: <string, required, non-blank after trim>
description: <string, optional, default "">
keyword: <string, optional, if present must be non-blank after trim>
slot: <string, optional, one of head|body|hand (case-insensitive)>
damage: <integer, optional, default 0, must be >= 0>
armor: <integer, optional, default 0, must be >= 0>
constitution: <integer, optional, default 0, must be >= 0>
consumable: <boolean, optional, default false>
charges: <integer, optional, must be > 0 when present>
onUse: <OnUse, optional>
room: <room-id string, optional>
respawnSeconds: <long > 0, optional - requires room placement; see "Timed respawn" below>
matchByKey: <boolean, optional, default false>
basePrice: <integer, optional, default 0, must be >= 0>
image: <string, optional - relative path under /images/>
video: <string, optional - relative path under /videos/, shown in context menu>
takeable: <boolean, optional, default true>
```

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
- `mob` placement is deprecated and rejected by the loader.

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

Each key is a shop ID (local identifier, not normalized).
Each value:

```yaml
name:  <string, required, non-blank after trim>
room:  <room-id string, required - the room where the shop NPC is located>
items: <list<string>, optional, default [] - item IDs available for purchase>
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

Each key is a trainer ID (local identifier).
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
Class IDs are normalized to uppercase by the loader and must each be one of `WARRIOR | MAGE | CLERIC | ROGUE | RANGER` (case-insensitive at the YAML level; see `application.yaml` `engine.classes` for the canonical list).

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

### `gatheringNodes` map

Each key is a gathering node ID (local or fully qualified).
Each value:

```yaml
displayName:    <string, required, non-blank after trim>
keyword:        <string, required, non-blank after trim>
skill:          <string, required - one of MINING|HERBALISM (case-insensitive); must be a gathering skill>
skillRequired:  <integer >= 1, optional, default 1>
yields:         <list<Yield>, required, must be non-empty>
respawnSeconds: <integer > 0, optional, default 60>
xpReward:       <integer >= 0, optional, default 10>
room:           <room-id string, required>
```

`Yield` entry:

```yaml
itemId:      <item-id string, required - must resolve to an existing item>
minQuantity: <integer >= 1, optional, default 1>
maxQuantity: <integer >= minQuantity, optional, default 1>
```

Gathering node notes:
- `skill` must be a **gathering** skill (`MINING` or `HERBALISM`). Crafting skills (`SMITHING`, `ALCHEMY`) are rejected.
- Nodes are visible in the room when players use the `look` command ("Resources: a copper ore vein, ...").
- After a player gathers from a node, it becomes depleted for `respawnSeconds` before becoming available again.
- Players must have a skill level >= `skillRequired` to gather from the node.
- There is a configurable cooldown between gather attempts (`crafting.gatherCooldownMs`, default 3000ms).

Commands:
- `gather <keyword>` / `harvest <keyword>` / `mine <keyword>` — gather from a node in the current room.
- `craftskills` / `professions` / `prof` — view your gathering and crafting skill levels.

### `recipes` map

Each key is a recipe ID (local or fully qualified).
Each value:

```yaml
displayName:   <string, required, non-blank after trim>
skill:         <string, required - one of SMITHING|ALCHEMY (case-insensitive); must be a crafting skill>
skillRequired: <integer >= 1, optional, default 1>
levelRequired: <integer >= 1, optional, default 1>
materials:     <list<Material>, required, must be non-empty>
outputItemId:  <item-id string, required - must resolve to an existing item>
outputQuantity: <integer >= 1, optional, default 1>
station:       <string, optional - one of FORGE|ALCHEMY_TABLE|WORKBENCH (case-insensitive)>
stationBonus:  <integer >= 0, optional, default 0 - extra output quantity when crafted at a matching station>
xpReward:      <integer >= 0, optional, default 10>
```

`Material` entry:

```yaml
itemId:   <item-id string, required - must resolve to an existing item>
quantity: <integer >= 1, required>
```

Recipe notes:
- `skill` must be a **crafting** skill (`SMITHING` or `ALCHEMY`). Gathering skills are rejected.
- `materials` are consumed from the player's inventory when crafting.
- If `station` is set and the player is in a room with a matching `station` type, the `stationBonus` extra output is produced. If `stationBonus` is 0, the global `crafting.stationBonusQuantity` (default 1) is used instead.
- `levelRequired` is the player's character level (not skill level).
- Crafting stations are visible in the room description ("Crafting station: Forge").

Commands:
- `craft <keyword>` / `make <keyword>` / `create <keyword>` — craft a recipe.
- `recipes [filter]` — list all recipes, optionally filtered by skill name or recipe name.
- `craftskills` / `professions` / `prof` — view your gathering and crafting skill levels.

### `puzzles` map

Each key is a puzzle ID (local identifier).
Each value:

```yaml
puzzles:
  <puzzle-id>:
    type: <string, required - one of riddle|sequence>
    # For riddle type:
    mobId: <mob-id string, optional - the NPC that poses the riddle>
    roomId: <room-id string, required - room where the puzzle is>
    question: <string, required for riddle>
    answer: <string, required for riddle - the correct answer>
    acceptableAnswers: <list<string>, optional - additional accepted answers>
    reward:
      type: <string, required - one of unlock_exit|give_item|give_gold|give_xp>
      exitDirection: <direction, required for unlock_exit>
      targetRoom: <room-id, required for unlock_exit>
      amount: <integer, required for give_gold|give_xp>
      itemId: <item-id, required for give_item>
    failMessage: <string, optional>
    successMessage: <string, optional>
    cooldownMs: <long, optional, default 0 - 0 means one-time per session>
    # For sequence type:
    steps:
      - { feature: <feature-id>, action: <string> }
    resetOnFail: <boolean, optional, default true>
```

Puzzle notes:
- Puzzles are session-scoped: solved state resets when the player disconnects.
- The `answer` command is used to submit a riddle answer (`answer <text>`).
- For `riddle` type: `question` and `answer` are required. The `mobId` optionally ties the riddle to an NPC in the room.
- For `sequence` type: `steps` defines an ordered list of feature interactions the player must complete. `resetOnFail` (default `true`) resets progress if the player performs the wrong step.
- Reward types:
  - `unlock_exit` — reveals a hidden exit in `exitDirection` leading to `targetRoom`.
  - `give_item` — grants the item specified by `itemId`.
  - `give_gold` — awards `amount` gold.
  - `give_xp` — awards `amount` XP.
- `cooldownMs` of `0` (default) means the puzzle can only be solved once per session.

Puzzle ID normalization:
- `roomId` follows the same normalization rules as other room references (prefixed with `<zone>:` when unqualified).
- `mobId` follows the same normalization rules as mob references.
- Reward `targetRoom` and `itemId` follow standard normalization.

### Quest reward `currencies` extension

Quests can award secondary currencies via the `currencies` field on quest rewards:

```yaml
quests:
  <quest-id>:
    rewards:
      currencies: <map<string, long>, optional> # e.g., { quest_points: 10, honor: 5 }
```

Currency keys must match defined currency IDs in `application.yaml` under `engine.currencies.definitions`. See `CurrencySystem` for runtime handling.

### Zero-objective "visit" quests

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

### Quest reward `items` extension

Quests can hand out fixed items on completion. Each entry references an item id that must exist in the same world load (zone-qualified or bare for same-zone items, normalized like `mobs.*.drops.*.itemId`).

```yaml
quests:
  <quest-id>:
    rewards:
      items: # optional list
        - itemId: <string>   # e.g., "academy:rusty_dagger" or "rusty_dagger" within the same zone
          count: <int>       # >= 1; loader rejects 0 or negative
```

Items are spawned into the player's inventory at the moment of quest completion (turn-in or auto-complete) and surfaced through GMCP `Quest.Available` (on offer) and `Quest.Complete` (on turn-in) so clients can preview and celebrate them. Unknown template ids are skipped with a `[Quest]` warning to the player.

## ID Normalization Rules

The loader normalizes IDs with this logic:

1. Trim whitespace.
2. Reject blank strings.
3. If the string contains `:`, use it as-is.
4. Otherwise prefix with `<zone>:` from the current file.

This applies to:

- `startRoom`
- `rooms` keys
- room exit targets
- `mobs` keys and `mobs.*.room`
- `mobs.*.drops.*.itemId`
- `quests.*.rewards.items.*.itemId`
- `items` keys and `items.*.room`
- `shops.*.room`
- `shops.*.items` entries
- `gatheringNodes` keys and `gatheringNodes.*.room`
- `gatheringNodes.*.yields.*.itemId`
- `recipes` keys
- `recipes.*.materials.*.itemId`
- `recipes.*.outputItemId`
- `puzzles.*.roomId`
- `puzzles.*.mobId` (if present)
- `puzzles.*.reward.targetRoom` (if present)
- `puzzles.*.reward.itemId` (if present)

Examples with `zone: swamp`:

- `edge` -> `swamp:edge`
- `forest:trailhead` -> `forest:trailhead`

## Validation Rules

## Per-file validation

Each individual file must satisfy:

1. `zone` is non-blank after trim.
2. If `lifespan` is present, it is `>= 0` (minutes).
3. `rooms` is not empty.
4. `startRoom` (after normalization) exists among that same file's normalized room IDs.

## Cross-file (merged world) validation

When loading multiple files:

1. At least one file must be provided.
2. The world start room is taken from the first file in the list:
   - `world.startRoom = normalize(firstFile.zone, firstFile.startRoom)`
3. Duplicate normalized IDs are rejected globally:
   - room IDs must be unique across all files
   - mob IDs must be unique across all files
   - item IDs must be unique across all files
4. Every exit target must resolve to an existing merged room.
5. Every mob `room` must resolve to an existing merged room.
6. Every item `room` (if set) must resolve to an existing merged room.
7. Every mob drop `itemId` must resolve to an existing merged item.
8. Every shop `room` must resolve to an existing merged room.
9. Every shop `items` entry must resolve to an existing merged item.
10. Every gathering node `room` must resolve to an existing merged room.
11. Every gathering node `yields.*.itemId` must resolve to an existing merged item.
12. Every recipe `materials.*.itemId` must resolve to an existing merged item.
13. Every recipe `outputItemId` must resolve to an existing merged item.
14. For repeated `zone` names across files, `lifespan` merge rule is:
   - if only one file sets `lifespan`, that value is used
   - if multiple files set it, all non-null values must match
   - conflicting non-null values are rejected

## Item Keyword Resolution

If `items.<id>.keyword` is omitted, keyword is derived from the raw item map key:

- Take the text after the last `:`
- Example: key `silver_coin` -> keyword `silver_coin`
- Example: key `swamp:silver_coin` -> keyword `silver_coin`

If `keyword` is provided, it is trimmed and must be non-blank.

## Generator Checklist

For each file your tool emits:

1. Emit required top-level fields: `zone`, `startRoom`, `rooms`.
2. Ensure `rooms` has at least one entry.
3. Ensure `startRoom` points to a room in that same file (after normalization).
4. Restrict exit direction keys to the allowed set.
5. Use only non-negative integers for `lifespan`, `damage`, `armor`, `constitution`.
6. For every item, use `room` or omit placement entirely (unplaced). Do not use `mob`.
7. If `onUse` is present, include at least one positive effect (`healHp`, `healMana`, or `grantXp`).
8. If `charges` is present, it must be > 0.
9. Ensure all local/qualified references resolve in the merged set of files.
10. Ensure normalized room/mob/item IDs are globally unique across files.
11. If splitting one zone across files, keep `lifespan` consistent when repeated.
12. If `respawnSeconds` is present, it must be > 0.
13. If `basePrice` is present, it must be >= 0.
14. If `goldMin` or `goldMax` is present, both must be >= 0 and `goldMax` >= `goldMin`.
15. For every shop, ensure `room` resolves to an existing room and all `items` resolve to existing items.
16. Shop `name` must be non-blank after trim.
17. If `behavior` is present, `template` must be one of the known templates.
18. Do not combine `behavior` with `stationary: true` — they are mutually exclusive.
19. If using `patrol` or `patrol_aggro` templates, `patrolRoute` must be non-empty and all room IDs must resolve.
20. If `gatheringNodes` is present, each node's `skill` must be a gathering skill (`MINING` or `HERBALISM`).
21. Each gathering node must have a non-empty `yields` list; all `itemId` references must resolve to existing items.
22. Each gathering node's `room` must resolve to an existing room.
23. If `recipes` is present, each recipe's `skill` must be a crafting skill (`SMITHING` or `ALCHEMY`).
24. Each recipe must have a non-empty `materials` list; all `itemId` references must resolve to existing items.
25. Each recipe's `outputItemId` must resolve to an existing item.
26. If a recipe specifies `station`, it must be one of `FORGE`, `ALCHEMY_TABLE`, or `WORKBENCH`.
27. If a room specifies `station`, it must be one of `FORGE`, `ALCHEMY_TABLE`, or `WORKBENCH`.
28. If `puzzles` is present, each puzzle must have a valid `type` (`riddle` or `sequence`).
29. For `riddle` puzzles, `question` and `answer` must be non-blank; `roomId` must resolve to an existing room.
30. For `sequence` puzzles, `steps` must be non-empty.
31. Puzzle reward `targetRoom` (for `unlock_exit`) and `itemId` (for `give_item`) must resolve to existing rooms/items.
32. If `pvpEnabled` is present, it must be a boolean.

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
    room: hall
    respawnSeconds: 30 # reappears 30 s after being killed
    drops:
      - itemId: fang
        chance: 1.0
  sentinel:
    name: "a stone sentinel"
    room: entry
    tier: elite
    level: 3
    goldMin: 15        # explicit gold override (ignores tier formula)
    goldMax: 30
    behavior:
      template: aggro_guard
      params:
        aggroMessage: "The sentinel's eyes glow red!"
    # no respawnSeconds — relies on zone-wide reset

items:
  helm:
    displayName: "a dented helm"
    description: "Old iron, still useful."
    slot: head
    armor: 1
    room: entry
    basePrice: 12
  fang:
    displayName: "a rat fang"
    basePrice: 2
  iron_ore:
    displayName: "a chunk of iron ore"
    basePrice: 12
  sigil:
    displayName: "a chalk sigil"
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
  hall:
    title: "Hall"
    description: "Pillars vanish into shadow."
    exits:
      south: entry
      east: overworld:graveyard
  forge:
    title: "The Forge"
    description: "A sweltering room with a roaring forge."
    station: FORGE
    exits:
      e: entry

gatheringNodes:
  iron_vein:
    displayName: "an iron ore vein"
    keyword: iron
    skill: MINING
    skillRequired: 1
    yields:
      - itemId: iron_ore
        minQuantity: 1
        maxQuantity: 2
    respawnSeconds: 30
    xpReward: 15
    room: hall

recipes:
  iron_blade:
    displayName: "Iron Blade"
    skill: SMITHING
    skillRequired: 5
    materials:
      - itemId: iron_ore
        quantity: 3
    outputItemId: helm
    station: FORGE
    stationBonus: 0
    xpReward: 25
```

## Notes For Robust Generators

- Keep IDs stable and slug-like (for example `snake_case`) even though loader checks are minimal.
- Prefer local IDs within the same file; use qualified IDs only for cross-zone references.
- Do not emit unknown fields unless you verify loader behavior for your target version.
