# Ability System Design (Issue #114)

> **Status: Phase 1 is fully implemented.** This document is a historical design record. The `AbilitySystem`, `AbilityRegistry`, mana persistence, `cast`/`spells` commands, and auto-learn-on-levelup are all in production. Phase 2 (status effects: DoT, HoT, buffs/debuffs) described in §11 has **not** been built yet.

> This document captures the design for AmbonMUD's spell/skill system. It is intended as a reference for implementation. The system adds tactical depth, resource management, and build diversity to combat.

## 1. Overview

Combat today is `kill <target>` and wait. The ability system adds player-activated abilities (spells and skills) gated behind the `cast` command:

```
cast fireball wolf
cast heal
```

### Phased approach

| Phase | Scope | Deliverable |
|-------|-------|-------------|
| **1 — Foundation** | Mana pool, mana regen, `cast` command, direct damage + heal, auto-learn by level, 3-4 starter abilities | Working `cast <spell> [target]` pipeline end-to-end |
| **2 — Status effects** | DoT, HoT, buff, debuff, timed effects via Scheduler | StatusEffectSystem, ticking effects, `effects` command |
| **3 — Depth** | More abilities, mob abilities, spell interrupts, resistance, equipment spell bonuses | Richer combat tactics |

This document designs Phase 1 in full detail and lays out the data model for Phase 2 so the architecture supports it from day one.

---

## 2. Resource Model: Mana

Mana mirrors HP — a numeric pool that depletes on ability use and regenerates over time.

### Mana scaling

```
maxMana(level) = baseMana + (level - 1) * manaPerLevel
```

**Suggested defaults:**

| Param | Value | Rationale |
|-------|-------|-----------|
| `baseMana` | 20 | Enough for ~2-3 casts at level 1 |
| `manaPerLevel` | 5 | Steady growth; level 10 = 65 mana |

Players start at full mana on creation and on level-up (mirrors `fullHealOnLevelUp`).

### Mana regeneration

Mana regen uses the same time-gated pattern as `RegenSystem` for HP. Rather than a separate system, extend `RegenSystem` to also tick mana:

```
manaRegenInterval = baseManaRegenIntervalMs - (intelligence * msPerIntelligence)
                    clamped to minManaRegenIntervalMs
```

For Phase 1, since we have no intelligence stat yet, mana regens at a flat rate:

```
manaRegenInterval = baseManaRegenIntervalMs   (default: 3000ms)
manaRegenAmount   = 1                          (default: 1 mana per tick)
```

This is configurable via `ambonMUD.engine.regen.mana.*` (see Section 12).

When an intelligence stat is added later, the formula naturally extends.

---

## 3. Ability Definitions

Each ability is an immutable definition loaded from config/YAML.

```kotlin
data class AbilityDefinition(
    val id: AbilityId,              // e.g., "magic_missile"
    val displayName: String,        // e.g., "Magic Missile"
    val description: String,        // e.g., "Hurls a bolt of arcane energy"
    val manaCost: Int,              // mana consumed on cast
    val cooldownMs: Long,           // per-player cooldown (0 = no cooldown)
    val levelRequired: Int,         // auto-learned at this level
    val targetType: TargetType,     // ENEMY, SELF, ALLY (Phase 2+)
    val effect: AbilityEffect,      // what happens on cast
)

@JvmInline
value class AbilityId(val value: String)

enum class TargetType {
    ENEMY,  // requires mob target keyword
    SELF,   // no target needed
}

sealed interface AbilityEffect {
    /** Deal direct damage to the target mob. */
    data class DirectDamage(
        val minDamage: Int,
        val maxDamage: Int,
    ) : AbilityEffect

    /** Heal the caster. */
    data class DirectHeal(
        val minHeal: Int,
        val maxHeal: Int,
    ) : AbilityEffect

    // --- Phase 2 (designed now, built later) ---

    /** Damage over time applied to a mob. */
    data class DamageOverTime(
        val damagePerTick: Int,
        val tickIntervalMs: Long,
        val durationMs: Long,
    ) : AbilityEffect

    /** Heal over time applied to the caster. */
    data class HealOverTime(
        val healPerTick: Int,
        val tickIntervalMs: Long,
        val durationMs: Long,
    ) : AbilityEffect

    /** Stat modifier applied to a target (buff or debuff). */
    data class StatModifier(
        val stat: ModifiableStat,
        val amount: Int,            // positive = buff, negative = debuff
        val durationMs: Long,
    ) : AbilityEffect
}

enum class ModifiableStat {
    MAX_HP, ARMOR, DAMAGE, CONSTITUTION,
    // future: INTELLIGENCE, MANA_REGEN, etc.
}
```

