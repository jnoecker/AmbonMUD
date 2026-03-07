# Data-Driven Stats — Engineering Plan

> **All phases complete.** The data-driven stats system is fully shipped across all layers (engine, persistence, GMCP, world YAML, web client). This document is preserved as a historical engineering reference.

## Goal

Replace the 6 hardcoded stats (STR, DEX, CON, INT, WIS, CHA) with a fully data-driven stat system where stats are defined in YAML config, formula bindings map stats to game mechanics, and the Creator tool can add/remove/modify stats without code changes.

---

## Current State

### How stats are represented today

**StatBlock** (`domain/StatBlock.kt`) — 6 named fields:
```kotlin
data class StatBlock(
    val str: Int = 0, val dex: Int = 0, val con: Int = 0,
    val int: Int = 0, val wis: Int = 0, val cha: Int = 0,
)
```

**PlayerState** (`engine/PlayerState.kt`) — 6 mutable properties:
```kotlin
var strength: Int = BASE_STAT  // BASE_STAT = 10
var dexterity: Int = BASE_STAT
// ... etc
```

**PlayerRecord** (`persistence/PlayerRecord.kt`) — 6 fields persisted to YAML/Postgres.

**PlayersTable** (`persistence/PlayersTable.kt`) — 6 individual database columns.

### How stats flow through the system

```
YAML Config (race mods, item bonuses)
    ↓
WorldLoader → Item.stats: StatBlock, RaceDef.statMods: StatBlock
    ↓
PlayerRegistry.prepareCreateAccount() → applies race mods to base stats
    ↓
PlayerState (base stats) + ItemRegistry.equipmentBonuses().stats + StatusEffectSystem.getPlayerStatMods()
    ↓
resolveEffectiveStats() → combined StatBlock
    ↓
CombatSystem, RegenSystem, AbilitySystem, PlayerProgression (consume individual stat values)
    ↓
GmcpEmitter.sendCharStats() → Char.Stats GMCP → web client
```

### Where stats are hardcoded (complete inventory)

#### Core Data Model (6 files)
| File | Lines | What | How |
|------|-------|------|-----|
| `domain/StatBlock.kt` | 8-14 | 6 named fields | Data class definition |
| `engine/PlayerState.kt` | 25-30 | 6 mutable properties | Runtime state |
| `engine/PlayerState.kt` | 133-138 | `toPlayerState()` | Maps record → state |
| `engine/PlayerState.kt` | 176-181 | `toPlayerRecord()` | Maps state → record |
| `engine/PlayerState.kt` | 222-233 | `resolveEffectiveStats()` | Combines base + equip + effects |
| `persistence/PlayerRecord.kt` | 19-24 | 6 fields | Persistence DTO |
| `persistence/PlayersTable.kt` | 33-38 | 6 columns | Exposed table definition |
| `persistence/PlayersTable.kt` | 74-79 | `readRecord()` | DB read |
| `persistence/PlayersTable.kt` | 114-119 | `writeRecord()` | DB write |

#### Config (3 files)
| File | Lines | What | How |
|------|-------|------|-----|
| `config/AppConfig.kt` | 479-486 | `RaceStatModsConfig` | 6 named fields for race mods |
| `config/AppConfig.kt` | 634-637 | `CombatSystemConfig` | `strDivisor`, `dexDodgePerPoint`, `intSpellDivisor` |
| `config/AppConfig.kt` | 649 | `RegenEngineConfig` | `msPerConstitution` |
| `config/AppConfig.kt` | 658 | `ManaRegenConfig` | `msPerWisdom` |
| `config/AppConfig.kt` | 93-96, 101, 122 | `validated()` | Stat-specific require checks |
| `application.yaml` | 180-194 | Runtime config | `strDivisor: 3`, `msPerConstitution: 200`, etc. |

