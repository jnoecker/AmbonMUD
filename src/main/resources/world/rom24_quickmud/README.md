# ROM 2.4 QuickMUD Import Folder

This folder is reserved for converted area files sourced from:

- <https://github.com/avinson/rom24-quickmud/tree/master/area>

## Conversion workflow

1. Obtain the original `.are` files locally (network access from this environment may block direct GitHub fetches).
2. Run:

```bash
python tools/convert_rom_area.py \
  --input-dir /path/to/rom24-quickmud/area \
  --output-dir src/main/resources/world/rom24_quickmud
```

The converter prioritizes `midgaard.are` first, then processes remaining files alphabetically.

## Mapping defaults used by converter

- Zone name: area filename slug.
- Room IDs: `r<vnum>`
- Mob IDs: `m<vnum>`
- Item IDs: `i<vnum>`
- Lifespan: `30`
- Mob defaults: `tier: standard`, `level: 1`
- Item defaults:
  - `description: "Imported from ROM area file."`
  - `keyword`: first tokenized slug segment from item name.

The converter preserves room titles/descriptions/exits and reset-based room placement for mobs/items when possible.
