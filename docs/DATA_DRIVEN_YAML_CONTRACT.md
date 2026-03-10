# Data-Driven YAML Contract

> **Purpose:** This document specifies the YAML contract for all data-driven
> game mechanics in AmbonMUD. World builders and the Arcanum style guide should
> reference this as the authoritative schema for configurable fields.
>
> All config lives under the `ambonmud` root key in `application.yaml` unless
> otherwise noted. World YAML files live in `src/main/resources/world/`.

---

## Equipment Slots

**Config path:** `ambonmud.engine.equipment.slots`

Defines the set of equipment wear locations. Items reference these by their
lowercase slot ID in the `slot:` field.

```yaml
ambonmud:
  engine:
    equipment:
      slots:
        head:
          displayName: Head
          order: 0
        body:
          displayName: Body
          order: 1
        hand:
          displayName: Hand
          order: 2
        # — add custom slots —
        feet:
          displayName: Feet
          order: 3
        shield:
          displayName: Shield
          order: 4
        neck:
          displayName: Neck
          order: 5
        ring:
          displayName: Ring
          order: 6
        waist:
          displayName: Waist
          order: 7
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `<key>` | string | yes | Slot ID (always lowercase, no whitespace). Used in item YAML `slot:` field. |
| `displayName` | string | no | Human-readable name. Defaults to capitalized key. |
| `order` | int | no | Display ordering (ascending). Defaults to 0. |

**World YAML item reference:**
```yaml
items:
  iron_helm:
    name: an iron helm
    slot: head          # must match a configured slot key
    armor: 3
  boots_of_speed:
    name: boots of speed
    slot: feet          # custom slot
    armor: 1
```

**GMCP wire format:** `Char.Items.List` sends `equipment` as an object with
slot IDs as keys and item payloads (or null) as values. Clients should render
whatever slots the server sends — never hardcode slot names.

**Telnet commands:**
- `equipment` — lists all configured slots in display order
- `remove <slot>` — accepts any configured slot name
- `wear <item>` — auto-detects the item's slot

---

## Gender

**Config path:** `ambonmud.engine.genders`

Defines available gender options for character creation and the `gender` command.

```yaml
ambonmud:
  engine:
    genders:
      male:
        displayName: Male
      female:
        displayName: Female
      enby:
        displayName: Enby
      # — add custom genders —
      agender:
        displayName: Agender
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `<key>` | string | yes | Gender ID (lowercase). Stored on player record. |
| `displayName` | string | no | Shown in score, `gender` command output. Defaults to capitalized key. |

**Telnet commands:**
- `gender <id>` — sets gender; lists valid options on invalid input
- `score` — displays current gender displayName

**GMCP:** `Char.Name` includes `gender` field as the ID string.

---

## Achievement Categories

**Config path:** `ambonmud.engine.achievementCategories`

Defines grouping labels for achievements. Referenced in world YAML achievement
definitions.

```yaml
ambonmud:
  engine:
    achievementCategories:
      combat:
        displayName: Combat
      exploration:
        displayName: Exploration
      social:
        displayName: Social
      crafting:
        displayName: Crafting
      class:
        displayName: Class
      # — add custom categories —
      economy:
        displayName: Economy
      puzzle:
        displayName: Puzzle
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `<key>` | string | yes | Category ID (lowercase). Used in world YAML `category:` field. |
| `displayName` | string | no | Shown in achievement list UI. Defaults to capitalized key. |

**World YAML reference:**
```yaml
achievements:
  first_blood:
    name: First Blood
    category: combat     # must match a configured category key
    criteria:
      - type: kill
        count: 1
```

---

## Achievement Criterion Types

**Config path:** `ambonmud.engine.achievementCriterionTypes`

Defines how achievement progress is tracked and displayed.

```yaml
ambonmud:
  engine:
    achievementCriterionTypes:
      kill:
        displayName: Kill
        progressFormat: "{current}/{required}"
      reach_level:
        displayName: Reach Level
        progressFormat: "level {current}/{required}"
      quest_complete:
        displayName: Quest Complete
        progressFormat: "{current}/{required}"
      # — add custom criterion types —
      gold_earned:
        displayName: Gold Earned
        progressFormat: "{current}/{required} gold"
      craft_item:
        displayName: Craft Item
        progressFormat: "{current}/{required}"
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `<key>` | string | yes | Criterion type ID (lowercase). Used in world YAML `type:` field. |
| `displayName` | string | no | Human label. Defaults to capitalized key. |
| `progressFormat` | string | no | Template with `{current}` and `{required}` placeholders. |

