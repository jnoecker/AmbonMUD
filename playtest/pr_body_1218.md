Closes #1218.

## Problem

Riddle questions and sequence-puzzle progress were emitted **only** via the GMCP `Puzzle.List` package (`emitPuzzleGmcp`), which feeds the web client's puzzle popout. Over telnet there was nothing: during the live-demo playtest, "The Door That Asks" presented a locked exit and zero discoverable interaction — `look`/`read`/`talk`/`unlock`/`listen` all dead-ended, the riddle question was never shown, and the `answer` verb wasn't documented anywhere (not even in `help`).

## Changes

**`look` now renders room puzzles as text**, mirroring what the web popout shows (`sendLook` in `HandlerHelpers.kt`, threaded `puzzleSystem` through the existing optional-dependency pattern):

```
Riddle: What has roots that nobody sees, ... never grows?  (type 'answer <your guess>')
Riddle (solved): What has roots that nobody sees, ...
Puzzle: interact with the features here in the correct order (step 1/3).
Puzzle (solved): the mechanisms here rest in their final arrangement.
```

- Unsolved riddles include the `answer` usage hint; solved ones drop the prompt (matching the web's "Solved" badge).
- Sequence puzzles show live step progress (matching the web's step dots), updating as levers are pulled.
- Null-question riddles fall back to the same default copy the web client uses.

**`answer` registered in the command manifest** (`AppConfig.defaultCommandEntries()` + `application.yaml`, updated together per the config rule). It now appears in `help` and, via `Server.Commands` GMCP, in the web command palette — it was previously invisible in both.

**Parity-test hardening:** added the `Answer → answer` mapping to `WebClientParityTest`, so any future parser command missing a manifest entry fails CI the way this one should have.

## Tests

New cases in `PuzzleSystemTest` (router-driven, against the `ok_puzzles` fixture):
- `look` shows the riddle question + answer hint
- solved riddles render as `Riddle (solved):` and stop prompting
- sequence progress renders `step 0/3` → `step 1/3` after pulling the first lever
- rooms with multiple riddles list every one

`ktlintCheck`, full `test`, and `integrationTest` all pass. (`RouterTestHelper` now wires `puzzleSystem` into the test `EngineContext`, matching production wiring.)
