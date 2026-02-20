# World Zone YAML Spec

This document defines the YAML contract loaded by `WorldLoader` (`src/main/kotlin/dev/ambon/domain/world/load/WorldLoader.kt`).
It is written for code generators that need to emit valid zone files.

## Scope

- One YAML document describes one zone file.
- Multiple zone files can be merged into one world.
- YAML files are deserialized into:
  - `WorldFile` (`zone`, `lifespan`, `startRoom`, `rooms`, `mobs`, `items`)
  - `RoomFile`
  - `MobFile`
  - `ItemFile`

## Top-Level Schema

```yaml
zone: <string, required, non-blank after trim>
lifespan: <integer minutes >= 0, optional>
startRoom: <room-id string, required>
rooms: <map<string, Room>, required, must be non-empty>
mobs: <map<string, Mob>, optional, default {}>
items: <map<string, Item>, optional, default {}>
```

`lifespan` notes:
- Units are minutes.
- `0` is allowed and, in the current engine, effectively disables runtime resets (zones reset only when `lifespan > 0`).

### Required vs optional

- Required top-level fields: `zone`, `startRoom`, `rooms`
- Optional top-level fields: `lifespan`, `mobs`, `items`

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
name: <string, required>
room: <room-id string, required>
```

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
room: <room-id string, optional>
mob: <mob-id string, optional>
```

Location rules for items:

- `room` and `mob` cannot both be set.
- Both may be omitted (item starts unplaced).

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
- `items` keys, `items.*.room`, `items.*.mob`

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
7. Every item `mob` (if set) must resolve to an existing merged mob.
8. For repeated `zone` names across files, `lifespan` merge rule is:
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
6. For every item, set at most one of `room` or `mob`.
7. Ensure all local/qualified references resolve in the merged set of files.
8. Ensure normalized room/mob/item IDs are globally unique across files.
9. If splitting one zone across files, keep `lifespan` consistent when repeated.

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

items:
  helm:
    displayName: "a dented helm"
    description: "Old iron, still useful."
    slot: head
    armor: 1
    room: entry
  fang:
    displayName: "a rat fang"
    mob: rat
  sigil:
    displayName: "a chalk sigil"

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
