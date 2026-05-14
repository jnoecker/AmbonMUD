# Arcanum ↔ AmbonMUD Tuning Sync

The [AmbonArcanum](https://github.com/jpnoecker/AmbonArcanum) creator app ships a
**Tuning Wizard** (`creator/src/components/tuning/TuningWizard.tsx`) with six themed
presets — Casual, Balanced, Hardcore, Solo Story, PvP Arena, and Lore Explorer —
expressed as `DeepPartial<AppConfig>` overlays in `creator/src/lib/tuning/presets.ts`.

The AmbonMUD server's defaults are kept in lockstep with the **Balanced** preset so
operators can dial up or down from a known anchor. This doc records the wire-up
points so the two repos don't drift.

## Source of truth

For the fields that overlap, treat the AmbonArcanum `BALANCED_PRESET` overlay as the
**reference values** and the AmbonMUD `application.yaml` (under `ambonmud.engine`) as the
**deployment values**. Kotlin defaults in `AppConfig.kt` exist as a fallback for tests and
dev runs without a config file; they should not drift far from the YAML.

## Synced surfaces

| AmbonMUD location | AmbonArcanum location |
|---|---|
| `engine.mob.tiers.{weak,standard,elite,boss}` in `application.yaml` | `BALANCED_PRESET.config.mobTiers` |
| `MobTierConfig` defaults in `AppConfig.kt` | `BALANCED_PRESET.config.mobTiers` |
| `engine.combat.{tickMillis,minDamage,maxDamage}` | `BALANCED_PRESET.config.combat` |
| `engine.stats.bindings.*` divisors | `BALANCED_PRESET.config.stats.bindings` |
| `engine.regen.*` and `engine.regen.mana.*` | `BALANCED_PRESET.config.regen` |
| `engine.economy.{buyMultiplier,sellMultiplier}` | `BALANCED_PRESET.config.economy` |
| `engine.progression.{xp,rewards,quests}` | `BALANCED_PRESET.config.progression` |

When any of these are touched on either side, mirror the change in the other repo and
update this doc.

## New mob shorthand: multipliers

AmbonMUD mob YAML now accepts four optional multiplier fields alongside `tier` and `level`:

```yaml
mobs:
  forest_goblin:
    name: a forest goblin
    tier: standard
    level: 5
    dmgMult: 1.15   # "hits a bit harder than baseline standard-5"
    xpMult: 1.0
    goldMult: 1.5   # rewards more gold than a typical standard mob
```

The multipliers apply to tier × level baselines; absolute overrides
(`hp:`, `minDamage:`, etc.) still win and bypass the multiplier for that field.

The Arcanum builder UI does not surface these yet. To mirror this on the Arcanum
side, the suggested next step is to add multiplier inputs to the mob editor and have
the preview math run them through `resolveMobStats`-equivalent logic
(`creator/src/lib/tuning/formulas.ts` already implements the tier + level part).

## New item power-budget system

AmbonMUD items can declare a `level:` and `rarity:` to opt into a power-budget check
at world load. Budget rules live in `engine.items.budget` in `application.yaml` (see
`docs/WORLD_YAML_SPEC.md` § "Item power budget" for the formula and defaults).

There is nothing equivalent in Arcanum today. To mirror:

1. **Schema** — add `level: number | null` and `rarity: string | null` to the item type
   in `creator/src/types/world.ts`.
2. **Validator** — port `evaluateItemBudget` (`src/main/kotlin/dev/ambon/domain/world/ItemBudgetEvaluation.kt`)
   to TypeScript and run it in the Validate Zone path
   (`creator/src/lib/validateZone.ts`) so over-budget items get a warning chip in the editor.
3. **Tuning preset coverage** — extend `BALANCED_PRESET.config` with an `items.budget`
   section so operators can preview the effect of relaxing or tightening the budget
   from the Tuning Wizard. The current presets cover combat/economy/progression but
   stop at mob tiers; item budgets are the natural next pillar.

## Smoke check

Whenever defaults change on either side, run:

```bash
# AmbonMUD
./gradlew test --tests "*WorldLoaderTest*" --tests "*MobStatResolverTest*"
```

```bash
# AmbonArcanum
bun test creator/src/lib/tuning
```

If the AmbonMUD `MobTierConfig` defaults change, the table in
`docs/WORLD_YAML_SPEC.md` § "Tier formula" needs updating in the same commit.
