# Skill System PR Review: #115 vs #116

## Comparative Evaluation

Both PRs implement the ability/skill system described in Issue #114 with the same
design document and substantially similar architecture. They share: mana persistence
across all three backends (YAML, Postgres, Redis), a Flyway migration, `CommandParser`
extensions for `cast`/`spells`, session cleanup on disconnect, session remapping on
takeover, `score` command mana display, and config-driven ability definitions.

Below is a point-by-point comparison, followed by a recommendation and a detailed code
review of the recommended PR.

---

### Where PR #115 edges ahead

1. **Stronger config validation** — Validates `displayName.isNotBlank()`, `targetType`
   enum membership, and `effect.type` enum membership at startup. PR #116 only validates
   numeric constraints, so a misconfigured ability (`targetType: "FOE"`) would be
   silently skipped by `AbilityRegistryLoader` instead of failing fast.

2. **Better `spells` command output** — Shows live cooldown remaining per-spell and the
   player's current mana pool, giving more actionable in-game information. PR #116 only
   shows the static cooldown duration from the ability definition.

3. **Cleaner `handleSpellKill`** — Delegates to the existing private `handleMobDeath()`
   method, avoiding code duplication. PR #116 inlines all mob-death steps as a copy of
   `handleMobDeath`, creating a maintenance risk if that logic changes.

4. **Level-up ability notifications** — `syncAbilitiesAndReturnNew()` returns the display
   names of newly learned abilities, and the `onLevelUp` callback sends "You have learned
   X!" messages. PR #116's `syncAbilities()` returns newly learned definitions, but the
   return value is silently discarded in the `onLevelUp` lambda — **players never see
   level-up ability notifications**.