#### Game Systems (6 files)
| File | Lines | What | How |
|------|-------|------|-----|
| `engine/CombatSystem.kt` | 328 | Melee damage | `statBonus(playerStats.str, config.strDivisor)` |
| `engine/CombatSystem.kt` | 411 | Dodge chance | `statBonus(targetStats.dex, 1) * config.dexDodgePerPoint` |
| `engine/CombatSystem.kt` | 702-703 | XP bonus | `charisma + equipCha` |
| `engine/abilities/AbilitySystem.kt` | 133 | Spell damage | `statBonus(playerStats.int, intSpellDivisor)` |
| `engine/PlayerProgression.kt` | 87-93 | HP/Mana scaling | `constitution` → max HP, `intelligence` → max mana |
| `engine/PlayerProgression.kt` | 140-148 | XP bonus | `applyCharismaXpBonus(totalCha, baseXp)` |
| `engine/RegenSystem.kt` | 63, 73 | Regen intervals | `constitution` → HP regen, `wisdom` → mana regen |

#### Display / Network (4 files)
| File | Lines | What | How |
|------|-------|------|-----|
| `engine/commands/handlers/ProgressionHandler.kt` | 76-87 | Score command | Hardcoded STR/DEX/CON/INT/WIS/CHA lines |
| `engine/GmcpEmitter.kt` | 505-517, 1056-1073 | GMCP payload | 12 fields: 6 base + 6 effective |
| `web-v3/src/types.ts` | 242-253 | TS types | 12 fields matching GMCP |
| `web-v3/src/gmcp/applyGmcpPackage.ts` | 630-641 | GMCP handler | Applies incoming stat data |

#### World Content (3 files)
| File | Lines | What | How |
|------|-------|------|-----|
| `domain/world/data/ItemFile.kt` | 12-17 | Item YAML DTO | 6 individual stat fields |
| `domain/world/load/WorldLoader.kt` | 340, 401-407 | YAML parsing | Reads 6 fields, constructs StatBlock |
| 17 zone YAML files | various | Item definitions | `constitution: 2`, `strength: 1`, etc. |

#### Admin / Persistence (3 files)
| File | Lines | What | How |
|------|-------|------|-----|
| `admin/AdminHttpServer.kt` | 106-111, 178-183, 231-236 | Admin API | 6 stat fields in request/response DTOs |
| `persistence/PlayerCreationRequest.kt` | — | Create account | 6 stat fields |
| Flyway V1, V3 | — | DB migrations | `constitution` column + 5 added later |

#### Tests (8+ files)
| File | What |
|------|------|
| `PersistenceFieldCoverageTest.kt` | Round-trip with specific stat values |
| `CombatSystemTest.kt` | StatBlock in combat scenarios |
| `GmcpEmitterTest.kt` | Stat broadcasting |
| `StatusEffectSystemTest.kt` | STAT_BUFF/STAT_DEBUFF |
| `GameEngineLoginFlowTest.kt` | Race mod assertion (Human STR +1) |
| `HandoffManagerTest.kt` | Cross-engine stat serialization |
| `InterEngineMessageSerializationTest.kt` | gRPC stat fields |
| `RaceRegistryTest.kt` | Race stat mods loading |

---

## New Design

### Config Schema

