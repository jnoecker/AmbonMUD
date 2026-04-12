# Arcanum YAML Extensions — Rolling Change Log

> **Purpose:** This document is a rolling change log of YAML and `application.yaml` additions as the server evolves. It exists as a single convenient reference for the Ambon Arcanum creator tool, which needs to support every field world authors can set.
>
> Most individual systems now also have dedicated living documentation:
> - Trainer + multi-classing → [`TRAINER_SYSTEM.md`](./TRAINER_SYSTEM.md)
> - Dungeon templates → [`DUNGEON_TEMPLATE_REFERENCE.md`](./DUNGEON_TEMPLATE_REFERENCE.md)
> - Environment themes + weather → [`ENVIRONMENT_THEMES.md`](./ENVIRONMENT_THEMES.md)
> - Zone file schema → [`WORLD_YAML_SPEC.md`](./WORLD_YAML_SPEC.md)
> - Data-driven mechanics contract → [`DATA_DRIVEN_YAML_CONTRACT.md`](./DATA_DRIVEN_YAML_CONTRACT.md)
> - `application.yaml` key reference → [`CREATOR_CONFIG_REFERENCE.md`](./CREATOR_CONFIG_REFERENCE.md)
>
> Prefer those living docs when updating a single system. Add entries here when shipping a brand-new subsystem so Arcanum authors see it in one place.

---

## Trainer System & Multi-Classing (application.yaml + zone YAML)

### Skill Points Config

```yaml
ambonmud:
  engine:
    skillPoints:
      interval: 2     # 1 skill point earned per N levels (default: 2)
```

### Multi-Classing Config

```yaml
ambonmud:
  engine:
    multiclass:
      minLevel: 10    # minimum level to unlock a second class
      goldCost: 500   # gold cost per additional class unlock
```

### Ability Level Scaling Fields

Each ability in `engine.abilities.definitions` now supports two optional scaling fields:

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `damagePerLevel` | Float | `0.0` | Additional min/max damage per player level |
| `healPerLevel` | Float | `0.0` | Additional healing per player level |

```yaml
ambonmud:
  engine:
    abilities:
      definitions:
        fireball:
          displayName: Fireball
          requiredClass: MAGE
          levelRequired: 5
          manaCost: 22
          cooldownMs: 6000
          targetType: ENEMY
          effect:
            type: DIRECT_DAMAGE
            minDamage: 3
            maxDamage: 8
            damagePerLevel: 1.5    # +1.5 damage per player level
```

### Trainer Zone YAML (trainers: section)

New top-level `trainers:` section in zone YAML files. Each key is a trainer ID matching a mob defined in `mobs:`.

```yaml
trainers:
  warrior_trainer:
    name: "Sergeant Crag"
    class: WARRIOR          # legacy single-class form
    room: training_yard
    image: null             # optional portrait image filename

  # Multi-class trainer — teaches several classes from one room
  combat_instructor:
    name: "Master Grizelda"
    classes: [WARRIOR, ROGUE, RANGER]
    room: training_yard
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `<key>` | string | yes | Trainer ID — must match a mob key in `mobs:` |
| `name` | string | yes | Display name in GMCP output |
| `class` | string | one of | Single class ID (legacy form). All abilities with matching `requiredClass` are shown. |
| `classes` | list\<string\> | one of | Preferred multi-class form. Either `class:` or `classes:` must be set; `classes:` wins if both are present. |
| `room` | string | yes | Room ID where the trainer stands |
| `image` | string? | no | Portrait image filename for web client |

Multi-class behavior:
- `train list` renders a section per class. Each class has its own locked/unlocked indicator and ability list.
- `train unlock <class>` is required to specify which class to unlock at a multi-class trainer (single-class trainers still accept `train unlock` with no arg).
- `train learn <keyword>` searches abilities across every class the trainer teaches that the player has unlocked, so users don't need to know which class an ability belongs to.
- The web `Trainer.List` GMCP payload now contains a `classes: [{ class, classUnlocked, abilities }]` array instead of the old top-level `class`/`classUnlocked`/`abilities` fields. Single-class trainers emit a one-element list — clients can still treat them as single-class by reading `classes[0]`.

### GMCP Packages (new)

| Package | Subscribe via | When Sent |
|---------|---------------|-----------|
| `Trainer.List` | `Trainer 1` | On `train` or `train list` in a trainer room |
| `Char.Classes` | `Char.Classes 1` | On login and whenever a new class is unlocked |

See `docs/TRAINER_SYSTEM.md` for full details and `Trainer.List` payload schema.

---

## Pet / Companion System (application.yaml)

New config section under `ambonmud.engine.pets`:

```yaml
ambonmud:
  engine:
    pets:
      definitions:
        fire_familiar:
          name: "a fire familiar"
          description: "A small elemental of living flame."
          hp: 20
          minDamage: 2
          maxDamage: 5
          armor: 1
          image: fire_familiar.png   # optional
        stone_golem:
          name: "a stone golem"
          description: "A hulking construct of animate stone."
          hp: 50
          minDamage: 4
          maxDamage: 8
          armor: 5
