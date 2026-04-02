# Arcanum Sprite Instructions

This document describes the sprite system's naming conventions, file structure, and how to add new sprites using the Ambon Arcanum creator tool.

## Overview

Player sprites are images displayed in the web client to represent the player's character. Sprites are organized into four categories:

- **General sprites** — Independent sprites with flexible unlock criteria based on any combination of race, class, level, achievement, and staff status. This is the primary format for new sprites.
- **Tier sprites** (legacy) — Auto-generated race×class×level sprites. Controlled by `images.legacyTierSprites` config flag.
- **Achievement sprites** (legacy) — Unlocked by a single achievement. Still supported but new sprites should use the requirements format.
- **Staff sprites** — Available only to staff members. One image per race.

## Sprite Requirements (New Format)

New sprites use a **requirements list** with AND logic — all requirements must be met for the sprite to be unlocked. This replaces the legacy single-condition `unlock` block.

### Requirement Types

| Type | Fields | Description |
|------|--------|-------------|
| `minLevel` | `level: Int` | Player level >= value |
| `race` | `race: String` | Player race matches (e.g. "ELF") |
| `class` | `playerClass: String` | Player class matches (e.g. "MAGE") |
| `achievement` | `achievementId: String` | Player has unlocked the achievement |
| `staff` | — | Player is staff |

### Examples

**Race-only sprite** (available to all elves):
```yaml
  elven_heritage:
    displayName: "Elven Heritage"
    description: "The grace of the elder folk."
    category: general
    sortOrder: 50
    requirements:
      - type: race
        race: ELF
    image: player_sprites/elven_heritage.png
```

**Class + Level sprite** (level 20+ warriors):
```yaml
  sword_saint:
    displayName: "Sword Saint"
    description: "A warrior who has mastered the blade."
    category: general
    sortOrder: 120
    requirements:
      - type: class
        playerClass: WARRIOR
      - type: minLevel
        level: 20
    image: player_sprites/sword_saint.png
```

**Race + Class + Level sprite** (level 30+ elf mages):
```yaml
  elven_arcanist:
    displayName: "Elven Arcanist"
    description: "An elf who has mastered ancient magic."
    category: general
    sortOrder: 200
    requirements:
      - type: race
        race: ELF
      - type: class
        playerClass: MAGE
      - type: minLevel
        level: 30
    image: player_sprites/elven_arcanist.png
```

**Achievement + Level sprite**:
```yaml
  dragon_slayer:
    displayName: "Dragon Slayer"
    description: "Slew the great wyrm."
    category: general
    sortOrder: 300
    requirements:
      - type: achievement
        achievementId: combat/dragon_kill
      - type: minLevel
        level: 20
    image: player_sprites/dragon_slayer.png
```

## Single-Image vs. Variants

Most new sprites use a **single image** via the `image` shorthand field. This creates one variant with the sprite's ID as the imageId.

For sprites that need **race/class/gender-specific images**, use the `variants` list instead:

```yaml
  beast_tamer:
    displayName: "Beast Tamer"
    category: general
    sortOrder: 150
    requirements:
      - type: minLevel
        level: 15
    variants:
      - imageId: beast_tamer
        displayName: "Beast Tamer"
        imagePath: player_sprites/beast_tamer.png
      - imageId: elf_beast_tamer
        displayName: "Beast Tamer (Elf)"
        race: ELF
        imagePath: player_sprites/elf_beast_tamer.png
```

When both `image` and `variants` are present, `variants` takes precedence.

## File Location

All player sprite images go in the `player_sprites/` directory under the images asset base.

## Image Specifications

- **Format:** PNG with transparency
- **Dimensions:** 64x64 pixels
- **Style:** Follow the Surreal Gentle Magic aesthetic (see `.impeccable.md`)
- **Background:** Transparent

## Legacy Tier Sprites

The auto-generated tier sprites (96 images: 4 races × 4 classes × 6 tiers) are controlled by `images.legacyTierSprites` in config (default: `true`). When ready to sunset:

1. Set `legacyTierSprites: false` in `application.yaml`
2. Ensure replacement sprites exist using the requirements format
3. Players with old `activeSprite` values gracefully fall back to auto-resolve

### Legacy tier naming convention (for reference)

Format: `{race}_{class}_{tierSuffix}.png` (e.g. `elf_mage_t20.png`)

Tier suffixes: t1, t10, t20, t30, t40, t50

## Legacy Achievement Format

The old single-condition format is still supported:

```yaml
  beetle_slayer:
    displayName: "Beetle Slayer"
    category: achievement
    sortOrder: 100
    unlock:
      type: achievement
      achievementId: combat/beetle_exterminator
    variants:
      - imageId: beetle_slayer
        imagePath: player_sprites/beetle_slayer.png
```

New sprites should use `requirements` instead.

## Current Sprites

### New-style sprites (requirements-based, in sprites.yaml)

| Sprite | Requirements | Sort |
|--------|-------------|------|
| Elven Heritage | race: ELF | 50 |
| Dwarven Resilience | race: DWARF | 50 |
| Human Adaptability | race: HUMAN | 50 |
| Halfling Luck | race: HALFLING | 50 |
| Sword Saint | class: WARRIOR, level 20+ | 120 |
| Arcane Adept | class: MAGE, level 20+ | 120 |
| Divine Servant | class: CLERIC, level 20+ | 120 |
| Shadow Dancer | class: ROGUE, level 20+ | 120 |
| Elven Arcanist | race: ELF, class: MAGE, level 30+ | 200 |
| Dwarven Bulwark | race: DWARF, class: WARRIOR, level 30+ | 200 |
| Twilight Wanderer | level 40+ | 250 |
| Dragon Slayer | achievement: combat/dragon_kill, level 20+ | 300 |

### Legacy sprites (still loaded)

| Category | Count | Notes |
|----------|-------|-------|
| Tier (auto-generated) | 96 | Gated behind `legacyTierSprites` flag |
| Staff (auto-generated) | 4 | One per race |
| Beetle Slayer (achievement) | 3 variants | Legacy unlock format |
| Spider Hunter (achievement) | 2 variants | Legacy unlock format |

## Export Checklist

When creating/exporting sprites for AmbonMUD:

1. Verify file names exactly match the `imageId` + `.png` extension (or `image` field)
2. Place all files in the `player_sprites/` directory
3. Upload to the assets CDN at `assets.ambon.dev`
4. Update `sprites.yaml` with the sprite definition
5. Test in-game with `sprite list` and `sprite set <id>` commands
