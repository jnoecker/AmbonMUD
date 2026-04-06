# Phase 2: Layout Shell & Canvas Safety - Context

**Gathered:** 2026-04-05
**Status:** Ready for planning

<domain>
## Phase Boundary

The app renders in a portrait-first responsive shell that adapts from 375px to 1440px+, and the PixiJS canvas runs safely on mobile without VRAM crashes or performance degradation. Requirements: LAYOUT-01, LAYOUT-02, LAYOUT-03, CANVAS-02, CANVAS-03.

</domain>

<decisions>
## Implementation Decisions

### Canvas/UI Split
- **D-01:** Canvas dominant layout on mobile -- canvas fills ~60% of viewport height as a floor, using `clamp(60%, 65vh, 75%)` so taller phones get more canvas (up to 75%)
- **D-02:** Below the canvas: HUD slot (~60px) for HP/mana/XP bars, then InteractionZone (remaining space) for panels/sheets
- **D-03:** On desktop (1024px+), canvas fills 100% of viewport; panels render as floating overlays positioned on top of the canvas (full-viewport overlay mode, not sidebar)

### Layout Shell Design
- **D-04:** New `LayoutShell` component wraps `AppShell` -- handles viewport zones (CanvasZone, HudSlot, InteractionZone), safe area insets, and responsive breakpoints. AppShell retains its state wiring role unchanged.
- **D-05:** Standard 3-tier breakpoints: mobile < 768px, tablet 768-1023px, desktop >= 1024px (replaces current 960px/480px breakpoints)
- **D-06:** CSS strategy is Claude's discretion (grid vs flexbox) -- pick what works best during implementation

### VRAM Budget Strategy
- **D-07:** Aggressive VRAM management: 5-second GC idle timer + VRAM budget cap (~64MB mobile, ~256MB desktop) + destroy all textures on scene transitions + texture pool reuse + fallback to 1x resolution and disable parallax if budget exceeded + log warnings at 80% budget
- **D-08:** DPR capped at 2x on mobile devices (CANVAS-03)

### Existing Panel Impact
- **D-09:** Reposition existing panels into new LayoutShell zones on mobile -- action bar moves into InteractionZone, top banner shrinks into HUD slot. Transitional appearance is acceptable; everything must remain functional.
- **D-10:** Terminal/combat log hidden on mobile entirely in this phase. Players rely on existing popouts and canvas for feedback until Phase 5 adds toasts. Desktop keeps terminal as-is.

### Claude's Discretion
- CSS layout strategy for LayoutShell (grid with named areas vs flexbox column)
- VRAM manager architecture (standalone utility vs integrated in SceneManager)
- Exact safe-area-inset CSS implementation details (top/left/right coverage)
- How to handle the tablet breakpoint (768-1023px) -- interpolation between mobile and desktop

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Layout and responsive design
- `web-v3/src/App.tsx` -- Slim entry point wrapping GameStateProvider + AppShell (post-Phase 1)
- `web-v3/src/AppShell.tsx` -- ~1000-line composition root; state wiring, socket, GMCP, UI dispatch
- `web-v3/src/components/PlayPanel.tsx` -- Current canvas + terminal stacking; will be restructured
- `web-v3/src/styles.css` -- All CSS custom properties and responsive breakpoints (search for @media, .app-shell, .panel-play, .pixi-canvas-host)
- `.planning/REQUIREMENTS.md` -- LAYOUT-01 (portrait-first), LAYOUT-02 (safe area insets), LAYOUT-03 (canvas viewport)

### Canvas and PixiJS
- `web-v3/src/canvas/PixiCanvas.tsx` -- Canvas initialization, ResizeObserver, DPR setting (currently uncapped)
- `web-v3/src/canvas/SceneManager.ts` -- Scene lifecycle management, resize propagation
- `web-v3/src/canvas/scenes/WorldScene.ts` -- Entity sprite management, mobile text scaling, minimap sizing
- `web-v3/src/canvas/scenes/BattleScene.ts` -- Combat scene rendering
- `.planning/REQUIREMENTS.md` -- CANVAS-02 (5s texture GC for iOS), CANVAS-03 (DPR cap at 2x)

### Design system
- `docs/STYLE_GUIDE.md` -- Color tokens, typography, motion, component states
- `.impeccable.md` -- Design context, brand personality, aesthetic direction

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `PixiCanvas.tsx` ResizeObserver pattern -- already handles canvas resize, just needs DPR capping added
- `WorldScene.ts` mobile-aware scaling -- `Math.max(0.6, Math.min(1.0, w / 700))` for text, `MINIMAP_MOBILE` vs `MINIMAP_DESKTOP` for minimap
- `.action-bar` safe-area CSS -- `padding-bottom: max(0px, env(safe-area-inset-bottom))` pattern to extend to other edges
- Existing sprite `.destroy()` calls in WorldScene -- foundation for expanded VRAM management

### Established Patterns
- `100dvh` used in `.app-shell` for iOS Safari address bar handling -- maintain this
- CSS custom properties in `styles.css` for all colors/spacing -- new layout classes must use these
- PlayPanel renders PixiCanvas + CombatLog with flex stacking -- this structure will be replaced by LayoutShell zones

### Integration Points
- `LayoutShell` wraps around `AppShell` -- GameStateProvider nests inside, 7 domain contexts unaffected
- `PixiCanvas.tsx` `app.init()` resolution parameter -- cap DPR here
- `SceneManager.ts` scene transition methods -- add texture destruction calls
- Existing breakpoints at 960px and 480px in `styles.css` -- will be migrated to 768px/1024px

</code_context>

<specifics>
## Specific Ideas

No specific requirements -- open to standard approaches

</specifics>

<deferred>
## Deferred Ideas

None -- discussion stayed within phase scope

</deferred>

---

*Phase: 02-layout-shell-canvas-safety*
*Context gathered: 2026-04-05*