```

### Pet Template Fields

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `name` | String | `"a pet"` | Display name shown in-game |
| `description` | String | `""` | Flavor text |
| `hp` | Int | `20` | Base HP (scaled by owner level: +10% per level) |
| `minDamage` | Int | `1` | Minimum damage roll (scaled by owner level) |
| `maxDamage` | Int | `4` | Maximum damage roll (scaled by owner level) |
| `armor` | Int | `0` | Armor value |
| `image` | String? | `null` | Optional image asset |

### SUMMON_PET Ability Type

Pets are summoned via abilities with `effect.type: SUMMON_PET`:

```yaml
ambonmud:
  engine:
    abilities:
      definitions:
        summon_familiar:
          displayName: Summon Familiar
          manaCost: 15
          cooldownMs: 30000
          levelRequired: 5
          targetType: self
          effect:
            type: SUMMON_PET
            petTemplateKey: fire_familiar   # references pets.definitions key
            durationMs: 0                   # 0 = permanent until dismissed
          description: Summon a magical familiar to aid you in battle.
          requiredClass: MAGE               # optional class restriction
```

### Ability Effect Fields for SUMMON_PET

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `petTemplateKey` | String | `""` | Key referencing a pet template in `pets.definitions` |
| `durationMs` | Long | `0` | Duration in ms (0 = permanent until dismissed) |

### Pet Commands

| Command | Description |
|---------|-------------|
| `pet` / `pet status` | Show active pet stats |
| `pet dismiss` | Dismiss active pet |
| `pet name <name>` | Rename active pet |

### Pet Behavior
- One active pet at a time per player
- Summoning a new pet auto-dismisses the current one
- Pets follow the owner when moving between rooms
- Pets are dismissed on player disconnect (session-only, no persistence)
- Pet stats scale with owner level: +10% per level above 1

---

## Faction System (application.yaml)

New config section under `ambonmud.engine.factions`:

```yaml
ambonmud:
  engine:
    factions:
      defaultReputation: 0        # Starting reputation with all factions
      killPenalty: 5               # Base rep lost with a mob's faction per kill (scaled by level)
      killBonus: 3                 # Base rep gained with enemy factions per kill (scaled by level)
      definitions:
        crimson_guild:
          name: "The Crimson Guild"
          description: "A secretive mercenary organization"
          enemies:
            - shadowclan           # Must reference another defined faction
        shadowclan:
          name: "Shadow Clan"
          description: "Ancient ninja assassins"
          enemies:
            - crimson_guild
      questRewards:
        # questId → { factionId → reputation amount }
        crimson_quest_1:
          crimson_guild: 100
          shadowclan: -50          # Negative = lose rep
        shadowclan_mission_1:
          shadowclan: 75
```

### Faction Definition Fields

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `name` | String | `""` | Display name shown to players |
| `description` | String | `""` | Flavor text |
| `enemies` | List\<String\> | `[]` | IDs of enemy factions (validated against defined factions) |

### Standing Tiers

| Tier | Min Reputation | Trigger |
|------|---------------|---------|
| Hated | < -1000 | |
| Hostile | -1000 | |
| Unfriendly | -500 | |
| Neutral | -100 | Default starting tier |
| Friendly | 100 | |
| Honored | 500 | |
| Revered | 1000 | |

### Mob Faction Affiliation (zone YAML)

New `faction` field on mob definitions:

```yaml
mobs:
  crimson_soldier:
    name: "a Crimson soldier"
    room: barracks
    tier: standard
    level: 5
    faction: crimson_guild      # ← NEW: killing this mob affects faction standings
    drops:
      - itemId: soldier_badge
        chance: 0.3
```

When a player kills a mob with a `faction` field:
- Loses `killPenalty * (1 + mobLevel/10)` rep with the mob's faction
- Gains `killBonus * (1 + mobLevel/10)` rep with each of the faction's declared enemies
- Level proxy is `maxHp/10`, capped at 20 to prevent extreme swings from bosses

---

## Auction House

The auction house is runtime-only — no YAML configuration needed. Listings are persisted to `data/auction_listings.json` automatically.

### Commands (for documentation/help text)

| Command | Description |
|---------|-------------|
| `auction [filter]` | Browse auction listings |
| `auction sell <item> <price>` | Post an item for sale |
| `auction buy <#>` | Purchase a listing |
| `auction cancel <#>` | Cancel your own listing |

### Config (application.yaml)

No dedicated config section. The listing duration defaults to 1 hour (hardcoded in `AuctionSystem`). This could be made configurable if needed.

---

## Player-to-Player Trading

The trading system is runtime-only — no YAML configuration needed.

### Commands (for documentation/help text)

