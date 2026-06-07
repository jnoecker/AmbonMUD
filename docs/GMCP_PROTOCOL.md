# AmbonMUD GMCP Protocol Reference

**Date:** 2026-05-11

GMCP (Generic MUD Communication Protocol) is a telnet subnegotiation extension (option 201 / `0xC9`) that lets the server send structured JSON data alongside the plain text MUD stream. AmbonMUD extends this to WebSocket clients via a thin JSON envelope.

This document covers what you need to implement a client that communicates with AmbonMUD: negotiation, subscription, payload shapes, and send triggers.

> **Coverage notice (2026-05).** The transport, subscription, and prefix-matching sections are authoritative. `GmcpEmitter.kt` currently emits **~100 outbound packages**; the per-package payload reference below documents roughly the first ~50 (the stable Char / Room / Comm / Group / Quest / Dialogue / Guild / Friends / Shop / Trainer / Server.Assets / Char.Classes core). The remaining packages — `Auction.*`, `Bank` (`Char.Bank`), `Char.Currencies`, `Char.Equipment.Slots`, `Char.Factions`, `Char.LevelUp`, `Char.Pet`, `Char.Stylist*`, `Crafting.*`, `Duel.*`, `Dungeon.*`, `Group.Invite`, `Guild.Hall`, `Guild.Invite`, `Housing.Info`, `Leaderboard.Data`, `Lottery.Info`, `Mail.*`, `Prestige.Info`, `Puzzle.*`, `Quest.Auto` / `Quest.Available` / `Quest.Daily` / `Quest.Global` / `Quest.Weekly`, `Room.ContainerContents` / `Room.Features` / `Room.LookTarget`, `Server.Broadcast` / `Server.Commands` / `Server.EmotePresets` / `Server.Features` / `Server.Who`, `Session.AuthResult` / `Session.AuthToken` / `Session.ResumeResult` / `Session.ResumeToken`, `Staff.*`, `Trade.State`, `UI.Feedback`, `World.Events` / `World.Time` / `World.Weather`, `Zone.Environment` / `Zone.Instances` / `Zone.Map` — are exhaustively listed in [§ 8 Complete emitted-package inventory](#8-complete-emitted-package-inventory) but their payload shapes are not yet rewritten here. Use the package name to grep `GmcpEmitter.kt` for the canonical payload data class, and `web-v3/src/gmcp/applyGmcpPackage.ts` for the consumer side.

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
8. [Complete emitted-package inventory](#8-complete-emitted-package-inventory)

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
- `Char.Sprites` (if subscribed)
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

Sent on login and whenever the player's sprite, level, or name changes (e.g. level-up, sprite selection).

```json
{
  "name": "Ambuoroko",
  "gender": "neutral",
  "race": "ELF",
  "class": "MAGE",
  "level": 7,
  "sprite": "https://assets.ambon.dev/player_sprites/elf_mage_t1.png",
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
| `sprite` | string  | URL to the player's active sprite image. Reflects the player's selection or auto-resolved best tier match. |
| `isStaff`| boolean | `true` for staff/admin characters |

---

### `Char.Sprites`

Sent on login, level-up, achievement unlock, and after the player changes their sprite selection. Lists all sprites the player has unlocked and can use.

```json
{
  "active": "elf_mage_t1",
  "sprites": [
    {
      "imageId": "elf_mage_t1",
      "displayName": "Novice (Elf Mage)",
      "category": "tier",
      "imagePath": "https://assets.ambon.dev/player_sprites/elf_mage_t1.png"
    },
    {
      "imageId": "t1",
      "displayName": "Novice",
      "category": "tier",
      "imagePath": "https://assets.ambon.dev/player_sprites/t1.png"
    },
    {
      "imageId": "beetle_slayer",
      "displayName": "Beetle Slayer",
      "category": "achievement",
      "imagePath": "https://assets.ambon.dev/player_sprites/beetle_slayer.png"
    }
  ]
}
```

| Field               | Type     | Notes |
|---------------------|----------|-------|
| `active`            | string?  | `imageId` of the currently active sprite, or the auto-resolved best match if no explicit selection. `null` if no sprites available. |
| `sprites`           | array    | All sprites the player can choose from (unlocked + matching their race/class/gender). |
| `sprites[].imageId` | string   | Unique variant identifier. Used in the `sprite set <id>` command. |
| `sprites[].displayName` | string | Human-readable label for this variant. |
| `sprites[].category` | string  | One of `tier`, `achievement`, `staff`. |
| `sprites[].imagePath` | string | Full URL to the sprite image. |

**Sprite categories:**
- **tier** — Unlocked by reaching a level threshold (1, 10, 20, 30, 40, 50). One variant per race/class combo.
- **achievement** — Unlocked by earning a specific achievement. May have race/class-specific variants.
- **staff** — Available only to staff members. One variant per race.

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
      "id": "thornhaven_city:short_sword#3",
      "name": "Short Sword",
      "slot": "MAIN_HAND",
      "damage": 8,
      "armor": 0
    },
    {
      "id": "thornhaven_city:health_potion#7",
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
  "id": "thornhaven_city:iron_shield#12",
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
  "id": "thornhaven_city:health_potion#7",
  "name": "Health Potion"
}
```

| Field  | Type   | Notes |
|--------|--------|-------|
| `id`   | string | Instance ID of the removed item |
| `name` | string | Display name (for UI acknowledgment) |

---

### `Char.Skills`

Full ability list. Sent on login, when a new ability is learned (via trainer), and when a cooldown starts or expires. Only contains abilities the player has explicitly learned — abilities must be unlocked at a class trainer by spending skill points.

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
| `manaCost`             | int          | Absolute mana consumed on cast for *this* player at their current level and class. Server-computed from the ability's authored `manaCostPct` against the player's level-derived base mana pool — see `docs/DEVELOPER_GUIDE.md` § Abilities. |
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
  "id": "thornhaven_city:market_square",
  "title": "Sunlit Clearing",
  "description": "A wide meadow bathed in afternoon light. Wildflowers dot the tall grass.",
  "zone": "thornhaven_city",
  "exits": {
    "north": "thornhaven_city:main_street",
    "east":  "thornhaven_city:east_gate"
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
    "id": "thornhaven_city:guard#2",
    "name": "City Guard",
    "description": "A watchful guard in polished chainmail.",
    "hp": 28,
    "maxHp": 40,
    "image": "/images/mobs/guard.png"
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
  "id": "thornhaven_city:guard#2",
  "name": "City Guard",
  "description": "A watchful guard in polished chainmail.",
  "hp": 40,
  "maxHp": 40,
  "image": "/images/mobs/guard.png"
}
```

---

### `Room.UpdateMob`

Sent once per tick to all players in a room when a mob's HP changes (combat damage, regen, etc.). Same field set as `Room.Mobs` entries.

```json
{
  "id": "thornhaven_city:guard#2",
  "name": "City Guard",
  "description": "A watchful guard in polished chainmail.",
  "hp": 12,
  "maxHp": 40,
  "image": "/images/mobs/guard.png"
}
```

---

### `Room.RemoveMob`

Sent immediately to all players in the room when a mob dies or wanders out.

```json
{ "id": "thornhaven_city:guard#2" }
```

---

### `Room.Items`

Full snapshot of items on the room floor. Sent after an item is dropped, picked up, or placed by a mob death.

```json
[
  {
    "id": "thornhaven_city:iron_key#5",
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
  "targetId": "thornhaven_city:guard#2",
  "targetName": "City Guard",
  "targetHp": 22,
  "targetMaxHp": 40,
  "targetImage": "/images/mobs/guard.png"
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
  "targetName": "City Guard",
  "targetId": "thornhaven_city:guard#2",
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
  "source": "City Guard"
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
    "id": "thornhaven_city:guard#1",
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
  { "name": "Thornveil", "online": true, "level": 15, "zone": "thornhaven_city" },
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
      "id": "thornhaven_city:health_potion",
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

### `Trainer.List`

Sent when a player uses `train list` (or just `train`) at a class trainer. Contains the trainer's metadata, skill point balance, and available learnable abilities.

Subscribe with `"Trainer 1"`.

```json
{
  "trainerId": "warrior_trainer",
  "name": "Captain Varek",
  "class": "WARRIOR",
  "image": null,
  "classUnlocked": true,
  "availableSkillPoints": 3,
  "multiclassMinLevel": 10,
  "multiclassGoldCost": 500,
  "multiclassMaxClasses": 3,
  "multiclassUnlockedCount": 1,
  "abilities": [
    {
      "id": "power_strike",
      "name": "Power Strike",
      "description": "A mighty blow that deals extra melee damage.",
      "levelRequired": 1,
      "manaCost": 10,
      "cooldownMs": 5000,
      "targetType": "ENEMY",
      "effectType": "DIRECT_DAMAGE",
      "image": "/images/abilities/power_strike.png"
    }
  ]
}
```

| Field                  | Type               | Notes |
|------------------------|--------------------|-------|
| `trainerId`            | string             | Trainer registry key |
| `name`                 | string             | Trainer display name |
| `class`                | string             | The class this trainer teaches |
| `image`                | string\|null       | Trainer portrait image URL |
| `classUnlocked`        | boolean            | Whether this class is unlocked for the player |
| `availableSkillPoints` | int                | Points the player can spend right now |
| `multiclassMinLevel`   | int                | Minimum level to unlock a new class |
| `multiclassGoldCost`   | long               | Gold cost for **this player's next** unlock — already includes the exponential `goldCostMultiplier` scaling |
| `multiclassMaxClasses` | long               | Hard cap on the player's total unlocked classes (including starter). Long-typed so "unlimited" sentinels written by JS tooling (e.g. `Number.MAX_SAFE_INTEGER`) round-trip cleanly |
| `multiclassUnlockedCount` | int             | Player's current unlocked-class count (compare to `multiclassMaxClasses` to detect "at limit") |
| `abilities[]`          | array              | Abilities available to learn at this trainer |
| `abilities[].id`       | string             | Ability ID (for `train learn <id>`) |
| `abilities[].levelRequired` | int           | Minimum player level to learn |
| `abilities[].manaCost` | int                | Absolute mana cost for *this player* at their current level/class. Server-computed from the authored `manaCostPct` — see `docs/DEVELOPER_GUIDE.md` § Abilities. |
| `abilities[].cooldownMs` | long             | Full cooldown duration in milliseconds |
| `abilities[].targetType` | string           | `SELF`, `ENEMY`, `ALLY`, `ALL_ENEMIES`, `ALL_ALLIES` |
| `abilities[].effectType` | string           | `DIRECT_DAMAGE`, `AREA_DAMAGE`, `DIRECT_HEAL`, `BUFF`, `DEBUFF` |
| `abilities[].image`    | string\|null       | Ability sprite image URL |

When `classUnlocked` is `false`, `abilities` is an empty array. The client should show an unlock prompt instead.

---

### `Char.Classes`

Sent on login and whenever the player unlocks a new class via `train unlock`.

Subscribe with `"Char.Classes 1"`.

```json
{
  "originalClass": "WARRIOR",
  "unlockedClasses": ["WARRIOR", "MAGE"]
}
```

| Field             | Type            | Notes |
|-------------------|-----------------|-------|
| `originalClass`   | string          | The class the character was created with |
| `unlockedClasses` | array of string | All classes the player has unlocked (always includes `originalClass`) |

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
| `Char.Sprites`       | Login / level-up / achievement unlock / sprite set/clear |
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
| `Trainer.List`       | Player uses `train` / `train list` at a trainer |
| `Char.Classes`       | Login / player unlocks a new class |
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
← {"gmcp":"Room.Info","data":{"id":"thornhaven_city:market_square","title":"Mossy Steps","description":"...","zone":"thornhaven_city","exits":{"north":"thornhaven_city:main_street"}}}
← {"gmcp":"Char.Name","data":{"name":"Ambuoroko","race":"ELF","class":"MAGE","level":3}}
← {"gmcp":"Char.Items.List","data":{"inventory":[],"equipment":{"HEAD":null,"NECK":null,"CHEST":null,"HANDS":null,"WAIST":null,"LEGS":null,"FEET":null,"MAIN_HAND":null,"OFF_HAND":null}}}
← {"gmcp":"Room.Players","data":[]}
← {"gmcp":"Room.Mobs","data":[{"id":"thornhaven_city:guard#1","name":"City Guard","hp":40,"maxHp":40}]}
← {"gmcp":"Room.Items","data":[]}
← {"gmcp":"Char.Skills","data":[...]}
← {"gmcp":"Char.StatusEffects","data":[]}
← {"gmcp":"Char.Achievements","data":{"completed":[],"inProgress":[]}}
← {"gmcp":"Char.Sprites","data":{"active":"elf_mage_t1","sprites":[{"imageId":"elf_mage_t1","displayName":"Novice (Elf Mage)","category":"tier","imagePath":"https://assets.ambon.dev/player_sprites/elf_mage_t1.png"}]}}
```

### WebSocket combat tick

```
← {"gmcp":"Room.UpdateMob","data":{"id":"thornhaven_city:guard#1","name":"City Guard","hp":22,"maxHp":40}}
← {"gmcp":"Char.Vitals","data":{"hp":72,"maxHp":100,"mana":58,"maxMana":80,"level":3,"xp":1800,"xpIntoLevel":800,"xpToNextLevel":1000,"gold":0,"inCombat":true}}
```

---

## 7. Planned Future Packages

> The previously-listed `World.Map` was implemented as `Zone.Map` and is now part of the live package set — see [§ 8](#8-complete-emitted-package-inventory). `Admin.Status` and `Char.Title` were never built; they are speculative and may or may not happen. Treat them as ideas rather than commitments.

### `Admin.Status` *(speculative)*

Engine and session health telemetry, gated to staff-level sessions. Intended for the admin dashboard.

```json
{
  "onlinePlayers": 12,
  "uptimeMs": 86400000,
  "tickDurationMs": 4,
  "pendingInbound": 0
}
```

### `Char.Title` *(speculative)*

Notifies the client when the character's active display title changes.

```json
{ "title": "the Blooded" }
```

---

## 8. Complete emitted-package inventory

Every outbound package name currently emitted by `GmcpEmitter.kt`. Packages marked with **[detailed above]** have a payload spec earlier in this document; the rest are catalogued here with a short description — grep `GmcpEmitter.kt` for the package name to find the canonical Kotlin payload class.

### Inbound (client → server)

| Package | Notes |
|---------|-------|
| `Core.Hello` | Optional greeting — **[detailed above]** |
| `Core.Supports.Set` | Declare subscriptions — **[detailed above]** |
| `Core.Supports.Remove` | Remove subscriptions — **[detailed above]** |
| `Core.Ping` | Keep-alive — **[detailed above]** |

### Outbound (server → client)

**Core / Session**
| Package | Notes |
|---------|-------|
| `Core.Ping` | Echoed ping — **[detailed above]** |
| `Session.AuthResult` | Login outcome (success/failure + reason) |
| `Session.AuthToken` | Remember-me token issued on login |
| `Session.ResumeResult` | Token-resume outcome |
| `Session.ResumeToken` | Refreshed resume token |

**Char (the player)**
| Package | Notes |
|---------|-------|
| `Char.Name` | Login only — **[detailed above]** |
| `Char.Vitals` | Batched per tick — **[detailed above]** |
| `Char.StatusVars` | Login only — **[detailed above]** |
| `Char.Stats` | Batched per tick — **[detailed above]** |
| `Char.Skills` | Full snapshot — **[detailed above]** |
| `Char.StatusEffects` | Batched per tick — **[detailed above]** |
| `Char.Achievements` | Full snapshot — **[detailed above]** |
| `Char.Sprites` | Full snapshot — **[detailed above]** |
| `Char.Cooldown` | Immediate — **[detailed above]** |
| `Char.Gain` | Immediate — **[detailed above]** |
| `Char.Combat` | Batched per tick — **[detailed above]** |
| `Char.Combat.Event` | Immediate per combat event — **[detailed above]** |
| `Char.Classes` | Login / class unlock — **[detailed above]** |
| `Char.LevelUp` | Immediate on level gain |
| `Char.Items.List` | Full inventory — **[detailed above]** |
| `Char.Items.Add` | Inventory add — **[detailed above]** |
| `Char.Items.Remove` | Inventory remove — **[detailed above]** |
| `Char.Equipment.Slots` | Equipped-slot snapshot |
| `Char.Bank` | Bank vault contents |
| `Char.Currencies` | Secondary currency balances |
| `Char.Factions` | Reputation standings per faction |
| `Char.Pet` | Active pet stats and status |
| `Char.Stylist` | Stylist NPC session state |
| `Char.Stylist.Close` | Stylist session end |

**Room**
| Package | Notes |
|---------|-------|
| `Room.Info` | On login/move/look — **[detailed above]** |
| `Room.Players` | Full snapshot — **[detailed above]** |
| `Room.AddPlayer` | Immediate — **[detailed above]** |
| `Room.RemovePlayer` | Immediate — **[detailed above]** |
| `Room.Mobs` | Full snapshot — **[detailed above]** |
| `Room.AddMob` | Immediate — **[detailed above]** |
| `Room.UpdateMob` | Batched per tick — **[detailed above]** |
| `Room.RemoveMob` | Immediate — **[detailed above]** |
| `Room.Items` | Immediate — **[detailed above]** |
| `Room.MobInfo` | Immediate — **[detailed above]** |
| `Room.ContainerContents` | Contents of an open container |
| `Room.Features` | Doors / levers / signs / interactables |
| `Room.LookTarget` | Result of `look <target>` |

**Comm / Social**
| Package | Notes |
|---------|-------|
| `Comm.Channel` | Immediate — **[detailed above]** |
| `Group.Info` | Batched + on join/leave — **[detailed above]** |
| `Group.Invite` | Incoming group invitation |
| `Friends.List` | Full snapshot — **[detailed above]** |
| `Friends.Online` | Immediate — **[detailed above]** |
| `Friends.Offline` | Immediate — **[detailed above]** |
| `Mail.List` | Inbox summary |
| `Mail.Message` | Full message body |
| `Mail.Notification` | New-mail notification |

**Guild / Housing**
| Package | Notes |
|---------|-------|
| `Guild.Info` | On login / state change — **[detailed above]** |
| `Guild.Members` | On request — **[detailed above]** |
| `Guild.Chat` | Immediate — **[detailed above]** |
| `Guild.Invite` | Incoming guild invitation |
| `Guild.Hall` | Guild hall info (rooms, upgrades) |
| `Housing.Info` | Personal-housing state |

**Quests**
| Package | Notes |
|---------|-------|
| `Quest.List` | Full snapshot — **[detailed above]** |
| `Quest.Update` | Immediate — **[detailed above]** |
| `Quest.Complete` | Immediate — **[detailed above]** |
| `Quest.Available` | NPC-offered quests |
| `Quest.Auto` | Session-only auto-bounty quests |
| `Quest.Daily` | Daily-quest rotation |
| `Quest.Weekly` | Weekly-quest rotation |
| `Quest.Global` | Server-wide cooperative quest |

**Dialogue / Trainers / Shops**
| Package | Notes |
|---------|-------|
| `Dialogue.Node` | Current dialogue node — **[detailed above]** |
| `Dialogue.End` | Dialogue closed — **[detailed above]** |
| `Trainer.List` | Trainable abilities — **[detailed above]** |
| `Shop.List` | Shop inventory — **[detailed above]** |
| `Shop.Close` | Shop session end — **[detailed above]** |

**Crafting / Enchant**
| Package | Notes |
|---------|-------|
| `Crafting.Recipes` | Known recipes |
| `Crafting.Skills` | Skill levels per profession |
| `Crafting.Nodes` | Visible gathering nodes |
| `Crafting.Cooldown` | Active gather/craft cooldowns |
| `Crafting.Result` | Outcome of a craft action |

**Economy / PvP / Misc gameplay**
| Package | Notes |
|---------|-------|
| `Auction.List` | Active auction listings |
| `Trade.State` | Bilateral-trade UI state |
| `Duel.Challenge` | Incoming duel challenge |
| `Duel.State` | Active duel state |
| `Dungeon.Catalog` | Available dungeon templates |
| `Dungeon.Info` | Active dungeon instance state |
| `Prestige.Info` | Prestige rank / perks / cost |
| `Lottery.Info` | Active lottery state + ticket/dice settings |
| `Lottery.Gamble` | Resolved tavern dice roll (outcome, bet, payout, roll) |
| `Leaderboard.Data` | Leaderboard rows |
| `Puzzle.List` | Available puzzles |
| `Puzzle.Close` | Puzzle session end |

**World / Zone**
| Package | Notes |
|---------|-------|
| `World.Time` | 24-hour world clock + period |
| `World.Weather` | Per-zone weather state |
| `World.Events` | Active seasonal events |
| `Zone.Map` | Zone topology graph (replaces the formerly-planned `World.Map`) |
| `Zone.Environment` | Lighting / ambience tuning |
| `Zone.Instances` | Available instances of an instanced zone |

**Server / Staff / UI**
| Package | Notes |
|---------|-------|
| `Server.Assets` | Login only — **[detailed above]** |
| `Server.Features` | Server feature flags |
| `Server.Commands` | Command metadata for client help/completion |
| `Server.EmotePresets` | Built-in emote list |
| `Server.Who` | `who` results |
| `Server.Broadcast` | Server-wide announcement |
| `Staff.MobTemplates` | Staff mob-spawn catalog |
| `Staff.WorldInfo` | Staff world inspector data |
| `Staff.Possession` | Active staff-possession state |
| `UI.Feedback` | Client-side UI feedback signals |
