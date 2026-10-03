Closes #1221, closes #1223, closes #1224 — the remaining quick-hit findings from the live-demo playtest, one commit each.

## #1221 — `cast hammer strike <target>` misparsed

The parser blindly splits `cast <word> <rest>`, so a multi-word spell name swallowed part of itself into the target (`spell="hammer", target="strike rat"`) and target resolution failed. `AbilitySystem.cast` now re-tokenizes the whole input and tries the **longest known-spell prefix** first, remainder = target:

- `cast hammer strike rat` → Hammer Strike @ rat ✓
- `cast hammer rat` (prefix form) → unchanged ✓
- `cast magic missile` (full name, no target) → "Cast Magic Missile on whom?" instead of treating "missile" as a target ✓
- Only abilities the caster **knows** participate in resolution, so an unknown longer name can't shadow a known shorter one; unknown input keeps the existing error message.

This also makes quoting unnecessary (the playtest note about `cast 'hammer strike'` failing) — greedy matching handles the ambiguity that quotes would have resolved.

## #1223 — gather-quest items not consumed on turn-in

`completeQuest` now removes `count` items per collect-type objective before announcing completion, with a `You hand over 2x a handful of cosmic scrap.` line. Details:

- Matching mirrors the built-in collect handler (exact id or `:<localId>` suffix), so cross-zone drops count the same way they did when progressing the objective.
- Best-effort: items dropped since completion are skipped rather than blocking turn-in (objective progress never regresses today — changing that would be a separate, bigger fix).
- Surplus copies beyond the required count stay in the bag.
- New `QuestSystem.onItemsConsumed` callback → GameEngine re-syncs the `Char.Items` GMCP list so the web inventory updates.
- Applies to both `npc_turn_in` and `auto` completion (shared path).

## #1224 — `eat`/`drink` aliases

`eat sandwich` / `drink tea` answered "Huh?". Both now alias to `Command.Use`, and the command manifest (AppConfig defaults + `application.yaml`, kept in sync) advertises `use/eat/drink <item>` in `help` and the web command palette.

## Tests

- `AbilitySystemTest`: multi-word spell + target resolves and damages; full name with no target prompts for one (existing prefix/exact-id tests cover regression).
- `QuestSystemTest`: completing a 2-rock collect quest consumes exactly 2 of 3 rocks, fires the inventory-sync callback once, and emits the hand-over message.
- `CommandParserTest`: `eat`/`drink` parse to `Command.Use`; bare verbs are `Invalid` with the `use <item>` usage.

`ktlintCheck`, full `test`, and `integrationTest` all pass.