| Command | Description |
|---------|-------------|
| `trade <player>` | Initiate a trade |
| `trade offer <item>` | Add an item to your offer |
| `trade offer <amount> gold` | Set your gold offer |
| `trade accept` | Accept the current offers |
| `trade cancel` | Cancel the trade |
| `trade` / `trade status` | View current trade state |

---

## PvP Dueling

The dueling system is runtime-only — no YAML configuration needed.

### Commands (for documentation/help text)

| Command | Description |
|---------|-------------|
| `duel <player>` | Challenge a player to a duel |
| `duel accept` / `duel yes` | Accept a duel challenge |
| `duel decline` / `duel no` | Decline a duel challenge |
| `flee` | Flee from an active duel |

---

## Crafting Phase 2 (recap)

### Rare Gathering Yields (zone YAML)

New `rareYields` field on gathering nodes:

```yaml
gatheringNodes:
  copper_vein:
    # ... normal fields ...
    rareYields:
      - itemId: rough_gem
        quantity: 1
        dropChance: 0.08     # 8% chance per gather (0.0 to 1.0)
```

### Specialization Config (application.yaml)

New field under `ambonmud.engine.crafting`:

```yaml
ambonmud:
  engine:
    crafting:
      specializationXpBonus: 0.25   # +25% XP for specialized skill
```

### Persistence Fields (Flyway V22)

- `discovered_recipes TEXT` — JSON set of discovered recipe IDs
- `crafting_specialization VARCHAR(64)` — player's chosen specialization

### Persistence Fields (Flyway V23)

- `faction_standings TEXT` — JSON map of faction ID → reputation integer

---

## Item Enchanting System (application.yaml)

New config section under `ambonmud.engine.enchanting`:

```yaml
ambonmud:
  engine:
    enchanting:
      maxEnchantmentsPerItem: 1       # Max enchantments per item (default: 1)
      definitions:
        keen_edge:
          displayName: Keen Edge
          skill: enchanting
          skillRequired: 1
          materials:
            - itemId: arcane_essence
              quantity: 1
          damageBonus: 2
          targetSlots: [hand]          # Empty list = any slot
          xpReward: 30
        might:
          displayName: Might
          skill: enchanting
          skillRequired: 10
          materials:
            - itemId: arcane_essence
              quantity: 2
            - itemId: rough_gem
              quantity: 1
          statBonuses:
            STR: 2
          targetSlots: [hand]
          xpReward: 50
        arcane_infusion:
          displayName: Arcane Infusion
          skill: enchanting
          skillRequired: 20
          materials:
            - itemId: arcane_essence
              quantity: 3
            - itemId: rough_gem
              quantity: 2
          statBonuses:
            INT: 2
            WIS: 1
          damageBonus: 3
          targetSlots: []              # Empty = any equipment slot
          xpReward: 80
```

### Enchantment Definition Fields

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `displayName` | String | `""` | Display name shown in-game (required, validated non-blank) |
| `skill` | String | `"enchanting"` | Crafting skill used for this enchantment |
| `skillRequired` | Int | `1` | Minimum skill level to apply (validated > 0) |
| `materials` | List | `[]` | Materials consumed (validated non-empty) |
| `statBonuses` | Map\<String, Int\> | `{}` | Stat bonuses added to item (e.g., `STR: 2`) |
| `damageBonus` | Int | `0` | Extra damage added to item |
| `armorBonus` | Int | `0` | Extra armor added to item |
| `targetSlots` | List\<String\> | `[]` | Equipment slots this can be applied to (empty = any) |
| `xpReward` | Int | `30` | Enchanting XP awarded |

### Shipped Enchantments

| ID | Skill | Materials | Effect | Slots |
|----|-------|-----------|--------|-------|
| `keen_edge` | 1 | 1 essence | +2 dmg | hand |
| `fortify` | 1 | 1 essence | +2 armor | head, body |
| `might` | 10 | 2 essence + 1 gem | +2 STR | hand |
| `resilience` | 10 | 2 essence + 1 gem | +2 CON | head, body |
| `arcane_infusion` | 20 | 3 essence + 2 gem | +2 INT, +1 WIS, +3 dmg | any |

### Commands

| Command | Description |
|---------|-------------|
| `enchant <item> [enchantment]` | Apply an enchantment to an inventory item (auto-selects if omitted) |
| `enchantments` | List all available enchantments with requirements |

### Enchanting Behavior
- Items must be equippable (have a `slot`) to be enchanted
- Materials are consumed on enchantment
- Display name is modified with a suffix (e.g., `a copper sword (+2 dmg, +1 STR)`)
- Enchantments are tracked per item; duplicate enchantments on the same item are rejected
- `maxEnchantmentsPerItem` limits total enchantments per item
- Enchanting XP uses the same skill/leveling system as crafting

### New Crafting Skill and Station

The `enchanting` crafting skill and `enchanting_table` station type are added to defaults:

