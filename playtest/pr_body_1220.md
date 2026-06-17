Closes #1220.

## Problem

Feature messages hardcode `the `/`The ` before `displayName`s that already carry their own article — observed live as "You open the **a lacquered** curio chest." and "You pull the **an ostentatious** brass lever."

## Fix

Two small helpers in `HandlerHelpers.kt`:

- `the(name)` — definite-article form for mid-sentence use: swaps a leading `a `/`an ` for `the `; names already starting with `the` pass through (`The Door That Asks` stays untouched); bare names (`door to the north`) get `the ` prefixed.
- `theCap(name)` — sentence-initial variant (`The lacquered curio chest`).

Applied at every site that prepended an article to a feature name:

| File | Sites |
|---|---|
| `WorldFeaturesHandler` | open/close/unlock/lock (success, error, and room broadcasts), search ("In the X:", "X is empty"), get-from/put-in, pull |
| `NavigationHandler` | "The door to the north is locked." movement block |
| `PuzzleHandler` | auto-unlock "swings open" (player + broadcast) |
| `HandlerHelpers` | `requireOpenContainer` "is not open" |
| `TimedRespawnHandler` | revert flavor lines, now consistently sentence-cased ("The sprung lever snaps back into place.") |

Item display names inside the same messages ("You take **a silver coin** from the supply chest.") were already bare and remain untouched.

## Tests

- `DefiniteArticleTest` — article swap, `the`-passthrough (including capitalized proper names), bare-name prefixing, no mangling of words that merely start with article letters (`anvil`, `theater door`, `apple cart`), capitalization.
- `CommandRouterFeaturesTest` — end-to-end regression asserting the exact strings: "You open **the supply chest**." and "You pull **the iron lever**. It moves down." against the `ok_features` fixture (whose names are authored as "a supply chest" / "an iron lever").
- `TimedRespawnHandlerTest` updated for the sentence-cased revert line.

`ktlintCheck`, full `test`, and `integrationTest` all pass.
