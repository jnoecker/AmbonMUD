# Trainer System & Multi-Classing

Class trainers replace the old auto-learn ability system. Players now choose which abilities to unlock by spending skill points at class trainers. This document covers commands, skill points, multi-classing, ability scaling, and how to add trainer NPCs to a zone.

---

## Commands

| Command | Aliases | Description |
|---------|---------|-------------|
| `train` | `train list`, `trainer` | List abilities available from the trainer in the current room |
| `train learn <ability>` | — | Spend one skill point to learn an ability |
| `train unlock` | — | Pay gold to unlock the class taught by this trainer (multi-classing) |

---

## Skill Points

Each character earns **1 skill point every N levels** (default: every 2 levels). Skill points are never lost or reset.

```
Total skill points  = floor(level / interval)
Available points    = total - count of learned abilities
```

Each learned ability costs exactly 1 skill point. There are no other costs.

Use `score` to see your current level; use `train list` at any trainer to see your available points.

### Configuration

```yaml
ambonmud:
  engine:
    skillPoints:
      interval: 2       # 1 point per N levels (default: 2)
```

---

## Ability Level Scaling

All abilities now scale with the player's level using `damagePerLevel` and `healPerLevel` fields in `application.yaml`.

```
effectiveDamageMin = minDamage + floor(damagePerLevel × level)
effectiveDamageMax = maxDamage + floor(damagePerLevel × level)
effectiveHeal      = heal + floor(healPerLevel × level)
```

Mana cost does **not** scale — it stays flat regardless of level. This keeps resource management meaningful as players grow stronger.

### Configuring scaling in application.yaml

```yaml
abilities:
  definitions:
    fireball:
      displayName: "Fireball"
      requiredClass: "MAGE"
      levelRequired: 5
      manaCost: 22
      cooldownMs: 6000
      targetType: ENEMY
      effect:
        type: DIRECT_DAMAGE
        minDamage: 3
        maxDamage: 8
        damagePerLevel: 1.5    # +1.5 damage per level
```

---

## Classes & Multi-Classing

Every character starts with one class (set at character creation). Their original class is automatically unlocked — they can visit that class's trainer immediately.

### Unlocking additional classes

At a trainer for a **different** class, use `train unlock` to pay the gold cost and unlock that class. Once unlocked, the trainer's ability list becomes available.

```
Requirements:  Level >= multiclassMinLevel (default: 10)
               Gold >= multiclassGoldCost (default: 500)
```

Multi-classing lets players spend skill points across multiple class ability lists. HP and mana scaling always stay based on the character's **original class** — multi-classing gives ability access, not stat changes.

### Configuration

```yaml
ambonmud:
  engine:
    multiclass:
      minLevel:  10     # minimum level to unlock a second class
      goldCost:  500    # gold cost per class unlock
```

---

## Ability Assignment (requiredClass)

Every ability in `application.yaml` must have a `requiredClass` field matching one of the playable classes (`WARRIOR`, `MAGE`, `CLERIC`, `ROGUE`). This field determines which trainer teaches that ability.

```yaml
abilities:
  definitions:
    heal:
      displayName: "Heal"
      requiredClass: "CLERIC"
      ...
```

Abilities without a `requiredClass` (empty string) are not shown by any trainer and cannot be learned via the trainer system.

---

## Adding a Trainer to a Zone

A trainer requires two entries: a mob definition (the NPC) and a trainer registry binding.

### 1. Define the mob

In the `mobs:` section, add the trainer NPC. The `room` must match the trainer binding below.

```yaml
mobs:
  my_warrior_trainer:
    name: "Sergeant Crag"
    room: training_yard
    dialogue:
      root:
        text: "Type 'train list' to see what I can teach you."
        choices:
          - text: "Let me see the list."
          - text: "Goodbye."
```

### 2. Register the trainer

In the `trainers:` section, bind the trainer to a class and room:

```yaml
trainers:
  my_warrior_trainer:
    name: "Sergeant Crag"
    class: WARRIOR
    room: training_yard
    image: null           # optional portrait image filename
```

The `class` field determines which abilities are shown (all abilities with `requiredClass: WARRIOR`). The `name` in the trainer binding is used in GMCP output; keep it consistent with the mob name.

### 3. Add the room

```yaml
rooms:
  training_yard:
    title: "Training Yard"
    description: "A dusty yard with training dummies. Sergeant Crag watches with a critical eye."
    exits:
      s: town_square
```

---

## GMCP Packages

The trainer system emits two GMCP packages. Subscribe via `Core.Supports.Set`:

```json
["Trainer 1", "Char.Classes 1"]
```

### `Trainer.List`

Sent when a player uses `train` or `train list` at a trainer room.

```json
{
  "trainerId": "my_warrior_trainer",
  "name": "Sergeant Crag",
  "class": "WARRIOR",
  "image": null,
  "classUnlocked": true,
  "availableSkillPoints": 3,
  "multiclassMinLevel": 10,
  "multiclassGoldCost": 500,
  "abilities": [
    {
      "id": "power_strike",
      "name": "Power Strike",
      "description": "A powerful blow dealing extra damage.",
      "levelRequired": 1,
      "manaCost": 10,
      "cooldownMs": 5000,
      "targetType": "ENEMY",
      "effectType": "DIRECT_DAMAGE",
      "image": "/images/abilities/power_strike.png"
    }
  ]
}
```

When `classUnlocked` is `false`, `abilities` is empty. The client should show an unlock button instead.

### `Char.Classes`

Sent on login and whenever the player unlocks a new class.

```json
{
  "originalClass": "WARRIOR",
  "unlockedClasses": ["WARRIOR", "MAGE"]
}
```

---

## Migration Notes for Existing Characters

Players whose characters existed before the trainer system was introduced will have:

- `learnedAbilityIds = []` — no abilities learned yet
- `unlockedClasses = []` — auto-migrated to `{originalClass}` on first login

On their first login after the migration, they will have zero abilities but will have accumulated skill points based on their current level (`floor(level / interval)`). They should visit a trainer to choose their abilities.