```yaml
# These are auto-included in defaults; only override if customizing
ambonmud:
  engine:
    craftingSkills:
      skills:
        enchanting:
          displayName: Enchanting
          type: crafting
    craftingStationTypes:
      stationTypes:
        enchanting_table:
          displayName: Enchanting Table
```

### World Content: Enchanting Chamber

Enchanting tables can be placed in any zone room. For example, in `thornhaven_city`:

```yaml
rooms:
  enchanting_chamber:
    title: "Enchanting Chamber"
    description: "A dim, circular room lit by floating motes of arcane light..."
    station: enchanting_table
    exits:
      w: market_square
```

### GMCP Changes

`Char.Items.List`, `Char.Items.Add`, and equipment payloads now include:

```json
{
  "id": "sword_1",
  "name": "a copper sword (+2 dmg)",
  "keyword": "sword",
  "slot": "hand",
  "damage": 6,
  "armor": 0,
  "stats": { "STR": 2 },
  "enchantments": ["keen_edge"]
}
```

| Field | Type | Description |
|-------|------|-------------|
| `stats` | Object\|null | Non-zero stat bonuses (omitted if none) |
| `enchantments` | Array\|null | Applied enchantment IDs (omitted if none) |

`Crafting.Result` now supports `type: "enchant"` alongside `"craft"` and `"gather"`.

---

## Bank NPC System (application.yaml + zone YAML)

### Config

New config section under `ambonmud.engine.bank`:

```yaml
ambonmud:
  engine:
    bank:
      maxItems: 50              # Maximum items stored in bank vault (default: 50)
```

### Room Flag

Rooms with a bank NPC must set `bank: true`:

```yaml
rooms:
  vault_hall:
    title: "The Ambon Vault"
    description: "A hushed chamber..."
    bank: true                  # ← enables deposit/withdraw commands
    exits:
      s: hall_of_portals
```

### Banker NPC (zone YAML)

```yaml
mobs:
  banker:
    name: "the Banker"
    room: vault_hall
    dialogue:
      root:
        text: "Welcome to the Ambon Vault..."
        choices:
          - text: "How does the bank work?"
            next: explain
          # ...
```

### Commands

| Command | Description |
|---------|-------------|
| `deposit <amount> gold` | Deposit gold into bank |
| `deposit all gold` | Deposit all carried gold |
| `deposit <item>` | Store an item in the vault |
| `withdraw <amount> gold` | Withdraw gold from bank |
| `withdraw all gold` | Withdraw all banked gold |
| `withdraw <item>` | Retrieve an item from vault |
| `bank` | Show bank gold and vault contents |

### Behavior
- Player must be in a room with `bank: true` for deposit/withdraw commands
- `balance`/`bank` command works from anywhere
- Bank gold and items persist across sessions (stored in PlayerRecord)
- Vault has a configurable item limit (`maxItems`, default 50)
- Gold has no limit

### Persistence (Flyway V24)

New columns on `players` table:
- `bank_gold BIGINT NOT NULL DEFAULT 0` — banked gold
- `bank_items TEXT NOT NULL DEFAULT '[]'` — JSON array of stored ItemInstance objects

### GMCP

New `Char.Bank` package emitted after deposit/withdraw:

```json
{
  "gold": 500,
  "items": [
    { "id": "sword_1", "name": "a copper sword", "keyword": "sword", "image": null }
  ],
  "maxItems": 50
}
```

| Field | Type | Description |
|-------|------|-------------|
| `gold` | Long | Banked gold balance |
| `items` | Array | Items in vault (id, name, keyword, image) |
| `maxItems` | Int | Maximum vault capacity |

---

## Stylist NPC System (application.yaml + zone YAML)

### Config

```yaml
ambonmud:
  engine:
    stylist:
      feeGold: 500               # Gold charged per race change (default: 500)
```

### Room Flag

Rooms with a stylist NPC must set `stylist: true`:

```yaml
rooms:
  stylist_salon:
    title: "The Arcanum Mirror"
    description: "A hall of looking-glasses, each one reflecting a different self."
    stylist: true               # ← enables stylist/changerace commands
    image: stylist_mirror.png
    exits:
      s: town_square
```

### Commands

| Command | Description |
|---------|-------------|
| `stylist` | List available races, fee, and current race |
| `changerace <race>` | Swap to the given race (charges the fee) |

### Behavior
- Player must be in a room with `stylist: true` for both commands
- The fee is deducted on successful swap; the command fails cleanly if the player cannot afford it
- The new race's stat modifiers are applied as a delta against the old race's, so stats earned via levelling, prestige, or equipment are preserved
- Derived HP/mana caps are recomputed; current HP/mana are clamped to the new caps (bonuses above the level-derived base, e.g. from prestige perks, are preserved)
- Racial abilities are **not** currently transferred on swap — tracked in GH issue #993

### Global Asset

