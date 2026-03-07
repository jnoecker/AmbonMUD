# Hardcoded Config Extraction Plan

## What This Document Is

An audit of every value that a world designer or balance tuner might want to change but
currently cannot without editing Kotlin source code. Each item is assessed for whether it
is **already configurable**, **worth extracting**, or **intentionally fixed**.

Instructions for content creators (the Arcanum) on what the new `application.yaml` keys
look like are in the **YAML Changes** section of each item.

---

## Status: Mob Tiers

**Already fully data-driven.** No action needed.

All four tiers (`weak`, `standard`, `elite`, `boss`) are configurable today under
`ambonmud.engine.mob.tiers` in `application.yaml`. Values are already present and
documented there.

---

## Remaining Hardcodes

Three genuine hardcodes remain after all phases of PR #589.

---

### 1. Level-1 HP and Mana Floors

**What is hardcoded:**
`PlayerState.BASE_MAX_HP = 10` and `PlayerState.BASE_MANA = 20`.

These are the minimum HP and mana a character can ever have — the value returned when
`maxResourceForLevel(level=1, ...)` is called. A level-1 Warrior with no CON bonus gets
exactly 10 HP. The per-level values (`hpPerLevel`, `manaPerLevel`) sit in
`LevelRewardsConfig` already, but the level-1 floor is a Kotlin constant.

**Proposed location:** Add two fields to the existing `LevelRewardsConfig` block.

**New `application.yaml` keys** (under `ambonmud.engine.progression.rewards`):

```yaml
ambonmud:
  engine:
    progression:
      rewards:
        hpPerLevel: 2           # existing
        manaPerLevel: 5         # existing
        fullHealOnLevelUp: true # existing
        fullManaOnLevelUp: true # existing
        baseHp: 10              # NEW — HP at level 1 before class/stat scaling
        baseMana: 20            # NEW — Mana at level 1 before class/stat scaling
```

**What changes for the Arcanum:**

| Key | Default | Effect |
|-----|---------|--------|
| `rewards.baseHp` | `10` | The HP floor every new character starts with, regardless of class or CON. Raising this makes low-level play feel less punishing. |
| `rewards.baseMana` | `20` | The mana floor. Raising this lets all classes cast more at level 1. |

These interact with per-class `hpPerLevel`/`manaPerLevel` and the CON/INT scaling
divisors — the total HP at level N is `baseHp + (N-1)*hpPerLevel + conBonus`.

---

### 2. Starting Gold

**What is hardcoded:**
New characters always begin with 0 gold. `PlayerCreationRequest` has no gold field;
`toNewPlayerRecord()` always sets `gold = 0L`.

**Proposed location:** A new `CharacterCreationConfig` nested inside `EngineConfig`, or
a single field added directly to `EngineConfig`. The single-field approach is simpler and
avoids over-engineering a one-value config.

**New `application.yaml` keys** (under `ambonmud.engine`):

```yaml
ambonmud:
  engine:
    characterCreation:
      startingGold: 0   # NEW — gold granted to a brand-new character at account creation
```

**What changes for the Arcanum:**

| Key | Default | Effect |
|-----|---------|--------|
| `characterCreation.startingGold` | `0` | Gold placed in the player's purse the moment they complete character creation. Set to a small value (e.g. `25`) to let new players buy one basic item from a shop. |

Note: This is a one-time grant at creation, not recurring. It does not affect existing
characters.

---

### 3. Warrior Threat Multiplier (class name hardcode)

**What is hardcoded:**
`CombatSystem.threatMultiplier()` contains:

```kotlin
if (player.playerClass.equals("WARRIOR", ignoreCase = true)) {
    config.threatMultiplierWarrior
} else {
    config.threatMultiplierDefault
}
```

The string `"WARRIOR"` is baked in. `CombatSystemConfig` already has two fields
(`threatMultiplierWarrior = 1.5`, `threatMultiplierDefault = 1.0`) but the class name is
not configurable. If a designer renames the warrior class or adds a second tank class,
the code must be changed.

**Proposed fix:** Move the multiplier onto `PlayerClassDef` as a new optional field,
removing both `CombatSystemConfig` fields entirely.

**New `application.yaml` keys** (under `ambonmud.engine.classes.definitions.<CLASS>`):

```yaml
ambonmud:
  engine:
    classes:
      definitions:
        WARRIOR:
          displayName: "Warrior"
          hpPerLevel: 8
          manaPerLevel: 4
          threatMultiplier: 1.5   # NEW — multiplies all threat generated in combat
        MAGE:
          displayName: "Mage"
          hpPerLevel: 4
          manaPerLevel: 16
          # threatMultiplier omitted → defaults to 1.0
        PALADIN:
          displayName: "Paladin"
          hpPerLevel: 7
          manaPerLevel: 8
          threatMultiplier: 1.2   # NEW — partial tank, partial healer
```

**What changes for the Arcanum:**

| Key | Default | Effect |
|-----|---------|--------|
| `classes.definitions.X.threatMultiplier` | `1.0` | Multiplies all threat this class generates in melee combat. Values > 1.0 cause mobs to prefer targeting this class (tank role). Values < 1.0 keep mobs from focusing this class (glass cannon role). |

