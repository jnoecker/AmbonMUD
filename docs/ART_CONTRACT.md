# Painted Art & Panel Reskin Contract

Shared spec for the web client's painted UI art — the panel frames, control overlays, canvas
widgets, and default sprites that turn plain glass-morphism dialogs into illustrated windows.
It is the visual analogue of [`VOICE_OVER_CONTRACT.md`](./VOICE_OVER_CONTRACT.md) and spans the
same two repos:

- **Arcanum** (content tooling) — generates the painted art, publishes content-hashed files to
  R2, and maintains the `images.globalAssets` map in the lore config overlay.
- **AmbonMUD** (this repo) — registers the logical asset keys, resolves them to URLs, and sends
  the resolved map to the web client over GMCP. The client seats live content into the painted
  frames with CSS and degrades gracefully when art is absent.

Painted art is a **web-client-only** feature, like canvas rendering and voice-overs. Telnet
clients are unaffected.

## Division of labor

| Side | Responsibility |
|---|---|
| Arcanum | Paint/generate each asset at its target pixel dimensions, upload to R2 under a **content-hashed filename**, and map `logicalKey → <hash>.<ext>` in the published `application-local.yaml` under `ambonmud.images.globalAssets`. Owns regeneration, cost, and licensing. |
| AmbonMUD engine | Own the logical key registry (`ImagesConfig.DEFAULT_GLOBAL_ASSETS`, `AppConfig.kt`). Resolve every key to a **fully-resolved URL** and emit the map once per session as the `Server.Assets` GMCP package (`GmcpEmitter.sendServerAssets`). No image I/O in the engine. |
| Web client | Read `state.serverAssets`, inject URLs as CSS custom properties, apply the `-skinned` class variant, seat content into the painted boxes with percentage insets, and fall back to pure-CSS styling when an asset is missing. The client **never concatenates paths**. |

## Asset registry & naming conventions

The single source of truth for logical keys is `ImagesConfig.DEFAULT_GLOBAL_ASSETS` in
`src/main/kotlin/dev/ambon/config/AppConfig.kt` (~line 3045). Keys are stable identifiers;
the *values* (paths/filenames) are what deployments override.

| Family | Convention | Examples |
|---|---|---|
| Panel frames | `<panel>_bg` | `admin_bg`, `chat_bg`, `who_bg`, `guild_bg`, `friends_bg`, `group_bg`, `auction_bg`, `crafting_bg`, `professions_bg`, `housing_bg`, `stylist_bg`, `lottery_bg`, `dice_bg`, `jukebox_bg`, `bank_bg`, `puzzle_bg`, `command_reference_bg`, `character_bg`, `character_scribe_bg` |
| Phone-portrait companions | `<key>_portrait` | `login_bg_portrait` (+ the other login scenes), `lottery_bg_portrait`, `dice_bg_portrait`, `jukebox_bg_portrait` — a 941×1672 *recomposition* of the landscape art (not a crop); the client prefers it on portrait viewports |
| Control overlays | `<panel>_<control>_btn` / `<scope>_<control>` | `staff_action_btn`, `staff_action_btn_active`, `who_examine_btn`, `who_tell_btn`, `who_friend_btn`, `char_btn_achievements`, `char_btn_prestige`, `char_btn_professions` |
| Multi-piece sets | `<panel>_<piece>` | Character "Woodland Fae Cabinet": `character_niche` (full art), `character_frame` / `character_plaque` / `character_charm` (**9-slice** carved frames) |
| World-feature art | `feature_*`, `door_*`, `lever_*`, `container_bg`, `sign_bg` | `door_frame` + `door_leaf` + `door_lock` + `door_portal` (static frame, swinging leaf, warded-seal lock, doorway vortex), `lever_plate` + `lever_handle` |
| Canvas widgets & indicators | `<thing>_widget`, `<thing>_indicator`, `<thing>_kiosk` | `dice_table_widget`, `lottery_board_widget`, `shop_kiosk`, `aggro_indicator`, `quest_available_indicator` |
| Game-piece art | themed per system | Aineroia's Dice: `dice_<child>` + `dice_<child>_max`, `coin_luneqrae_moon` / `coin_luneqrae_wind` |
| Terrain / mob defaults | `default_bg_<terrain>`, `default_mob_<category>` | `default_bg_forest`, `default_mob_undead` |

When adding a new key, keep it `snake_case`, scoped by panel/system, and add it to
`DEFAULT_GLOBAL_ASSETS` with a bundled-path value (see resolution below) plus a comment stating
its fallback behavior — the existing entries model this.