```yaml
engine:
  stats:
    definitions:
      STR:
        displayName: "Strength"
        abbreviation: "STR"
        description: "Physical power. Increases melee damage."
        baseStat: 10
      DEX:
        displayName: "Dexterity"
        abbreviation: "DEX"
        description: "Agility and reflexes. Increases dodge chance."
        baseStat: 10
      CON:
        displayName: "Constitution"
        abbreviation: "CON"
        description: "Endurance and health. Increases max HP and HP regen."
        baseStat: 10
      INT:
        displayName: "Intelligence"
        abbreviation: "INT"
        description: "Arcane aptitude. Increases max mana and spell damage."
        baseStat: 10
      WIS:
        displayName: "Wisdom"
        abbreviation: "WIS"
        description: "Insight and perception. Increases mana regen."
        baseStat: 10
      CHA:
        displayName: "Charisma"
        abbreviation: "CHA"
        description: "Force of personality. Increases XP gain."
        baseStat: 10
      # --- Custom stats example ---
      LCK:
        displayName: "Luck"
        abbreviation: "LCK"
        description: "Fortune favors the bold. Increases critical hit chance and drop rates."
        baseStat: 5

    # Formula bindings — map game mechanics to stats by ID
    bindings:
      meleeDamageStat: "STR"
      meleeDamageDivisor: 3
      dodgeStat: "DEX"
      dodgePerPoint: 2
      maxDodgePercent: 30
      spellDamageStat: "INT"
      spellDamageDivisor: 3
      hpScalingStat: "CON"
      hpScalingDivisor: 5
      manaScalingStat: "INT"
      manaScalingDivisor: 5
      hpRegenStat: "CON"
      hpRegenMsPerPoint: 200
      manaRegenStat: "WIS"
      manaRegenMsPerPoint: 200
      xpBonusStat: "CHA"
      xpBonusPerPoint: 0.005
```

### New Kotlin Data Model

```kotlin
// domain/StatDefinition.kt
data class StatDefinition(
    val id: String,           // "STR" — uppercase key
    val displayName: String,  // "Strength"
    val abbreviation: String, // "STR"
    val description: String = "",
    val baseStat: Int = 10,
)

// engine/StatRegistry.kt
class StatRegistry {
    fun get(id: String): StatDefinition?
    fun all(): List<StatDefinition>   // in definition order
    fun ids(): List<String>           // ordered list of stat IDs
    fun baseStat(id: String): Int     // default base value
}
```

### StatMap replaces StatBlock

```kotlin
// domain/StatMap.kt — replaces StatBlock
@JvmInline
value class StatMap(val values: Map<String, Int> = emptyMap()) {
    operator fun get(id: String): Int = values[id.uppercase()] ?: 0
    operator fun plus(other: StatMap): StatMap {
        val merged = values.toMutableMap()
        for ((k, v) in other.values) merged[k] = (merged[k] ?: 0) + v
        return StatMap(merged)
    }
    companion object {
        val EMPTY = StatMap()
    }
}
```

### Formula Bindings

```kotlin
// config/AppConfig.kt — new config class
data class StatBindingsConfig(
    val meleeDamageStat: String = "STR",
    val meleeDamageDivisor: Int = 3,
    val dodgeStat: String = "DEX",
    val dodgePerPoint: Int = 2,
    val maxDodgePercent: Int = 30,
    val spellDamageStat: String = "INT",
    val spellDamageDivisor: Int = 3,
    val hpScalingStat: String = "CON",
    val hpScalingDivisor: Int = 5,
    val manaScalingStat: String = "INT",
    val manaScalingDivisor: Int = 5,
    val hpRegenStat: String = "CON",
    val hpRegenMsPerPoint: Long = 200,
    val manaRegenStat: String = "WIS",
    val manaRegenMsPerPoint: Long = 200,
    val xpBonusStat: String = "CHA",
    val xpBonusPerPoint: Double = 0.005,
)
```

Systems read bindings instead of hardcoding which stat to use:
```kotlin
// Before:
val bonus = PlayerState.statBonus(playerStats.str, config.strDivisor)

// After:
val bonus = PlayerState.statBonus(
    effectiveStats[bindings.meleeDamageStat],
    bindings.meleeDamageDivisor,
)
```

### Persistence Strategy

**Postgres:** Add a new `stats_json` column (JSONB) alongside existing columns. New migration:
```sql
-- V12__add_stats_json.sql
ALTER TABLE players ADD COLUMN stats_json JSONB DEFAULT '{}';
-- Migrate existing data
UPDATE players SET stats_json = jsonb_build_object(
    'STR', strength, 'DEX', dexterity, 'CON', constitution,
    'INT', intelligence, 'WIS', wisdom, 'CHA', charisma
);
```

Read priority: `stats_json` if non-empty, else fall back to individual columns. This ensures backward compatibility. The old 6 columns can be dropped in a future migration once all data is migrated.