This replaces the two combat config fields `combat.threatMultiplierWarrior` and
`combat.threatMultiplierDefault`, which will be removed.

---

## What Stays Hardcoded (Intentionally)

| Constant | Value | Reason |
|----------|-------|--------|
| `PlayerState.BASE_STAT = 10` | 10 | The "neutral point" for the stat bonus formula `(stat - 10) / divisor`. Making this configurable would require re-specifying it in every per-stat formula reference; the divisors in `StatBindingsConfig` already let designers control the sensitivity curve. |
| `StatRegistry.baseStat(unknown) → 0` | 0 | Returns 0 for undefined stat IDs as a sentinel. Callers that need the creation default use `statRegistry?.get(id)?.baseStat ?: PlayerState.BASE_STAT` explicitly. |
| Drop chance RNG | — | Percentage values already live on `MobDropFile.chance` per-mob in world YAML. |

---

## Implementation Plan (one PR per item, or combined)

### PR A — `baseHp` / `baseMana` in `LevelRewardsConfig`

**Files changed:**

| File | Change |
|------|--------|
| `AppConfig.kt` | Add `baseHp: Int = 10` and `baseMana: Int = 20` to `LevelRewardsConfig`. Add `require(baseHp >= 1)` and `require(baseMana >= 0)` in `validated()`. |
| `PlayerProgression.kt` | Pass `config.rewards.baseHp` and `config.rewards.baseMana` as the `baseValue` argument to `maxResourceForLevel()` instead of the `PlayerState` constants. |
| `PlayerState.kt` | Remove `BASE_MAX_HP` and `BASE_MANA` companion constants (or keep as internal fallback aliases — see note). |
| `application.yaml` | Add `baseHp: 10` and `baseMana: 20` under `progression.rewards`. |
| Tests | Update `PlayerProgressionTest` to verify custom `baseHp`/`baseMana` flows through correctly. |

> **Note on `InterEngineMessage.kt`:** `SerializedPlayerState` uses `PlayerState.BASE_MANA`
> as a default for three fields (`mana`, `maxMana`, `baseMana`). After removal, these
> should default to `20` as a literal or be sourced from the config. The handoff path does
> not have access to config at the message level, so `20` as a literal default is
> acceptable — it only affects the serialization default, not the actual persisted value.

---

### PR B — `startingGold` in `CharacterCreationConfig`

**Files changed:**

| File | Change |
|------|--------|
| `AppConfig.kt` | Add `data class CharacterCreationConfig(val startingGold: Long = 0L)` and add `val characterCreation: CharacterCreationConfig = CharacterCreationConfig()` to `EngineConfig`. Add `require(startingGold >= 0L)` in `validated()`. |
| `PlayerRegistry.kt` | Pass `config.characterCreation.startingGold` (or equivalent injected value) to `PlayerCreationRequest`, which passes it to `toNewPlayerRecord()`. |
| `PlayerRepository.kt` | Add `val gold: Long = 0L` to `PlayerCreationRequest`. Update `toNewPlayerRecord()` to use it. |
| `application.yaml` | Add `characterCreation: { startingGold: 0 }` under `engine`. |
| Tests | Add a `PlayerRegistryFactoryTest` case verifying a character created with `startingGold: 25` has 25 gold. |

---

### PR C — `threatMultiplier` on `PlayerClassDef`

**Files changed:**

| File | Change |
|------|--------|
| `PlayerClassDef.kt` | Add `val threatMultiplier: Double = 1.0`. |
| `AppConfig.kt` | Add `val threatMultiplier: Double = 1.0` to `ClassDefinitionConfig`. Remove `threatMultiplierWarrior` and `threatMultiplierDefault` from `CombatEngineConfig`. Add `require(def.threatMultiplier >= 0.0)` in the class definitions loop of `validated()`. |
| `ClassEngineConfig` default | Set `WARRIOR`'s default `threatMultiplier` to `1.5` to preserve existing behavior. |
| `PlayerClassRegistryLoader.kt` | Map `defConfig.threatMultiplier` → `PlayerClassDef.threatMultiplier`. |
| `CombatSystem.kt` | Replace the `if (player.playerClass.equals("WARRIOR"...))` branch with `classRegistry?.get(player.playerClass)?.threatMultiplier ?: 1.0`. |
| `GameEngine.kt` / `MudServer.kt` | Thread `classRegistry` into `CombatSystemConfig` or inject directly into `CombatSystem`. |
| `application.yaml` | Add `threatMultiplier: 1.5` to the WARRIOR class definition. Remove `combat.threatMultiplierWarrior` / `combat.threatMultiplierDefault` keys. |
| Tests | Update `CombatSystemTest` threat tests to use a `classRegistry` with a custom `threatMultiplier`. |

---

## Suggested Sequencing

Complete **PR A** first — it is pure additive (no behavior change, just makes constants
configurable) and touches the fewest files. **PR B** is equally small. **PR C** is the
most impactful refactor because it changes how `CombatSystem` resolves threat and removes
two existing config fields, but it closes the last class-name hardcode in the engine.

All three could be bundled into a single PR if preferred; they have no dependencies on
each other.