- `stylist_mirror` → `global_assets/stylist_mirror.png` (registered in `ImagesConfig.DEFAULT_GLOBAL_ASSETS`)
- Place the real art at `src/main/resources/world/images/global_assets/stylist_mirror.png`

### GMCP

New `Char.Stylist` package emitted after `stylist` or `changerace`:

```json
{
  "currentRace": "HUMAN",
  "feeGold": 500,
  "playerGold": 1200,
  "races": [
    {
      "id": "HUMAN",
      "displayName": "Human",
      "description": "Versatile and adaptable.",
      "image": "/images/human_portrait.jpg",
      "statMods": { "STR": 1, "CHA": 1 }
    }
  ]
}
```

| Field | Type | Description |
|-------|------|-------------|
| `currentRace` | String | Player's current race ID |
| `feeGold` | Long | Gold fee charged per swap |
| `playerGold` | Long | Player's current gold (for affordability UI) |
| `races` | Array | Available races (id, displayName, description, image, statMods) |

`Char.Name` is also re-emitted after a successful swap so clients refresh the displayed race.

---

## Sprite Requirements System (sprites.yaml)

Sprites now support a **requirements list** with AND logic, replacing the legacy single-condition `unlock` block. This allows sprites to depend on any combination of race, class, level, achievement, and staff status.

### New YAML Format

```yaml
sprites:
  elven_arcanist:
    displayName: "Elven Arcanist"
    description: "An elf who has mastered ancient magic."
    category: general          # NEW category for requirements-based sprites
    sortOrder: 200
    requirements:              # NEW: AND-logic requirements list
      - type: race
        race: ELF
      - type: class
        playerClass: MAGE
      - type: minLevel
        level: 30
    image: player_sprites/elven_arcanist.png    # Single-image shorthand
```

### Requirement Types

| Type | Fields | Description |
|------|--------|-------------|
| `minLevel` | `level: Int` | Player level >= value |
| `race` | `race: String` | Player race matches |
| `class` | `playerClass: String` | Player class matches |
| `achievement` | `achievementId: String` | Achievement unlocked |
| `staff` | — | Player is staff |

### New Fields

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `description` | String | `""` | Flavor text for the sprite |
| `requirements` | List | `[]` | AND-logic unlock requirements (takes precedence over `unlock`) |
| `image` | String | `""` | Single-image shorthand (creates one variant automatically) |

### Legacy Tier Sprites Config

New config field `images.legacyTierSprites` (default: `true`). Set to `false` to disable auto-generation of the 96 race x class x tier sprites.

### Backwards Compatibility

- Legacy `unlock` block still works for existing sprites
- Legacy `variants` list still works
- `activeSprite` persistence unchanged (imageId string)
- Players with old sprite selections keep them

See `docs/ARCANUM_SPRITE_INSTRUCTIONS.md` for full authoring guide.
## Day/Night Cycle (application.yaml)

New config section under `ambonmud.engine.worldTime`:

```yaml
ambonmud:
  engine:
    worldTime:
      cycleLengthMs: 3600000     # Real ms for one game day (default: 1 hour)
      dawnHour: 5                # Game hour when dawn begins
      dayHour: 8                 # Game hour when day begins
      duskHour: 18               # Game hour when dusk begins
      nightHour: 21              # Game hour when night begins
```

### Time Periods

| Period | Default Hours | Description |
|--------|--------------|-------------|
| NIGHT | 21:00–04:59 | Stars glitter overhead in the dark sky. |
| DAWN | 05:00–07:59 | The sky brightens with the first light of dawn. |
| DAY | 08:00–17:59 | Sunlight fills the world. |
| DUSK | 18:00–20:59 | Long shadows stretch as the sun sinks low. |

### Command

| Command | Description |
|---------|-------------|
| `time` | Show current game time, weather, and active events |

### GMCP: `World.Time`

Broadcast to all players when the time period changes:

```json
{ "period": "DAY", "hour": 8, "minute": 0 }
```

---

## Weather System (application.yaml)

New config section under `ambonmud.engine.weather`:

```yaml
ambonmud:
  engine:
    weather:
      minTransitionMs: 300000    # Min real ms between weather changes (5 min)
      maxTransitionMs: 900000    # Max real ms between weather changes (15 min)
```

### Weather Types

| Type | Weight | Description |
|------|--------|-------------|
| CLEAR | 3.0 | The sky is clear. |
| RAIN | 2.0 | A steady rain falls. |
| STORM | 0.5 | Thunder rumbles and lightning splits the sky. |
| FOG | 1.0 | A thick fog blankets the area. |
| SNOW | 0.8 | Soft snow drifts down from above. |
| WIND | 1.0 | A fierce wind howls through the area. |

Weather transitions are per-zone and weighted random. Higher weight = more likely.

### GMCP: `World.Weather`

Sent to players in a zone when its weather changes:

