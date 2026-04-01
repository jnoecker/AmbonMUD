# Dungeon Template Reference

This document describes how to create procedural dungeon templates for AmbonMUD. It is written for the **Ambon Arcanum** creator tool and world builders who author dungeon YAML.

---

## Overview

A **dungeon template** defines the theme, room pools, mob pools, and loot tables for a procedural dungeon. At runtime, the engine generates a unique layout from the template for each party that enters — assembling rooms, placing mobs, and scaling difficulty. Each run is instanced per party and cleaned up on completion.

Dungeon templates live inside regular zone YAML files as a `dungeon:` section. The zone also contains the mob definitions (for stat templates), item definitions (for drops and rewards), and a **portal chamber** room where players enter.

---

## Zone File Structure

A dungeon zone file has the same top-level structure as any zone file, plus a `dungeon:` section:

```yaml
zone: sunken_crypt
startRoom: portal_chamber
lifespan: 0

items:
  # Items used as drops, rewards, and crafting materials
  bone_fragment:
    displayName: "a bone fragment"
    keyword: bone
    description: "A jagged fragment of ancient bone."
    basePrice: 15

mobs:
  # Mob templates referenced by the dungeon's mobPools.
  # Place in a hidden room (mob_templates) so they don't spawn in the real world.
  crypt_skeleton:
    name: "a skeletal warrior"
    description: "A rattling skeleton wielding a rusted sword."
    room: mob_templates       # hidden room — not connected to any exits
    tier: standard
    level: 5
    drops:
      - itemId: bone_fragment
        chance: 0.3

rooms:
  portal_chamber:
    title: "Dungeon Portal Chamber"
    description: "Players enter the dungeon from here."
    exits:
      u: ambon_hub:hall_of_portals    # connect to the hub or another zone
  mob_templates:
    title: "Dungeon Mob Templates"
    description: "Internal room for dungeon mob template definitions."
    # No exits — players can never reach this room.

dungeon:
  # ... template definition (see below)
```

### Key Conventions

- **Mob definitions go in `mobs:`** using the zone's normal mob format. The dungeon references them by ID from `mobPools`.
- **Mobs must be in a room** (required by the YAML schema). Use a hidden `mob_templates` room with no exits so they don't spawn in the real world.
- **Do not add `behavior: { template: aggro_guard }`** to dungeon mob templates. The dungeon system forces all spawned mobs to be aggressive regardless.
- **Items go in `items:`** — drops, completion rewards, and cosmetic titles are all regular item definitions.
- **The portal chamber** is a normal room connected to the rest of the world. Players walk here and use `dungeon enter <name> [difficulty]` to start a run.

---

## Dungeon Template Schema

```yaml
dungeon:
  name: <string, required>           # Display name shown to players
  description: <string, optional>    # Flavor text
  image: <string, optional>          # Image asset ID
  minLevel: <int, default 1>         # Minimum player level to enter (checked for all party members)
  roomCountMin: <int, default 20>    # Minimum rooms generated per run (must be >= 3)
  roomCountMax: <int, default 25>    # Maximum rooms generated (must be >= roomCountMin)
  portalRoom: <string, optional>     # Room ID within this zone where players return after leaving/completing
  roomTemplates:                     # Pools of room descriptions per type (at least one type required)
    entrance: [...]
    corridor: [...]
    chamber: [...]
    treasure: [...]
    boss: [...]
  mobPools:                          # Mob IDs drawn from the zone's mobs section
    common: [...]
    elite: [...]
    boss: [...]                      # Required — at least one boss mob
  lootTables:                        # Rewards per difficulty tier
    lore: { ... }
    normal: { ... }
    hard: { ... }
    heroic: { ... }
```

---

## Room Templates

Each room type has a pool of descriptions. The generator picks randomly from each pool during layout generation, so more entries = more variety.

