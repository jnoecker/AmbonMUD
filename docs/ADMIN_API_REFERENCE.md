# AmbonMUD Admin Server — API Reference

**For:** Ambon Arcanum integration
**Source:** `src/main/kotlin/dev/ambon/admin/AdminHttpServer.kt`
**Last Updated:** 2026-03-26

---

## Overview

The admin server is an embedded Ktor/Netty HTTP server that runs alongside the game engine. It provides both an HTML dashboard (for browser use) and a JSON API (for programmatic use). The Arcanum should use the **JSON API endpoints** exclusively.

### Connection Details

| Setting | Default | Config Key |
|---------|---------|------------|
| Port | `9091` | `ambonmud.admin.port` |
| Enabled | `false` | `ambonmud.admin.enabled` |
| Auth token | (blank — must be set) | `ambonmud.admin.token` |
| Grafana link | (optional) | `ambonmud.admin.grafanaUrl` |
| CORS origins | `[]` (disabled) | `ambonmud.admin.corsOrigins` |

### Authentication

Every request requires **HTTP Basic Auth**. The username is ignored; the password must match the configured `token`. CORS preflight (`OPTIONS`) requests skip authentication.

```
Authorization: Basic base64(anything:<token>)
```

Example with curl:
```bash
curl -u ":mytoken" http://localhost:9091/api/overview
```

### CORS

Cross-origin requests are supported when `corsOrigins` is configured. Set to specific origins or `["*"]` for development:

```yaml
ambonmud:
  admin:
    corsOrigins:
      - "http://localhost:3000"
      - "https://arcanum.ambon.dev"
```

The server responds with `Access-Control-Allow-Origin`, `Access-Control-Allow-Headers` (Authorization, Content-Type), and `Access-Control-Allow-Methods` (GET, POST, OPTIONS) headers.

### Response Format

All `/api/*` endpoints return `application/json`. Errors return appropriate HTTP status codes (400, 404, 500, 501) with a JSON body containing an `error` or `message` field.

---

## JSON API Endpoints

### Server Status

#### `GET /api/health`

Health check with uptime.

```json
{
  "status": "ok",
  "uptimeMs": 3600000,
  "playersOnline": 3
}
```

#### `GET /api/overview`

Server-wide summary stats.

```json
{
  "playersOnline": 3,
  "mobsAlive": 47,
  "zonesLoaded": 13,
  "roomsTotal": 218,
  "grafanaUrl": "https://grafana.example.com/d/ambonmud",
  "metricsUrl": "http://localhost:9090/metrics"
}
```

---

### Players

#### `GET /api/players`

List all **online** players (summary view).

```json
[
  {
    "name": "Lexa",
    "level": 7,
    "playerClass": "MAGE",
    "race": "ELF",
    "room": "ambon_hub:town_square",
    "isOnline": true,
    "isStaff": true,
    "hp": 85,
    "maxHp": 100
  }
]
```

#### `GET /api/players/search?q={name}`

Search for a player by name (online or offline). Case-insensitive exact match.

**Query params:** `q` (required) — player name

**Response:** Same as `GET /api/players/{name}` (detail view).

**Errors:** `400` if `q` is missing/blank, `404` if player not found.

#### `GET /api/players/{name}`

Detailed view of a single player. Checks online players first, falls back to persistence for offline players.

```json
{
  "name": "Lexa",
  "level": 7,
  "playerClass": "MAGE",
  "race": "ELF",
  "room": "ambon_hub:town_square",
  "isOnline": true,
  "isStaff": true,
  "hp": 85,
  "maxHp": 100,
  "mana": 120,
  "maxMana": 150,
  "xpTotal": 4500,
  "gold": 230,
  "stats": { "strength": 8, "intelligence": 16, "wisdom": 14 },
  "activeTitle": "Archmage",
  "activeQuestIds": ["tutorial_glade:first_quest"],
  "completedQuestIds": ["tutorial_glade:intro"],
  "achievementIds": ["first_kill", "explorer_10"]
}
```

**Note:** When offline, `hp` and `maxHp` are `0` and `isOnline` is `false`.

