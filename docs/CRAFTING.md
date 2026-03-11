# Crafting & Gathering

The crafting system provides resource gathering and item creation through skill-based progression. Four skills (two gathering, two crafting) let players harvest materials and combine them into equipment and consumables.

## Commands

| Command | Aliases | Description |
|---------|---------|-------------|
| `gather <node>` | `harvest`, `mine` | Harvest a resource node in the current room |
| `craft <recipe>` | `make`, `create` | Craft an item from materials in your inventory |
| `recipes [filter]` | `recipe` | List available recipes, optionally filtered by skill or name |
| `craftskills` | `professions`, `prof` | Show your crafting skill levels and XP progress |

## Skills

| Skill | Type | Description |
|-------|------|-------------|
| Mining | Gathering | Harvest ore veins |
| Herbalism | Gathering | Harvest plants and herbs |
| Smithing | Crafting | Forge weapons and armor from ore |
| Alchemy | Crafting | Brew potions and elixirs from herbs |

Skills are defined in `application.yaml` under `ambonmud.engine.craftingSkills.skills`. Each skill has a `displayName` and `type` (`gathering` or `crafting`).

### Progression

Skills level from 1 to 100. XP required for the next level follows an exponential curve:

```
xpForLevel(level) = baseXpPerLevel * level ^ xpExponent
```

Default config: `baseXpPerLevel: 50`, `xpExponent: 1.5`. Examples:

| Level | XP Required |
|-------|-------------|
| 1 | 50 |
| 2 | 141 |
| 5 | 559 |
| 10 | 1,581 |
| 20 | 4,472 |

Excess XP carries over when leveling up.

## Gathering

Use `gather <keyword>` in a room that has gathering nodes. Nodes are defined per-zone in the world YAML (see [WORLD_YAML_SPEC.md](./WORLD_YAML_SPEC.md#gatheringnode-schema)).

**Flow:**
1. Player types `gather copper`
2. System finds a matching node in the room (exact keyword, prefix, or display name match — case-insensitive)
3. Checks: skill level >= node's `skillRequired`, node is not depleted, player is not on cooldown
4. On success: 1–N items added to inventory (random between `minQuantity` and `maxQuantity`), skill XP awarded, node enters respawn timer

**Cooldown:** Players must wait between gathers (default 3 seconds, configurable via `ambonmud.engine.crafting.gatherCooldownMs`).

**Node respawn:** After harvesting, a node is depleted for its `respawnSeconds` duration, then becomes available again. Respawn timers tick with the engine loop.

**Error messages:**
- No matching node in room
- Skill level too low (shows required vs current)
- Node depleted (shows respawn time remaining)
- On cooldown (shows time remaining)

## Crafting

Use `craft <keyword>` to create items from materials in your inventory. Recipes are defined per-zone in the world YAML.

**Flow:**
1. Player types `craft copper sword`
2. System finds a matching recipe (exact keyword, display name, prefix, or partial match — case-insensitive)
3. Checks: player level >= `levelRequired`, skill level >= `skillRequired`, inventory contains all required materials
4. On success: materials consumed, output item(s) added to inventory, skill XP awarded
5. If player is at a matching crafting station, bonus quantity is added

**Recipe lookup priority:** exact keyword > exact display name > keyword prefix > display name contains.

**Error messages:**
- Unknown recipe
- Skill level too low
- Player level too low
- Missing materials (lists each missing item and quantity needed)

### Crafting Stations

Rooms can declare a `station` field (e.g., `forge`, `alchemy_table`, `workbench`). When a player crafts a recipe whose `stationType` matches the room's station, they receive bonus output items.

Station bonus defaults to `stationBonusQuantity: 1` (configurable in `ambonmud.engine.crafting`). Recipes can override this with their own `stationBonus` value.

Station types are defined in `application.yaml` under `ambonmud.engine.craftingStationTypes.stationTypes`.

## Recipes List

Use `recipes` to see all available recipes, or `recipes smithing` to filter by skill. The list shows:
- Recipe name
- Required skill and level
- Materials needed
- An `*` marker if you don't meet the skill/level requirements

## Configuration

All crafting config lives under `ambonmud.engine.crafting` in `application.yaml`:

| Key | Default | Description |
|-----|---------|-------------|
| `maxSkillLevel` | 100 | Maximum level for any crafting skill |
| `baseXpPerLevel` | 50 | Base XP for level 1 |
| `xpExponent` | 1.5 | Exponential scaling factor for XP curve |
| `gatherCooldownMs` | 3000 | Cooldown between gathers (ms) |
| `stationBonusQuantity` | 1 | Default bonus items when crafting at a matching station |

## Persistence

Crafting skills are stored per-player as a JSON map in `PlayerRecord.craftingSkills`:

```json
{"mining": {"level": 5, "xp": 200}, "smithing": {"level": 3, "xp": 85}}
```

- **YAML backend:** Serialized in the player YAML file
- **PostgreSQL:** `crafting_skills TEXT` column (migration `V13__add_crafting_skills.sql`)

Gather cooldowns are runtime-only (`PlayerState.gatherCooldownUntilMs`) and reset on logout.

## Crafting Workshop Zone

The `crafting_workshop` zone provides a starter crafting area:

| Room | Contents |
|------|----------|
| Entrance | Hub with directional signage |
| Mine Tunnel (north) | Copper vein (skill 1), iron vein (skill 15) |
| Herb Garden (east) | Wildflower patch (skill 1), healing herbs (skill 15) |
| Forge Room (west) | Station: `forge` — smithing bonus |
| Alchemy Lab | Station: `alchemy_table` — alchemy bonus |
| Supply Shop (south) | NPC merchant selling raw materials |

**Starter recipes:**
- **Smithing:** Copper Sword (3 copper ore, skill 1), Copper Helm (4 copper ore, skill 5), Iron Sword (3 iron + 1 copper ore, skill 20)
- **Alchemy:** Minor Healing Potion (2 wildflower, skill 1), Strength Elixir (2 healing herb + 1 wildflower, skill 20)

## Key Source Files

| File | Purpose |
|------|---------|
| `engine/crafting/CraftingSystem.kt` | Core gathering/crafting logic, skill XP |
| `engine/crafting/CraftingRegistry.kt` | Recipe lookup |
| `engine/crafting/GatheringRegistry.kt` | Node lookup by room |
| `engine/crafting/CraftingSkillRegistry.kt` | Skill definitions |
| `engine/crafting/CraftingStationTypeRegistry.kt` | Station type validation |
| `engine/commands/handlers/CraftingHandler.kt` | Command routing |
| `domain/crafting/CraftingSkillState.kt` | Skill level + XP model |
| `domain/crafting/RecipeDef.kt` | Recipe definition |
| `domain/crafting/GatheringNodeDef.kt` | Gathering node definition |

## Adding Content

To add new gathering nodes and recipes, edit the zone YAML — no code changes needed. See [WORLD_YAML_SPEC.md](./WORLD_YAML_SPEC.md) for the schema. To add a new skill or station type, update `application.yaml`.
