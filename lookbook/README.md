# AmbonMUD Lookbook

A visual tour of AmbonMUD: every major subsystem of the web client and all 128 rooms of
the Auringold Academy. Every screenshot is from the Auringold Academy zone.

- **`lookbook.pdf`** — the assembled document: painted cover, a gameplay overview and
  a one-page technical summary, a codex of the six classes, nine peoples (grouped by
  their maker), and the gods and figures of Ambon, then large subsystem captures, a
  handful of featured Academy rooms, and a small grid of every Academy room.
- **`lookbook.md`** — the prose source; image references point into `screenshots/`.
- **`codex.json`** — the class/race/character codex data (titles, stats, summaries,
  portrait filenames), rendered directly into the PDF.
- **`screenshots/portraits/`** — the lore showcase art for the six classes, nine races,
  and all thirteen characters.
- **`screenshots/subsystems/`** — login flow, world view, the vitals-bar and room-sign
  close-ups, and every panel (character, professions, achievements, inventory, equipment,
  spellbook, quests, combat log, social boards, shop, bank, auction, stylist, housing,
  inn/recall, crafting, lottery, dice, jukebox, puzzles, NPC dialogue, combat, staff
  control), captured in real Academy service rooms.
- **`screenshots/academy/`** — one capture per Auringold Academy room, named by room id.

## How it was captured

Captured live from the public demo at **mud.ambon.dev** — which already serves the full
Auringold world and the painted R2 backgrounds — driven by a headless-Chromium Playwright
script (`pipeline/`): log in as the staff lookbook character, `goto` each room, open each
panel (dock buttons by accessible label, room-service kiosks via their canvas badges),
and screenshot at 1600×1000. The Social Board shot is a real multi-account Gossip
conversation. Markdown is rendered to PDF via headless Chromium print. Screenshots are
JPEG quality 82. See `pipeline/README.md` for the repeatable capture process.