#### `POST /api/players/{name}/staff`

Toggle a player's staff flag. Returns JSON with the new value.

```json
{ "name": "Lexa", "isStaff": true }
```

**Errors:** `404` if player not found.

---

### World

#### `GET /api/world/zones`

List all loaded zones with activity counts.

```json
[
  {
    "name": "ambon_hub",
    "roomCount": 12,
    "playersOnline": 2,
    "mobsAlive": 5
  }
]
```

#### `GET /api/world/zones/{zone}`

All rooms in a zone with exits and current occupants.

```json
{
  "name": "ambon_hub",
  "rooms": [
    {
      "id": "ambon_hub:town_square",
      "title": "Town Square",
      "exits": ["north", "south", "east"],
      "players": ["Lexa"],
      "mobs": ["Town Guard"]
    }
  ]
}
```

**Errors:** `404` if zone not found.

#### `GET /api/world/zones/{zone}/rooms/{room}`

Full detail for a single room, including description, media, map coordinates, and live occupants.

**Path params:** `zone` + `room` — combined as `zone:room` for RoomId lookup.

```json
{
  "id": "ambon_hub:town_square",
  "title": "Town Square",
  "description": "A bustling square at the heart of town...",
  "exits": [
    { "direction": "north", "target": "ambon_hub:market" },
    { "direction": "east", "target": "ambon_hub:tavern" }
  ],
  "players": ["Lexa"],
  "mobs": [
    {
      "id": "ambon_hub:guard_1",
      "name": "Town Guard",
      "hp": 50,
      "maxHp": 50,
      "templateKey": "town_guard"
    }
  ],
  "features": ["Door(north, locked=false)"],
  "station": null,
  "image": "/images/town_square.png",
  "video": null,
  "music": "/audio/town_theme.mp3",
  "ambient": null,
  "mapX": 5,
  "mapY": 3
}
```

**Errors:** `404` if room not found.

---

### Mobs

#### `GET /api/mobs`

List all active mob instances. Optionally filter by zone.

**Query params:** `zone` (optional) — filter to mobs in this zone.

```json
[
  {
    "id": "ambon_hub:guard_1",
    "name": "Town Guard",
    "roomId": "ambon_hub:town_square",
    "hp": 50,
    "maxHp": 50,
    "templateKey": "town_guard",
    "aggressive": false,
    "xpReward": 30,
    "armor": 5,
    "image": "/images/mobs/guard.png",
    "questIds": [],
    "spawnRoomId": "ambon_hub:town_square"
  }
]
```

#### `GET /api/mobs/{id}`

Detail for a single mob instance.

**Errors:** `404` if mob not found (may have been killed/despawned).

---

### Abilities

#### `GET /api/abilities`

All loaded ability/spell definitions.

```json
[
  {
    "id": "fireball",
    "displayName": "Fireball",
    "description": "Hurls a ball of flame...",
    "manaCost": 25,
    "cooldownMs": 6000,
    "levelRequired": 5,
    "targetType": "enemy",
    "requiredClass": "MAGE",
    "image": "/images/abilities/fireball.png",
    "effectType": "DirectDamage"
  }
]
```

#### `GET /api/abilities/{id}`

Single ability definition. **Errors:** `404` if not found.

---

### Status Effects

#### `GET /api/effects`

All loaded status effect definitions.

```json
[
  {
    "id": "poison",
    "displayName": "Poison",
    "effectType": "DOT",
    "durationMs": 15000,
    "tickIntervalMs": 3000,
    "tickMinValue": 5,
    "tickMaxValue": 10,
    "shieldAmount": 0,
    "statMods": {},
    "stackBehavior": "refresh",
    "maxStacks": 1
  }
]
```

#### `GET /api/effects/{id}`

Single effect definition. **Errors:** `404` if not found.

---

### Quests

#### `GET /api/quests`

All loaded quest definitions.