5. **12 unit tests** (vs 11 in #116), including an explicit test for
   `syncAbilitiesAndReturnNew`.

### Where PR #116 edges ahead

1. **Sharding/handoff support** — Adds mana fields to `HandoffManager` and
   `SerializedPlayerState`, so mana survives cross-zone handoffs. PR #115 omits
   this entirely, meaning **mana would reset to defaults on zone transfer** — a
   correctness bug.

2. **Separated `AbilityRegistryLoader`** — Extracts config-to-registry loading into
   its own `object`, keeping `GameEngine`'s property initializer clean. PR #115 inlines
   ~16 lines of parsing logic directly in the `GameEngine` class.

3. **Better `findByKeyword` matching** — Uses prefix matching on ability IDs and
   requires a 3-character minimum for substring matching, reducing false positives.
   PR #115 does broad substring matching with no minimum length.

4. **Constructor-based `onLevelUp` callback** — Passes the level-up callback as a
   constructor parameter to `CombatSystem`, avoiding PR #115's mutable `setOnLevelUp`
   pattern that requires a separate `init` block call.

5. **Combined HP+mana regen per player** — Processes both HP and mana regen for each
   player in one loop iteration with a shared `didWork` counter, which is slightly more
   cache-friendly and prevents the mana regen loop from exceeding `maxPlayersPerTick`.
   PR #115 uses a separate uncapped loop for mana regen.

6. **Room broadcasts on self-cast** — Notifies other players when someone casts
   (e.g., "Alice casts Heal."). PR #115 only broadcasts for enemy-targeted spells
   (damage messages) and omits broadcasts for self-heals.

### Recommendation

**PR #116 is the stronger candidate.** The sharding/handoff gap in PR #115 is a
correctness bug that causes data loss in multi-engine deployments. PR #116's
architectural choices (separated loader, constructor callbacks, combined regen loops)
are also cleaner. However, PR #116 has several issues that should be addressed before
merge — see detailed review below.

---

## Detailed Code Review: PR #116

### Critical Issues

#### 1. `handleSpellKill` duplicates `handleMobDeath` logic
**File:** `CombatSystem.kt` (lines added in diff)

```kotlin
suspend fun handleSpellKill(killerSessionId: SessionId, mob: MobState) {
    val fight = fightsByMob[mob.id]
    if (fight != null) endFight(fight)
    mobs.remove(mob.id)
    onMobRemoved(mob.id)
    items.dropMobItemsToRoom(mob.id, mob.roomId)
    rollDrops(mob)
    broadcastToRoom(mob.roomId, "${mob.name} dies.")
    grantKillXp(killerSessionId, mob)
}
```

This is a near-verbatim copy of the private `handleMobDeath()` method with an added
`endFight` call at the top. If `handleMobDeath` is ever updated (e.g., to add death
animations, quest triggers, or metrics), this copy will silently diverge.

**Fix:** Call the existing `handleMobDeath` after ending the fight:
```kotlin
suspend fun handleSpellKill(killerSessionId: SessionId, mob: MobState) {
    val fight = fightsByMob[mob.id]
    if (fight != null) endFight(fight)
    handleMobDeath(killerSessionId, mob)
}
```

#### 2. Level-up ability notifications are silently discarded
**File:** `GameEngine.kt`

```kotlin
onLevelUp = { sid, level -> abilitySystem.syncAbilities(sid, level) },
```

`syncAbilities()` returns `List<AbilityDefinition>` (the newly learned abilities),
but this return value is ignored. Players never see "You have learned Fireball!"
messages on level-up.

**Fix:**
```kotlin
onLevelUp = { sid, level ->
    val newAbilities = abilitySystem.syncAbilities(sid, level)
    for (ability in newAbilities) {
        outbound.send(OutboundEvent.SendText(sid, "You have learned ${ability.displayName}!"))
    }
},
```

#### 3. `AbilityRegistryLoader` silently skips invalid abilities
**File:** `AbilityRegistryLoader.kt`

```kotlin
val targetType = when (defConfig.targetType.uppercase()) {
    "ENEMY" -> TargetType.ENEMY
    "SELF" -> TargetType.SELF
    else -> continue   // ← silently skipped
}
val effect = when (defConfig.effect.type.uppercase()) {
    "DIRECT_DAMAGE" -> ...
    "DIRECT_HEAL" -> ...
    else -> continue   // ← silently skipped
}
```

If someone misconfigures an ability (e.g., `targetType: "FOE"`), it is silently
dropped. Combined with the fact that `AppConfig.validated()` does not check
`targetType` or `effect.type` values, this creates a confusing failure mode where
an ability simply doesn't exist at runtime with no error message.

**Fix:** Either add validation in `AppConfig.validated()`:
```kotlin
engine.abilities.definitions.forEach { (key, def) ->
    require(def.targetType.uppercase() in listOf("ENEMY", "SELF")) {
        "ability '$key' targetType must be ENEMY or SELF, got '${def.targetType}'"
    }
    require(def.effect.type.uppercase() in listOf("DIRECT_DAMAGE", "DIRECT_HEAL")) {
        "ability '$key' effect.type must be DIRECT_DAMAGE or DIRECT_HEAL, got '${def.effect.type}'"
    }
}
```
Or throw from the loader instead of using `continue`.

### Moderate Issues

#### 4. Mana can go negative via direct subtraction
**File:** `AbilitySystem.kt`

```kotlin
// 5. Deduct mana
player.mana -= ability.manaCost
```

While there is a mana-sufficiency check earlier, there is no `coerceAtLeast(0)` on the
subtraction itself. If another system modifies mana between the check and the deduction
(unlikely in the current single-threaded engine, but a defensive coding concern),
mana could go negative. PR #115 uses `(player.mana - ability.manaCost).coerceAtLeast(0)`.

**Fix:** Use the defensive form:
```kotlin
player.mana = (player.mana - ability.manaCost).coerceAtLeast(0)
```

#### 5. `AbilitySystem` has unused `progression` dependency
**File:** `AbilitySystem.kt`

```kotlin
class AbilitySystem(
    ...
    private val progression: PlayerProgression = PlayerProgression(),
)
```

The `progression` field is never referenced in `AbilitySystem`. It adds an unnecessary
dependency and constructor parameter. It should be removed.

#### 6. `AbilitySystem.findMobInRoom` duplicates `CombatSystem` functionality
**File:** `AbilitySystem.kt`

```kotlin
private fun findMobInRoom(roomId: RoomId, keyword: String): MobState? {
    val lower = keyword.lowercase()
    return mobs.mobsInRoom(roomId).firstOrNull { it.name.lowercase().contains(lower) }
}
```

PR #115 exposed `findMobInRoom` as a public method on `CombatSystem` and reused it
from `AbilitySystem`. PR #116 duplicates the matching logic in `AbilitySystem` and
also depends on `MobRegistry` directly, creating two places where mob keyword matching
must be kept in sync.

**Fix:** Expose the existing mob-finding logic from `CombatSystem` (or `MobRegistry`)
and call it from `AbilitySystem` instead of duplicating. This also removes the need
for `AbilitySystem` to depend on `MobRegistry` directly.

#### 7. `spells` command doesn't show current mana or live cooldowns
**File:** `CommandRouter.kt`

```kotlin
val cdText = if (a.cooldownMs > 0) "${a.cooldownMs / 1000}s cooldown" else "no cooldown"
```

This shows the *definition's* cooldown duration, not the player's *remaining* cooldown.
A player who just cast Heal wants to know "3s remaining", not "5s cooldown".
Additionally, the current mana total is not shown, so the player has to use `score`
separately.

**Fix:** Use `AbilitySystem.cooldownRemaining()` (or add such a method) and append
a mana summary line.

#### 8. `CombatSystem.onLevelUp` callback type is not suspending
**File:** `CombatSystem.kt`

```kotlin
private val onLevelUp: (SessionId, Int) -> Unit = { _, _ -> },
```

This is a non-suspending callback, but it's invoked from `grantKillXp` which is inside
a `suspend fun`. PR #115 uses `suspend (SessionId, Int) -> Unit`. The current
non-suspending form prevents the callback from performing any suspend operations
(like sending outbound messages), which would be needed for the level-up notification
fix described in issue #2 above.

**Fix:** Change the type to `suspend (SessionId, Int) -> Unit`.

### Minor / Style Issues

#### 9. Flyway migration uses single ALTER TABLE with multiple ADD COLUMN
**File:** `V2__add_player_mana.sql`

```sql
ALTER TABLE players
    ADD COLUMN mana       INTEGER NOT NULL DEFAULT 20,
    ADD COLUMN max_mana   INTEGER NOT NULL DEFAULT 20;
```

This is fine for Postgres, but the multi-column ADD COLUMN syntax is not supported by
all SQL dialects. Not a problem if Postgres is the only target, but worth noting if
portability matters. PR #115 uses two separate ALTER TABLE statements.

#### 10. `SerializedPlayerState` defaults mana to 20 as magic numbers
**File:** `InterEngineMessage.kt`

```kotlin
val mana: Int = 20,
val maxMana: Int = 20,
val baseMana: Int = 20,
```

These default values duplicate the `BASE_MANA` constant from `PlayerState`. Consider
referencing `PlayerState.BASE_MANA` to keep the values in sync.

#### 11. Missing `CombatSystemTest` update
PR #115 updates the existing `CombatSystemTest` level-up message assertion to account
for the new mana display in level-up messages. PR #116 adds mana to the level-up
message in `CombatSystem` but does not update the corresponding test assertion, which
may cause an existing test to fail.

---

## Summary of Recommended Changes for PR #116

| # | Severity | Issue | Fix |
|---|----------|-------|-----|
| 1 | Critical | `handleSpellKill` code duplication | Delegate to `handleMobDeath` |
| 2 | Critical | Level-up notifications discarded | Use `syncAbilities` return value |
| 3 | Critical | Invalid abilities silently skipped | Add config validation or throw |
| 4 | Moderate | Mana can go negative | Use `coerceAtLeast(0)` |
| 5 | Moderate | Unused `progression` dependency | Remove from constructor |
| 6 | Moderate | Duplicated `findMobInRoom` | Reuse from `CombatSystem` |
| 7 | Moderate | Static cooldown display | Show live remaining time |
| 8 | Moderate | Non-suspending `onLevelUp` callback | Change to `suspend` type |
| 9 | Minor | Multi-column ALTER TABLE portability | Informational only |
| 10 | Minor | Magic number defaults in serialization | Reference `PlayerState.BASE_MANA` |
| 11 | Minor | Missing test update for level-up message | Update `CombatSystemTest` assertion |
