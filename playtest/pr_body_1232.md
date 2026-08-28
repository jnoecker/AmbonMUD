Closes #1232.

## Problem

`kill` checks `mob.role.isCombatant` and refuses with role-specific flavor — but targeted spell casts never did. Repro (as reported): press a skill hotkey out of combat, cursor becomes a crosshair, click a non-killable mob (quest giver / vendor / dialog NPC / prop) → `applySpellDamage` lands, `handleSpellKill` fires, and the NPC dies without combat ever starting. The earlier #1219 fix made hostile casts *engage* combat, but `engageMobCombat` deliberately no-ops for non-combatants — leaving the damage path itself ungated.

## Server fix (authoritative)

- The role-refusal messages move out of `startCombat` into a shared **`CombatSystem.nonCombatantRefusal(mob)`** (exhaustive `when` over `MobRole`, null for `COMBAT`).
- `AbilitySystem.handleEnemyCast` applies it immediately after target resolution — **before** mana deduction, cooldown, combat engagement, or any effect — so the spell gets exactly the same refusals as the sword:
  - "X isn't interested in fighting — try the shop instead." (vendor)
  - "X has no quarrel with you. Maybe they have work to offer?" (quest giver)
  - "X has no interest in fighting you." (dialog)
  - "X is not something you can attack." (prop)
- Covers DirectDamage, ApplyStatus (DoTs could also kill), and the single-target path generally. Area damage and taunts already only consider mobs *in combat*, which non-combatants can never be.

## Web client polish

The crosshair targeting overlay was advertising the broken behavior:
- ENEMY-targeted skills no longer pulse or show a crosshair over non-combatants (the `mobInfo.combatant` GMCP flag was already plumbed for the context-menu Attack gate).
- Clicking a non-combatant in target mode is ignored rather than consumed as a target selection — targeting stays active so the player can pick a real enemy.
- Absent mob info defaults to targetable, matching the existing legacy-mob convention.

## Tests

New `AbilitySystemTest` case iterates all four non-combat roles: cast refused with the kill-command wording, **zero damage, zero mana spent, no combat started**. Existing kill-command refusal coverage still passes against the extracted helper.

`ktlintCheck`, full `test`, `integrationTest`, `bun run lint`, and `buildWeb` all pass.