**YAML persistence:** `PlayerRecord` serializes stats as a map:
```yaml
stats:
  STR: 11
  DEX: 10
  CON: 10
  INT: 10
  WIS: 10
  CHA: 11
  LCK: 5
```

**Redis cache:** Already uses JSON — just serialize the map.

### GMCP Changes

```json
// Before: Char.Stats
{
  "strength": 11, "dexterity": 10, ...,
  "effectiveStrength": 13, "effectiveDexterity": 12, ...
}

// After: Char.Stats
{
  "stats": [
    { "id": "STR", "name": "Strength", "abbrev": "STR", "base": 11, "effective": 13 },
    { "id": "DEX", "name": "Dexterity", "abbrev": "DEX", "base": 10, "effective": 12 },
    { "id": "LCK", "name": "Luck", "abbrev": "LCK", "base": 5, "effective": 7 }
  ]
}
```

The web client renders stats dynamically from the array instead of reading named fields.

### World YAML Changes

Items currently use named fields:
```yaml
items:
  iron_helm:
    displayName: "Iron Helm"
    constitution: 2
    strength: 1
```

New format uses a `stats` map:
```yaml
items:
  iron_helm:
    displayName: "Iron Helm"
    stats:
      CON: 2
      STR: 1
```

**Backward compatibility:** `WorldLoader` reads both formats during migration. If the old named fields are present and `stats` is absent, it converts them. This lets existing zone YAML files work unchanged until re-saved by the Creator.

### Race/Status Effect Changes

Already use `StatBlock` — these just change to `StatMap`:

```yaml
# Race definition (already works this way with current refactor)
races:
  definitions:
    HUMAN:
      displayName: "Human"
      statMods:
        STR: 1
        CHA: 1
        LCK: 1    # custom stat — just works
```

`StatusEffectDefinition.statMods` changes from `StatBlock` to `StatMap`. No structural change needed — the `yaml` package deserializes maps natively.

---

## Files to Change (Complete List)

### New files (3)
| File | Purpose |
|------|---------|
| `domain/StatDefinition.kt` | Stat definition data class |
| `engine/StatRegistry.kt` | Registry + loader |
| `db/migration/V12__add_stats_json.sql` | Postgres migration |

### Delete (1)
| File | Replaced by |
|------|-------------|
| `domain/StatBlock.kt` | `domain/StatMap.kt` (or rename in place) |

### Modify — data model (6 files)
| File | Change |
|------|--------|
| `domain/StatBlock.kt` | Rewrite as `StatMap` (map-based) |
| `engine/PlayerState.kt` | 6 properties → `stats: StatMap`; `resolveEffectiveStats()` uses StatMap |
| `persistence/PlayerRecord.kt` | 6 fields → `stats: Map<String, Int>`; backward-compat defaults |
| `persistence/PlayersTable.kt` | Add `stats_json` column; read/write as JSON |
| `persistence/PlayerCreationRequest.kt` | 6 fields → `stats: Map<String, Int>` |
| `persistence/YamlPlayerRepository.kt` | Serialize stats map |

### Modify — config (2 files)
| File | Change |
|------|--------|
| `config/AppConfig.kt` | Add `StatsEngineConfig`, `StatDefinitionConfig`, `StatBindingsConfig`; replace `RaceStatModsConfig` with `Map<String, Int>`; replace hardcoded divisor fields in `CombatSystemConfig`/`RegenEngineConfig` |
| `application.yaml` | Add `engine.stats` section; migrate combat/regen divisors to bindings |

