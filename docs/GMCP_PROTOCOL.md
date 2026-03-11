# AmbonMUD GMCP Protocol Reference

**Date:** 2026-03-11

GMCP (Generic MUD Communication Protocol) is a telnet subnegotiation extension (option 201 / `0xC9`) that lets the server send structured JSON data alongside the plain text MUD stream. AmbonMUD extends this to WebSocket clients via a thin JSON envelope.

This document covers everything you need to implement a client that communicates with AmbonMUD — negotiation, subscription, all supported packages, payload shapes, send triggers, and the planned roadmap.

---

## Table of Contents

1. [Transport Layer](#1-transport-layer)
   - [Telnet Negotiation](#telnet-negotiation)
   - [WebSocket Protocol](#websocket-protocol)
2. [Protocol Basics](#2-protocol-basics)
   - [Package Naming & Versioning](#package-naming--versioning)
   - [Subscription Model](#subscription-model)
   - [Prefix Matching](#prefix-matching)
3. [Inbound Packages (Client → Server)](#3-inbound-packages-client--server)
4. [Outbound Packages (Server → Client)](#4-outbound-packages-server--client)
5. [Send Triggers & Timing](#5-send-triggers--timing)
6. [Wire Format Examples](#6-wire-format-examples)
7. [Planned Future Packages](#7-planned-future-packages)

---

## 1. Transport Layer

### Telnet Negotiation

AmbonMUD speaks standard RFC 2066 / GMCP (option `0xC9`). On connection the server proactively offers GMCP along with TTYPE and NAWS:

```
Server → Client:  IAC WILL GMCP    (FF FB C9)
Server → Client:  IAC DO   TTYPE   (FF FD 18)
Server → Client:  IAC DO   NAWS    (FF FD 1F)
```

To enable GMCP, the client must accept the offer:

```
Client → Server:  IAC DO   GMCP    (FF FD C9)   ← enables GMCP
Client → Server:  IAC DONT GMCP    (FF FE C9)   ← rejects GMCP
```

If the client rejects GMCP the server continues normally; all GMCP frames are silently suppressed for that session.

**Telnet protocol constants:**

| Symbol | Decimal | Hex  |
|--------|---------|------|
| `SE`   | 240     | `F0` |
| `SB`   | 250     | `FA` |
| `WILL` | 251     | `FB` |
| `WONT` | 252     | `FC` |
| `DO`   | 253     | `FD` |
| `DONT` | 254     | `FE` |
| `IAC`  | 255     | `FF` |
| `TTYPE`| 24      | `18` |
| `NAWS` | 31      | `1F` |
| `GMCP` | 201     | `C9` |

**Subnegotiation frame:**

```
IAC SB GMCP <payload bytes> IAC SE
FF FA C9    <...>            FF F0
```

**Payload format:**

```
<package-name> <json>
```

The package name and JSON data are separated by a single space. If the package carries no data, the JSON is omitted (just the package name alone).

---

### WebSocket Protocol

WebSocket clients connect to `/ws`. All game communication happens over a single text-message WebSocket channel.

**GMCP messages are wrapped in a JSON envelope:**

```json
{"gmcp":"<Package.Name>","data":<json-value>}
```

Plain text MUD output (room descriptions, combat messages, etc.) is sent as bare text frames — not in the envelope.

**Outbound example** (server → client):
```json
{"gmcp":"Char.Vitals","data":{"hp":85,"maxHp":100,"mana":42,"maxMana":100,"level":5,"xp":12400,"xpIntoLevel":2400,"xpToNextLevel":7600,"gold":350,"inCombat":true}}
```

**Inbound example** (client → server):
```json
{"gmcp":"Core.Ping","data":{}}
```

The `"data"` key is optional on inbound messages; if omitted it defaults to `{}`.

**Auto-subscription:** Unlike telnet clients, WebSocket clients are automatically subscribed to the full core package set the moment they connect (no `Core.Supports.Set` required). The auto-subscribed packages are:

```
Char.Vitals, Room.Info, Char.StatusVars, Char.Items, Room.Players,
Room.Mobs, Room.Items, Char.Skills, Char.Name, Char.StatusEffects,
Comm.Channel, Core.Ping
```

Telnet clients start with no subscriptions and must explicitly send `Core.Supports.Set`.

---

## 2. Protocol Basics

### Package Naming & Versioning

Package names follow a `Namespace.Name` (or `Namespace.Name.Subname`) dotted convention. Names are **case-sensitive**.

When sending `Core.Supports.Set`, each entry includes a version number:

```json
["Char.Vitals 1", "Room.Info 1"]
```

Version numbers are parsed but currently ignored — all packages are treated as version 1.

---

### Subscription Model

The server only sends a package if the client has declared support for it via `Core.Supports.Set` (telnet) or the auto-subscription list (WebSocket). Unsupported packages are silently dropped.

The subscription list is stored server-side per session and persists until `Core.Supports.Remove` is received or the session ends.

---

### Prefix Matching

Subscriptions use **prefix matching**. Subscribing to `Char.Items` enables all sub-packages:

| Subscribed to | Also receives |
|---------------|---------------|
| `Char.Items`  | `Char.Items.List`, `Char.Items.Add`, `Char.Items.Remove` |
| `Room`        | All `Room.*` packages |
| `Char.Vitals` | Only `Char.Vitals` (no sub-packages) |

An exact match also satisfies the check (subscribing to `Char.Items.Add` receives only `Char.Items.Add`).

---

## 3. Inbound Packages (Client → Server)

### `Core.Hello`

Optional client greeting. Logged at debug level; no state change.

```json
{"gmcp": "Core.Hello", "data": {"client": "MyMudClient", "version": "1.0"}}
```

---

### `Core.Supports.Set`

Declares the complete list of GMCP packages the client wants to receive. Replaces (not extends) the current subscription list. After receiving this, the server sends a full initial state dump for all subscribed packages.

```json
{"gmcp": "Core.Supports.Set", "data": ["Char.Vitals 1", "Room.Info 1", "Char.Name 1"]}
```

On telnet the payload is sent as the raw JSON array (no outer envelope). Upon receiving `Core.Supports.Set`, the server immediately sends:

- `Char.StatusVars` (if subscribed)
- `Char.Vitals` (if subscribed)
- `Room.Info` (if subscribed)
- `Char.Name` (if subscribed)
- `Char.Items.List` (if subscribed to `Char.Items` or `Char.Items.List`)
- `Room.Players` (if subscribed)
- `Room.Mobs` (if subscribed)
- `Room.Items` (if subscribed)
- `Char.Skills` (if subscribed)
- `Char.StatusEffects` (if subscribed)
- `Char.Achievements` (if subscribed)
- `Group.Info` (if subscribed and player is in a group)

---

### `Core.Supports.Remove`

Removes packages from the active subscription list. Same array format as `Core.Supports.Set`.

```json
{"gmcp": "Core.Supports.Remove", "data": ["Char.Skills 1"]}
```

---

### `Core.Ping`

Keep-alive heartbeat. The server echoes back a `Core.Ping` response immediately.

```json
{"gmcp": "Core.Ping", "data": {}}
```

---

## 4. Outbound Packages (Server → Client)

---

### `Core.Ping`

Response to a client `Core.Ping`.

```json
{}
```

---

### `Char.Name`

Sent once on login. Static for the duration of the session (except `level` updates on level-up).

```json
{
  "name": "Ambuoroko",
  "gender": "neutral",
  "race": "ELF",
  "class": "MAGE",
  "level": 7,
  "sprite": "/images/player_sprites/elf_mage_t1.png",
  "isStaff": false
}
```

| Field    | Type    | Notes |
|----------|---------|-------|
| `name`   | string  | Character name |
| `gender` | string  | Character gender |
| `race`   | string  | Race enum name: `HUMAN`, `ELF`, `DWARF`, `HALFLING` |
| `class`  | string  | Class enum name: `WARRIOR`, `MAGE`, `CLERIC`, `ROGUE` |
| `level`  | int     | Current level |
| `sprite` | string  | URL path to the player's current sprite image |
| `isStaff`| boolean | `true` for staff/admin characters |

---

### `Char.Vitals`

Sent on login and whenever HP, mana, XP, gold, level, or combat state changes. Batched per engine tick (100 ms); multiple changes within one tick produce a single send.

```json
{
  "hp": 85,
  "maxHp": 110,
  "mana": 42,
  "maxMana": 100,
  "level": 7,
  "xp": 14800,
  "xpIntoLevel": 4800,
  "xpToNextLevel": 9600,
  "gold": 312,
  "inCombat": false
}
```

| Field            | Type    | Notes |
|------------------|---------|-------|
| `hp`             | int     | Current hit points |
| `maxHp`          | int     | Maximum hit points |
| `mana`           | int     | Current mana |
| `maxMana`        | int     | Maximum mana |
| `level`          | int     | Current character level |
| `xp`             | long    | Total XP earned (all-time) |
| `xpIntoLevel`    | long    | XP earned into the current level |
| `xpToNextLevel`  | long\|null | XP still needed for next level; `null` at level cap (50) |
| `gold`           | long    | Gold carried |
| `inCombat`       | boolean | `true` while in an active fight |

---

### `Char.StatusVars`

Sent once on login. Provides human-readable labels for the vitals fields (useful for generic client UIs).

```json
{
  "hp": "HP",
  "maxHp": "Max HP",
  "mana": "Mana",
  "maxMana": "Max Mana",
  "level": "Level",
  "xp": "XP"
}
```

---

### `Char.Items.List`

Full snapshot of inventory and equipped items. Sent on login and after any inventory or equipment change.

```json
{
  "inventory": [
    {
      "id": "tutorial_glade:short_sword#3",
      "name": "Short Sword",
      "slot": "MAIN_HAND",
      "damage": 8,
      "armor": 0
    },
    {
      "id": "tutorial_glade:health_potion#7",
      "name": "Health Potion",
      "slot": null,
      "damage": 0,
      "armor": 0
    }
  ],
  "equipment": {
    "HEAD": null,
    "NECK": null,
    "CHEST": { "id": "...", "name": "Leather Vest", "slot": "CHEST", "damage": 0, "armor": 3 },
    "HANDS": null,
    "WAIST": null,
    "LEGS": null,
    "FEET": null,
    "MAIN_HAND": { "id": "...", "name": "Short Sword", "slot": "MAIN_HAND", "damage": 8, "armor": 0 },
    "OFF_HAND": null
  }
}
```

**Item object:**

| Field       | Type         | Notes |
|-------------|--------------|-------|
| `id`        | string       | Unique instance ID — format `zone:item_id#instance` |
| `name`      | string       | Display name |
| `keyword`   | string       | Keyword for commands (e.g., `sword`, `potion`) |
| `slot`      | string\|null | Equipment slot if wearable; `null` for non-equipment |
| `damage`    | int          | Weapon damage (0 for non-weapons) |
| `armor`     | int          | Armor value (0 for non-armor) |
| `basePrice` | int          | Base gold value (0 if not sellable) |
| `image`     | string\|null | Item sprite image URL |
| `video`     | string\|null | Item video URL |

**Equipment slot keys:** `HEAD`, `NECK`, `CHEST`, `HANDS`, `WAIST`, `LEGS`, `FEET`, `MAIN_HAND`, `OFF_HAND`.
Each slot is present in the map; value is `null` if empty.

---

### `Char.Items.Add`

Sent immediately when the player picks up an item (ground → inventory).

```json
{
  "id": "tutorial_glade:iron_shield#12",
  "name": "Iron Shield",
  "slot": "OFF_HAND",
  "damage": 0,
  "armor": 5
}
```

Same field set as the item object in `Char.Items.List`.

---

### `Char.Items.Remove`

Sent immediately when an item leaves the player's inventory (dropped, sold, consumed).

```json
{
  "id": "tutorial_glade:health_potion#7",
  "name": "Health Potion"
}
```

| Field  | Type   | Notes |
|--------|--------|-------|
| `id`   | string | Instance ID of the removed item |
| `name` | string | Display name (for UI acknowledgment) |

---

### `Char.Skills`

Full ability list. Sent on login, when a new ability is learned (level-up), and when a cooldown starts or expires.

```json
[
  {
    "id": "fireball",
    "name": "Fireball",
    "description": "Hurls a blazing sphere of fire at a single target.",
    "manaCost": 22,
    "cooldownMs": 6000,
    "cooldownRemainingMs": 0,
    "levelRequired": 5,
    "targetType": "ENEMY",
    "classRestriction": "MAGE",
    "image": "/images/abilities/fireball.png"
  }
]
```

| Field                  | Type         | Notes |
|------------------------|--------------|-------|
| `id`                   | string       | Ability identifier (matches `application.yaml` key) |
| `name`                 | string       | Display name |
| `description`          | string       | Flavour/effect text |
| `manaCost`             | int          | Mana consumed on cast |
| `cooldownMs`           | long         | Full cooldown duration in milliseconds |
| `cooldownRemainingMs`  | long         | Milliseconds until the ability is ready (0 = ready) |
| `levelRequired`        | int          | Minimum character level to use |
| `targetType`           | string       | `SELF`, `ENEMY`, `ALLY`, `ALL_ENEMIES`, `ALL_ALLIES` |
| `classRestriction`     | string\|null | Required class, or `null` if any class can use it |
| `image`                | string\|null | URL path to the ability's sprite image |

---

### `Char.StatusEffects`

Full snapshot of active status effects on the player. Sent on login and batched per tick when effects are applied, updated, or expire.

```json
[
  {
    "id": "poison",
    "name": "Poison",
    "type": "DOT",
    "remainingMs": 4200,
    "stacks": 1
  },
  {
    "id": "strength_boost",
    "name": "Strength Boost",
    "type": "STAT_BUFF",
    "remainingMs": 12000,
    "stacks": 1
  }
]
```

| Field         | Type   | Notes |
|---------------|--------|-------|
| `id`          | string | Effect identifier (matches `application.yaml` key) |
| `name`        | string | Display name |
| `type`        | string | `DOT`, `HOT`, `STAT_BUFF`, `STAT_DEBUFF`, `STUN`, `ROOT`, `SHIELD` |
| `remainingMs` | long   | Milliseconds until the effect expires |
| `stacks`      | int    | Stack count (always ≥ 1) |

---

### `Char.Achievements`

Sent on login and when an achievement is unlocked or its progress changes.

```json
{
  "completed": [
    {
      "id": "first_kill",
      "name": "First Blood",
      "title": "the Blooded"
    }
  ],
  "inProgress": [
    {
      "id": "kill_100_mobs",
      "name": "Slayer",
      "current": 37,
      "required": 100
    }
  ]
}
```

**Completed achievement object:**

| Field   | Type         | Notes |
|---------|--------------|-------|
| `id`    | string       | Achievement identifier |
| `name`  | string       | Display name |
| `title` | string\|null | Title awarded on completion; `null` if no title |

**In-progress achievement object:**

| Field      | Type   | Notes |
|------------|--------|-------|
| `id`       | string | Achievement identifier |
| `name`     | string | Display name |
| `current`  | int    | Current progress value |
| `required` | int    | Value needed to complete |

---

### `Char.Stats`

Sent on login, on level-up, and whenever a status effect that modifies stats is applied or expires (batched per tick).

```json
{
  "stats": [
    { "id": "STR", "name": "Strength",     "abbrev": "STR", "base": 10, "effective": 12 },
    { "id": "DEX", "name": "Dexterity",    "abbrev": "DEX", "base": 14, "effective": 14 },
    { "id": "CON", "name": "Constitution", "abbrev": "CON", "base": 12, "effective": 13 },
    { "id": "INT", "name": "Intelligence", "abbrev": "INT", "base": 16, "effective": 16 },
    { "id": "WIS", "name": "Wisdom",       "abbrev": "WIS", "base": 11, "effective": 11 },
    { "id": "CHA", "name": "Charisma",     "abbrev": "CHA", "base": 10, "effective": 10 }
  ],
  "baseDamageMin": 1,
  "baseDamageMax": 4,
  "armor": 3,
  "dodgePercent": 8
}
```

**`stats` array entry:**

| Field       | Type   | Notes |
|-------------|--------|-------|
| `id`        | string | Stat identifier (uppercase, e.g. `"STR"`) |
| `name`      | string | Full stat name (e.g. `"Strength"`) |
| `abbrev`    | string | Short label for UI display |
| `base`      | int    | Base stat value before equipment or status effect modifiers |
| `effective` | int    | Final stat value after all modifiers |

**Top-level fields:**

| Field           | Type | Notes |
|-----------------|------|-------|
| `baseDamageMin` | int  | Minimum unarmed damage before STR scaling |
| `baseDamageMax` | int  | Maximum unarmed damage before STR scaling |
| `armor`         | int  | Total armor from equipped items |
| `dodgePercent`  | int  | Effective dodge chance (capped at `maxDodgePercent` in config) |

---

### `Room.Info`

Sent on login, every time the player moves to a new room, and in response to the `look` command.

```json
{
  "id": "tutorial_glade:clearing",
  "title": "Sunlit Clearing",
  "description": "A wide meadow bathed in afternoon light. Wildflowers dot the tall grass.",
  "zone": "tutorial_glade",
  "exits": {
    "north": "tutorial_glade:forest_path",
    "east":  "tutorial_glade:stream_bank"
  },
  "image": "/images/rooms/clearing.png",
  "music": "forest_theme",
  "ambient": "birds_chirping"
}
```

| Field         | Type                | Notes |
|---------------|---------------------|-------|
| `id`          | string              | Room ID — format `zone:room_id` |
| `title`       | string              | Short room name |
| `description` | string              | Long room description |
| `zone`        | string              | Zone identifier |
| `exits`       | object              | Map of direction → destination room ID |
| `image`       | string\|null        | Room background image URL |
| `video`       | string\|null        | Room background video URL |
| `music`       | string\|null        | Background music identifier |
| `ambient`     | string\|null        | Ambient sound identifier |

**Exit direction keys:** `north`, `south`, `east`, `west`, `up`, `down`, `northeast`, `northwest`, `southeast`, `southwest` (lowercase, only present if the exit exists).

---

### `Room.Players`

Full snapshot of other players in the room. Sent on login and after any player enters or leaves.

```json
[
  { "name": "Thornveil", "level": 12 }
]
```

Does **not** include the receiving player. Empty array if alone.

| Field   | Type   |
|---------|--------|
| `name`  | string |
| `level` | int    |

---

### `Room.AddPlayer`

Sent immediately to all players in the room when another player enters.

```json
{ "name": "Thornveil", "level": 12 }
```

---

### `Room.RemovePlayer`

Sent immediately to all players in the room when a player leaves or disconnects.

```json
{ "name": "Thornveil" }
```

---

### `Room.Mobs`

Full snapshot of mobs currently in the room. Sent on login and after any mob enters, dies, or respawns.

```json
[
  {
    "id": "tutorial_glade:wolf#2",
    "name": "Grey Wolf",
    "description": "A lean grey wolf with watchful eyes.",
    "hp": 28,
    "maxHp": 40,
    "image": "/images/mobs/wolf.png"
  }
]
```

| Field         | Type         | Notes |
|---------------|--------------|-------|
| `id`          | string       | Mob instance ID — format `zone:mob_id#instance` |
| `name`        | string       | Display name |
| `description` | string       | Mob description (may be empty) |
| `hp`          | int          | Current hit points |
| `maxHp`       | int          | Maximum hit points |
| `image`       | string\|null | Mob sprite image URL |
| `video`       | string\|null | Mob video URL |

---

### `Room.AddMob`

Sent immediately to all players in a room when a mob spawns or wanders in. Same field set as `Room.Mobs` entries.

```json
{
  "id": "tutorial_glade:wolf#2",
  "name": "Grey Wolf",
  "description": "A lean grey wolf with watchful eyes.",
  "hp": 40,
  "maxHp": 40,
  "image": "/images/mobs/wolf.png"
}
```

---

### `Room.UpdateMob`

Sent once per tick to all players in a room when a mob's HP changes (combat damage, regen, etc.). Same field set as `Room.Mobs` entries.

```json
{
  "id": "tutorial_glade:wolf#2",
  "name": "Grey Wolf",
  "description": "A lean grey wolf with watchful eyes.",
  "hp": 12,
  "maxHp": 40,
  "image": "/images/mobs/wolf.png"
}
```

---

### `Room.RemoveMob`

Sent immediately to all players in the room when a mob dies or wanders out.

```json
{ "id": "tutorial_glade:wolf#2" }
```

---

### `Room.Items`

Full snapshot of items on the room floor. Sent after an item is dropped, picked up, or placed by a mob death.

```json
[
  {
    "id": "tutorial_glade:iron_key#5",
    "name": "Iron Key",
    "description": "A heavy iron key.",
    "image": "/images/items/iron_key.png"
  }
]
```

| Field         | Type         | Notes |
|---------------|--------------|-------|
| `id`          | string       | Item instance ID |
| `name`        | string       | Display name |
| `description` | string       | Item description (may be empty) |
| `image`       | string\|null | Item sprite image URL |
| `video`       | string\|null | Item video URL |

---

### `Comm.Channel`

Sent immediately when a chat message is received on any subscribed channel.

```json
{
  "channel": "say",
  "sender":  "Ambuoroko",
  "message": "Has anyone seen the archivist?"
}
```

| Field     | Type   | Notes |
|-----------|--------|-------|
| `channel` | string | `say`, `tell`, `whisper`, `gossip`, `shout`, `ooc`, `gtell` |
| `sender`  | string | Name of the character who sent the message |
| `message` | string | Message text (no trailing newline) |

> **Note:** `tell` and `whisper` messages are sent only to the recipient. `say` and `gtell` (group tell) are sent only to players in the same room or group respectively.

---

### `Group.Info`

Sent on login (if in a group), when the player joins or leaves a group, and once per tick when any group member's HP changes.

```json
{
  "leader": "Ambuoroko",
  "members": [
    {
      "name": "Ambuoroko",
      "level": 7,
      "hp": 85,
      "maxHp": 110,
      "class": "MAGE"
    },
    {
      "name": "Thornveil",
      "level": 9,
      "hp": 140,
      "maxHp": 155,
      "class": "WARRIOR"
    }
  ]
}
```

| Field           | Type         | Notes |
|-----------------|--------------|-------|
| `leader`        | string\|null | Name of the group leader; `null` if not in a group |
| `members`       | array        | All group members including the receiving player |
| `members[].name`   | string    | Character name |
| `members[].level`  | int       | Character level |
| `members[].hp`     | int       | Current HP |
| `members[].maxHp`  | int       | Maximum HP |
| `members[].class`  | string    | Class enum name |

When `leader` is `null`, `members` is an empty array.

---

### `Char.Combat`

Sent when the player enters or exits combat, and once per tick while in combat. Provides the current combat target's info.

```json
{
  "targetId": "tutorial_glade:wolf#2",
  "targetName": "Grey Wolf",
  "targetHp": 22,
  "targetMaxHp": 40,
  "targetImage": "/images/mobs/wolf.png"
}
```

| Field         | Type         | Notes |
|---------------|--------------|-------|
| `targetId`    | string\|null | Mob instance ID of the current target; `null` if not in combat |
| `targetName`  | string\|null | Target display name |
| `targetHp`    | int\|null    | Target's current HP |
| `targetMaxHp` | int\|null    | Target's max HP |
| `targetImage` | string\|null | Target's sprite image URL |

---

### `Char.Combat.Event`

Sent immediately for each combat event (hit, dodge, heal, DoT tick, kill, death, shield absorb). Drives combat animations and damage numbers on the canvas.

```json
{
  "type": "meleeHit",
  "targetName": "Grey Wolf",
  "targetId": "tutorial_glade:wolf#2",
  "damage": 14,
  "sourceIsPlayer": true
}
```

**Event types and their fields:**

| `type` | Description | Key fields |
|--------|-------------|------------|
| `meleeHit` | Auto-attack hit | `targetName`, `targetId`, `damage`, `sourceIsPlayer` |
| `abilityHit` | Spell/ability damage | `abilityId`, `abilityName`, `targetName`, `targetId`, `damage`, `sourceIsPlayer` |
| `heal` | Heal from ability | `abilityName`, `targetName`, `amount`, `sourceIsPlayer` |
| `dodge` | Attack dodged | `targetName`, `targetId`, `sourceIsPlayer` |
| `dotTick` | Damage-over-time tick | `effectName`, `targetName`, `targetId`, `damage` |
| `hotTick` | Heal-over-time tick | `effectName`, `targetName`, `amount` |
| `kill` | Target killed | `targetName`, `targetId`, `xpGained`, `goldGained` |
| `death` | Player died | `killerName`, `killerIsPlayer` |
| `shieldAbsorb` | Shield absorbed damage | `attackerName`, `absorbed`, `remaining` |

All fields are nullable except `type`. Only fields relevant to the event type are populated.

---

### `Char.Cooldown`

Sent immediately when an ability's cooldown starts.

```json
{
  "abilityId": "fireball",
  "cooldownMs": 6000
}
```

| Field        | Type   | Notes |
|--------------|--------|-------|
| `abilityId`  | string | Ability identifier |
| `cooldownMs` | long   | Full cooldown duration in milliseconds |

---

### `Char.Gain`

Sent immediately when the player gains XP, gold, or levels up. Drives floating popup numbers on the canvas.

```json
{
  "type": "xp",
  "amount": 150,
  "source": "Grey Wolf"
}
```

| Field      | Type         | Notes |
|------------|--------------|-------|
| `type`     | string       | `"xp"`, `"gold"`, or `"levelUp"` |
| `amount`   | long         | Amount gained |
| `source`   | string\|null | Source of the gain (mob name, etc.) |
| `newLevel` | int\|null    | New level (only present for `levelUp`) |
| `hpGained` | int\|null    | HP increase from leveling (only for `levelUp`) |
| `manaGained`| int\|null   | Mana increase from leveling (only for `levelUp`) |

---

### `Room.MobInfo`

Metadata about mobs in the room — level, tier, and interaction markers (quest, shop, dialogue). Sent on room entry and when mob state changes.

```json
[
  {
    "id": "tutorial_glade:merchant#1",
    "level": 10,
    "tier": "normal",
    "questGiver": true,
    "shopKeeper": true,
    "dialogue": true
  }
]
```

| Field        | Type    | Notes |
|--------------|---------|-------|
| `id`         | string  | Mob instance ID |
| `level`      | int     | Estimated mob level |
| `tier`       | string  | Mob tier (e.g., `"normal"`, `"elite"`, `"boss"`) |
| `questGiver` | boolean | `true` if mob offers a quest |
| `shopKeeper` | boolean | `true` if mob runs a shop |
| `dialogue`   | boolean | `true` if mob has a dialogue tree |

---

### `Quest.List`

Full quest log snapshot. Sent on login and after quest accepted/updated/completed/abandoned.

```json
[
  {
    "id": "find_the_relic",
    "name": "Find the Relic",
    "description": "Recover the lost relic from the ruins.",
    "objectives": [
      { "description": "Enter the ruins", "current": 1, "required": 1 },
      { "description": "Find the relic", "current": 0, "required": 1 }
    ]
  }
]
```

| Field                       | Type   | Notes |
|-----------------------------|--------|-------|
| `id`                        | string | Quest identifier |
| `name`                      | string | Quest display name |
| `description`               | string | Quest description |
| `objectives[].description`  | string | Objective text |
| `objectives[].current`      | int    | Current progress |
| `objectives[].required`     | int    | Required to complete |

---

### `Quest.Update`

Sent immediately when a single quest objective progresses.

```json
{
  "questId": "find_the_relic",
  "objectiveIndex": 1,
  "current": 1,
  "required": 1
}
```

---

### `Quest.Complete`

Sent immediately when a quest is completed.

```json
{
  "questId": "find_the_relic",
  "questName": "Find the Relic"
}
```

---

### `Dialogue.Node`

Sent when an NPC dialogue node is presented to the player.

```json
{
  "mobName": "Archivist Maren",
  "text": "Welcome, traveler. Are you here to help?",
  "choices": [
    { "index": 1, "text": "Yes, what do you need?" },
    { "index": 2, "text": "Not right now." }
  ]
}
```

---

### `Dialogue.End`

Sent when a dialogue conversation ends.

```json
{
  "mobName": "Archivist Maren",
  "reason": "farewell"
}
```

---

### `Guild.Info`

Sent on login and when guild state changes (join, leave, promote, MOTD update).

```json
{
  "name": "Silver Guard",
  "tag": "SG",
  "rank": "OFFICER",
  "motd": "Raid tonight at 8pm!",
  "memberCount": 12,
  "maxSize": 50
}
```

| Field         | Type         | Notes |
|---------------|--------------|-------|
| `name`        | string\|null | Guild name; `null` if not in a guild |
| `tag`         | string\|null | Guild tag (short label) |
| `rank`        | string\|null | Player's rank: `LEADER`, `OFFICER`, `MEMBER` |
| `motd`        | string\|null | Message of the day |
| `memberCount` | int          | Current member count |
| `maxSize`     | int          | Maximum guild size |

---

### `Guild.Members`

Sent on request. Full roster of guild members.

```json
[
  { "name": "Thornveil", "rank": "LEADER", "online": true, "level": 15 },
  { "name": "Ambuoroko", "rank": "OFFICER", "online": true, "level": 7 },
  { "name": "Silkwind", "rank": "MEMBER", "online": false, "level": 12 }
]
```

---

### `Guild.Chat`

Sent immediately when a guild chat message is received.

```json
{
  "sender": "Thornveil",
  "message": "Ready for the raid?"
}
```

---

### `Friends.List`

Full friends list snapshot. Sent on login and on `friend list`.

```json
[
  { "name": "Thornveil", "online": true, "level": 15, "zone": "tutorial_glade" },
  { "name": "Silkwind", "online": false, "level": null, "zone": null }
]
```

| Field   | Type         | Notes |
|---------|--------------|-------|
| `name`  | string       | Friend's character name |
| `online`| boolean      | Whether currently logged in |
| `level` | int\|null    | Level (null if offline) |
| `zone`  | string\|null | Current zone (null if offline) |

---

### `Friends.Online`

Sent immediately when a friend logs in.

```json
{ "name": "Thornveil", "level": 15 }
```

---

### `Friends.Offline`

Sent immediately when a friend logs out.

```json
{ "name": "Thornveil" }
```

---

### `Shop.List`

Sent when a player enters a shop or uses the `list` command at a shop.

```json
{
  "name": "General Store",
  "sellMultiplier": 0.5,
  "items": [
    {
      "id": "tutorial_glade:health_potion",
      "name": "Health Potion",
      "keyword": "potion",
      "description": "Restores 20 HP.",
      "slot": null,
      "damage": 0,
      "armor": 0,
      "buyPrice": 25,
      "basePrice": 20,
      "consumable": true,
      "image": "/images/items/health_potion.png"
    }
  ]
}
```

| Field            | Type         | Notes |
|------------------|--------------|-------|
| `name`           | string       | Shop name |
| `sellMultiplier` | double       | Multiplier applied to base price when selling |
| `items[].id`     | string       | Item template ID |
| `items[].name`   | string       | Display name |
| `items[].keyword`| string       | Command keyword |
| `items[].description` | string  | Item description |
| `items[].slot`   | string\|null | Equipment slot (null for non-equipment) |
| `items[].damage` | int          | Weapon damage |
| `items[].armor`  | int          | Armor value |
| `items[].buyPrice` | int        | Price to buy (base × buy multiplier) |
| `items[].basePrice` | int       | Base price |
| `items[].consumable` | boolean  | Whether item is consumable |
| `items[].image`  | string\|null | Item image URL |
| `items[].video`  | string\|null | Item video URL |

---

### `Shop.Close`

Sent when the player leaves a shop context.

```json
{}
```

---

### `Server.Assets`

Sent on login. Contains resolved URLs for global asset files (sprites, images). Payload is a map of asset keys to URL paths.

---

## 5. Send Triggers & Timing

### Batched (once per 100 ms tick)

These packages are coalesced — if the same session is marked dirty multiple times in one tick, only one send occurs at the end of the tick.

| Package              | Dirty trigger |
|----------------------|---------------|
| `Char.Vitals`        | HP/mana/XP/gold/level changes; combat state change |
| `Char.Combat`        | Combat target changes, combat start/end |
| `Char.StatusEffects` | Effect applied, ticked, or expired |
| `Char.Stats`         | Login / level-up / stat-modifying effect applied or expired |
| `Room.UpdateMob`     | Mob HP changes (combat, regen) |
| `Group.Info`         | Group membership or member HP change |

### Immediate (sent at the moment the event occurs)

| Package              | Trigger |
|----------------------|---------|
| `Char.Name`          | Login |
| `Char.StatusVars`    | Login / `Core.Supports.Set` |
| `Char.Items.List`    | Login / `Core.Supports.Set` |
| `Char.Skills`        | Login / level-up (new ability) / cooldown change |
| `Char.Achievements`  | Login / achievement progress or unlock |
| `Char.Combat.Event`  | Each combat event (hit, dodge, heal, kill, death) |
| `Char.Cooldown`      | Ability cooldown started |
| `Char.Gain`          | XP/gold gained or level-up |
| `Room.Info`          | Login / movement / `look` command |
| `Room.Players`       | Login / `Core.Supports.Set` |
| `Room.Mobs`          | Login / `Core.Supports.Set` / mob enters or dies |
| `Room.Items`         | Login / item dropped or picked up from floor |
| `Room.MobInfo`       | Room entry / mob state change |
| `Room.AddPlayer`     | Player enters room |
| `Room.RemovePlayer`  | Player leaves room |
| `Room.AddMob`        | Mob enters or spawns in room |
| `Room.RemoveMob`     | Mob dies or leaves room |
| `Char.Items.Add`     | Item picked up (ground → inventory) |
| `Char.Items.Remove`  | Item removed from inventory (drop, sell, use) |
| `Comm.Channel`       | Chat message received |
| `Core.Ping`          | In response to client `Core.Ping` |
| `Group.Info`         | Also sent immediately on join/leave events |
| `Quest.List`         | Login / quest accepted, updated, completed, abandoned |
| `Quest.Update`       | Quest objective progressed |
| `Quest.Complete`     | Quest completed |
| `Dialogue.Node`      | NPC dialogue presented |
| `Dialogue.End`       | Dialogue conversation ended |
| `Guild.Info`         | Login / guild state change |
| `Guild.Members`      | Guild roster requested |
| `Guild.Chat`         | Guild chat message received |
| `Friends.List`       | Login / `friend list` command |
| `Friends.Online`     | Friend logged in |
| `Friends.Offline`    | Friend logged out |
| `Shop.List`          | Player enters shop / `list` command |
| `Shop.Close`         | Player leaves shop context |
| `Server.Assets`      | Login |

---

## 6. Wire Format Examples

### Telnet session startup

```
Server → Client:  FF FB C9          (IAC WILL GMCP)
Server → Client:  FF FD 18          (IAC DO TTYPE)
Server → Client:  FF FD 1F          (IAC DO NAWS)
Client → Server:  FF FD C9          (IAC DO GMCP — accept)

Client → Server:  FF FA C9
                  43 6F 72 65 2E 53 75 70 70 6F 72 74 73 2E 53 65 74 20
                  5B 22 43 68 61 72 2E 56 69 74 61 6C 73 20 31 22 2C 22 52 6F 6F 6D 2E 49 6E 66 6F 20 31 22 5D
                  FF F0
                  (IAC SB GMCP Core.Supports.Set ["Char.Vitals 1","Room.Info 1"] IAC SE)

Server → Client:  FF FA C9
                  43 68 61 72 2E 56 69 74 61 6C 73 20 7B 22 68 70 22 3A 31 30 30 2C ...
                  FF F0
                  (IAC SB GMCP Char.Vitals {"hp":100,...} IAC SE)
```

### Telnet Core.Ping

```
Client → Server:  FF FA C9 43 6F 72 65 2E 50 69 6E 67 20 7B 7D FF F0
                  (IAC SB GMCP "Core.Ping {}" IAC SE)

Server → Client:  FF FA C9 43 6F 72 65 2E 50 69 6E 67 20 7B 7D FF F0
                  (IAC SB GMCP "Core.Ping {}" IAC SE)
```

### WebSocket login snapshot

After the WebSocket connection is established, the server automatically sends `Core.Supports.Set` on behalf of the client, then flushes the initial state. A client can expect this sequence of frames on connect:

```
← {"gmcp":"Char.StatusVars","data":{"hp":"HP","maxHp":"Max HP","mana":"Mana","maxMana":"Max Mana","level":"Level","xp":"XP"}}
← {"gmcp":"Char.Vitals","data":{"hp":100,"maxHp":100,"mana":80,"maxMana":80,"level":3,"xp":1800,"xpIntoLevel":800,"xpToNextLevel":1000,"gold":0,"inCombat":false}}
← {"gmcp":"Room.Info","data":{"id":"tutorial_glade:spawn","title":"Mossy Steps","description":"...","zone":"tutorial_glade","exits":{"north":"tutorial_glade:clearing"}}}
← {"gmcp":"Char.Name","data":{"name":"Ambuoroko","race":"ELF","class":"MAGE","level":3}}
← {"gmcp":"Char.Items.List","data":{"inventory":[],"equipment":{"HEAD":null,"NECK":null,"CHEST":null,"HANDS":null,"WAIST":null,"LEGS":null,"FEET":null,"MAIN_HAND":null,"OFF_HAND":null}}}
← {"gmcp":"Room.Players","data":[]}
← {"gmcp":"Room.Mobs","data":[{"id":"tutorial_glade:wolf#1","name":"Grey Wolf","hp":40,"maxHp":40}]}
← {"gmcp":"Room.Items","data":[]}
← {"gmcp":"Char.Skills","data":[...]}
← {"gmcp":"Char.StatusEffects","data":[]}
← {"gmcp":"Char.Achievements","data":{"completed":[],"inProgress":[]}}
```

### WebSocket combat tick

```
← {"gmcp":"Room.UpdateMob","data":{"id":"tutorial_glade:wolf#1","name":"Grey Wolf","hp":22,"maxHp":40}}
← {"gmcp":"Char.Vitals","data":{"hp":72,"maxHp":100,"mana":58,"maxMana":80,"level":3,"xp":1800,"xpIntoLevel":800,"xpToNextLevel":1000,"gold":0,"inCombat":true}}
```

---

## 7. Planned Future Packages

The following packages are on the roadmap. None are currently sent by the server.

---

### `World.Map` *(planned)*

Zone topology as a graph of rooms and connections. Enables clients to render a proper server-authoritative map rather than building one heuristically from observed movement.

```json
{
  "zone": "tutorial_glade",
  "rooms": [
    {
      "id":   "tutorial_glade:spawn",
      "title": "Mossy Steps",
      "x": 0, "y": 0,
      "exits": { "north": "tutorial_glade:clearing" }
    }
  ]
}
```

*Trigger: login or zone change (one packet per zone entered).*

---

### `Admin.Status` *(planned)*

Engine and session health telemetry, gated to staff-level sessions. Intended for the admin dashboard.

```json
{
  "onlinePlayers": 12,
  "uptimeMs": 86400000,
  "tickDurationMs": 4,
  "pendingInbound": 0
}
```

*Trigger: periodic (configurable interval).*

---

### `Char.Title` *(planned)*

Notifies the client when the character's active display title changes (set via the `title` command or earned via achievements).

```json
{ "title": "the Blooded" }
```

*Trigger: title set, cleared, or unlocked via achievement.*

---

## Appendix: Package Summary

| Package               | Direction | Notes |
|-----------------------|-----------|-------|
| `Core.Hello`          | ← C→S     | Optional greeting |
| `Core.Supports.Set`   | ← C→S     | Declare subscriptions |
| `Core.Supports.Remove`| ← C→S     | Remove subscriptions |
| `Core.Ping`           | ↔ Both    | Keep-alive (server echoes) |
| `Char.Name`           | → S→C     | Login only |
| `Char.Vitals`         | → S→C     | Batched per tick |
| `Char.StatusVars`     | → S→C     | Login only (static labels) |
| `Char.Combat`         | → S→C     | Batched per tick |
| `Char.Combat.Event`   | → S→C     | Immediate (per combat event) |
| `Char.Items.List`     | → S→C     | Full snapshot |
| `Char.Items.Add`      | → S→C     | Immediate |
| `Char.Items.Remove`   | → S→C     | Immediate |
| `Char.Skills`         | → S→C     | Full snapshot |
| `Char.StatusEffects`  | → S→C     | Batched per tick |
| `Char.Achievements`   | → S→C     | Full snapshot |
| `Char.Stats`          | → S→C     | Batched per tick |
| `Char.Cooldown`       | → S→C     | Immediate |
| `Char.Gain`           | → S→C     | Immediate |
| `Room.Info`           | → S→C     | On login/move/look |
| `Room.Players`        | → S→C     | Full snapshot |
| `Room.AddPlayer`      | → S→C     | Immediate |
| `Room.RemovePlayer`   | → S→C     | Immediate |
| `Room.Mobs`           | → S→C     | Full snapshot |
| `Room.AddMob`         | → S→C     | Immediate |
| `Room.UpdateMob`      | → S→C     | Batched per tick |
| `Room.RemoveMob`      | → S→C     | Immediate |
| `Room.Items`          | → S→C     | Immediate |
| `Room.MobInfo`        | → S→C     | Immediate |
| `Comm.Channel`        | → S→C     | Immediate |
| `Group.Info`          | → S→C     | Batched per tick + immediate on join/leave |
| `Quest.List`          | → S→C     | Full snapshot |
| `Quest.Update`        | → S→C     | Immediate |
| `Quest.Complete`      | → S→C     | Immediate |
| `Dialogue.Node`       | → S→C     | Immediate |
| `Dialogue.End`        | → S→C     | Immediate |
| `Guild.Info`          | → S→C     | On login / guild state change |
| `Guild.Members`       | → S→C     | On request |
| `Guild.Chat`          | → S→C     | Immediate |
| `Friends.List`        | → S→C     | On login / request |
| `Friends.Online`      | → S→C     | Immediate |
| `Friends.Offline`     | → S→C     | Immediate |
| `Shop.List`           | → S→C     | Immediate |
| `Shop.Close`          | → S→C     | Immediate |
| `Server.Assets`       | → S→C     | Login only |
| `World.Map`           | → S→C     | **Planned** |
| `Admin.Status`        | → S→C     | **Planned** (staff only) |
| `Char.Title`          | → S→C     | **Planned** |