### Message templates

Each ability definition should include customizable message strings. Suggested fields:

```kotlin
val castMessage: String,       // "You cast $spell on $target!"
val targetMessage: String,     // "$caster casts $spell on you!" (Phase 2, PvP)
val roomMessage: String,       // "$caster casts $spell!" (room broadcast)
val effectMessage: String,     // "Your fireball hits $target for $damage damage."
```

For Phase 1 we can use sensible defaults derived from the ability name, and add per-ability customization when the ability count grows.

---

## 4. Ability Registry

`AbilityRegistry` is a simple lookup structure, analogous to how mob tiers work in config:

```kotlin
class AbilityRegistry {
    private val abilities = mutableMapOf<AbilityId, AbilityDefinition>()

    fun register(ability: AbilityDefinition)
    fun get(id: AbilityId): AbilityDefinition?
    fun findByKeyword(keyword: String): AbilityDefinition?  // case-insensitive prefix/substring match
    fun abilitiesForLevel(level: Int): List<AbilityDefinition>  // all abilities with levelRequired <= level
    fun all(): Collection<AbilityDefinition>
}
```

### Where abilities are defined

For Phase 1, ability definitions live in `application.yaml` under `ambonMUD.engine.abilities`. This keeps them config-driven and consistent with how mob tiers work. When we later want zone-specific abilities (e.g., a spell only learnable in a particular zone), we can extend the YAML zone format.

```yaml
ambonMUD:
  engine:
    abilities:
      definitions:
        magic_missile:
          displayName: "Magic Missile"
          description: "Hurls a bolt of arcane energy at your foe."
          manaCost: 8
          cooldownMs: 0
          levelRequired: 1
          targetType: ENEMY
          effect:
            type: DIRECT_DAMAGE
            minDamage: 4
            maxDamage: 8
        heal:
          displayName: "Heal"
          description: "Mend your wounds with restorative magic."
          manaCost: 10
          cooldownMs: 5000
          levelRequired: 1
          targetType: SELF
          effect:
            type: DIRECT_HEAL
            minHeal: 6
            maxHeal: 10
        fireball:
          displayName: "Fireball"
          description: "Engulf your enemy in flames."
          manaCost: 15
          cooldownMs: 3000
          levelRequired: 5
          targetType: ENEMY
          effect:
            type: DIRECT_DAMAGE
            minDamage: 8
            maxDamage: 16
        mend:
          displayName: "Mend"
          description: "A powerful healing spell that restores significant health."
          manaCost: 25
          cooldownMs: 8000
          levelRequired: 10
          targetType: SELF
          effect:
            type: DIRECT_HEAL
            minHeal: 15
            maxHeal: 25
```

---

## 5. Player State Changes

### PlayerState (runtime)

Add to `src/main/kotlin/dev/ambon/engine/PlayerState.kt`:

```kotlin
data class PlayerState(
    // ... existing fields ...
    var mana: Int = BASE_MANA,
    var maxMana: Int = BASE_MANA,
    var baseMana: Int = BASE_MANA,
) {
    companion object {
        const val BASE_MAX_HP = 10
        const val BASE_MANA = 20
    }
}
```

### Player ability tracking

Ability state is tracked per-session in the `AbilitySystem` (not on `PlayerState` itself, mirroring how `CombatSystem` tracks fights and `RegenSystem` tracks regen timers):

```kotlin
// Inside AbilitySystem
private val learnedAbilities = mutableMapOf<SessionId, MutableSet<AbilityId>>()
private val cooldowns = mutableMapOf<SessionId, MutableMap<AbilityId, Long>>()  // ability → cooldown-expires-at epoch ms
```