### Modify — game systems (6 files)
| File | Change |
|------|--------|
| `engine/CombatSystem.kt` | Read `bindings.meleeDamageStat` instead of `.str`; read `bindings.dodgeStat` instead of `.dex` |
| `engine/abilities/AbilitySystem.kt` | Read `bindings.spellDamageStat` instead of `.int` |
| `engine/PlayerProgression.kt` | Read `bindings.hpScalingStat`/`manaScalingStat` instead of `constitution`/`intelligence` |
| `engine/RegenSystem.kt` | Read `bindings.hpRegenStat`/`manaRegenStat` instead of `constitution`/`wisdom` |
| `engine/status/StatusEffectDefinition.kt` | `statMods: StatBlock` → `statMods: StatMap` |
| `engine/status/StatusEffectSystem.kt` | `computeStatMods()` returns `StatMap` |

### Modify — world loading (3 files)
| File | Change |
|------|--------|
| `domain/world/data/ItemFile.kt` | 6 named fields → `stats: Map<String, Int>` (keep old fields for backward compat) |
| `domain/world/load/WorldLoader.kt` | Read `stats` map; fall back to named fields if absent |
| `engine/RaceRegistryLoader.kt` | `RaceStatModsConfig` → `Map<String, Int>` |

### Modify — display/network (4 files)
| File | Change |
|------|--------|
| `engine/commands/handlers/ProgressionHandler.kt` | Iterate `statRegistry.all()` instead of hardcoded 6 lines |
| `engine/GmcpEmitter.kt` | `CharStatsPayload` becomes array-based |
| `web-v3/src/types.ts` | 12 named fields → `stats: StatEntry[]` |
| `web-v3/src/gmcp/applyGmcpPackage.ts` | Dynamic stat handling |

### Modify — admin / wiring (4 files)
| File | Change |
|------|--------|
| `admin/AdminHttpServer.kt` | 6 stat fields → `stats: Map<String, Int>` in DTOs |
| `MudServer.kt` | Construct `StatRegistry`, pass to systems |
| `engine/GameEngine.kt` | Accept/construct `StatRegistry`, pass bindings to systems |
| `engine/items/ItemRegistry.kt` | `EquipmentBonuses.stats` → `StatMap` |

### Modify — gRPC/sharding (2 files)
| File | Change |
|------|--------|
| `grpc/ProtoMapper.kt` | Map stat fields to/from proto |
| Proto files | Stats as repeated key-value pairs |

### Modify — tests (8+ files)
| File | Change |
|------|--------|
| `PersistenceFieldCoverageTest.kt` | Use map-based stats |
| `CombatSystemTest.kt` | `StatBlock(str = 6)` → `StatMap(mapOf("STR" to 6))` |
| `GmcpEmitterTest.kt` | Updated payload assertions |
| `StatusEffectSystemTest.kt` | StatMap assertions |
| `GameEngineLoginFlowTest.kt` | Race mod assertions |
| `HandoffManagerTest.kt` | Stat serialization |
| `InterEngineMessageSerializationTest.kt` | Proto stat mapping |
| `RaceRegistryTest.kt` | StatMap in race defs |
| `TestFixtures.kt` | Helper updates |

---

## Implementation Phases

### Phase 1: StatMap + StatRegistry (foundation) ✅ Complete

**Goal:** Introduce `StatMap` and `StatRegistry` alongside `StatBlock`. No behavioral change.

1. Create `StatDefinition` data class and `StatRegistry`
2. Add `StatsEngineConfig` and `StatBindingsConfig` to `AppConfig.kt`
3. Add `engine.stats` section to `application.yaml` with the 6 default stats
4. Create `StatMap` value class
5. Add `StatBlock.toStatMap()` and `StatMap.toStatBlock()` bridge methods
6. Construct `StatRegistry` in `MudServer` and `GameEngine`
7. Tests: `StatRegistryTest`, `StatMapTest`

### Phase 2: Migrate consumers to StatMap + bindings ✅ Complete

**Goal:** Systems read stats from `StatMap` via bindings. `StatBlock` still exists internally as a bridge.

1. `CombatSystem` — read melee/dodge stats from bindings
2. `AbilitySystem` — read spell stat from bindings
3. `PlayerProgression` — read HP/mana scaling stats from bindings
4. `RegenSystem` — read regen stats from bindings
5. `ProgressionHandler` — dynamic score display from `statRegistry.all()`
6. `StatusEffectDefinition` — `statMods: StatMap`
7. `GmcpEmitter` — array-based `Char.Stats` payload
8. Tests for each system updated