```json
{ "zone": "crossroads_path", "weather": "RAIN", "description": "A steady rain falls." }
```

---

## Seasonal Events Framework (application.yaml)

New config section under `ambonmud.engine.worldEvents`:

```yaml
ambonmud:
  engine:
    worldEvents:
      definitions:
        spring_festival:
          displayName: "Spring Festival"
          description: "Flowers bloom across the realm."
          startDate: "2026-03-20"    # ISO date (yyyy-MM-dd)
          endDate: "2026-04-20"
          flags:
            - spring_festival        # Queryable by quests, mobs, items
            - bonus_herbalism
          startMessage: "The Spring Festival has begun!"
          endMessage: "The Spring Festival has ended."
```

### Event Definition Fields

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `displayName` | String | `""` | Display name shown to players |
| `description` | String | `""` | Flavor text |
| `startDate` | String | `""` | ISO date for event start (empty = always active) |
| `endDate` | String | `""` | ISO date for event end (empty = no end) |
| `flags` | List\<String\> | `[]` | Flags set when event is active |
| `startMessage` | String | `""` | Broadcast when event activates |
| `endMessage` | String | `""` | Broadcast when event deactivates |

### Behavior
- Events activate/deactivate based on real-world UTC date
- `startMessage` and `endMessage` are broadcast to all online players
- `flags` are queryable by other systems via `WorldEventSystem.hasFlag(flag)`
- Empty `startDate`/`endDate` = always active (permanent event)

### GMCP: `World.Events`

Broadcast to all players when events change:

```json
[
  { "id": "spring_festival", "name": "Spring Festival", "description": "Flowers bloom across the realm." }
]
```

---

## Zone PvP (zone YAML)

New top-level `pvpEnabled` field on zone files:

```yaml
zone: blood_arena
pvpEnabled: true           # enables player-vs-player combat in this zone
startRoom: arena_entrance
rooms:
  arena_entrance:
    title: "The Blood Arena"
    description: "A gladiatorial pit where combatants fight for glory."
```

### Behavior
- When `pvpEnabled: true`, `kill <player>` targets other players instead of requiring a mob
- PvP death respawns at the zone's `startRoom` with full HP/mana, no loot loss
- PvP kills/deaths tracked on `PlayerRecord.pvpKills`/`pvpDeaths`

### Persistence (Flyway V29)

New columns on `players` table:
- `pvp_kills INTEGER NOT NULL DEFAULT 0`
- `pvp_deaths INTEGER NOT NULL DEFAULT 0`

---

## Tavern Rooms (zone YAML)

New `tavern` field on room definitions:

```yaml
rooms:
  tavern_hall:
    title: "The Rusty Tankard"
    description: "A lively tavern with games of chance in every corner."
    tavern: true              # enables gambling commands (gamble, dice)
    exits:
      s: market_square
```

### Commands

| Command | Description |
|---------|-------------|
| `gamble <amount>` | Gamble gold on a coin flip |
| `dice` | Roll dice for entertainment |

---

## Puzzle System (zone YAML)

New top-level `puzzles` section in zone YAML files:

```yaml
puzzles:
  sphinx_riddle:
    type: riddle
    mobId: sphinx_guardian
    roomId: sphinx_chamber
    question: "What has keys but no locks?"
    answer: "piano"
    acceptableAnswers: ["a piano", "keyboard"]
    reward:
      type: unlock_exit
      exitDirection: north
      targetRoom: hidden_treasury
    failMessage: "The sphinx shakes its head disapprovingly."
    successMessage: "The sphinx nods and a hidden passage reveals itself!"
    cooldownMs: 0

  lever_sequence:
    type: sequence
    roomId: puzzle_room
    steps:
      - { feature: red_lever, action: pull }
      - { feature: blue_lever, action: pull }
      - { feature: green_lever, action: pull }
    resetOnFail: true
    reward:
      type: give_item
      itemId: puzzle_key
    successMessage: "A click echoes through the chamber as a key drops from the ceiling."
```

### Puzzle Fields

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `type` | String | — | Required: `riddle` or `sequence` |
| `roomId` | String | — | Required: room where the puzzle is |
| `mobId` | String? | `null` | NPC that poses the riddle (riddle type) |
| `question` | String | — | Required for riddle: the question text |
| `answer` | String | — | Required for riddle: the correct answer |
| `acceptableAnswers` | List\<String\> | `[]` | Additional accepted answers |
| `reward` | Reward | — | Required: what the player receives on success |
| `failMessage` | String? | `null` | Custom failure message |
| `successMessage` | String? | `null` | Custom success message |
| `cooldownMs` | Long | `0` | Cooldown; 0 = one-time per session |
| `steps` | List\<Step\> | `[]` | Required for sequence: ordered interactions |
| `resetOnFail` | Boolean | `true` | Sequence resets on wrong step |

### Reward Types

