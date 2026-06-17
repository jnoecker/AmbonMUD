# Lookbook pipeline

The two scripts that build `../lookbook.pdf`. Both run on [Bun](https://bun.com)
and a Chromium that Playwright can drive.

```bash
bun install
```

## Stage 1 — render the PDF (`makepdf.ts`)

Turns the committed sources — `../lookbook.md` (prose), `../codex.json` (the
class / race / character codex), the `../screenshots/` tree, and the cover and
`page_bg.jpg` art — into `../lookbook.html` and then `../lookbook.pdf` via a
headless-Chromium print. This is all you need to regenerate the PDF from the
checked-in content:

```bash
bun run build          # → ../lookbook.html and ../lookbook.pdf
bun run promo          # → ../promo.pdf — a 2-page double-sided Letter handout
```

`makepromo.ts` builds the giveaway flyer: page 1 is the painted cover with a
play-the-demo CTA + QR over the centre plaque; page 2 is a parchment montage of
the best captures, feature bullets, and QR codes to the lookbook and the source.
QR codes are generated at build time with `qrcode` (no network), so changing a
URL just means editing `makepromo.ts` and re-running.

How it lays out:

- **Prose** (`lookbook.md`) is rendered with `marked`. Each `### heading` + image
  (+ italic caption) becomes a centered "plate"; the Academy room grid becomes
  2-up `figure` pages.
- **Codex** (`codex.json`) is rendered directly, *not* through `marked` (which
  strips the nested `</div>` tags). Each section (`classes`, `races`, `chars`)
  has a `pages` array; each inner array is one printed page, and the section
  header + lead ride on that section's first page. Items are tagged tuples:
  `["entry", img, name, meta?, desc]` for a portrait card, or
  `["origin", title, note]` for a maker subheading.
- The page that overflows is the constraint: codex portraits/margins are sized
  so a page holds ~7 entries. If you add entries, rebalance the `pages` arrays
  in `codex.json` rather than letting a page clip at the bottom.

## Stage 2 — capture the screenshots (`capture.ts` + `steps/`)

Re-captures `../screenshots/` **from the live demo** (`https://mud.ambon.dev`) —
no local server or `AMBONMUD_DATA_DIR` assembly needed. The live demo already
serves the full Auringold world and the painted R2 backgrounds, so art-forward
panels (signs, the inn key, the vitals bar, staff control) come through skinned.
Just point a Playwright Chromium at it and drive it as the lookbook character.

`capture.ts` reads a JSON step-script. Steps:

| Step | Effect |
|------|--------|
| `{ "login": true }` | log in as `LOOKBOOK_NAME`/`LOOKBOOK_PASS` (waits on the in-game vitals HUD) |
| `{ "cmd": "goto cozy_inn" }` | submit an in-game command (staff `goto <room-id>` teleports) |
| `{ "shot": "path.jpg" }` | full-page screenshot |
| `{ "clip": ".sel", "shot": "path.jpg", "pad": 10 }` | screenshot one element's box (UI close-ups) |
| `{ "click": "Chat" }` | click the first DOM button whose aria-label/text starts with the string |
| `{ "clickxy": [x, y] }` | click a viewport coordinate (Pixi canvas badges: the inn kiosk, the STAFF/Recall pills) |
| `{ "press": "Escape" }`, `{ "sleep": 3 }`, `{ "fill": ".sel", "value": "x" }`, `{ "waitfor": ".sel" }`, `{ "js": "expr" }` | misc |

The committed step-scripts in `steps/` are the repeatable process:

```bash
bun capture.ts steps/rooms.json          # re-shoot every Auringold Academy room (goto by room id)
bun capture.ts steps/subsystems.json     # panels, vitals/sign close-ups, inn recall, staff control, all room-service kiosks
LOOKBOOK_DSF=3 bun capture.ts steps/subsystems.json   # hi-res for the {clip} close-ups
bun social.ts                            # populate the Social Board (gossip) with a multi-account conversation
bun combat.ts                            # combat (damage toasts + victory) and the battle-journal combat log
```

Two panels need a sprite click, and mob sprite positions shift per session, so
they use dedicated scripts / a recon click rather than fixed `clickxy`:

- **NPC dialogue** is the *field manual's* Talk view — click the NPC sprite
  (≈ `[1080, 330]` for Krioshaeu in `krioshaeu_cabin`; the manual root is
  `.mm-card`) then the **Talk** tab. The plain `talk <npc>` command only opens the
  unskinned canvas overlay.
- **Combat** can't be driven by typed commands; `combat.ts` grid-clicks to find
  the mob whose manual has an **Attack** action, attacks, and shoots the result.

The room-service kiosks are Pixi badges stacked on the left rail at `x≈76`, first
badge `y≈168`, ~150px apart (see `subsystems.json`); the dock panels, social
stack, and field-manual tabs are DOM and clickable by `{click}` label.

`steps/rooms.json` is generated from the room ids (the `../screenshots/academy/`
filenames are the room ids); regenerate it if rooms are added or removed. Room
**services** opened from a room's painted kiosk badge (shop, bank, auction,
stylist, housing, crafting, lottery, dice, jukebox, music box, puzzle, chest,
inn) are Pixi canvas elements — open them with `clickxy` at the badge on the
left rail (see `subsystems.json`'s inn step for the pattern), or by their
in-game command, then `shot`.

The `*.sh` helpers (`cap.sh`, `ensure.sh`, `send.sh`, `panel.sh`) are an
alternative driver built on the gstack `browse` daemon for interactive capture
and auto-relogin; they read the same env.

## Configuration (env)

| Var | Default | Used by |
|-----|---------|---------|
| `CHROMIUM_BIN` | macOS Playwright cache, else Playwright's resolved Chromium | both |
| `LOOKBOOK_URL` | `https://mud.ambon.dev` (the live demo) | capture |
| `LOOKBOOK_NAME` / `LOOKBOOK_PASS` | `Claude` / `ClaudeFable5` (the staff lookbook character, with the painted "animae akathavae illustrator" skin) | capture |
| `LOOKBOOK_DSF` | `1` (raise to 3 for crisp `{clip}` close-ups) | capture |
| `BROWSE_BIN` | `~/.claude/skills/gstack/browse/dist/browse` | `cap.sh` |

The lookbook lives outside the Gradle build, so `ktlintCheck` / `test` don't
apply — validate by opening the regenerated `../lookbook.pdf`.
