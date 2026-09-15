Fixes the three highest-severity findings from today's live playtest of mud.ambon.dev (telnet, fresh character + demo guest sessions).

## 1. Hostile casts never engaged combat — free-kill exploit (High)

**Observed live:** `cast hammer goblin` repeated until the goblin died. It never retaliated; full XP + gold awarded; zero damage taken. `applySpellDamage` added threat and handled the kill, but only the `kill` command ever called `startCombat` — threat is meaningless for a mob that isn't in combat.

**Fix:** new `CombatSystem.engageMobCombat(sessionId, mob)` — registers the combatant and fires `onPlayerEnteredCombat` without keyword resolution or attack messaging. `handleEnemyCast` invokes it after mana/cooldown are paid, so any hostile cast (damage, debuff, taunt-join) provokes its target. No-ops when the player already has a target, the mob is a non-combatant, or either side is dead.

**Tests:** out-of-combat cast engages combat with the target; killing cast does not leave the player stuck in combat; off-target cast while melee-engaged does not retarget.

## 2. `claim` accepted the reserved name "Demo" — account permanently unreachable (High)

**Observed live:** `claim Demo <password>` succeeded; the login flow intercepts any case variant of `demo` at the name prompt and starts a guest session, so that account can never log in again.

**Fix:** `PlayerRegistry.RESERVED_NAMES` (`demo`) + `isReservedName()`. `claim` returns a new `ClaimResult.Reserved` with a clear message ("That name is reserved and cannot be used…"), and `isValidName` now rejects reserved names so the create/login paths are covered even on servers with the demo flow disabled.

**Tests:** claim rejects `demo`/`Demo`/`DEMO` and leaves the demo character unchanged; create rejects the reserved name.

> Note: the live demo instance now has an orphaned `Demo` player record (created during the playtest, pre-fix). It may want manual cleanup in the player store.

## 3. Ghost "[Currency] You receive 1 Crafting Tokens." (Medium)

**Observed live:** crafting announced a Crafting Tokens award while `currencies` reported "No secondary currencies exist." Default config ships `tokensPerCraft = 1` with empty `definitions`; `CurrencySystem.award` silently dropped the award but the announcement was sent unconditionally.

**Fix:** `award()` now returns `Boolean` (credited or not, never mutating on failure). All three announcement sites — craft tokens, PvP honor, quest currency rewards — gate the player message and GMCP wallet refresh on it.

**Bonus latent bug fixed in the same block:** `combatSystem.onPvpKill` was assigned twice in `GameEngine`; the honor lambda silently overwrote the `notifyDailyQuest(sid, "pvpKill")` hook, so PvP-kill daily quest progress never fired. Merged into a single assignment.

**Tests:** award return-value semantics (success, zero/negative amount, unknown currency, empty definitions).

## Verification

- `./gradlew ktlintCheck test integrationTest` — all green.
- Findings 1 and 2 were reproduced live on mud.ambon.dev before writing the fixes; finding 3's root cause confirmed by code inspection (config defaults + silent no-op in `award`).