| Type | Required Fields | Description |
|------|----------------|-------------|
| `unlock_exit` | `exitDirection`, `targetRoom` | Reveals a hidden exit |
| `give_item` | `itemId` | Grants an item |
| `give_gold` | `amount` | Awards gold |
| `give_xp` | `amount` | Awards XP |

### Behavior
- Puzzles are session-scoped (solved state resets on disconnect)
- `answer <text>` command submits riddle answers
- Sequence puzzles track ordered feature interactions

### Arcanum authoring notes

Sequence puzzle steps reference **room feature IDs**, not display names. The creator should expose the local
feature key directly when authors build a `steps:` sequence so builders can select a lever/container/sign without
guessing the underlying YAML identifier.

---

## World Features & Web Feature Badges (zone YAML + web assets)

Doors, levers, and containers were already supported by the runtime; the web client now treats them as first-class
room interactions with dedicated badges and a focused feature drawer. Ambon Arcanum should expose these authoring
surfaces explicitly instead of leaving them implicit inside raw YAML.

### Exit-attached doors

Door definitions live on a room exit in object form:

```yaml
rooms:
  vault_approach:
    exits:
      e:
        to: vault_interior
        door:
          initialState: locked
          keyItemId: old_mines:bronze_vault_key
          keyConsumed: false
          resetWithZone: true
```

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `initialState` | `open \| closed \| locked` | `closed` | Starting door state |
| `keyItemId` | String? | `null` | Optional item required to unlock |
| `keyConsumed` | Boolean | `false` | Consume the key on unlock |
| `resetWithZone` | Boolean | `true` | Reset to original state on zone reset |

### Non-exit room features

Containers, levers, and signs live in the room's `features:` map:

```yaml
rooms:
  support_chamber:
    features:
      vault_lever:
        type: LEVER
        displayName: a heavy iron lever
        keyword: lever
        initialState: up
        resetWithZone: true
      expedition_chest:
        type: CONTAINER
        displayName: an expedition chest
        keyword: chest
        initialState: closed
        items:
          - old_mines:expedition_notes
```

| Field | Applies To | Description |
|-------|------------|-------------|
| `type` | all | `CONTAINER`, `LEVER`, or `SIGN` |
| `displayName` | all | In-room text shown to players |
| `keyword` | all | Command target (`open chest`, `pull lever`) |
| `initialState` | container/lever | `open/closed/locked` for containers, `up/down` for levers |
| `keyItemId` | container | Optional key required to unlock |
| `keyConsumed` | container | Consume key on unlock |
| `resetWithZone` | container/lever | Reset on zone reset |
| `items` | container | Initial contents |
| `text` | sign | Readable sign text |

### Arcanum UI expectations

The creator tool should add:

- An **Exit door editor** inside each room exit row, with a visible "Has door" toggle and fields for `initialState`,
  `keyItemId`, `keyConsumed`, and `resetWithZone`.
- A **Room features editor** that manages the local `features:` map with add/remove/reorder support for containers,
  levers, and signs.
- A visible **feature ID / local key** field for each feature. This matters because puzzle sequence steps reference
  `steps[].feature`, and the authored key must stay stable when names change.
- Context-aware forms:
  `CONTAINER` shows lock + contents fields, `LEVER` shows up/down state, `SIGN` shows text only.
- Preview labels that mirror the web client vocabulary: `Door`, `Container`, `Lever`, and `Puzzle`.

### Web/global asset keys

The web client now looks for these `images.globalAssets` keys in `Server.Assets`, falling back to
`/images/global_assets/<filename>` when the key is absent:

| Key | Fallback file | Used For |
|-----|---------------|----------|
| `puzzle_kiosk` | `puzzle_kiosk.png` | Puzzle badge |
| `feature_door` | `feature_door.png` | Door badge |
| `feature_container` | `feature_container.png` | Container/chest badge |
| `feature_lever` | `feature_lever.png` | Lever badge |
| `crafting_station` | `crafting_station.png` | Crafting badge fallback |
| `trainer_icon` | `trainer_icon.png` | Trainer badge fallback |
| `bank_vault` | `bank_vault.png` | Bank badge fallback |
| `tavern_icon` | `tavern_icon.png` | Tavern badge fallback |

Arcanum does not need to author these into zone YAML, but any built-in preview or export pipeline that wants parity
with the web client should treat the keys above as stable.

---

## Prestige System (application.yaml)

New config section under `ambonmud.engine.prestige`:

```yaml
ambonmud:
  engine:
    prestige:
      perks:
        xp_boost:
          displayName: "XP Boost"
          description: "Gain 10% bonus XP per prestige level"
          perLevel: 0.1
        gold_boost:
          displayName: "Gold Boost"
          description: "Gain 5% bonus gold per prestige level"
          perLevel: 0.05
```

### Commands

| Command | Description |
|---------|-------------|
| `prestige` | Reset level to 1, gain a prestige level |
| `prestige info` | View prestige level and active perks |