```json
[
  {
    "id": "tutorial_glade:first_quest",
    "name": "A Hero's Beginning",
    "description": "Prove yourself by defeating the training dummies.",
    "giverMobId": "tutorial_glade:mentor",
    "completionType": "auto",
    "objectives": [
      {
        "type": "kill",
        "targetId": "training_dummy",
        "count": 3,
        "description": "Defeat 3 training dummies"
      }
    ],
    "rewards": { "xp": 100, "gold": 50 }
  }
]
```

#### `GET /api/quests/{id}`

Single quest definition. **Errors:** `404` if not found.

---

### Achievements

#### `GET /api/achievements`

All loaded achievement definitions.

```json
[
  {
    "id": "first_kill",
    "displayName": "First Blood",
    "description": "Defeat your first enemy.",
    "category": "combat",
    "hidden": false,
    "criteria": [
      {
        "type": "kill",
        "targetId": "",
        "count": 1,
        "description": "Kill any enemy"
      }
    ],
    "rewards": { "xp": 50, "gold": 10, "title": "Blooded" }
  }
]
```

#### `GET /api/achievements/{id}`

Single achievement definition. **Errors:** `404` if not found.

---

### Shops

#### `GET /api/shops`

All loaded shop definitions with their item inventories.

```json
[
  {
    "id": "general_store",
    "name": "General Store",
    "roomId": "ambon_hub:market",
    "items": [
      {
        "id": "health_potion",
        "displayName": "Health Potion",
        "basePrice": 25,
        "slot": null
      },
      {
        "id": "iron_sword",
        "displayName": "Iron Sword",
        "basePrice": 100,
        "slot": "weapon"
      }
    ]
  }
]
```

---

### Items

#### `GET /api/items`

All item templates (from world YAML definitions).

```json
[
  {
    "id": "iron_sword",
    "displayName": "Iron Sword",
    "description": "A sturdy iron blade.",
    "slot": "weapon",
    "damage": 8,
    "armor": 0,
    "stats": {},
    "consumable": false,
    "basePrice": 100,
    "image": "/images/items/iron_sword.png",
    "spawnRoom": "ambon_hub:armory"
  }
]
```

---

### Actions

#### `POST /api/reload`

Hot-reload game data from disk without restarting.

**Query params:** `target` (optional) — `world`, `abilities`, `effects`, or `all` (default).

```json
{ "status": "ok", "summary": "Hot reload complete. Zones: 13. Abilities: 24. Status effects: 18." }
```

**Errors:** `501` if hot reload not configured, `400` for invalid target, `500` on failure.

**What reloads:**
- `world` — rooms, mob spawns, item templates, shops, dialogue, quests, gathering, crafting
- `abilities` — spell definitions
- `effects` — status effect definitions
- `all` — everything above

**What stays:** player sessions, player state, active mobs/combat, engine tick loop.

#### `POST /api/broadcast`

Send a text message to all online players.

**Request body:**
```json
{ "message": "[ADMIN] World update in 30 seconds..." }
```

**Response:**
```json
{ "status": "ok", "recipients": 3 }
```

**Errors:** `501` if not configured, `400` if message is missing/blank.

---

## HTML Dashboard Endpoints

