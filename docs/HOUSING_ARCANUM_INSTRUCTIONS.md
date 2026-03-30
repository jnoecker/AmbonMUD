# Player Housing — Arcanum Integration Instructions

**For:** Ambon Arcanum creator tool
**Backend source:** `engine/HousingSystem.kt`, `persistence/HouseRepository.kt`, `config/AppConfig.kt`
**GMCP packages:** `Housing.Info`, `Room.Info` (extended)

---

## Overview

AmbonMUD now supports player housing. Each player can own one house with expandable rooms. Houses are virtual instances — they don't exist on the world map. Players enter via `recall` or innkeeper dialogue, and exit back to where they came from.

The Arcanum needs to support:
1. **Viewing housing data** — which players own houses, what rooms they have
2. **Managing room templates** — the purchasable room types defined in config
3. **Housing GMCP** — the web game client needs a handler for `Housing.Info`

---

## Data Model

### Room Templates (config-defined)

Room templates live in `application.yaml` under `engine.housing.templates`. Each template defines a purchasable room type:

```yaml
engine:
  housing:
    enabled: true
    entryExitDirection: SOUTH  # direction that leads "out" from the entry room
    templates:
      cottage_entry:
        title: "Cottage Entryway"
        description: "A cozy stone-walled entryway..."
        cost: 1000           # gold
        isEntry: true        # exactly one template must be the entry
        safe: true           # combat blocked
      vault:
        title: "Storage Vault"
        description: "A reinforced room..."
        cost: 2000
        maxDroppedItems: 50  # items dropped here persist across sessions
        safe: true
      workshop:
        title: "Crafting Workshop"
        description: "A well-equipped workshop..."
        cost: 1500
        station: forge       # crafting station type
        safe: true
```

**Template properties:**
| Field | Type | Description |
|-------|------|-------------|
| `title` | string | Default room title (player can override) |
| `description` | string | Default room description (player can override) |
| `cost` | long | Gold cost to purchase |
| `isEntry` | boolean | If true, this is the initial room when buying a house. Exactly one template must have this. |
| `image` | string? | Optional room image path |
| `maxDroppedItems` | int | When > 0, items dropped here persist (vault). 0 = transient. |
| `safe` | boolean | When true, combat is blocked in this room |
| `station` | string? | Crafting station type (e.g. "forge", "alchemy_bench") |

### House Record (per-player, persisted)

Each player's house is stored as a `HouseRecord`:

```json
{
  "ownerId": 42,
  "ownerName": "Gandalf",
  "rooms": [
    {
      "templateId": "cottage_entry",
      "customTitle": "Gandalf's Study",
      "customDescription": "Books line every wall...",
      "exits": { "NORTH": 1 },
      "storedItems": []
    },
    {
      "templateId": "vault",
      "customTitle": null,
      "customDescription": null,
      "exits": { "SOUTH": 0 },
      "storedItems": [
        { "id": "iron_sword", "item": { "keyword": "sword", "displayName": "an iron sword" } }
      ]
    }
  ],
  "createdAtEpochMs": 1711756800000
}
```

**Key fields:**
- `rooms[0]` is always the entry room
- `exits` maps `Direction` → room index (within the same house)
- `storedItems` only persists for rooms where `maxDroppedItems > 0` (vault rooms)
- `customTitle` / `customDescription` are player overrides (null = use template default)

### Room IDs

House rooms are injected into the world at runtime with IDs in the format:
```
house_<playerName>:room_<index>
```
Examples: `house_Gandalf:room_0`, `house_Gandalf:room_1`

The entry room's exit direction (configured as `entryExitDirection`, default `SOUTH`) points to a sentinel: `house_<playerName>:exit` — resolved dynamically per-visitor to their origin room.

---

## Player Commands

| Command | Context | Effect |
|---------|---------|--------|
| `house` / `house status` | Anywhere | Show house summary (rooms, templates) |
| `house list` | At broker NPC | Browse available room templates + prices |
| `house buy` | At broker NPC | Purchase initial house (entry room) |
| `house expand <template> <dir>` | In own house | Buy and attach a new room in the given direction |
| `house describe title <text>` | In own house | Set custom room title (1-60 chars) |
| `house describe desc <text>` | In own house | Set custom room description (1-500 chars) |
| `house invite <player>` | In own house | Teleport an online player to your entry room |
| `house kick <player>` | In own house | Boot a visitor back to their origin |
| `house guests` | In own house | List current visitors |
| `recall` | Anywhere | If player has a house, teleports home. Exit leads to recall inn. |

**Aliases:** `home` is an alias for `house`.

---

## GMCP Packages

### `Housing.Info` (server → client)