## URL resolution

`GmcpEmitter` resolves every registry value at construction (`GmcpEmitter.kt`, `resolvedAssets`):

- Values starting with `defaults/` or `global_assets/` are **bundled** assets — they always
  resolve against local `/images/` (served from classpath `src/main/resources/world/images/`
  via the Ktor static route in `KtorWebSocketTransport.kt`). This keeps a fresh checkout
  working with zero CDN setup.
- Any other value (in practice: an Arcanum content-hashed filename) resolves against
  `ambonmud.images.baseUrl` — the CDN base, e.g. `https://assets.ambon.dev/`.

```yaml
ambonmud:
  images:
    baseUrl: "/images/"        # production overlay: https://<r2-host>/
    globalAssets:              # production overlay remaps keys to hashed filenames
      admin_bg: 3f9a1c…e2.png  # → https://<r2-host>/3f9a1c…e2.png
```

### Cache-busting

There is no query-string versioning. Arcanum publishes each image under a **content-hashed
filename** and updates the overlay's `globalAssets` map to point at the new hash — changing the
art yields a new URL, so stale images are never served and old files become GC-able. This is the
image-side equivalent of the voice contract's `<sha8>` path segment. Bundled fallback art (under
`global_assets/`/`defaults/` in the repo) is versioned by git instead.

## GMCP delivery — `Server.Assets`

On session init the engine emits the complete resolved map (`GmcpEmitter.sendServerAssets`):

```json
{ "gmcp": "Server.Assets", "data": { "admin_bg": "https://…/3f9a1c…e2.png", "shop_kiosk": "/images/global_assets/shop_kiosk.png", … } }
```

The client stores it verbatim as `state.serverAssets` (`applyGmcpPackage.ts`, `Server.Assets`
case) and components receive either the whole map or a pre-plucked URL prop. Keys for which no
file exists yet simply 404 → the client's CSS fallback renders instead; absent keys mean the
unskinned variant renders. Either way nothing breaks — same silent-degradation philosophy as
`voiceUrl: null`.

## The panel reskin pattern