### Phase 3: Migrate persistence ✅ Complete

**Goal:** Stats stored as map, not individual columns.

1. `PlayerState` — 6 properties → `stats: MutableMap<String, Int>` with accessor helpers
2. `PlayerRecord` — 6 fields → `stats: Map<String, Int>` with backward-compat read
3. `PlayersTable` — add `stats_json` column, read/write as JSON
4. `PlayerCreationRequest` — `stats: Map<String, Int>`
5. Flyway migration `V12__add_stats_json.sql`
6. `YamlPlayerRepository` — serialize stats map
7. `RedisCachingPlayerRepository` — JSON stats
8. Admin API — map-based stats
9. Tests: persistence round-trip

### Phase 4: Migrate world YAML ✅ Complete

**Goal:** Items use `stats:` map. Backward compat for old format.

1. `ItemFile` — add `stats: Map<String, Int>?`, keep old 6 fields
2. `WorldLoader` — read `stats` map; fall back to named fields
3. `RaceStatModsConfig` → `Map<String, Int>`
4. `StatusEffectRegistryLoader` — stat mods as map
5. Re-export zone YAML files with new format (Creator can do this)

### Phase 5: Delete StatBlock, remove backward compat ✅ Complete

**Goal:** Clean up. `StatBlock` is gone, only `StatMap` remains.

1. Remove `StatBlock` class
2. Remove bridge methods
3. Remove old named fields from `ItemFile`
4. Remove individual stat columns from `PlayersTable` (future migration)
5. Remove `RaceStatModsConfig` (replaced by `Map<String, Int>`)
6. Update all remaining `StatBlock` references in tests

### Phase 6: Web client ✅ Complete

1. Update `types.ts` — dynamic stat array
2. Update `applyGmcpPackage.ts` — handle array-based `Char.Stats`
3. Update score/stat display components to render dynamically
4. Update character creation UI if it shows stat previews

---

## Backward Compatibility Strategy

| Layer | Strategy |
|-------|----------|
| **World YAML** | `WorldLoader` reads both old named fields and new `stats:` map. Old format works indefinitely. Creator writes new format on save. |
| **Player saves (YAML)** | Read old 6-field format, write new map format. One-way migration on next save. |
| **Postgres** | New `stats_json` column alongside old columns. Read from JSON if present, else from columns. Old columns preserved until explicit migration. |
| **GMCP** | New array-based `Char.Stats` packet. Web client v4+ handles arrays. Old v3 clients would need a compat shim or forced upgrade. |
| **gRPC** | Proto messages updated. Both engine and gateway must be deployed together (already required). |

---

## Risks

| Risk | Mitigation |
|------|-----------|
| Performance: `Map<String, Int>` vs 6 fields | StatMap is a value class wrapping a small map (6-10 entries). Benchmarking needed but unlikely to matter vs. I/O costs. |
| Compile-time safety lost | Systems reference stats by string ID. Typos in bindings config caught at startup validation, not compile time. Add `validated()` checks that all binding stat IDs exist in the registry. |
| GMCP breaking change | Version the packet or support both formats during transition. Web client can detect format. |
| Massive test churn | Phases 1-2 are additive — existing tests don't break until Phase 3+. Run full suite at each phase boundary. |
| World YAML re-export diffs | Backward compat means no forced re-export. Creator writes new format only for modified files. |

---

## Verification

- `./gradlew ktlintCheck` at each phase
- Run full test suite after Phases 2, 3, 4
- Manual: create character, verify stats display, equip items, cast spells, check regen, verify GMCP in web client
- Round-trip test: save player → reload → verify all stats preserved
- Persistence coverage test: verify all stat fields survive YAML/Redis/Postgres round-trip
- Config validation test: binding references non-existent stat → startup error