Sent on login and after house changes (purchase, expansion, customization).

```json
{
  "hasHouse": true,
  "ownerName": "Gandalf",
  "rooms": [
    { "templateId": "cottage_entry", "title": "Gandalf's Study", "description": "Books line every wall..." },
    { "templateId": "vault", "title": "Storage Vault", "description": "A reinforced room..." }
  ]
}
```

When the player has no house:
```json
{
  "hasHouse": false,
  "ownerName": null,
  "rooms": []
}
```

**Registration:** `"Housing 1"` is included in the WebSocket auto-opt-in list (`Core.Supports.Set`).

### `Room.Info` (extended)

The existing `Room.Info` GMCP payload gains two new fields when the player is in a house room:

```json
{
  "id": "house_Gandalf:room_0",
  "title": "Gandalf's Study",
  "description": "Books line every wall...",
  "zone": "house_Gandalf",
  "exits": { "north": "house_Gandalf:room_1", "south": "house_Gandalf:exit" },
  "housing": true,
  "housingOwner": "Gandalf",
  "station": null,
  "mapX": 0,
  "mapY": 0
}
```

| New Field | Type | Description |
|-----------|------|-------------|
| `housing` | boolean | `true` when this room is inside a player's house |
| `housingOwner` | string? | The owner's player name (null for non-housing rooms) |

These fields are always present but default to `false` / `null` for normal world rooms.

---

## Entry/Exit Mechanics

Understanding this is important for any UI that shows "where am I" or minimap state:

- **Recall → house:** Player's origin is their recall inn. House exit leads back there.
- **NPC dialogue → house:** Player enters via `enter_house` dialogue action. Origin is the inn/broker room. Exit leads back there.
- **Invite → house:** Visitor is teleported in. Origin is where they were standing. Exit leads back there.
- **Owner disconnect:** All visitors are booted back to their origin rooms.
- **Visitor reconnect in house:** Relocated to their recall room (invite was session-only).

---

## Visitor Restrictions

When a player is in a house they don't own:
- **Cannot pick up items** ("You can't take items from someone else's house.")
- **Cannot start combat** in safe rooms ("This room is protected.")
- **Can drop items** (leave gifts)
- **Can look, say, emote, etc.** normally

---

## Minimap Integration

House rooms should be rendered distinctly on the minimap. The `Room.Info` GMCP includes `housing: true` and `housingOwner: "Name"` for house rooms. Suggested treatment:
- Use a special node icon (house/home icon) instead of the standard room dot
- Show owner name as a label
- The `house_<name>:exit` sentinel in exits should render as a door/portal icon
- House rooms have `mapX: 0, mapY: 0` — the minimap should auto-layout based on exit connectivity rather than coordinates

---

## Admin API Gaps

The admin API (`/api/...`) does **not yet expose housing endpoints**. When adding them, the recommended endpoints are:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/housing` | GET | List all houses (summary: owner, room count) |
| `/api/housing/{playerName}` | GET | Full house record for a player |
| `/api/housing/templates` | GET | List configured room templates |

These would need to be added to `AdminHttpServer.kt` and documented in `ADMIN_API_REFERENCE.md`.

---

## Persistence

Houses are stored via `HouseRepository` with two backends:

- **YAML:** One file per house at `<rootDir>/houses/<ownerId>.yaml`
- **Postgres:** `houses` table (Flyway V20), rooms stored as JSON in a `TEXT` column

Both backends are interchangeable. The backend is selected by `ambonmud.persistence.backend` (default: `YAML`).

---

## Housing Broker NPC

The `housingBroker: true` flag on a mob definition marks it as a housing broker. When a broker is in the room, `house list` and `house buy` commands are available. This flag is parsed by `WorldLoader` from zone YAML:

```yaml
mobs:
  housing_broker:
    name: "Aldric the Housing Broker"
    room: marketplace
    housingBroker: true
```

Currently only the ambon_hub innkeeper has an `enter_house` dialogue action. Additional brokers can be added to any zone.

---

## Web Client TODO

The web game client (`web-v3/`) needs these additions:

1. **`Housing.Info` GMCP handler** in `applyGmcpPackage.ts` — store housing state in the game store
2. **Housing panel** (new `PopoutPanel` type) — show house status, room list, and available templates
3. **Minimap** — render `housing: true` rooms with a distinct icon; auto-layout since `mapX/mapY` are 0
4. **Vault indicator** — when in a vault room, show item capacity (e.g. "3/50 items stored")
5. **Non-vault drop warning** — mirror the server warning "Items left here won't survive a restart" in the UI
6. **Room customization UI** — inline editing for room title/description when in own house
