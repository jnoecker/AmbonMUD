Closes #1222.

## Problem

A zone reset mid-fight removed the player's opponent ("The air shimmers as the area resets around you. / Your opponent vanishes.") with no kill credit — hit twice during the live-demo playtest, both times a few swings from the kill.

## Approach

Mobs **currently in combat are exempt from the reset** rather than duplicated:

- The reset skips `removeMobExternally` for fighting mobs — the swing-for-swing fight continues untouched.
- Their spawn slot is **not** repopulated during that reset. This matters because mob identity *is* the spawn id: `mobs.upsert(spawnToMobState(spawn))` would have silently *replaced* the live opponent, not stood a second copy next to it. (A true duplicate would need synthetic MobIds and ripple through the threat table, removal coordinator, behavior trees, GMCP payloads, and the death-respawn guard — plus open a double-boss-loot surface.)
- Their carried loot is excluded from `items.resetZone`'s mob-item wipe, so the imminent kill still drops everything.
- Self-healing afterward: when the fight ends in the mob's death, the existing `respawnSeconds` scheduler refills the slot exactly as for any normal kill. If the player flees instead, the next reset reclaims the slot once the mob is out of combat (covered by a test).

`ZoneResetHandler` gains an `isMobInCombat: (MobId) -> Boolean` probe (defaults to `{ false }`, i.e. prior behavior); `GameEngine` wires it to `CombatSystem.isMobInCombat`.

## Tests

New `ZoneResetCombatExemptionTest` (real subsystems, `ok_small` fixture, `MutableClock`):
- idle mobs are still replaced by fresh full-HP copies on reset (baseline preserved)
- an in-combat mob survives the reset — same instance, damage retained, combat state intact, no "Your opponent vanishes.", while the zone-reset notice still reaches the player
- after the player flees, the *next* reset reclaims the slot with a fresh spawn

`ktlintCheck`, full `test`, and `integrationTest` all pass.