**World YAML reference:**
```yaml
achievements:
  dragon_slayer:
    criteria:
      - type: kill          # must match a configured criterion type
        targetId: dragon
        count: 10
```

---

## Quest Objective Types

**Config path:** `ambonmud.engine.questObjectiveTypes`

Defines quest objective tracking behaviors.

```yaml
ambonmud:
  engine:
    questObjectiveTypes:
      kill:
        displayName: Kill
      collect:
        displayName: Collect
      # — add custom objective types —
      explore:
        displayName: Explore
      escort:
        displayName: Escort
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `<key>` | string | yes | Objective type ID (lowercase). Used in world YAML `type:` field. |
| `displayName` | string | no | Shown in quest log UI. |

**World YAML reference:**
```yaml
quests:
  wolves_den:
    objectives:
      - type: kill        # must match a configured objective type
        targetId: wolf
        count: 5
        description: Slay 5 wolves
```

---

## Quest Completion Types

**Config path:** `ambonmud.engine.questCompletionTypes`

Defines how quests are turned in.

```yaml
ambonmud:
  engine:
    questCompletionTypes:
      auto:
        displayName: Automatic
      npc_turn_in:
        displayName: NPC Turn-In
      # — add custom completion types —
      item_turn_in:
        displayName: Item Turn-In
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `<key>` | string | yes | Completion type ID (lowercase). Used in world YAML `completionType:` field. |
| `displayName` | string | no | Human label. |

**World YAML reference:**
```yaml
quests:
  wolves_den:
    completionType: npc_turn_in   # must match a configured completion type
    giverMobId: quest_master
```

---

## Status Effect Types

**Config path:** `ambonmud.engine.statusEffects.effectTypes`

Defines the categories of status effects and their tick behavior.

```yaml
ambonmud:
  engine:
    statusEffects:
      effectTypes:
        dot:
          displayName: Damage over Time
          tickBehavior: damage
        hot:
          displayName: Heal over Time
          tickBehavior: heal
        stat_buff:
          displayName: Stat Buff
          tickBehavior: none
        stat_debuff:
          displayName: Stat Debuff
          tickBehavior: none
        stun:
          displayName: Stun
          tickBehavior: none
          preventsActions: true
        root:
          displayName: Root
          tickBehavior: none
          preventsMovement: true
        shield:
          displayName: Shield
          tickBehavior: none
          absorbsDamage: true
        # — add custom effect types —
        blind:
          displayName: Blind
          tickBehavior: none
          reducesAccuracy: true
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `<key>` | string | yes | Effect type ID (lowercase). Used in status effect definitions. |
| `displayName` | string | no | Shown in effects list. |
| `tickBehavior` | string | no | `damage`, `heal`, or `none`. Default: `none`. |
| `preventsActions` | bool | no | Whether this effect prevents all actions (stun). Default: false. |
| `preventsMovement` | bool | no | Whether this effect prevents movement (root). Default: false. |
| `absorbsDamage` | bool | no | Whether this effect absorbs incoming damage (shield). Default: false. |

**Status effect definition reference:**
```yaml
ambonmud:
  engine:
    statusEffects:
      definitions:
        ignite:
          displayName: Ignite
          effectType: dot         # must match a configured effect type
          durationMs: 6000
          tickIntervalMs: 2000
          tickMinValue: 3
          tickMaxValue: 5
```

---

## Stack Behaviors

**Config path:** `ambonmud.engine.statusEffects.stackBehaviors`

Defines how status effect stacking works on reapplication.

```yaml
ambonmud:
  engine:
    statusEffects:
      stackBehaviors:
        refresh:
          displayName: Refresh Duration
        stack:
          displayName: Stack
        none:
          displayName: No Reapplication
        # — add custom behaviors —
        extend:
          displayName: Extend Duration
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `<key>` | string | yes | Stack behavior ID. Used in status effect definitions `stackBehavior:` field. |
| `displayName` | string | no | Human label. |

---

## Ability Target Types

**Config path:** `ambonmud.engine.abilities.targetTypes`

Defines valid targeting modes for abilities/spells.

```yaml
ambonmud:
  engine:
    abilities:
      targetTypes:
        enemy:
          displayName: Enemy
        self:
          displayName: Self
        ally:
          displayName: Ally
        # — add custom target types —
        area:
          displayName: Area of Effect
        cone:
          displayName: Cone
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `<key>` | string | yes | Target type ID (lowercase). Used in ability definitions `targetType:` field. |
| `displayName` | string | no | Shown in spell info. |

**Ability definition reference:**
```yaml
ambonmud:
  engine:
    abilities:
      definitions:
        fireball:
          displayName: Fireball
          targetType: enemy      # must match a configured target type
          manaCost: 10