### Persistence (Flyway V28)

New columns on `players` table:
- `prestige_level INTEGER NOT NULL DEFAULT 0`
- `prestige_xp_spent BIGINT NOT NULL DEFAULT 0`

---

## Secondary Currencies (application.yaml + zone YAML)

New config section under `ambonmud.engine.currencies`:

```yaml
ambonmud:
  engine:
    currencies:
      definitions:
        quest_points:
          displayName: "Quest Points"
          description: "Earned by completing quests"
        honor:
          displayName: "Honor"
          description: "Earned through PvP combat"
```

### Quest Reward Integration

Quests can award secondary currencies via a `currencies` map on rewards:

```yaml
quests:
  goblin_slayer:
    rewards:
      xp: 100
      gold: 50
      currencies:
        quest_points: 10
        honor: 5
```

### Commands

| Command | Description |
|---------|-------------|
| `currencies` | View all secondary currency balances |

### Persistence (Flyway V32)

New column on `players` table:
- `currencies TEXT NOT NULL DEFAULT '{}'` — JSON map of currency ID to amount

---

## Guild Halls (application.yaml)

New config section under `ambonmud.engine.guildHalls`:

```yaml
ambonmud:
  engine:
    guildHalls:
      baseCost: 5000          # Gold cost to purchase a guild hall
      expansionCost: 2500     # Gold cost per expansion
      maxExpansions: 5        # Maximum hall expansions
```

### Commands

| Command | Description |
|---------|-------------|
| `guild hall` | View guild hall info |
| `guild hall buy` | Purchase a guild hall |
| `guild hall expand` | Expand the guild hall |
| `guild hall enter` | Enter the guild hall |
| `guild hall leave` | Leave the guild hall |

### Persistence (Flyway V30)

Guild hall data stored as JSON on the guilds table.

---

## Lottery System (application.yaml)

New config section under `ambonmud.engine.lottery`:

```yaml
ambonmud:
  engine:
    lottery:
      ticketCost: 100         # Gold per ticket
      drawIntervalMs: 3600000 # Draw every hour
      jackpotPercent: 0.8     # 80% of pool goes to winner
    gambling:
      minBet: 10
      maxBet: 1000
```

### Commands

| Command | Description |
|---------|-------------|
| `lottery` / `lottery info` | View current jackpot and next draw time |
| `lottery buy [count]` | Purchase lottery tickets |
| `gamble <amount>` | Gamble gold (requires `tavern: true` room) |

### Persistence

Lottery state persisted to `data/lottery_state.json`.

---

## Auto Quest / Daily Quest / Global Quest Systems (application.yaml)

Three new quest systems with config under `ambonmud.engine`:

```yaml
ambonmud:
  engine:
    autoQuests:
      enabled: true
      maxActive: 3            # Max auto-quests per player
    dailyQuests:
      enabled: true
      resetHourUtc: 0         # UTC hour for daily reset
      dailySlots: 3           # Number of daily quest slots
      weeklySlots: 1          # Number of weekly quest slots
    globalQuests:
      enabled: true
      checkIntervalMs: 60000  # How often to check global quest progress
```

### Commands

| Command | Description |
|---------|-------------|
| `quest auto` | Toggle auto-quest generation |
| `quest auto info` | View auto-quest settings |
| `quest auto abandon` | Abandon current auto-quest |
| `daily` / `dailyquests` | View available daily quests |
| `weekly` / `weeklyquests` | View available weekly quests |
| `globalquest` / `globalquest info` | View active global quest progress |

### Behavior
- **Auto quests**: Session-only procedural quests generated based on player location and level
- **Daily quests**: Time-rotated quests that reset daily/weekly; progress persisted via `PlayerRecord.dailyQuestData`
- **Global quests**: Server-wide cooperative objectives; ephemeral (not persisted per player)

### Persistence (Flyway V34)

New column on `players` table:
- `daily_quest_data TEXT NOT NULL DEFAULT '{}'` — JSON object for daily/weekly quest progress

---

## Screen Reader Mode

New accessibility feature for screen reader users:

### Commands

| Command | Description |
|---------|-------------|
| `screenreader on` | Enable screen reader mode (strips ANSI, simplifies output) |
| `screenreader off` | Disable screen reader mode |

### Persistence

New columns on `players` table:
- `screen_reader_enabled BOOLEAN NOT NULL DEFAULT false`

### Behavior
- `ScreenReaderFilter.kt` in the transport package strips ANSI codes and reformats output for screen readers
- `PlayerRecord.screenReaderEnabled` persists the preference

---

## Player Description

New `describe` command for setting a custom player description visible to others:

### Commands

| Command | Description |
|---------|-------------|
| `describe <text>` | Set your character description |
| `describe clear` | Clear your description |
| `describe check` | View your current description |

### Persistence

New column on `players` table:
- `description TEXT NOT NULL DEFAULT ''`