On login, `learnedAbilities` is populated from the player's level (all abilities with `levelRequired <= player.level`). On level-up, new abilities are checked and the player is notified.

### PlayerRecord (persisted)

Add to `src/main/kotlin/dev/ambon/persistence/PlayerRecord.kt`:

```kotlin
data class PlayerRecord(
    // ... existing fields ...
    val mana: Int = 20,
    val maxMana: Int = 20,
)
```

Cooldowns are **not** persisted — they reset on login. Learned abilities are derived from level, so they don't need persistence either. If we later add trainer-based learning, we'd persist a `learnedAbilities: Set<String>` field.

---

## 6. Persistence Impact

### YAML backend

`PlayerRecord` is serialized to YAML. Adding `mana` and `maxMana` fields with defaults means existing player files load cleanly (Jackson defaults handle missing fields).

### Postgres backend

New Flyway migration: `V<next>__add_player_mana.sql`

```sql
ALTER TABLE players
    ADD COLUMN mana       INTEGER NOT NULL DEFAULT 20,
    ADD COLUMN max_mana   INTEGER NOT NULL DEFAULT 20;
```

Update `PlayersTable.kt` to include the new columns in read/write operations.

### Persistence chain

`WriteCoalescingPlayerRepository` → `RedisCachingPlayerRepository` → `YamlPlayerRepository`/`PostgresPlayerRepository`

All three layers just pass through the `PlayerRecord` data class, so adding fields to `PlayerRecord` propagates automatically. The `toRecord()`/`fromRecord()` mapping functions in `PlayerRegistry` need updating to include mana.

---

## 7. Command Parsing

### New commands

Add to `CommandParser.kt`:

```kotlin
sealed interface Command {
    // ... existing ...

    data class Cast(
        val spellName: String,
        val target: String?,     // null for self-targeted spells
    ) : Command

    data object Spells : Command  // list known spells
}
```

### Parsing rules

```
cast <spell> <target>   → Command.Cast(spell, target)
cast <spell>            → Command.Cast(spell, null)
c <spell> <target>      → Command.Cast(spell, target)    (alias)
c <spell>               → Command.Cast(spell, null)      (alias)
spells                  → Command.Spells
abilities               → Command.Spells                  (alias)
```

Implementation in `CommandParser.parse()`:

```kotlin
// cast / c
matchPrefix(line, listOf("cast", "c")) { rest ->
    if (rest.isEmpty()) return@matchPrefix Command.Invalid(line, "cast <spell> [target]")
    val parts = rest.split(Regex("\\s+"), limit = 2)
    val spellName = parts[0]
    val target = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
    Command.Cast(spellName, target)
}?.let { return it }
```

Note: `c` is a short alias. This does **not** conflict with any existing commands (movement uses `n/s/e/w/u/d`, not `c`).

### Help text update

Add to the help output in `CommandRouter`:

```
cast/c <spell> [target]
spells/abilities
```

---

## 8. Engine Integration

### AbilitySystem class

```kotlin
class AbilitySystem(
    private val players: PlayerRegistry,
    private val mobs: MobRegistry,
    private val registry: AbilityRegistry,
    private val outbound: OutboundBus,
    private val combat: CombatSystem,
    private val clock: Clock,
    private val rng: Random = Random(),
    private val progression: PlayerProgression,
    private val metrics: GameMetrics = GameMetrics.noop(),
) {
    private val learnedAbilities = mutableMapOf<SessionId, MutableSet<AbilityId>>()
    private val cooldowns = mutableMapOf<SessionId, MutableMap<AbilityId, Long>>()

    /** Called when a player logs in or levels up. */
    fun syncAbilities(sessionId: SessionId, level: Int)

    /** Handle "cast <spell> [target]". Returns error string or null on success. */
    suspend fun cast(sessionId: SessionId, spellName: String, targetKeyword: String?): String?

    /** List known spells for a player. */
    fun knownAbilities(sessionId: SessionId): List<AbilityDefinition>

    /** Clean up on disconnect. */
    fun onPlayerDisconnected(sessionId: SessionId)

    /** Remap session on takeover. */
    fun remapSession(oldSid: SessionId, newSid: SessionId)
}
```