```

---

## Crafting Skills

**Config path:** `ambonmud.engine.crafting.skills`

Defines available crafting and gathering skills.

```yaml
ambonmud:
  engine:
    crafting:
      skills:
        mining:
          displayName: Mining
          type: gathering
        herbalism:
          displayName: Herbalism
          type: gathering
        smithing:
          displayName: Smithing
          type: crafting
        alchemy:
          displayName: Alchemy
          type: crafting
        # — add custom skills —
        fishing:
          displayName: Fishing
          type: gathering
        tailoring:
          displayName: Tailoring
          type: crafting
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `<key>` | string | yes | Skill ID (lowercase). Used in recipe definitions and gather node configs. |
| `displayName` | string | no | Shown in skill list. Defaults to capitalized key. |
| `type` | string | yes | Either `gathering` or `crafting`. Determines command routing. |

---

## Crafting Station Types

**Config path:** `ambonmud.engine.crafting.stationTypes`

Defines the types of crafting stations that can appear in the world.

```yaml
ambonmud:
  engine:
    crafting:
      stationTypes:
        forge:
          displayName: Forge
        alchemy_table:
          displayName: Alchemy Table
        workbench:
          displayName: Workbench
        # — add custom station types —
        enchanting_table:
          displayName: Enchanting Table
        loom:
          displayName: Loom
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `<key>` | string | yes | Station type ID (lowercase). Used in world YAML room definitions and recipe configs. |
| `displayName` | string | no | Shown when player enters a room with this station. Defaults to capitalized key. |

**World YAML reference:**
```yaml
rooms:
  blacksmith:
    title: The Blacksmith's Forge
    craftingStation: forge    # must match a configured station type
```

**Recipe config reference:**
```yaml
ambonmud:
  engine:
    crafting:
      recipes:
        iron_sword:
          station: forge       # must match a configured station type
```

---

## Guild Ranks

**Config path:** `ambonmud.engine.guild.ranks`

Defines the guild rank hierarchy and permissions.

```yaml
ambonmud:
  engine:
    guild:
      ranks:
        leader:
          displayName: Leader
          level: 100
          permissions: [invite, kick, promote, demote, disband, set_motd]
        officer:
          displayName: Officer
          level: 50
          permissions: [invite, kick]
        member:
          displayName: Member
          level: 0
          permissions: []
        # — add custom ranks —
        recruit:
          displayName: Recruit
          level: -10
          permissions: []
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `<key>` | string | yes | Rank ID (lowercase). Stored on player record. |
| `displayName` | string | no | Shown in guild roster. Defaults to capitalized key. |
| `level` | int | yes | Numeric rank level. Higher = more authority. Used for permission comparisons. |
| `permissions` | list | no | List of permission strings this rank holds. Default: empty. |

**Permission strings:**
- `invite` — can invite players to the guild
- `kick` — can kick lower-ranked members
- `promote` — can promote members up to one level below own rank
- `demote` — can demote lower-ranked members
- `disband` — can disband the guild
- `set_motd` — can set the message of the day

---

## Summary of All Data-Driven Fields

| System | Config Path | World YAML Field | Default Values |
|--------|-------------|-----------------|----------------|
| Equipment Slots | `engine.equipment.slots` | item `slot:` | head, body, hand |
| Gender | `engine.genders` | — (player command) | male, female, enby |
| Achievement Categories | `engine.achievementCategories` | achievement `category:` | combat, exploration, social, crafting, class |
| Achievement Criteria | `engine.achievementCriterionTypes` | criterion `type:` | kill, reach_level, quest_complete |
| Quest Objectives | `engine.questObjectiveTypes` | objective `type:` | kill, collect |
| Quest Completion | `engine.questCompletionTypes` | quest `completionType:` | auto, npc_turn_in |
| Effect Types | `engine.statusEffects.effectTypes` | effect `effectType:` | dot, hot, stat_buff, stat_debuff, stun, root, shield |
| Stack Behaviors | `engine.statusEffects.stackBehaviors` | effect `stackBehavior:` | refresh, stack, none |
| Target Types | `engine.abilities.targetTypes` | ability `targetType:` | enemy, self, ally |
| Crafting Skills | `engine.crafting.skills` | recipe/gather `skill:` | mining, herbalism, smithing, alchemy |
| Station Types | `engine.crafting.stationTypes` | room `craftingStation:`, recipe `station:` | forge, alchemy_table, workbench |
| Guild Ranks | `engine.guild.ranks` | — (runtime) | leader, officer, member |
