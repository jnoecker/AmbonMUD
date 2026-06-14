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
```

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

## Stage 2 — capture the screenshots (`capture.ts` + `*.sh`)

Only needed to *re-capture* `../screenshots/` (login flow, every panel, and one
shot per Auringold Academy room). Requires a local server with the full world
**and the painted backgrounds from R2** — without them, art-forward panels (the
chest/container modal, signs, levers, door heroes) fall back to the unskinned CSS
and the screenshots look plain.

1. **Assemble `AMBONMUD_DATA_DIR`** the way production does (see
   `AmbonMUD/docs/DEPLOYMENT.md` § "Remote world & config overlay"): a directory
   holding `application-local.yaml` (the lore config overlay), the zone YAMLs
   under `world/`, `sprites.yaml`, and `achievements.yaml`. The overlay's
   `ambonmud.globalAssets` map (e.g. `container_bg: <hash>.jpg`) is what points
   the web client at the painted backdrops, which it fetches from R2 at runtime —
   so the capture box needs outbound network to R2.
2. **Boot the demo against it:**

```bash
# in the AmbonMUD repo root:
AMBONMUD_DATA_DIR=/path/to/data ./gradlew demo   # note the web port it prints (e.g. 18543)
```

Needs JDK 21 on PATH (the Gradle build won't start without a JVM).

Then drive it. `capture.ts` takes a JSON step-script (`{login}`, `{goto}`,
`{cmd}`, `{click}`, `{shot}`, `{sleep}`, …):

```bash
LOOKBOOK_URL=http://localhost:18543 bun capture.ts steps.json
```

The `*.sh` helpers (`cap.sh`, `ensure.sh`, `send.sh`, `panel.sh`) are an
alternative driver built on the gstack `browse` daemon for interactive capture
and auto-relogin; they read the same env.

## Configuration (env)

| Var | Default | Used by |
|-----|---------|---------|
| `CHROMIUM_BIN` | macOS Playwright cache, else Playwright's resolved Chromium | both |
| `LOOKBOOK_URL` | `http://localhost:18543` | capture |
| `LOOKBOOK_NAME` / `LOOKBOOK_PASS` | `Loremaster` / `lookbook-pass-1` (throwaway local-demo staff login) | capture |
| `BROWSE_BIN` | `~/.claude/skills/gstack/browse/dist/browse` | `cap.sh` |

The lookbook lives outside the Gradle build, so `ktlintCheck` / `test` don't
apply — validate by opening the regenerated `../lookbook.pdf`.
