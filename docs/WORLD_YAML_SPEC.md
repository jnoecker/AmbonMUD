# World Zone YAML Spec

This document defines the YAML contract loaded by `WorldLoader` (`src/main/kotlin/dev/ambon/domain/world/load/WorldLoader.kt`).
It is written for code generators that need to emit valid zone files.

## Scope

- One YAML document describes one zone file.
- Multiple zone files can be merged into one world.
- YAML files are deserialized into:
  - `WorldFile` (`zone`, `lifespan`, `startRoom`, `rooms`, `mobs`, `items`, `shops`)
  - `RoomFile`
  - `MobFile`
  - `MobDropFile`
  - `ItemFile`
  - `ShopFile`

## Top-Level Schema

```yaml
zone: <string, required, non-blank after trim>
lifespan: <integer minutes >= 0, optional>
startRoom: <room-id string, required>
rooms: <map<string, Room>, required, must be non-empty>
mobs: <map<string, Mob>, optional, default {}>
items: <map<string, Item>, optional, default {}>
shops: <map<string, Shop>, optional, default {}>
```

`lifespan` notes:
- Units are minutes.
- `0` is allowed and, in the current engine, effectively disables runtime resets (zones reset only when `lifespan > 0`).

### Required vs optional

- Required top-level fields: `zone`, `startRoom`, `rooms`
- Optional top-level fields: `lifespan`, `mobs`, `items`, `shops`

## Nested Schemas

### `rooms` map

Each key is a room ID (local or fully qualified).
Each value:

```yaml
title: <string, required>
description: <string, required>
exits: <map<string direction, string target-room-id>, optional, default {}>
```

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
minDamage:      <integer >= 1, optional - overrides tier-computed minDamage>
maxDamage:      <integer >= minDamage, optional - overrides tier-computed maxDamage>
armor:          <integer >= 0, optional - overrides tier baseArmor (flat damage reduction, no level scaling)>
xpReward:       <long >= 0, optional - overrides tier-computed xpReward>
goldMin:        <long >= 0, optional - overrides tier-computed goldMin>
goldMax:        <long >= goldMin, optional - overrides tier-computed goldMax>
drops:          <list<Drop>, optional, default []>
behavior:       <Behavior, optional - assigns a behavior tree to this mob; see Behavior section>
respawnSeconds: <long > 0, optional - seconds after death before this mob respawns in its origin room;
                omit to rely on zone-wide reset only>
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
hp         = T.baseHp + (L-1) * T.hpPerLevel
minDamage  = T.baseMinDamage + (L-1) * T.damagePerLevel
maxDamage  = T.baseMaxDamage + (L-1) * T.damagePerLevel
armor      = T.baseArmor
xpReward   = T.baseXpReward + (L-1) * T.xpRewardPerLevel
goldMin    = T.baseGoldMin + (L-1) * T.goldPerLevel
goldMax    = T.baseGoldMax + (L-1) * T.goldPerLevel
```

Any explicit per-mob field overrides the computed value from the tier formula.

Tier default values are operator-configurable via `application.yaml` under `ambonmud.engine.mob.tiers`.
The built-in defaults are:

| Tier     | baseHp | hpPerLevel | baseMinDmg | baseMaxDmg | dmgPerLevel | baseArmor | baseXp | xpPerLevel | baseGoldMin | baseGoldMax | goldPerLevel |
|----------|--------|------------|------------|------------|-------------|-----------|--------|------------|-------------|-------------|--------------|
| weak     | 5      | 2          | 1          | 2          | 0           | 0         | 15     | 5          | 1           | 3           | 1            |
| standard | 10     | 3          | 1          | 4          | 1           | 0         | 30     | 10         | 2           | 8           | 2            |
| elite    | 20     | 5          | 2          | 6          | 1           | 1         | 75     | 20         | 10          | 25          | 5            |
| boss     | 50     | 10         | 3          | 8          | 2           | 3         | 200    | 50         | 50          | 100         | 15           |

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
matchByKey: <boolean, optional, default false>
basePrice: <integer, optional, default 0, must be >= 0>
```

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
grantXp: <long, optional, default 0, must be >= 0>
```

If `onUse` is present, at least one effect must be positive (`healHp > 0` or `grantXp > 0`).

Charge/consumption notes:
- If `charges` is set, one charge is spent per use.
- If `consumable: true`, the item is removed when charges are exhausted (or immediately after use when `charges` is unset).

Location rules for items:

- `room` may be omitted (item starts unplaced).
- `mob` placement is deprecated and rejected by the loader.

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
- `items` keys and `items.*.room`
- `shops.*.room`
- `shops.*.items` entries

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
10. For repeated `zone` names across files, `lifespan` merge rule is:
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
7. If `onUse` is present, include at least one positive effect (`healHp` or `grantXp`).
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

rooms:
  entry:
    title: "Entry"
    description: "A cracked stair descends."
    exits:
      n: hall
  hall:
    title: "Hall"
    description: "Pillars vanish into shadow."
    exits:
      south: entry
      east: overworld:graveyard
```

## Notes For Robust Generators

- Keep IDs stable and slug-like (for example `snake_case`) even though loader checks are minimal.
- Prefer local IDs within the same file; use qualified IDs only for cross-zone references.
- Do not emit unknown fields unless you verify loader behavior for your target version.