This is the canonical way a window is "reskinned" onto a painted frame. **Staff Control is the
reference implementation** (PRs #1282, #1283, #1287): `AdminPanel.tsx` + the
`.admin-dialog-skinned` block in `web-v3/src/styles.css` (~line 25955). Mechanism in one
sentence: *the painted PNG is stretched full-bleed behind the dialog, the dialog's aspect ratio
is locked to the art's pixel dimensions, and live content is absolutely positioned into the
boxes the artist painted, using percentage insets measured off the artwork.*

There is no 9-slice and no PixiJS involved in panel frames (9-slice appears only in the carved
multi-piece sets noted above). It is plain CSS + React.

### Checklist for reskinning a panel

1. **Commission the frame** at fixed pixel dimensions with the content regions painted as
   visually empty boxes. Established sizes: full-screen boards `1456×1093`, Staff Control
   `1456×1024`, Monster Manual `1280×853`, Dice table `1280×960` — match an existing size
   when the layout is similar.
2. **Register the key** in `DEFAULT_GLOBAL_ASSETS` (`<panel>_bg` → `global_assets/<panel>_bg.png`)
   with a comment describing the fallback.
3. **Thread the prop**: the component takes `backgroundImage: string | null` (and optionally
   `serverAssets: Record<string, string>` for control overlays); the composition root passes
   `state.serverAssets["<panel>_bg"] ?? null`.
4. **Inject CSS variables** (see `AdminPanel.tsx` ~493–509):

   ```tsx
   const skinVars: Record<string, string> = {};
   if (backgroundImage) skinVars["--admin-bg"] = `url("${backgroundImage}")`;
   if (serverAssets.staff_action_btn) {
     skinVars["--admin-action-btn"] = `url("${serverAssets.staff_action_btn}")`;
     skinVars["--admin-action-edge"] = "transparent";   // suppress the CSS fallback chrome
     skinVars["--admin-action-radius"] = "0px";
     skinVars["--admin-action-shadow"] = "none";
   }
   ```

5. **Toggle the skinned class** off prop presence — the unskinned dialog must keep working:

   ```tsx
   className={`popout-dialog admin-dialog${backgroundImage ? " admin-dialog-skinned" : ""}`}
   style={Object.keys(skinVars).length > 0 ? skinVars : undefined}
   ```

6. **Write the skinned CSS** — aspect-ratio lock + full-bleed art + zero padding:

   ```css
   .admin-dialog.admin-dialog-skinned {
     position: relative;
     width: min(95vw, 132vh);          /* cap by both axes so the frame never crops */
     aspect-ratio: 1456 / 1024;        /* the art's exact pixel dimensions */
     padding: 0;                       /* content insets handle all spacing */
     background: var(--admin-bg, none) center / 100% 100% no-repeat;
     border: none;
     overflow: hidden;
   }
   ```

7. **Seat content into the painted boxes** with absolute percentage insets (top/right/bottom/left):

   ```css
   .admin-dialog-skinned .admin-status-strip { position: absolute; inset: 18.8% 23% 63.2% 16.4%; }
   .admin-dialog-skinned .admin-main-pane    { position: absolute; inset: 39% 3% 11.5% 3%; }
   ```

   **Measuring convention:** because the art is stretched `100% / 100%`, image-space percentages
   map directly onto the dialog. Open the PNG, measure each painted box's pixel bounds, divide
   by the art dimensions: `inset-top = boxTop/artHeight`, `inset-right = (artWidth−boxRight)/artWidth`,
   etc. Document the measured regions in a CSS comment next to the rules (see the
   `admin_bg` comment block in `styles.css`) so the next person doesn't re-derive them.
8. **Replace painted-over chrome** — hide the standard header/title/close when skinned and float
   replacements at art-defined positions (e.g. `.admin-skin-close` at `top: 2.2%; right: 2.4%`).
9. **Verify the fallback** — render with `backgroundImage={null}`: the unskinned dialog must be
   fully usable. Also verify a present-but-404 URL (the local default until art is uploaded)
   doesn't break layout.
10. **Ship the art** — Arcanum uploads the hashed file to R2 and adds the key to the overlay's
    `globalAssets`. Until then the panel renders its CSS fallback everywhere except environments
    that bundle the PNG.

### Phone-portrait companions (drawer skins)

Drawer-skinned panels (lottery, dice, jukebox) carry a second painting for portrait
viewports: a **941×1672 recomposition** (the login-scene portrait stage) registered as
`<panel>_bg_portrait` and passed to the `Drawer` as `skinBgPortrait` (exposed to CSS as
`--skin-bg-portrait`). The skin CSS scopes the landscape frame to
`(min-width: 768px) and (orientation: landscape)` and seats a **second inset map** under
`(orientation: portrait)` — same measuring convention, measured off the portrait painting.
Components stay orientation-agnostic except where the art changes capacity (the jukebox
pages 4 songs on the landscape frame's scrolls, 6 on the portrait frame's).

### Fallback hierarchy

Every layer is optional and degrades one step at a time — this ordering is load-bearing:

1. **Entity-authored art** (e.g. a feature's own `backgroundImage`, a zone's room image) — most specific.
2. **Global asset** from `Server.Assets` (this contract).
3. **CSS-only treatment** — gradients, borders, vector shapes (e.g. the CSS door/lever, the
   gilded-glass button gradient behind `staff_action_btn`).

When a painted overlay replaces CSS chrome, the component must explicitly neutralize the
fallback styling via variables (`…-edge: transparent`, `…-radius: 0`, `…-shadow: none` — step 4
above), otherwise both render at once.

## Testing checklist

- Component renders correctly with the asset URL **null** (unskinned variant).
- Component renders correctly with the asset URL **404ing** (CSS fallback, layout intact).
- Skinned layout holds at desktop, tablet, and `95vw`-constrained small windows (the
  `min(95vw, …vh)` cap keeps the aspect ratio; insets are percentage-based so they scale).
- No hardcoded asset paths in the client — everything flows from `Server.Assets`.
- New keys added to `DEFAULT_GLOBAL_ASSETS` appear in `Server.Assets` (it emits the whole map).

## Summary

| Concern | Decision |
|---|---|
| Key registry | `ImagesConfig.DEFAULT_GLOBAL_ASSETS` in `AppConfig.kt`; `snake_case`, `<panel>_bg` for frames |
| URL resolution | `defaults/` & `global_assets/` paths → local `/images/`; anything else → `images.baseUrl` (CDN) |
| Delivery | Full resolved map as `Server.Assets` GMCP at session init; client never builds paths |
| Cache-busting | Arcanum content-hashed filenames remapped via the lore overlay (no query strings) |
| Reskin mechanism | Full-bleed CSS background + `aspect-ratio` lock + absolute percentage insets; `-skinned` class variant |
| Reference implementation | Staff Control: `AdminPanel.tsx` + `.admin-dialog-skinned` in `styles.css` |
| Fallbacks | entity art → global asset → CSS-only; null key → unskinned dialog |
| Transport scope | Web client only; telnet unaffected |