These serve server-rendered HTML pages. Listed for completeness — the Arcanum should generally prefer the JSON API.

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/` | Overview dashboard with stat cards, online players table, zone activity |
| `GET` | `/players` | Player list with search (`?q=`), filters (`?online=1`, `?staff=1`), sort (`?sort=name\|level\|class`) |
| `GET` | `/players/{name}` | Player detail page with stats, quests, achievements, staff toggle |
| `POST` | `/players/{name}/staff` | Toggle staff flag (form action, returns 302 redirect) |
| `GET` | `/world` | Zone list with filter (`?q=`) |
| `GET` | `/world/{zone}` | Zone detail — all rooms with exits, players, mobs |

---

## Endpoint Summary

| Method | Path | Category | Description |
|--------|------|----------|-------------|
| `GET` | `/api/health` | Status | Health check with uptime |
| `GET` | `/api/overview` | Status | Server stats summary |
| `GET` | `/api/players` | Players | Online players list |
| `GET` | `/api/players/search?q=` | Players | Search online + offline |
| `GET` | `/api/players/{name}` | Players | Player detail |
| `POST` | `/api/players/{name}/staff` | Players | Toggle staff (JSON) |
| `GET` | `/api/world/zones` | World | Zone list |
| `GET` | `/api/world/zones/{zone}` | World | Zone rooms |
| `GET` | `/api/world/zones/{zone}/rooms/{room}` | World | Room detail |
| `GET` | `/api/mobs` | Mobs | All mobs (`?zone=` filter) |
| `GET` | `/api/mobs/{id}` | Mobs | Mob detail |
| `GET` | `/api/abilities` | Content | Ability definitions |
| `GET` | `/api/abilities/{id}` | Content | Ability detail |
| `GET` | `/api/effects` | Content | Status effect definitions |
| `GET` | `/api/effects/{id}` | Content | Effect detail |
| `GET` | `/api/quests` | Content | Quest definitions |
| `GET` | `/api/quests/{id}` | Content | Quest detail |
| `GET` | `/api/achievements` | Content | Achievement definitions |
| `GET` | `/api/achievements/{id}` | Content | Achievement detail |
| `GET` | `/api/shops` | Content | Shop definitions + items |
| `GET` | `/api/items` | Content | Item templates |
| `GET` | `/api/housing/templates` | Housing | Room template definitions |
| `GET` | `/api/housing` | Housing | Houses owned by online players |
| `GET` | `/api/housing/{playerName}` | Housing | Full house record |
| `POST` | `/api/reload` | Actions | Hot reload (`?target=`) |
| `POST` | `/api/broadcast` | Actions | Send message to all players |

---

## Housing

### `GET /api/housing/templates`

Returns the configured room templates that players can purchase.

```json
[
  {
    "id": "cottage_entry",
    "title": "Cottage Entryway",
    "description": "A cozy stone-walled entryway...",
    "cost": 1000,
    "isEntry": true,
    "maxDroppedItems": 0,
    "safe": true,
    "station": null,
    "image": null
  }
]
```

### `GET /api/housing`

Lists houses owned by currently online players.

```json
[
  { "ownerName": "Gandalf", "ownerId": 42, "online": true }
]
```

### `GET /api/housing/{playerName}`

Returns the full house record for a player (case-insensitive lookup). Returns `404` if the player has no house.

```json
{
  "ownerId": 42,
  "ownerName": "Gandalf",
  "createdAtEpochMs": 1711756800000,
  "rooms": [
    {
      "templateId": "cottage_entry",
      "customTitle": "Gandalf's Study",
      "customDescription": "Books line every wall...",
      "exits": { "NORTH": 1 },
      "storedItemCount": 0
    },
    {
      "templateId": "vault",
      "customTitle": null,
      "customDescription": null,
      "exits": { "SOUTH": 0 },
      "storedItemCount": 3
    }
  ]
}
```

---

## Remaining Gaps

These were identified but not addressed in this iteration:

- **Player kick/disconnect** — not exposed via admin API (use in-game `kick` staff command)
- **Config inspection** — runtime config not exposed (security consideration)
- **Metrics summary** — available via Prometheus endpoint on a separate port
- **Reload history/audit log** — no tracking of past reloads
- **Staff toggle audit trail** — no logging of who granted/revoked staff

---

## Recommended Arcanum Integration Sequence

1. **Health check:** `GET /api/health` — verify connectivity and auth
2. **Read-only monitoring:** `/api/overview`, `/api/players`, `/api/world/zones`
3. **Content inspection:** `/api/abilities`, `/api/effects`, `/api/quests`, `/api/achievements`, `/api/shops`, `/api/items` — validate world-building
4. **Housing inspection:** `/api/housing/templates`, `/api/housing`, `/api/housing/{playerName}` — view housing config and player houses
4. **Room inspection:** `/api/world/zones/{zone}/rooms/{room}` — deep-dive into specific rooms
5. **Hot reload:** `POST /api/reload?target=all` — apply updated world files
6. **Broadcast:** `POST /api/broadcast` — announce reload to players
7. **Player management:** `/api/players/search`, `/api/players/{name}/staff` — staff administration
