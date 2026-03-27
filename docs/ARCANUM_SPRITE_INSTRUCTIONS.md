# Arcanum Sprite Instructions

This document describes the sprite system's naming conventions, file structure, and how to add new sprites using the Ambon Arcanum creator tool.

## Overview

Player sprites are images displayed in the web client to represent the player's character. Sprites are organized into three categories:

- **Tier sprites** — Unlocked by reaching level thresholds (1, 10, 20, 30, 40, 50). One image per race/class combination.
- **Achievement sprites** — Unlocked by earning specific achievements. May have race, class, and/or gender variants.
- **Staff sprites** — Available only to staff members. One image per race.

## File Location

All player sprite images go in the `player_sprites/` directory under the images asset base.

## Naming Conventions

### Tier Sprites (auto-generated, existing pattern)

Format: `{race}_{class}_{tierSuffix}.png`

| Level | Tier Suffix | Tier Name |
|-------|-------------|-----------|
| 1+    | `t1`        | Novice |
| 10+   | `t10`       | Apprentice |
| 20+   | `t20`       | Journeyman |
| 30+   | `t30`       | Expert |
| 40+   | `t40`       | Master |
| 50+   | `t50`       | Legend |

Races: `human`, `elf`, `dwarf`, `halfling`
Classes: `warrior`, `mage`, `cleric`, `rogue`

Examples:
- `elf_mage_t1.png` — Elf Mage, Novice tier
- `human_warrior_t50.png` — Human Warrior, Legend tier
- `dwarf_cleric_t20.png` — Dwarf Cleric, Journeyman tier

Total tier sprites: 4 races x 4 classes x 6 tiers = **96 images**

### Staff Sprites (auto-generated, existing pattern)

Format: `{race}_base_tstaff.png`

Examples:
- `elf_base_tstaff.png`
- `human_base_tstaff.png`
- `dwarf_base_tstaff.png`
- `halfling_base_tstaff.png`

Total staff sprites: **4 images** (one per race). Staff can choose any staff sprite regardless of their own race.

### Achievement Sprites (custom, defined in sprites.yaml)

Achievement sprites use a **template naming** system. Each sprite has a template name (e.g. `beetle_slayer`), and variants are created by prefixing with qualifiers:

| Variant Type | Format | Example |
|-------------|--------|---------|
| Generic (any race/class) | `{template}.png` | `beetle_slayer.png` |
| Race-specific | `{race}_{template}.png` | `elf_beetle_slayer.png` |
| Class-specific | `{class}_{template}.png` | `rogue_spider_hunter.png` |
| Race+Class | `{race}_{class}_{template}.png` | `elf_mage_beetle_slayer.png` |

Players see only the variants that match their own race/class/gender, plus any generic variants.

## Adding New Achievement Sprites

### Step 1: Create the images

Create one or more variant images following the naming convention above. At minimum, create a generic variant.

### Step 2: Update sprites.yaml

Add a new entry to `src/main/resources/world/sprites.yaml`:

```yaml
  golem_breaker:
    displayName: "Golem Breaker"
    category: achievement
    sortOrder: 102
    unlock:
      type: achievement
      achievementId: combat/secret_slayer
    variants:
      - imageId: golem_breaker
        displayName: "Golem Breaker"
        imagePath: player_sprites/golem_breaker.png
      - imageId: dwarf_golem_breaker
        displayName: "Golem Breaker (Dwarf)"
        race: DWARF
        imagePath: player_sprites/dwarf_golem_breaker.png
```

### Variant Fields

| Field | Required | Description |
|-------|----------|-------------|
| `imageId` | Yes | Unique identifier. Also used in `sprite set <id>` command. Must match file name stem. |
| `displayName` | No | Defaults to the parent definition's displayName. |
| `race` | No | Race filter (uppercase). If set, only players of this race see the variant. |
| `playerClass` | No | Class filter (uppercase). If set, only players of this class see the variant. |
| `gender` | No | Gender filter (lowercase). If set, only players of this gender see the variant. |
| `imagePath` | Yes | Path relative to the images base URL. Always starts with `player_sprites/`. |

### Unlock Types

| Type | Fields | Description |
|------|--------|-------------|
| `achievement` | `achievementId` | Unlocked when the player earns the specified achievement. |
| `level` | `minLevel` | Unlocked when the player reaches the specified level. |
| `staff` | — | Unlocked for staff members only. |

## Image Specifications

- **Format:** PNG with transparency
- **Dimensions:** 64x64 pixels (displayed at this size in the character panel)
- **Style:** Follow the Surreal Gentle Magic aesthetic (see `.impeccable.md`)
- **Background:** Transparent

## Current Placeholder Sprites

The following achievement sprites are defined in `sprites.yaml` but need images created:

| Template | Achievement | Variants Needed |
|----------|-------------|-----------------|
| `beetle_slayer` | `combat/beetle_exterminator` | generic, elf, dwarf |
| `spider_hunter` | `combat/spider_hunter` | generic, rogue |

## Export Checklist

When creating/exporting sprites for AmbonMUD:

1. Verify file names exactly match the `imageId` + `.png` extension
2. Place all files in the `player_sprites/` directory
3. Upload to the assets CDN at `assets.ambon.dev`
4. If adding new achievement sprites, update `sprites.yaml` with variant definitions
5. Test in-game with `sprite list` and `sprite set <id>` commands