### Cast flow

```
1. Resolve ability: registry.findByKeyword(spellName) → AbilityDefinition?
   → "You don't know a spell called '$spellName'." if not found or not learned

2. Check mana: player.mana >= ability.manaCost
   → "Not enough mana. ($current/$cost)" if insufficient

3. Check cooldown: cooldowns[sid][abilityId] <= clock.millis()
   → "Fireball is on cooldown (Xs remaining)." if still cooling

4. Resolve target (if ENEMY):
   a. targetKeyword required → "Cast fireball on whom?" if missing
   b. Find mob in room (same matching as CombatSystem.findMobsInRoom)
   → "You don't see '$target' here." if not found

5. Deduct mana: player.mana -= ability.manaCost

6. Set cooldown: cooldowns[sid][abilityId] = clock.millis() + ability.cooldownMs

7. Apply effect:
   - DirectDamage: roll damage, apply to mob (respecting mob armor),
     send messages, check mob death (delegate to CombatSystem or replicate kill logic)
   - DirectHeal: roll heal amount, apply to player (capped at maxHp), send message

8. Send prompt
```

### Spell damage and combat interaction

When a player casts a damage spell on a mob:
- If not already in combat, **do not** auto-start a combat fight. The spell deals its damage but doesn't initiate auto-attack. The player can choose to follow up with `kill` or another `cast`.
- If already in combat with that mob, the spell damage is applied immediately (in addition to the normal combat tick damage).
- If the spell kills the mob, handle death the same way `CombatSystem.handleMobDeath` does (XP, drops, room broadcast). To avoid duplicating that logic, expose a `handleSpellKill(sessionId, mob)` method on `CombatSystem` or extract the mob-death logic into a shared helper.

This means spells can be used as an opening strike (tactical) or as supplemental damage during combat.

### GameEngine tick loop

The `AbilitySystem` does not need its own tick in the game loop for Phase 1 — casts are immediate actions triggered by commands. Mana regen is handled by `RegenSystem`.

However, for future Phase 2 (DoT/HoT effects), the `StatusEffectSystem` will need a tick slot. The natural position is after `combatSystem.tick()` and before `regenSystem.tick()`:

```
mobSystem.tick()
combatSystem.tick()
statusEffectSystem.tick()   // Phase 2
regenSystem.tick()          // includes mana regen from Phase 1
scheduler.runDue()
```

### GameEngine wiring

In `GameEngine`:
- Construct `AbilityRegistry` from config, populate with definitions
- Construct `AbilitySystem` with dependencies
- Pass `AbilitySystem` to `CommandRouter`
- Call `abilitySystem.syncAbilities()` on successful login (in `finalizeSuccessfulLogin`)
- Call `abilitySystem.onPlayerDisconnected()` in disconnect handler
- Call `abilitySystem.remapSession()` in takeover handler

---

## 9. Combat Integration

### CombatSystem changes

Add a method for ability-initiated mob kills:

```kotlin
/** Handle mob death from a spell. Reuses drop/XP/broadcast logic. */
suspend fun handleSpellKill(killerSessionId: SessionId, mob: MobState) {
    // End any existing fight with this mob
    val fight = fightsByMob[mob.id]
    if (fight != null) endFight(fight)

    // Reuse existing death handling
    mobs.remove(mob.id)
    onMobRemoved(mob.id)
    items.dropMobItemsToRoom(mob.id, mob.roomId)
    rollDrops(mob)
    broadcastToRoom(mob.roomId, "${mob.name} dies.")
    grantKillXp(killerSessionId, mob)
}
```

Alternatively, extract `handleMobDeath` into a shared function that both the combat tick and spell kills can call.

### Casting during combat

- Allowed: you can cast while in combat (adds tactical depth)
- The `cast` command doesn't conflict with the combat tick — the combat system ticks automatically, spells are manually triggered
- A spell cast doesn't reset the combat tick timer

