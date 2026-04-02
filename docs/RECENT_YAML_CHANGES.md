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
