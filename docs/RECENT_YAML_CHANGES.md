# Recent YAML & Config Changes for the Arcanum

This document summarizes YAML and configuration changes from recent features that the Ambon Arcanum creator tool needs to support.

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

New room in `crafting_workshop` zone (accessible from alchemy lab):

```yaml
rooms:
  enchanting_chamber:
    title: "Enchanting Chamber"
    description: "A dim, circular room lit by floating motes of arcane light..."
    station: enchanting_table
    exits:
      w: alchemy_lab
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
{ "zone": "ambon_hub", "weather": "RAIN", "description": "A steady rain falls." }
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