```yaml
roomTemplates:
  entrance:
    - title: "Crypt Entrance"
      description: "Crumbling steps descend into darkness..."
      image: entrance.png            # optional
  corridor:
    - title: "Narrow Passage"
      description: "A cramped corridor winds through the bedrock..."
    - title: "Flooded Passage"
      description: "Ankle-deep water fills this low passage..."
  chamber:
    - title: "Burial Chamber"
      description: "Stone sarcophagi line the walls..."
  treasure:
    - title: "Hidden Vault"
      description: "Gold glints in the torchlight..."
  boss:
    - title: "The Crypt Heart"
      description: "A vast domed chamber..."
```

### Room Types

| Type | Purpose | Count | Mobs |
|------|---------|-------|------|
| `entrance` | Starting room | Always 1 | None |
| `corridor` | Connecting passages | ~50% of rooms | 1-2 common mobs |
| `chamber` | Larger rooms | ~30% of rooms | 1-3 common + 40% chance of 1 elite |
| `treasure` | Side rooms (branches) | ~10% of rooms | 1 elite guard |
| `boss` | Final room | Always 1 | 1 boss mob |

**Tips:**
- Provide at least 3-4 corridor variants and 2-3 chamber variants for variety.
- The entrance pool only needs 1 entry (there's only one entrance per run).
- The boss pool only needs 1 entry, but you can add variants for replay value.
- If a room type has no pool defined, the generator falls back to corridor templates.

---

## Layout Generation

The generator produces a **linear backbone with side branches**:

```
Entrance → Corridor → Chamber ──→ Corridor → Corridor → Chamber → Boss
                         │                                  │
                         └→ Treasure                        └→ Corridor → Treasure
```

- **Backbone:** entrance → mixed corridors/chambers → boss room (always connected in sequence)
- **Branches:** ~30% chance at each chamber, 1-2 rooms deep, often treasure rooms
- **Room count:** randomly chosen between `roomCountMin` and `roomCountMax` per run
- **Exits:** all bidirectional; the generator cycles through N/E/N/W directions with fallbacks
- **Every room is reachable** from the entrance via BFS

---

## Mob Pools

Mob IDs reference entries in the zone's `mobs:` section. The generator draws from these pools per room type.

```yaml
mobPools:
  common:                    # Standard enemies — appear in corridors and chambers
    - crypt_skeleton
    - crypt_spider
  elite:                     # Tougher enemies — appear in chambers (40% chance) and treasure rooms
    - crypt_guardian
  boss:                      # Boss mob(s) — one is placed in the boss room
    - crypt_lord             # Required: at least one boss
```

### Mob Stat Scaling

Dungeon mobs are **scaled at runtime** based on difficulty tier and party level. You define the base stats in the `mobs:` section using standard tier/level fields, and the system applies multipliers:

| Difficulty | HP | Damage | XP | Drop Rate |
|------------|-----|--------|-----|-----------|
| **Lore** | 0.5x | 0.5x | 0x | 0x (no drops) |
| **Normal** | 1.0x | 1.0x | 1.0x | 1.0x |
| **Hard** | 1.5x | 1.5x | 1.5x | 1.5x |
| **Heroic** | 2.0x | 2.0x | 2.0x | 2.0x |

Additionally, mob stats scale with the **party's average level**: +10% per level above 1. So a level 10 party faces mobs with ~1.9x base stats (before difficulty multiplier).

**Example:** A `standard` tier skeleton with `level: 5` and base HP 22 in a Hard dungeon with a level 8 party:
- Level scale: 1.0 + (8-1) × 0.1 = 1.7x
- Difficulty: 1.5x
- Final HP: 22 × 1.7 × 1.5 ≈ 56

### Mob Behavior

All dungeon mobs are **forced aggressive** — they attack players on sight regardless of the mob's YAML behavior template. Do not add `behavior: { template: aggro_guard }` to dungeon mob definitions; it's unnecessary and would make the mob aggressive at the portal room too.

---

## Loot Tables

Loot tables define what drops from mobs and what rewards are granted on boss kill, per difficulty tier.

```yaml
lootTables:
  lore:
    # Lore mode: cosmetic-only rewards, no mob drops
    completionRewards:
      - crypt_explorer_title      # A title item granted to each party member
  normal:
    mobDrops:                      # Extra items added to mob drop tables (currently informational)
      - bone_fragment
      - crypt_dust
    completionRewards:             # Items granted to each party member on boss kill
      - crypt_relic
  hard:
    mobDrops:
      - bone_fragment
      - crypt_dust
      - crypt_relic
    completionRewards:
      - crypt_relic
      - crypt_relic               # Duplicate = 2 separate items
  heroic:
    mobDrops:
      - crypt_relic
      - crypt_dust
    completionRewards:
      - crypt_relic
      - crypt_relic
      - crypt_relic               # 3 relics for heroic
```

### How Loot Works

- **Mob drops:** Mobs use their standard `drops:` list from the YAML, with the `chance` scaled by the difficulty's `dropRateMultiplier`. In Lore mode (0x multiplier), mobs drop nothing.
- **Completion rewards:** Each item in `completionRewards` is created and placed in every party member's inventory when the boss is killed. Duplicate entries grant multiple copies.
- **Lore mode** is specifically designed for cosmetic-only rewards (titles, sprites, vanity items) with no combat drops and reduced mob stats, so anyone can explore the content.

---

## Difficulty Tiers

Players choose difficulty when entering: `dungeon enter crypt normal` (defaults to Normal if omitted).

| Tier | Intended Audience | Mob Power | Rewards |
|------|-------------------|-----------|---------|
| **Lore** | Anyone — feel powerful, explore the story | 50% HP/damage, 0 XP | Cosmetic only (titles, sprites) |
| **Normal** | Standard challenge | 100% baseline | Level-appropriate drops |
| **Hard** | Experienced players | 150% HP/damage | Better drop rates, more completion rewards |
| **Heroic** | Endgame challenge | 200% HP/damage | Best drops, exclusive rewards |

---

## Player Experience

### Entry
1. Player walks to the **portal chamber** room
2. Types `dungeon enter <name> [difficulty]`
3. If in a group, all party members are teleported together
4. All party members must meet the `minLevel` requirement

### During the Run
- Navigate rooms, fight mobs, explore branches for treasure
- No time limit
- `dungeon leave` teleports back to the portal room at any time
- If disconnected, `dungeon enter <name>` re-enters the active instance

### Completion
- Kill the boss mob → all party members receive completion rewards
- "Type 'dungeon leave' to return to the portal" message shown
- Freely repeatable — enter again for a new layout

### Party Wipe
- If all members die or leave, the instance is destroyed
- Rooms and mobs are cleaned up automatically

---

## Complete Example

See `src/main/resources/world/sunken_crypt.yaml` for the full reference dungeon ("The Sunken Crypt"):
- 4 mob types across 3 tiers (2 common, 1 elite, 1 boss)
- 10 room template variants (4 corridor, 3 chamber, 2 treasure, 1 boss)
- 4 difficulty tiers with scaled rewards
- Rare drops (bone fragments, crypt dust, crypt relics)
- Lore-mode cosmetic title reward

---

## Checklist for New Dungeons

1. Create a new zone YAML file (e.g. `forbidden_tower.yaml`)
2. Define items: drops, rewards, cosmetic titles
3. Define mobs in `mobs:` with appropriate tiers/levels, placed in a hidden `mob_templates` room
4. Define at least two rooms: a `portal_chamber` (connected to the world) and `mob_templates` (hidden)
5. Add the `dungeon:` section with room templates, mob pools, and loot tables
6. Connect the portal chamber to an existing zone via exits
7. Ensure at least one boss mob in `mobPools.boss`
8. Ensure `roomCountMin >= 3` and `roomCountMin <= roomCountMax`
9. Test with `dungeon enter <name> lore` to verify layout and content
10. Test with `dungeon enter <name> heroic` to verify difficulty scaling
