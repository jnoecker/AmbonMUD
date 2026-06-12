# AmbonMUD Lookbook

A visual tour of AmbonMUD: every major subsystem of the web client, representative
rooms from the live Auringold zones, and all 128 rooms of the Auringold Academy.

- **`lookbook.pdf`** — the assembled document: painted cover, a gameplay overview and
  a one-page technical summary up front, then large subsystem captures, representative
  rooms, and a small grid of every Academy room.
- **`lookbook.md`** — the source document; image references point into `screenshots/`.
- **`screenshots/subsystems/`** — login flow, world view, and every panel (character,
  professions, achievements, prestige, inventory, equipment, spellbook, trainer, quests,
  combat log, social boards, shop, bank, auction, stylist, housing, inn, lottery, dice,
  crafting, puzzles, NPC dialogue, combat).
- **`screenshots/rooms/`** — representative rooms from the playtesting, Aineroia's
  Cottage, and Celestial Sanctum zones.
- **`screenshots/academy/`** — one capture per Auringold Academy room, named by room id.

## How it was captured

A local `./gradlew demo` server running the full Auringold world (the Ambon dataset,
assembled into `AMBONMUD_DATA_DIR` the same way production fetches it from R2), driven
by a headless-Chromium Playwright script: log in as a staff character, `goto` each room,
open each panel (dock buttons by accessible label, room services via their canvas-widget
rail), and screenshot at 1600×1000. Markdown is rendered to PDF via headless Chromium
print. Screenshots are JPEG quality 82.

Captured June 2026 at rare-mob variant chance 4% (the production rate).
