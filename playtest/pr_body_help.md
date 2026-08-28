Full audit of the in-game `help` / web command palette manifest, prompted by the live-playtest finding that auction, train, bank, rest, stylist, duel, pet, and `answer` were all undocumented. The audit found the problem was much wider than those, with four distinct root causes — each fixed with a guard so it can't regress.

## What was missing

**~40 player-facing commands** had no manifest entry at all: `auction` (4 forms), `trade` (3 forms), `bank`/`deposit`/`withdraw`, `duel`, `pet` (5 forms), `train` (4 forms), `prestige`, `leaderboard`, `stylist`/`changerace`, `dungeon`, `enchant`/`enchantments`, `rest`, `depart`, `consider`, `wimpy`*, `claim`, `reputation`, `describe`, `specialize`, `guild hall` (5 forms), `qoffers`, `bye`, `time`, `quickheal`/`quickmana`*, `run`*, `areas`* — plus 8 staff commands (`heal`, `pinfo`, `setstaff`, `setgold`, `setrace`, `setclass`, `setgender`, `setxp`).

\* present in only one of the two manifest sources — see root cause 2.

## Root causes and fixes

1. **`defaultCommandEntries()` lacked the entries.** Added all 47, with usage strings that spell out full subcommand syntax (`train [list] | train learn <ability> | train unlock <class> | train reset`) and descriptions that note room requirements ("requires a bank/tavern/stylist", "set by resting at an inn"). Existing vague wording clarified (`gamble` → "Roll d100 against the house (requires a tavern)").

2. **`application.yaml` wholesale-replaces the defaults, and they had drifted both ways.** The YAML was missing keys the defaults had (and vice versa — `rest`/`run`/`areas`/`consider`/`wimpy` existed *only* in YAML), carried almost no descriptions, and silently reset `requiresTarget` to false on every entry. The YAML block is now **regenerated verbatim from the code defaults**, and a new `CommandManifestSyncTest` pins the two equal — on any drift it fails with a field-level diff and writes the fresh block to `build/command-manifest.yaml` for copy-paste.

3. **`WebClientParityTest` only validated commands already in its hand-written map**, so unmapped commands escaped silently (and several map keys were dead names — `Accept`, `Achievements`, `Title`, `Open` don't exist as Command subtypes). The mapping now covers every `Command` subtype with its real name, and a new completeness test scans `CommandParser.kt`'s sealed hierarchy and fails **in both directions**: a new Command without a mapping+manifest entry, or a stale mapping for a removed Command. Exclusions are explicit and justified (`Noop`/`Invalid`/`Unknown` internals, `Petition` easter egg).

4. **`generateHelp()` silently dropped any category absent from its hardcoded order list and never showed descriptions.** `help` now renders category headers (`[Navigation]`), `usage — description` lines, and appends unknown categories instead of dropping them. `CommandsConfigHelpTest` covers the rendering and additionally requires every manifest entry to carry a non-blank usage *and* description.

## Sample of the new help output

```
Commands:
  [Navigation]
    look/l [target|direction] — Look around, at a target, or in a direction
    ...
    rest — Rest at an inn to make it your recall point.
  [Shops]
    bank — View bank balance and vault contents (requires a bank)
    auction sell <item> <price> — List an item on the auction house
```

`ktlintCheck`, full `test`, and `integrationTest` all pass. The richer descriptions also flow to the web palette via `Server.Commands` GMCP automatically.