### Casting to initiate

- `cast fireball wolf` when not in combat: deals spell damage only, does NOT start auto-attack
- Player must explicitly `kill wolf` to start auto-attack if desired
- This allows "kiting" gameplay: cast → move → cast

---

## 10. Auto-Learn System

Players automatically know all abilities with `levelRequired <= player.level`.

### On login

```kotlin
// In AbilitySystem.syncAbilities():
val known = registry.abilitiesForLevel(player.level).map { it.id }.toMutableSet()
learnedAbilities[sessionId] = known
```

### On level-up

In `PlayerProgression.grantXp()` or in the calling code (CombatSystem/AbilitySystem), after a level-up:

```kotlin
val newAbilities = registry.abilitiesForLevel(newLevel)
    .filter { it.id !in learnedAbilities[sessionId].orEmpty() }

for (ability in newAbilities) {
    learnedAbilities[sessionId]?.add(ability.id)
    outbound.send(OutboundEvent.SendText(sessionId,
        "You have learned ${ability.displayName}!"))
}
```

The cleanest integration point is a callback or return value from the level-up path, so the ability system can react without tight coupling. Alternatively, `AbilitySystem.syncAbilities()` can be called after any XP grant and it will diff against current known abilities.

---

## 11. Status Effect System (Phase 2 — Design Only)

This section documents the architecture so Phase 1 code doesn't paint us into a corner.

### Data model

```kotlin
data class StatusEffect(
    val id: String,                     // unique instance ID
    val sourceAbilityId: AbilityId,     // which ability created this
    val targetType: EffectTargetType,   // PLAYER or MOB
    val targetId: Long,                 // SessionId or MobId value
    val effect: AbilityEffect,          // the effect definition (DoT, HoT, StatModifier)
    val appliedAtMs: Long,              // when it was applied
    val expiresAtMs: Long,              // when it expires
    val nextTickAtMs: Long,             // for periodic effects
)

enum class EffectTargetType { PLAYER, MOB }
```

### StatusEffectSystem

```kotlin
class StatusEffectSystem(
    private val players: PlayerRegistry,
    private val mobs: MobRegistry,
    private val outbound: OutboundBus,
    private val clock: Clock,
) {
    private val activeEffects = mutableListOf<StatusEffect>()

    fun apply(effect: StatusEffect)
    fun removeExpired()
    fun effectsOn(sessionId: SessionId): List<StatusEffect>
    fun effectsOn(mobId: MobId): List<StatusEffect>

    suspend fun tick(maxPerTick: Int = 50): Int {
        // Tick periodic effects (DoT/HoT)
        // Remove expired effects
        // Restore stat modifiers on expiry
    }
}
```

### Stat modifier stacking

