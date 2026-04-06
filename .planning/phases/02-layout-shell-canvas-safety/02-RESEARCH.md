# Phase 2: Layout Shell & Canvas Safety - Research

**Researched:** 2026-04-05
**Domain:** CSS responsive layout, PixiJS VRAM management, iOS Safari canvas safety
**Confidence:** HIGH

## Summary

Phase 2 creates a portrait-first responsive layout shell wrapping the existing AppShell and implements aggressive VRAM management for the PixiJS canvas to prevent iOS Safari crashes. The existing codebase already has partial mobile support (960px/480px breakpoints, `100dvh`, safe-area-inset on action bar) but needs restructuring into a proper CanvasZone/HudSlot/InteractionZone model with new breakpoints (768px/1024px) and comprehensive safe-area coverage.

The PixiJS VRAM situation is better than initially feared. The project uses PixiJS 8.16.0, which includes the fix for the critical VRAM regression (#11331/PR #11343). However, PixiJS 8.15+ deprecated the old `TextureGCSystem` in favor of a new time-based `GCSystem` with options `gcActive`, `gcMaxUnusedTime` (default 60000ms), and `gcFrequency` (default 30000ms). The CANVAS-02 requirement for 5-second texture GC maps directly to `gcMaxUnusedTime: 5000` passed to `Application.init()`. Beyond built-in GC, a lightweight VRAM budget tracker should be built to enforce the 64MB/256MB caps and trigger fallback behaviors.

**Primary recommendation:** Use CSS Grid with named template areas for the LayoutShell component (cleaner breakpoint transitions via `grid-template-areas` changes), configure PixiJS 8.16's new `GCSystem` with aggressive mobile settings, and add a `VRAMBudgetManager` utility class alongside SceneManager for budget tracking and fallback triggers.

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
- **D-01:** Canvas dominant layout on mobile -- canvas fills ~60% of viewport height as a floor, using `clamp(60%, 65vh, 75%)` so taller phones get more canvas (up to 75%)
- **D-02:** Below the canvas: HUD slot (~60px) for HP/mana/XP bars, then InteractionZone (remaining space) for panels/sheets
- **D-03:** On desktop (1024px+), canvas fills 100% of viewport; panels render as floating overlays positioned on top of the canvas (full-viewport overlay mode, not sidebar)
- **D-04:** New `LayoutShell` component wraps `AppShell` -- handles viewport zones (CanvasZone, HudSlot, InteractionZone), safe area insets, and responsive breakpoints. AppShell retains its state wiring role unchanged.
- **D-05:** Standard 3-tier breakpoints: mobile < 768px, tablet 768-1023px, desktop >= 1024px (replaces current 960px/480px breakpoints)
- **D-06:** CSS strategy is Claude's discretion (grid vs flexbox) -- pick what works best during implementation
- **D-07:** Aggressive VRAM management: 5-second GC idle timer + VRAM budget cap (~64MB mobile, ~256MB desktop) + destroy all textures on scene transitions + texture pool reuse + fallback to 1x resolution and disable parallax if budget exceeded + log warnings at 80% budget
- **D-08:** DPR capped at 2x on mobile devices (CANVAS-03)
- **D-09:** Reposition existing panels into new LayoutShell zones on mobile -- action bar moves into InteractionZone, top banner shrinks into HUD slot. Transitional appearance is acceptable; everything must remain functional.
- **D-10:** Terminal/combat log hidden on mobile entirely in this phase. Players rely on existing popouts and canvas for feedback until Phase 5 adds toasts. Desktop keeps terminal as-is.

### Claude's Discretion
- CSS layout strategy for LayoutShell (grid with named areas vs flexbox column)
- VRAM manager architecture (standalone utility vs integrated in SceneManager)
- Exact safe-area-inset CSS implementation details (top/left/right coverage)
- How to handle the tablet breakpoint (768-1023px) -- interpolation between mobile and desktop

### Deferred Ideas (OUT OF SCOPE)
None -- discussion stayed within phase scope
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| LAYOUT-01 | Portrait-first responsive layout (375px -> 1440px+) as single component tree | LayoutShell with CSS Grid named areas; three-tier breakpoints (mobile < 768, tablet 768-1023, desktop >= 1024); existing 100dvh pattern maintained |
| LAYOUT-02 | Safe area inset handling for notches, Dynamic Island, punch-hole cameras | Add `viewport-fit=cover` to meta tag; apply `env(safe-area-inset-*)` via CSS custom properties on LayoutShell; extend existing action-bar pattern to all edges |
| LAYOUT-03 | Canvas viewport fills upper portion of mobile screen, adapts to all breakpoints | `clamp(60%, 65vh, 75%)` canvas zone on mobile; 100% canvas on desktop with floating overlays; tablet gets interpolated height |
| CANVAS-02 | Aggressive texture GC for iOS (5-second idle instead of 60) | PixiJS 8.16 new `GCSystem`: `gcMaxUnusedTime: 5000, gcFrequency: 5000`; plus manual `texture.source.unload()` on scene transitions |
| CANVAS-03 | Resolution capping on mobile devices for performance | Cap `resolution` parameter in `app.init()` to `Math.min(window.devicePixelRatio, 2)` |
</phase_requirements>

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| pixi.js | 8.16.0 | Canvas rendering, texture GC | Already installed; includes VRAM regression fix (PR #11343) and new GCSystem [VERIFIED: node_modules/pixi.js/package.json] |
| React | 19.2.0 | Component tree, LayoutShell | Already installed [VERIFIED: web-v3/package.json] |
| TypeScript | 5.9.3 | Type safety | Already installed [VERIFIED: web-v3/package.json] |
| Vite | 7.3.1 | Build tooling | Already installed [VERIFIED: web-v3/package.json] |

### Supporting
No additional libraries needed. This phase uses CSS-only layout (Grid/Flexbox), built-in PixiJS GC system, and standard Web APIs (`env()` safe-area-insets, `ResizeObserver`, `window.devicePixelRatio`).

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| CSS Grid named areas | Flexbox column | Flexbox works for simple stacking but Grid makes breakpoint transitions cleaner via `grid-template-areas` changes; Grid recommended for LayoutShell |
| Custom VRAM tracker | No tracking (GC only) | Built-in GC handles idle cleanup but cannot enforce budget caps or trigger quality fallbacks; lightweight tracker is necessary for D-07 |

**Installation:**
```bash
# No new packages needed -- all dependencies already installed
```

## Architecture Patterns

### Recommended Project Structure
```
web-v3/src/
  components/
    LayoutShell.tsx           # NEW: responsive layout zones
    LayoutShell.css           # NEW: or add to styles.css (project convention)
    AppShell.tsx              # MODIFIED: remove layout responsibility, let LayoutShell handle it
    panels/
      PlayPanel.tsx           # MODIFIED: simplified -- just renders PixiCanvas
  canvas/
    PixiCanvas.tsx            # MODIFIED: DPR cap, GC config
    SceneManager.ts           # MODIFIED: texture cleanup on scene transitions
    VRAMBudgetManager.ts      # NEW: budget tracking and fallback triggers
```

**Note on CSS:** The existing project convention puts ALL CSS in `web-v3/src/styles.css` (single file, ~10K+ lines). New LayoutShell styles should follow this convention unless the file becomes unmanageable. [VERIFIED: all existing component styles live in styles.css]

### Pattern 1: LayoutShell with CSS Grid Named Areas

**What:** A new `LayoutShell` component wraps `AppShell` and establishes three viewport zones using CSS Grid named template areas. The grid template changes across breakpoints.

**When to use:** This is the primary layout pattern for the entire phase.

**Example:**
```tsx
// Source: Project decision D-04, CSS Grid standard pattern
export function LayoutShell({ children }: { children: React.ReactNode }) {
  return (
    <div className="layout-shell">
      {children}
    </div>
  );
}
```

```css
/* Mobile-first (< 768px) */
.layout-shell {
  display: grid;
  grid-template-rows: clamp(60%, 65vh, 75%) 60px 1fr;
  grid-template-areas:
    "canvas"
    "hud"
    "interaction";
  height: 100dvh;
  overflow: hidden;
  /* Safe area insets */
  padding-top: env(safe-area-inset-top);
  padding-left: env(safe-area-inset-left);
  padding-right: env(safe-area-inset-right);
}

/* Tablet (768px - 1023px) */
@media (min-width: 768px) {
  .layout-shell {
    grid-template-rows: clamp(55%, 60vh, 70%) 60px 1fr;
  }
}

/* Desktop (>= 1024px) */
@media (min-width: 1024px) {
  .layout-shell {
    grid-template-rows: 1fr;
    grid-template-areas: "canvas";
    /* Canvas fills 100%; panels are floating overlays */
  }
}
```

### Pattern 2: DPR Capping in PixiCanvas

**What:** Cap `resolution` to 2x on mobile to prevent VRAM exhaustion on 3x screens (iPhone Pro/Max).

**When to use:** During PixiJS Application init.

**Example:**
```typescript
// Source: PixiJS Application init docs, CANVAS-03 requirement
const isMobile = window.innerWidth < 1024;
const maxDpr = isMobile ? 2 : window.devicePixelRatio;
const resolution = Math.min(window.devicePixelRatio || 1, maxDpr);

await app.init({
  background: 0x2f3446,
  resizeTo: container,
  antialias: true,
  autoDensity: true,
  resolution,
  // New GCSystem options (PixiJS 8.15+)
  gcActive: true,
  gcMaxUnusedTime: isMobile ? 5000 : 30000,  // 5s mobile, 30s desktop
  gcFrequency: isMobile ? 5000 : 15000,       // check every 5s mobile
});
```

### Pattern 3: VRAM Budget Manager

**What:** A standalone utility that tracks approximate VRAM usage and triggers quality fallbacks when budget is exceeded.

**When to use:** Instantiated alongside SceneManager, consulted before loading textures, runs periodic budget checks.

**Example:**
```typescript
// Source: Decision D-07, PixiJS texture management patterns
export class VRAMBudgetManager {
  private budgetBytes: number;
  private estimatedUsage = 0;
  private readonly WARNING_THRESHOLD = 0.8;

  constructor(isMobile: boolean) {
    this.budgetBytes = isMobile ? 64 * 1024 * 1024 : 256 * 1024 * 1024;
  }

  trackTexture(width: number, height: number, bytesPerPixel = 4): void {
    this.estimatedUsage += width * height * bytesPerPixel;
    if (this.estimatedUsage > this.budgetBytes * this.WARNING_THRESHOLD) {
      console.warn(`[VRAM] Budget ${Math.round(this.usagePercent())}% used`);
    }
  }

  releaseTexture(width: number, height: number, bytesPerPixel = 4): void {
    this.estimatedUsage = Math.max(0, this.estimatedUsage - width * height * bytesPerPixel);
  }

  isOverBudget(): boolean {
    return this.estimatedUsage > this.budgetBytes;
  }

  usagePercent(): number {
    return (this.estimatedUsage / this.budgetBytes) * 100;
  }
}
```

### Pattern 4: Safe Area Inset CSS Custom Properties

**What:** Map `env()` safe-area-inset values to CSS custom properties on LayoutShell for easier consumption by child components.

**Example:**
```css
/* Source: WebKit safe-area-inset documentation */
.layout-shell {
  --safe-top: env(safe-area-inset-top, 0px);
  --safe-bottom: env(safe-area-inset-bottom, 0px);
  --safe-left: env(safe-area-inset-left, 0px);
  --safe-right: env(safe-area-inset-right, 0px);

  padding-top: var(--safe-top);
  padding-left: var(--safe-left);
  padding-right: var(--safe-right);
  /* Bottom inset handled by InteractionZone / action bar */
}
```

### Anti-Patterns to Avoid
- **Using `100vh` instead of `100dvh`:** iOS Safari address bar causes `100vh` to extend behind the toolbar. The existing codebase correctly uses `100dvh`; maintain this. [VERIFIED: styles.css line 6880]
- **Applying `viewport-fit=cover` without safe-area padding:** Content will be obscured by notches/Dynamic Island. Always pair with `env(safe-area-inset-*)`. [CITED: webkit.org/blog/7929]
- **Using deprecated `textureGCMaxIdle` (frame-based):** PixiJS 8.15+ deprecated TextureGCSystem. Use new `gcMaxUnusedTime` (ms-based) on GCSystem instead. [VERIFIED: node_modules TextureGCSystem.mjs shows deprecation warnings since 8.15.0]
- **Setting `dvh` on deeply nested elements:** Only use `dvh` on the outermost container. Nested `dvh` causes layout jank as the iOS address bar animates. [CITED: medium.com/@tharunbalaji110]
- **Destroying textures that PixiJS Assets cache still references:** Use `Assets.unload(key)` or `texture.source.unload()` (GPU only), not `texture.destroy()` which invalidates the cache entry. [CITED: pixijs.com/8.x/guides/components/textures]

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Texture garbage collection | Custom timer-based texture destroyer | PixiJS 8.16 `GCSystem` (`gcMaxUnusedTime: 5000`) | Built-in, handles edge cases (render groups, instruction sets), already integrated with renderer lifecycle |
| Device pixel ratio detection | Navigator.userAgent sniffing | `window.devicePixelRatio` + `Math.min(dpr, 2)` | Standard API, works across all browsers, no UA parsing fragility |
| Safe area inset detection | JavaScript measurement of notch dimensions | CSS `env(safe-area-inset-*)` | CSS-native, auto-updates on orientation change, zero JS overhead |
| Responsive breakpoint detection | `window.matchMedia` polling | CSS `@media` queries with Grid template changes | CSS-only is simpler, no React re-renders, works with SSR |
| iOS address bar height | JavaScript height measurement | `100dvh` CSS unit | Natively tracks dynamic viewport, no JS polling or resize listeners |

**Key insight:** This phase is largely CSS + PixiJS configuration. The only new logic that needs to be written is the VRAM budget tracker (since PixiJS GC handles idle cleanup but not budget caps) and the LayoutShell React component (thin wrapper).

## Common Pitfalls

### Pitfall 1: Missing `viewport-fit=cover` in HTML Meta Tag
**What goes wrong:** Safe area inset CSS variables all resolve to `0px`, content renders in "safe" rectangle only, wasting screen real estate on notched devices.
**Why it happens:** The current `index.html` meta tag is `content="width=device-width, initial-scale=1.0"` without `viewport-fit=cover`. [VERIFIED: web-v3/index.html line 5]
**How to avoid:** Add `viewport-fit=cover` to the viewport meta tag AND apply `env(safe-area-inset-*)` padding on the layout shell.
**Warning signs:** On iPhone, content appears pushed away from edges even when there is no notch overlap.

### Pitfall 2: PixiJS 8.15+ GC API Change (Deprecated TextureGCSystem)
**What goes wrong:** Using `textureGCMaxIdle` (frame-based) triggers deprecation warnings and proxies through to the new system with a lossy conversion.
**Why it happens:** PixiJS 8.15.0 introduced `GCSystem` with millisecond-based `gcMaxUnusedTime`, deprecating the frame-based TextureGCSystem.
**How to avoid:** Use the new options directly: `gcActive: true, gcMaxUnusedTime: 5000, gcFrequency: 5000`. [VERIFIED: GCSystem.mjs default options and TextureGCSystem.mjs deprecation notices in node_modules]
**Warning signs:** Console deprecation warnings mentioning "TextureGCSystem.run is deprecated, please use the GCSystem instead".

### Pitfall 3: Canvas ResizeObserver + Grid Layout Fight
**What goes wrong:** PixiJS canvas uses `resizeTo: container` which triggers `ResizeObserver`. When the Grid row height uses relative units (`clamp`, `vh`), rapid viewport changes (iOS address bar animation) cause a resize feedback loop.
**Why it happens:** Grid row recalculation triggers container resize, which triggers PixiJS resize, which may trigger layout recalculation.
**How to avoid:** Use `overflow: hidden` on the canvas zone container and debounce the ResizeObserver callback. The existing PixiCanvas already creates a ResizeObserver -- just ensure the container's size is controlled by Grid alone.
**Warning signs:** Canvas flickers during iOS Safari scroll, layout jank when address bar animates.

### Pitfall 4: Breakpoint Migration Breaks Existing Panels
**What goes wrong:** Changing breakpoints from 960px/480px to 768px/1024px causes existing panel styles to render at wrong breakpoints.
**Why it happens:** Many CSS rules use `@media (max-width: 959px)` and `@media (min-width: 960px)`. Changing to 768/1024 without updating all existing rules creates a dead zone.
**How to avoid:** Phase the breakpoint migration: add the new LayoutShell breakpoints (768/1024) for new layout code while keeping old breakpoints for existing panel styles. Migrate old breakpoints incrementally within the same phase.
**Warning signs:** Panels that worked at 960px suddenly break at 768px.

### Pitfall 5: VRAM Budget Estimation Inaccuracy
**What goes wrong:** Budget tracker reports VRAM usage far below actual, causing iOS crash despite "safe" readings.
**Why it happens:** VRAM estimation based on `width * height * 4` ignores mipmaps (add ~33%), compressed textures, render targets, and WebGL internal overhead.
**How to avoid:** Use a conservative multiplier (1.5x raw calculation), and treat the budget as a soft limit with early warnings at 80%.
**Warning signs:** iOS tab crash despite budget tracker showing <100%.

### Pitfall 6: Desktop Overlay Mode Breaks Click-Through
**What goes wrong:** On desktop (D-03), floating overlay panels block canvas interaction even when closed/hidden.
**Why it happens:** Overlay containers with `position: absolute; inset: 0` capture pointer events for the entire viewport.
**How to avoid:** Use `pointer-events: none` on the overlay container; `pointer-events: auto` only on visible panel children.
**Warning signs:** Canvas compass and entity clicks stop working when any panel was previously opened.

## Code Examples

Verified patterns from the existing codebase and official sources:

### Viewport Meta Tag Fix
```html
<!-- Source: web-v3/index.html line 5 — MUST be changed -->
<!-- Before: -->
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<!-- After: -->
<meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover" />
```

### PixiJS 8.16 Application Init with GC Configuration
```typescript
// Source: PixiJS GCSystem.mjs (verified in node_modules), GCSystem.d.ts
const isMobile = window.innerWidth < 1024;

await app.init({
  background: 0x2f3446,
  resizeTo: container,
  antialias: true,
  autoDensity: true,
  resolution: Math.min(window.devicePixelRatio || 1, isMobile ? 2 : Infinity),
  // New GCSystem options (PixiJS 8.15+, replaces deprecated textureGC* options)
  gcActive: true,
  gcMaxUnusedTime: isMobile ? 5000 : 60000,   // 5s on mobile per CANVAS-02
  gcFrequency: isMobile ? 5000 : 30000,        // match frequency to max idle
});
```

### Scene Transition Texture Cleanup
```typescript
// Source: SceneManager.ts switchTo method — add texture cleanup
private switchTo(scene: SceneName) {
  if (scene === this.currentScene) return;

  if (scene === "battle") {
    this.worldScene.container.visible = false;
    this.worldScene.overlayContainer.visible = false;
    // Force unload world scene textures from GPU
    this.worldScene.unloadTextures();
    this.battleScene.container.visible = true;
    this.battleScene.enter();
  } else {
    this.battleScene.container.visible = false;
    // Force unload battle scene textures from GPU
    this.battleScene.unloadTextures();
    this.worldScene.container.visible = true;
    this.worldScene.overlayContainer.visible = true;
    canvasEvents.drain();
  }

  this.currentScene = scene;
}
```

### Existing Safe Area Pattern (extend to all edges)
```css
/* Source: styles.css line 6910 — existing pattern for bottom only */
.action-bar {
  padding-bottom: max(0px, env(safe-area-inset-bottom));
}

/* Extended pattern for LayoutShell — all four edges */
.layout-shell {
  padding-top: env(safe-area-inset-top, 0px);
  padding-left: env(safe-area-inset-left, 0px);
  padding-right: env(safe-area-inset-right, 0px);
  /* Bottom inset applied to the interaction zone, not the shell itself */
}

.layout-shell__interaction {
  padding-bottom: env(safe-area-inset-bottom, 0px);
}
```

### LayoutShell Component Structure
```tsx
// Source: Decision D-04, wraps existing AppShell
export function LayoutShell({ children }: { children: React.ReactNode }) {
  return (
    <div className="layout-shell">
      {children}
    </div>
  );
}

// In App.tsx:
function App() {
  return (
    <GameStateProvider>
      <LayoutShell>
        <AppShell />
      </LayoutShell>
    </GameStateProvider>
  );
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `textureGCMaxIdle` (frame-based) | `gcMaxUnusedTime` (ms-based) via `GCSystem` | PixiJS 8.15.0 | Must use new API; old one is deprecated proxy [VERIFIED: TextureGCSystem.mjs] |
| `TextureGCSystem` class | `GCSystem` class | PixiJS 8.15.0 | New system manages all GPU resources, not just textures [VERIFIED: GCSystem.mjs] |
| `100vh` for mobile full-height | `100dvh` / `100svh` | CSS spec, Safari 15.4+ | `100dvh` accounts for dynamic browser chrome; already used in project [VERIFIED: styles.css line 6880] |
| `@media (max-width: 959px)` breakpoint | `@media (max-width: 767px)` / `@media (min-width: 1024px)` | Phase 2 decision D-05 | Aligns with industry-standard 3-tier breakpoints |

**Deprecated/outdated:**
- `textureGCMaxIdle`, `textureGCCheckCountMax`, `textureGCActive`: All deprecated since PixiJS 8.15.0. They proxy to the new `GCSystem` but with lossy frame-to-ms conversion. [VERIFIED: TextureGCSystem.mjs deprecation notices]
- `PIXI.settings.RESOLUTION`: Global resolution setting was deprecated in PixiJS 8. Pass `resolution` to `Application.init()` instead. [CITED: pixijs.com/8.x/guides/migrations/v8]

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `gcMaxUnusedTime` and `gcFrequency` can be passed directly to `Application.init()` as top-level options | Architecture Patterns, Code Examples | If these are renderer-only options, init would need `renderer.gc.maxUnusedTime = 5000` post-init instead. Low risk -- the type chain suggests they flow through SharedRendererOptions |
| A2 | 64MB VRAM budget is safe for mid-range mobile devices (2-year-old phones) | Pitfalls, VRAM Budget | iOS devices have ~200-400MB WebGL VRAM; 64MB budget is conservative. Could be too conservative for content-heavy rooms |
| A3 | `texture.source.unload()` frees GPU memory while keeping the asset in PixiJS Assets cache for re-upload | Code Examples | If this also removes the cache entry, scenes would need to re-fetch assets from the server on revisit |
| A4 | The VRAM regression fix (PR #11343) is included in PixiJS 8.16.0 | Summary | PR merged May 2025 to dev branch; 8.16.0 released Feb 2026. Highly likely included given the timeline, and verified code in gpuUploadImageSource.mjs matches PR description |

## Open Questions

1. **GCSystem options passthrough to Application.init()**
   - What we know: `GCSystemOptions` interface exists, `GCSystem` is in `SharedSystems`, renderer options extend through to `ApplicationOptions`
   - What's unclear: Whether TypeScript compiler accepts `gcMaxUnusedTime` as a top-level `app.init()` option or requires post-init configuration
   - Recommendation: Try top-level first; fall back to `app.renderer.gc.maxUnusedTime = 5000` after init if type error occurs

2. **Tablet breakpoint behavior (768-1023px)**
   - What we know: User left this as Claude's discretion; it should interpolate between mobile and desktop
   - What's unclear: Exact canvas height ratio for tablets; whether overlay mode or zone mode works better
   - Recommendation: Use zone mode (like mobile) but with larger canvas proportion `clamp(55%, 60vh, 70%)` and slightly taller interaction zone

3. **Existing `@media (max-width: 959px)` rules coexistence**
   - What we know: 17+ existing media queries at 959px/960px/480px breakpoints in styles.css
   - What's unclear: Whether the LayoutShell can use new breakpoints (768/1024) while old panel styles keep old breakpoints temporarily
   - Recommendation: Yes -- LayoutShell uses new breakpoints; existing panel breakpoints can be migrated incrementally since both Grid and old Flex rules can coexist

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Bun | Web client build | Yes | 1.3.10 | -- |
| PixiJS 8.16 | Canvas rendering + GCSystem | Yes | 8.16.0 | -- |
| React 19 | Component tree | Yes | 19.2.0 | -- |
| CSS `env()` safe-area-inset | LAYOUT-02 | Yes | All target browsers | Graceful fallback to 0px via `env(..., 0px)` syntax |
| CSS `dvh` units | Viewport height | Yes | Safari 15.4+, Chrome 108+ | Existing `100dvh` already in use |
| CSS Grid `grid-template-areas` | Layout Shell | Yes | All modern browsers | Flexbox column fallback (not needed) |

**Missing dependencies with no fallback:** None
**Missing dependencies with fallback:** None

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | None currently installed for web-v3 |
| Config file | None -- Wave 0 must establish |
| Quick run command | N/A |
| Full suite command | `bun run lint` (ESLint only, no test runner) |

### Phase Requirements -> Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| LAYOUT-01 | LayoutShell renders three zones at mobile breakpoint | manual-only | Visual inspection via `./gradlew demo` | N/A |
| LAYOUT-02 | Safe area insets applied when viewport-fit=cover set | manual-only | Real device testing (iOS simulator/device) | N/A |
| LAYOUT-03 | Canvas fills upper portion, adapts at breakpoints | manual-only | Chrome DevTools responsive mode | N/A |
| CANVAS-02 | GCSystem configured with 5s idle on mobile | unit | Could test VRAMBudgetManager logic | Wave 0 |
| CANVAS-03 | DPR capped at 2x on mobile | unit | Could verify cap logic | Wave 0 |

**Justification for manual-only:** CSS layout, safe-area-insets, and canvas rendering behavior cannot be meaningfully tested without a real browser environment. ESLint catches type errors. Visual verification via `./gradlew demo` and Chrome DevTools responsive mode is the primary validation strategy. The VRAMBudgetManager could have unit tests if a test runner is added.

### Sampling Rate
- **Per task commit:** `bun run lint` (ESLint + TypeScript check)
- **Per wave merge:** `bun run lint` + `bun run build` + visual inspection
- **Phase gate:** Full build success + visual verification at 375px, 768px, 1024px, 1440px viewpoints

### Wave 0 Gaps
- No test framework installed for web-v3 (vitest would be natural fit with Vite, but adding it is out of scope for this phase -- focus is CSS layout + PixiJS config)
- Visual regression testing would require Playwright/Puppeteer -- deferred
- "None -- existing lint infrastructure covers type checking; layout validation is inherently visual"

## Security Domain

This phase involves CSS layout changes and PixiJS configuration only. No authentication, data handling, encryption, or user input processing is involved.

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | No | N/A |
| V3 Session Management | No | N/A |
| V4 Access Control | No | N/A |
| V5 Input Validation | No | N/A |
| V6 Cryptography | No | N/A |

### Known Threat Patterns
None applicable -- this phase is purely client-side layout and rendering configuration with no data flow changes.

## Sources

### Primary (HIGH confidence)
- [VERIFIED: node_modules] PixiJS 8.16.0 GCSystem.mjs -- confirmed `gcActive`, `gcMaxUnusedTime` (default 60000ms), `gcFrequency` (default 30000ms) options
- [VERIFIED: node_modules] PixiJS 8.16.0 TextureGCSystem.mjs -- confirmed deprecation since 8.15.0, proxies to GCSystem
- [VERIFIED: node_modules] PixiJS 8.16.0 gpuUploadImageSource.mjs -- confirmed PR #11343 VRAM fix is included (HTMLImageElement canvas conversion in GPU uploader)
- [VERIFIED: web-v3/package.json] pixi.js ^8.16.0, React ^19.2.0, TypeScript ~5.9.3
- [VERIFIED: web-v3/index.html] Current viewport meta tag lacks `viewport-fit=cover`
- [VERIFIED: web-v3/src/styles.css] Existing breakpoints at 959px, 960px, 480px; `100dvh` usage; `env(safe-area-inset-bottom)` on action-bar only
- [VERIFIED: web-v3/src/canvas/PixiCanvas.tsx] Current DPR uncapped: `resolution: window.devicePixelRatio || 1`

### Secondary (MEDIUM confidence)
- [CITED: pixijs.com/8.x/guides/concepts/garbage-collection] - PixiJS GC documentation (outdated for 8.15+ but principles valid)
- [CITED: github.com/pixijs/pixijs/issues/11331] - VRAM regression issue, closed with PR #11343
- [CITED: webkit.org/blog/7929/designing-websites-for-iphone-x] - Safe area inset original documentation
- [CITED: mohammadshehadeh.com/css/safe-area-insets] - Comprehensive env() safe-area-inset guide
- [CITED: medium.com/@tharunbalaji110] - dvh/svh/lvh viewport unit guide with iOS Safari caveats

### Tertiary (LOW confidence)
None -- all claims verified against installed packages or official documentation.

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH -- all packages verified in node_modules, no new dependencies needed
- Architecture: HIGH -- CSS Grid and PixiJS GC are well-documented, existing codebase patterns are clear
- Pitfalls: HIGH -- verified PixiJS deprecation directly in source, viewport-fit gap confirmed in index.html

**Research date:** 2026-04-05
**Valid until:** 2026-05-05 (30 days -- stable stack, no expected breaking changes)