For Phase 2, stat modifiers (buffs/debuffs) will need stacking rules:
- **Same ability, same target:** refresh duration (don't stack)
- **Different abilities, same stat:** stack additively (up to a cap)

### Integration points reserved

- `PlayerState`: no changes needed now — stat modifiers apply transiently via the effect system
- `MobState`: same — effects tracked externally
- `GameEngine` tick loop: slot reserved between combat and regen

---

## 12. Configuration

Add to `AppConfig.kt`:

```kotlin
data class EngineConfig(
    val mob: MobEngineConfig = MobEngineConfig(),
    val combat: CombatEngineConfig = CombatEngineConfig(),
    val regen: RegenEngineConfig = RegenEngineConfig(),
    val scheduler: SchedulerEngineConfig = SchedulerEngineConfig(),
    val abilities: AbilityEngineConfig = AbilityEngineConfig(),  // NEW
)

data class AbilityEngineConfig(
    val definitions: Map<String, AbilityDefinitionConfig> = emptyMap(),
)

data class AbilityDefinitionConfig(
    val displayName: String = "",
    val description: String = "",
    val manaCost: Int = 10,
    val cooldownMs: Long = 0L,
    val levelRequired: Int = 1,
    val targetType: String = "ENEMY",
    val effect: AbilityEffectConfig = AbilityEffectConfig(),
)

data class AbilityEffectConfig(
    val type: String = "DIRECT_DAMAGE",
    val minDamage: Int = 0,
    val maxDamage: Int = 0,
    val minHeal: Int = 0,
    val maxHeal: Int = 0,
    // Phase 2: damagePerTick, tickIntervalMs, durationMs, stat, amount
)
```

Extend `RegenEngineConfig`:

```kotlin
data class RegenEngineConfig(
    // ... existing HP regen fields ...
    val mana: ManaRegenConfig = ManaRegenConfig(),
)

data class ManaRegenConfig(
    val baseIntervalMillis: Long = 3_000L,
    val minIntervalMillis: Long = 1_000L,
    val regenAmount: Int = 1,
)
```

Extend `ProgressionConfig` or `LevelRewardsConfig`:

```kotlin
data class LevelRewardsConfig(
    val hpPerLevel: Int = 2,
    val manaPerLevel: Int = 5,           // NEW
    val fullHealOnLevelUp: Boolean = true,
    val fullManaOnLevelUp: Boolean = true, // NEW
)
```

### Validation

Add to `AppConfig.validated()`:

```kotlin
engine.abilities.definitions.forEach { (key, def) ->
    require(def.manaCost >= 0) { "ability '$key' manaCost must be >= 0" }
    require(def.cooldownMs >= 0L) { "ability '$key' cooldownMs must be >= 0" }
    require(def.levelRequired >= 1) { "ability '$key' levelRequired must be >= 1" }
}
require(engine.regen.mana.baseIntervalMillis > 0L)
require(engine.regen.mana.minIntervalMillis > 0L)
require(engine.regen.mana.regenAmount > 0)
require(progression.rewards.manaPerLevel >= 0)
```

---

## 13. Starter Abilities

| Ability | Level | Mana | Cooldown | Effect | Rationale |
|---------|-------|------|----------|--------|-----------|
| **Magic Missile** | 1 | 8 | 0s | 4-8 damage | Reliable opener; no cooldown means mana is the gate |
| **Heal** | 1 | 10 | 5s | 6-10 heal (self) | Essential survival tool; cooldown prevents spam |
| **Fireball** | 5 | 15 | 3s | 8-16 damage | Power spike at level 5; cooldown + cost balance |
| **Mend** | 10 | 25 | 8s | 15-25 heal (self) | Strong heal for mid-game; expensive |

### Balance notes

- At level 1 (20 mana): ~2 Magic Missiles or 1 Missile + 1 Heal before OOM
- At level 5 (40 mana): Fireball + Magic Missile + Heal before OOM
- At level 10 (65 mana): Mend + Fireball + Magic Missile + spare
- Mana regen at 1/3s = 20 mana/min baseline; a full pool refills in ~1-3 minutes depending on level
- Spell damage bypasses mob armor (spells are magical) — this makes spells clearly better than auto-attack against armored mobs, giving them a distinct tactical niche

---

## 14. Score Command Update

Update the `score` display in `CommandRouter` to show mana:

```
[ PlayerName — Level 5 Adventurer ]
  HP  : 18 / 20      XP : 150 / 400
  Mana: 35 / 40
  Dmg : 3–6           Armor: +2 (head: a leather cap)
```

And when listing spells (`spells` command):

```
Known spells:
  Magic Missile  — 8 mana, no cooldown — Hurls a bolt of arcane energy at your foe.
  Heal           — 10 mana, 5s cooldown — Mend your wounds with restorative magic.
  Fireball       — 15 mana, 3s cooldown — Engulf your enemy in flames.
```

---

## 15. Testing Strategy

Follow the existing `CombatSystemTest` pattern: `MutableClock`, `InMemoryPlayerRepository`, `LocalOutboundBus`, fixed `Random` seed for deterministic rolls.

### AbilitySystemTest cases

| Test | Validates |
|------|-----------|
| `cast damage spell reduces mob hp` | Basic damage pipeline |
| `cast heal spell restores player hp` | Basic heal pipeline |
| `cast with insufficient mana returns error` | Mana gating |
| `cast on cooldown returns error with remaining time` | Cooldown enforcement |
| `cast damage spell that kills mob grants xp and drops` | Spell kill → death handling |
| `cast enemy spell without target in combat uses current target` | UX convenience (optional) |
| `cast enemy spell without target outside combat returns error` | Requires explicit target |
| `cast unknown spell returns error` | Unknown spell handling |
| `cast unlearned spell returns error` | Level gating |
| `mana regenerates over time` | Mana regen |
| `level up grants new abilities and notifies player` | Auto-learn system |
| `level up increases max mana` | Mana scaling |
| `spell damage bypasses mob armor` | Armor bypass rule |
| `disconnect cleans up ability state` | Session cleanup |

### CommandParserTest additions

| Test | Validates |
|------|-----------|
| `cast fireball wolf parses correctly` | Two-arg cast |
| `cast heal parses correctly` | One-arg cast (no target) |
| `c fireball wolf parses correctly` | Alias |
| `cast with no args returns Invalid` | Error case |
| `spells parses correctly` | Spells list command |
| `abilities parses correctly` | Alias |

---

## 16. Files to Create/Modify

### New files

| File | Purpose |
|------|---------|
| `src/main/kotlin/dev/ambon/engine/abilities/AbilityDefinition.kt` | Data model: `AbilityDefinition`, `AbilityEffect`, `AbilityId`, `TargetType` |
| `src/main/kotlin/dev/ambon/engine/abilities/AbilityRegistry.kt` | Ability lookup |
| `src/main/kotlin/dev/ambon/engine/abilities/AbilitySystem.kt` | Cast handling, cooldowns, learned abilities |
| `src/test/kotlin/dev/ambon/engine/abilities/AbilitySystemTest.kt` | Unit tests |
| `src/main/resources/db/migration/V<next>__add_player_mana.sql` | Postgres migration |

### Modified files

| File | Change |
|------|--------|
| `src/main/kotlin/dev/ambon/engine/PlayerState.kt` | Add `mana`, `maxMana`, `baseMana` |
| `src/main/kotlin/dev/ambon/persistence/PlayerRecord.kt` | Add `mana`, `maxMana` |
| `src/main/kotlin/dev/ambon/persistence/PlayersTable.kt` | Add mana columns |
| `src/main/kotlin/dev/ambon/engine/commands/CommandParser.kt` | Add `Cast`, `Spells` commands |
| `src/main/kotlin/dev/ambon/engine/commands/CommandRouter.kt` | Handle `Cast`, `Spells`; update help text and score display |
| `src/main/kotlin/dev/ambon/engine/GameEngine.kt` | Wire `AbilitySystem`; call sync on login, cleanup on disconnect |
| `src/main/kotlin/dev/ambon/engine/CombatSystem.kt` | Add `handleSpellKill()` or extract shared mob-death helper |
| `src/main/kotlin/dev/ambon/engine/RegenSystem.kt` | Add mana regen alongside HP regen |
| `src/main/kotlin/dev/ambon/engine/PlayerProgression.kt` | Add `maxManaForLevel()`, mana scaling on level-up |
| `src/main/kotlin/dev/ambon/engine/PlayerRegistry.kt` | Map mana fields in `toRecord()`/`fromRecord()` |
| `src/main/kotlin/dev/ambon/config/AppConfig.kt` | Add `AbilityEngineConfig`, `ManaRegenConfig`, mana rewards |
| `src/main/resources/application.yaml` | Add ability definitions and mana config defaults |
| `src/test/kotlin/dev/ambon/engine/commands/CommandParserTest.kt` | Test cast/spells parsing |

---

## 17. Open Questions for Implementation

These can be resolved during implementation:

1. **Armor bypass:** Should spell damage fully bypass mob armor (recommended) or partially?
2. **Cast in combat convenience:** Should `cast fireball` (no target) auto-target the mob you're fighting? (Probably yes for UX.)
3. **Mana on prompt:** Should the game prompt show current mana alongside HP? (Probably yes once mana exists.)
4. **Spell fail messages:** Exact wording for error messages (can be iterated).
5. **`c` alias safety:** Confirm `c` doesn't conflict with any planned future single-letter commands.
