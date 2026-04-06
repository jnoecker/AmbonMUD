# Phase 3: Bottom Sheet, HUD & PWA Foundation - Research

**Researched:** 2026-04-05
**Domain:** Mobile UI interaction patterns (bottom sheet, HUD overlay), PWA installability, WebSocket reconnection
**Confidence:** HIGH

## Summary

Phase 3 builds three distinct but interconnected features on top of the Phase 2 LayoutShell: (1) a draggable bottom sheet with snap points for panel access, (2) a compact HUD overlay for always-visible vitals, and (3) PWA enhancements for installability and robust WebSocket reconnection. The codebase already provides strong foundations -- CSS design tokens for HP/mana/XP bar gradients exist, action-bar vitals CSS demonstrates the pattern to adapt for the HUD, the LayoutShell grid has named zones for HUD and interaction placement, and the PWA manifest + service worker are already functional with icons and caching.

The bottom sheet is the highest-complexity deliverable. The user decision (D-02) mandates custom CSS transforms + pointer events with zero new dependencies. Two viable implementation approaches exist: (a) JavaScript-driven pointer event tracking with CSS `transform: translateY()` and snap-on-release logic, or (b) CSS `scroll-snap-type: y mandatory` which offloads snapping to the compositor thread. Given D-02's emphasis on "custom CSS transforms + pointer event handlers" and "full control over snap physics," approach (a) is the correct interpretation -- pointer events drive translateY transforms, with snap thresholds computed in JavaScript. CSS scroll-snap could be explored as a discretionary enhancement but the primary approach should be pointer-driven transforms.

**Primary recommendation:** Build the bottom sheet as a single React component using pointer events + CSS transforms for drag, CSS transitions for snap animations, and CSS `@media (min-width: 1024px)` to reposition as a side panel. HUD component consumes vitals context directly and renders in the existing `layout-shell__hud` grid zone. PWA work enhances existing manifest/sw.js files and upgrades the 500ms reconnect retry to exponential backoff with jitter.

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
- **D-01:** Three snap points: Peek (~15%, tab bar visible), Half (~50%, quick interactions), Full (~90%, deep content like inventory). Standard 3-stop pattern like Apple/Google Maps.
- **D-02:** Custom CSS transforms + pointer event handlers -- no external gesture or animation libraries. Zero new dependencies, full control over snap physics.
- **D-03:** Tap outside the sheet (on canvas or HUD) to collapse to peek state.
- **D-04:** Drag handle for continuous resize with snap-on-release + tap tabs to jump to half snap. Full gesture vocabulary: drag to resize, tap tab to open, tap outside to collapse.
- **D-05:** Top 5 pinned icon tabs + overflow menu (three dots button) for remaining panels. Clean tab bar, 1 tap for essentials, 2 taps for the rest via overflow grid.
- **D-06:** Tab bar positioned at top of bottom sheet, below drag handle. Tabs move with the sheet as it snaps between positions.
- **D-07:** Default pinned tabs: Character, Inventory, Combat, Quest, Chat. These cover the core RPG loop + social. Equipment, Crafting, Bank, Auction, Mail, Housing, Leaderboard, Admin accessible via overflow.
- **D-08:** Thin stacked horizontal bars (HP, mana, XP) at 4-6px height each, with numeric values (e.g., "245/312"). Level and class badge displayed above the bars (e.g., "Lv12 Ranger"). Clean, data-dense, familiar RPG style consistent with existing design system.
- **D-09:** Tapping the HUD opens the bottom sheet to half-snap with the Character panel selected. Quick access to full stats as a natural extension of the at-a-glance pattern.
- **D-10:** Subtle banner during reconnection -- slim banner with spinner and "Reconnecting..." text. Game state stays visible underneath. Auto-dismiss on successful reconnect. Extends existing `.reconnect-banner` CSS pattern.
- **D-11:** Full GMCP resync after reconnection -- server sends all packages just like initial login. Client replaces all state. Simple, reliable, no server changes needed.
- **D-12:** Exponential backoff replaces the current simple 500ms retry in App.tsx. Proper backoff with jitter to prevent thundering herd.
- **D-13:** Enhance existing `manifest.json` and `sw.js` in `web-v3/public/` -- don't start from scratch.
- **D-14:** On desktop (1024px+), the bottom sheet renders as a side panel using CSS-only positioning change -- no JavaScript code branching between mobile and desktop modes (PANEL-03).

### Claude's Discretion
- Exact exponential backoff parameters (start delay, cap, max attempts, jitter strategy)
- Overflow menu layout (grid vs list of remaining panels)
- Drag handle visual design (pill indicator, width, color)
- Nested scroll handling strategy within sheet content areas
- Service worker cache versioning and update strategy
- Sheet transition animation timing and easing curves

### Deferred Ideas (OUT OF SCOPE)
None -- discussion stayed within phase scope
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| PANEL-01 | Bottom sheet container with peek/half/full snap points, drag-to-resize, swipe-to-dismiss | Bottom sheet architecture pattern with pointer events + CSS transforms; snap point calculation algorithm |
| PANEL-02 | Panel navigation tab bar with categorized icons for all 14+ panels | Tab bar component with 5 pinned + overflow pattern; existing panel component inventory (14 panels in `panels/` directory) |
| PANEL-03 | Bottom sheet becomes sidebar on desktop breakpoint (same component, CSS positioning) | CSS-only repositioning via `@media (min-width: 1024px)` -- transform axis changes from Y to X, position changes from bottom to right side |
| HUD-01 | Minimal always-visible HUD (HP/mana/XP bars, max ~60px vertical) | Existing color tokens (`--color-hp-light/dark`, `--color-mana-light/dark`, `--color-xp-light/dark`), action-bar-vitals CSS pattern, `layout-shell__hud` grid zone |
| HUD-03 | Level and class badge indicator | `CharacterInfo` type already has `className`, `level`; vitals context from Phase 1 provides these values |
| PWA-01 | Web app manifest (name, icons, theme_color, display: standalone) | Existing manifest.json already meets all installability requirements; needs minor enhancement (screenshots, categories) |
| PWA-02 | Service worker for app shell caching (cache-first static, network-only WebSocket) | Existing sw.js already implements cache-first statics + network-first HTML + WebSocket bypass; needs cache versioning strategy |
| PWA-05 | WebSocket reconnection with exponential backoff and state resync after backgrounding | Current 500ms fixed retry in App.tsx line ~600; upgrade to exponential backoff in `useMudSocket.ts`; GMCP resync via existing `applyGmcpPackage` |
</phase_requirements>

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| React | 19.2.0 | UI framework | Already installed, project standard [VERIFIED: web-v3/package.json] |
| TypeScript | 5.9.3 | Type safety | Already installed, strict mode enabled [VERIFIED: tsconfig.app.json] |
| Vite | 7.3.1 | Build tool | Already installed, handles bundling [VERIFIED: web-v3/package.json] |

### Supporting
No new dependencies required. This phase is explicitly zero-dependency (D-02). All functionality is built with:

| Tool | Purpose | When to Use |
|------|---------|-------------|
| Pointer Events API | Touch/mouse gesture handling | Bottom sheet drag interactions |
| CSS `transform: translateY()` | Sheet positioning + animation | All sheet movement |
| CSS transitions | Snap animation smoothing | Release-to-snap transitions |
| CSS `@media` queries | Desktop side panel layout | 1024px+ breakpoint |
| `navigator.serviceWorker` API | Service worker registration | PWA lifecycle management |
| Web App Manifest | PWA installability | Already linked in index.html |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Custom pointer events | CSS `scroll-snap-type` | Compositor-thread snapping is smoother but gives less control over snap physics; D-02 explicitly chooses pointer events |
| Custom pointer events | @use-gesture/react | Library adds ~8KB gzip; D-02 forbids external gesture libraries |
| Custom transitions | framer-motion / motion | Animation library adds significant bundle; D-02 forbids external animation libraries |
| Custom service worker | Workbox | Google's SW toolkit; overkill for current simple caching needs |

**Installation:**
```bash
# No new packages required
```

## Architecture Patterns

### Recommended Project Structure
```
web-v3/src/
  components/
    BottomSheet.tsx          # Sheet container: drag handle, snap logic, tab bar, content area
    BottomSheet.css          # (or section in styles.css) sheet-specific styles
    HudOverlay.tsx           # HP/mana/XP bars + level/class badge
    TabBar.tsx               # 5 pinned tabs + overflow menu
    OverflowMenu.tsx         # Grid/list of remaining panels
    panels/                  # (existing) 14 panel components -- no changes
  hooks/
    useBottomSheet.ts        # Pointer event tracking, snap calculation, state machine
    useMudSocket.ts          # (existing) WebSocket hook -- add exponential backoff
  public/
    manifest.json            # (existing) Enhanced with screenshots/categories
    sw.js                    # (existing) Enhanced with cache versioning
```

### Pattern 1: Pointer-Driven Bottom Sheet with CSS Transforms

**What:** A React component that tracks pointer events on a drag handle, computes translateY offset, and snaps to predefined positions on release. All positioning uses CSS `transform: translateY(Npx)` for compositor-thread rendering.

**When to use:** Any draggable overlay that needs discrete snap positions with smooth transitions.

**Implementation approach:**
```typescript
// useBottomSheet.ts -- core gesture logic
// [VERIFIED: Pointer Events API supported in all target browsers]
// [CITED: https://developer.mozilla.org/en-US/docs/Web/API/Pointer_events]

type SnapPoint = 'peek' | 'half' | 'full';

interface SheetState {
  currentSnap: SnapPoint;
  translateY: number;       // current pixel offset from top
  isDragging: boolean;
}

// Snap positions as percentages of viewport height
const SNAP_POSITIONS = {
  peek: 0.85,   // 85% from top = 15% visible
  half: 0.50,   // 50% from top = 50% visible
  full: 0.10,   // 10% from top = 90% visible
} as const;

// On pointerdown: record start position, set isDragging
// On pointermove: compute delta, update translateY directly on DOM element
// On pointerup: find nearest snap point, transition to it
// CSS: transition: transform 300ms var(--ease-out-soft) when NOT dragging
//      transition: none when dragging (immediate response)
```

**Critical details:**
- Use `touch-action: none` on the drag handle to prevent browser scroll interference [CITED: https://developer.mozilla.org/en-US/docs/Web/CSS/touch-action]
- Use `will-change: transform` on the sheet container for GPU compositing [ASSUMED]
- Set `pointer-events: none` on the sheet host when collapsed to allow canvas interaction (same pattern as Phase 2 desktop overlays) [VERIFIED: styles.css line 6887]
- During drag, update `element.style.transform` directly (not via React state) to avoid re-renders on every pointermove frame [CITED: https://nolanlawson.com/2019/08/11/high-performance-input-handling-on-the-web/]

### Pattern 2: CSS-Only Desktop Side Panel (PANEL-03)

**What:** At 1024px+ breakpoint, the bottom sheet repositions to the right side of the viewport using only CSS. The same component renders in both modes -- no JavaScript branching.

**When to use:** Responsive layout where the same component serves different viewport roles.

**Implementation approach:**
```css
/* Mobile: bottom sheet */
.bottom-sheet {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  transform: translateY(var(--sheet-translate-y, 85%));
  transition: transform 300ms var(--ease-out-soft);
  /* ... */
}

/* Desktop: side panel */
@media (min-width: 1024px) {
  .bottom-sheet {
    /* Override to side-panel positioning */
    position: absolute;       /* Within layout-shell__interaction */
    bottom: auto;
    top: 0;
    left: auto;
    right: 0;
    width: min(380px, 30vw);
    height: 100%;
    transform: none;          /* Disable translateY -- always fully visible */
    border-radius: var(--radius-lg) 0 0 var(--radius-lg);
  }

  /* Tab bar becomes vertical or stays horizontal at top */
  .bottom-sheet__tab-bar {
    /* Adjust layout for side panel context */
  }

  /* Drag handle hidden on desktop */
  .bottom-sheet__drag-handle {
    display: none;
  }
}
```

**Key insight:** The JavaScript pointer event logic can remain attached but will never fire because the drag handle is `display: none` and the sheet is never translateY-positioned on desktop. Zero JS branching needed.

### Pattern 3: HUD Overlay in Layout Grid Zone

**What:** A thin overlay component that renders HP/mana/XP bars and level/class badge within the `layout-shell__hud` grid zone (60px allocated by Phase 2).

**When to use:** Always-visible game status that must not interfere with primary content.

**Implementation approach:**
```typescript
// HudOverlay.tsx
// Consumes vitals + character from React context (Phase 1)
// Renders thin bars (4-6px each) with numeric overlays
// Tapping anywhere on HUD opens bottom sheet to half-snap with Character panel

function HudOverlay({ onTap }: { onTap: () => void }) {
  // Phase 1 context provides vitals and character info
  // const { vitals } = useVitalsContext();
  // const { character } = useCharacterContext();

  return (
    <div className="hud-overlay" onClick={onTap}>
      <div className="hud-badge">Lv{level} {className}</div>
      <div className="hud-bars">
        <HudBar type="hp" current={hp} max={maxHp} />
        <HudBar type="mana" current={mana} max={maxMana} />
        <HudBar type="xp" current={xpIntoLevel} max={xpToNextLevel} />
      </div>
    </div>
  );
}
```

**CSS budget:** The `layout-shell__hud` zone is 60px. With D-08 specifying 4-6px bars with numeric values plus a level/class badge, the layout is approximately:
- Badge line: ~20px (font + padding)
- 3 bars at 6px each: 18px
- Gaps + padding: ~12px
- Total: ~50px -- fits within 60px budget

### Pattern 4: Exponential Backoff with Jitter

**What:** Replace the fixed 500ms reconnect delay with exponential backoff and random jitter.

**When to use:** Any network reconnection to prevent thundering herd on server recovery.

**Recommended parameters (Claude's discretion):**
```typescript
// [CITED: https://dev.to/hexshift/robust-websocket-reconnection-strategies-in-javascript-with-exponential-backoff-40n1]

const BACKOFF_CONFIG = {
  baseDelay: 500,        // Start at 500ms (matches current behavior for first retry)
  maxDelay: 30_000,      // Cap at 30 seconds
  multiplier: 2,         // Double each attempt: 500, 1000, 2000, 4000, 8000, 16000, 30000
  jitterRange: 0.5,      // +/- 50% randomization (e.g., 1000ms becomes 500-1500ms)
  maxAttempts: 15,        // Give up after ~2 minutes total
};

function getBackoffDelay(attempt: number): number {
  const exponential = Math.min(
    BACKOFF_CONFIG.baseDelay * Math.pow(BACKOFF_CONFIG.multiplier, attempt),
    BACKOFF_CONFIG.maxDelay,
  );
  const jitter = exponential * BACKOFF_CONFIG.jitterRange * (Math.random() * 2 - 1);
  return Math.max(0, Math.round(exponential + jitter));
}
```

**Integration point:** The backoff logic wraps `useMudSocket.ts`'s `reconnect` function. The `onClose` handler in App.tsx (line ~600) currently does `window.setTimeout(() => reconnect(), 500)`. This becomes a call to a backoff scheduler that tracks attempt count and computes delay. Attempt counter resets on successful `onOpen`.

### Anti-Patterns to Avoid

- **React state for every pointermove:** Never `setState` on every pointer movement. Update DOM directly via `element.style.transform` during drag, then sync React state only on snap completion. [CITED: High-performance input handling pattern]
- **JavaScript media query branching:** D-14 explicitly forbids JS code branching for desktop vs mobile. All layout differences must be CSS `@media` queries. Do not use `window.matchMedia()` to conditionally render different component trees.
- **Nested scroll conflicts:** When sheet content scrolls (e.g., inventory list), the drag gesture must not interfere. Solution: only initiate sheet drag from the drag handle element, not from content area. Content area uses native overflow-y scroll. [ASSUMED]
- **Fixed viewport height:** Never use `100vh` -- always use `100dvh` to account for iOS Safari's dynamic address bar. This pattern is already established in the codebase. [VERIFIED: styles.css line 6836]
- **Hardcoded pixel snap positions:** Calculate snap positions as percentages of `window.innerHeight` (or container height), not fixed pixel values. Viewport height changes with device rotation and address bar toggling.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Touch/mouse unification | Separate touch + mouse handlers | Pointer Events API | Single API handles both; supported 97%+ browsers [CITED: caniuse.com] |
| Snap physics/spring | Custom spring physics engine | CSS `transition` with cubic-bezier easing | CSS transitions are GPU-composited and match native feel; `var(--ease-out-soft)` already defined [VERIFIED: styles.css line 97] |
| PWA installability detection | Custom `beforeinstallprompt` handler | Browser-native install UX | Phase 3 only needs installability (PWA-01), not custom install prompts (PWA-03 is Phase 9) |
| Service worker framework | Custom cache management | Hand-written sw.js (already exists) | Current sw.js is simple and correct; Workbox would be overkill [VERIFIED: sw.js] |
| Bar gradients | New gradient definitions | Existing CSS tokens | `--color-hp-light/dark`, `--color-mana-light/dark`, `--color-xp-light/dark` already defined [VERIFIED: styles.css lines 29-34] |

**Key insight:** The project already has extensive CSS infrastructure for bars, gradients, easing curves, safe-area handling, and responsive breakpoints. The HUD and bottom sheet should compose these existing tokens rather than introducing new design primitives.

## Common Pitfalls

### Pitfall 1: iOS Safari Bottom Sheet vs Address Bar
**What goes wrong:** The iOS Safari address bar resizes the viewport dynamically. A bottom sheet that uses `100vh` for snap position calculation will have incorrect snap points when the address bar shows/hides.
**Why it happens:** `100vh` is a static value in Safari that represents the maximum viewport. `100dvh` (dynamic viewport height) changes as the address bar toggles.
**How to avoid:** Use `window.innerHeight` for JavaScript snap calculations (it reflects dynamic viewport), and `100dvh` in CSS. The codebase already uses `100dvh` consistently. [VERIFIED: styles.css line 6836]
**Warning signs:** Sheet "jumps" when scrolling content causes address bar to show/hide.

### Pitfall 2: Pointer Event Capture and Canvas Interaction
**What goes wrong:** If the bottom sheet captures pointer events globally, the PixiJS canvas stops receiving touch/click input. Users cannot navigate the game world while the sheet is at peek position.
**Why it happens:** `pointer-events: all` on the sheet overlay blocks events from reaching the canvas underneath.
**How to avoid:** Sheet container uses `pointer-events: none`. Only the visible sheet area (drag handle, tab bar, content) uses `pointer-events: auto`. The Phase 2 desktop overlay already demonstrates this exact pattern. [VERIFIED: styles.css lines 6884-6893]
**Warning signs:** Cannot tap canvas compass or entities when bottom sheet is at peek.

### Pitfall 3: Nested Scrolling Conflict
**What goes wrong:** When a panel inside the bottom sheet has scrollable content (e.g., long inventory list), dragging within the content area either scrolls the content OR drags the sheet, but not both predictably.
**Why it happens:** Both the sheet drag handler and the native scroll handler compete for the same pointer events.
**How to avoid:** Restrict sheet drag initiation to the drag handle element only. Content areas use native `overflow-y: auto` without interference. If the user drags on content, it scrolls natively. If they drag on the handle, the sheet moves. Clear separation of gesture targets. [ASSUMED -- this is the standard pattern but needs validation during implementation]
**Warning signs:** Content scroll "fights" with sheet drag; sheet moves when user tries to scroll a list.

### Pitfall 4: Service Worker Cache Staleness
**What goes wrong:** After deploying a new version, users get stale JavaScript/CSS from the service worker cache. The app appears broken or shows old UI.
**Why it happens:** Cache-first strategy returns old assets before checking for updates. The current `CACHE_NAME = "ambonmud-v1"` never changes.
**How to avoid:** Use versioned cache names (e.g., `ambonmud-v2`, or hash-based). On activation, delete old caches (the sw.js already does this via key comparison). Vite's content-hash filenames for JS/CSS bundles help -- new deployments produce new filenames that bypass stale cache entries. [VERIFIED: sw.js lines 13-17 already delete old caches]
**Warning signs:** Users report seeing old UI after deployment; hard refresh fixes it.

### Pitfall 5: Reconnection State Desync
**What goes wrong:** After WebSocket reconnection, the client shows stale game state (wrong room, old HP values, missing inventory items) because the GMCP resync didn't fully replace all state.
**Why it happens:** Partial state replacement where some contexts get new data but others retain stale values from before disconnect.
**How to avoid:** On reconnection success, dispatch a `RESET_ALL` action to every domain context before processing the first GMCP resync message. This ensures no stale data survives. D-11 specifies the server sends a full resync automatically on reconnect. [ASSUMED -- need to verify server behavior sends complete state on WebSocket reconnect]
**Warning signs:** After reconnect, player appears in wrong room or has stale HP.

### Pitfall 6: iOS PWA Storage Eviction
**What goes wrong:** iOS automatically clears PWA storage (Cache API, IndexedDB) after 7 days of inactivity or when the user clears Safari history.
**Why it happens:** Apple's aggressive storage management policy for web apps. [CITED: https://www.magicbell.com/blog/pwa-ios-limitations-safari-support-complete-guide]
**How to avoid:** Keep the service worker cache lightweight (only cache app shell + static assets). Don't rely on persistent client-side storage for game state -- all state comes from the server via GMCP on each connection. This architecture is already correct for AmbonMUD since game state is server-authoritative.
**Warning signs:** PWA loads but shows empty state after iOS clears storage; next WebSocket connection repopulates everything.

## Code Examples

Verified patterns from the existing codebase:

### Existing Vitals Bar Pattern (reuse for HUD)
```css
/* Source: styles.css lines 8296-8322 */
.action-bar-vital-track {
  flex: 1;
  height: 10px;
  border-radius: 999px;
  background: rgb(24 22 35 / 28%);
  overflow: hidden;
  position: relative;
}

.action-bar-vital-fill {
  display: block;
  height: 100%;
  border-radius: 999px;
  transition: width 320ms var(--ease-out-soft);
}

.action-bar-vital-fill-hp {
  background: linear-gradient(90deg, var(--color-hp-light), var(--color-hp-dark));
}
```

HUD bars adapt this pattern at 4-6px height with numeric overlay text.

### Existing Pointer-Events Overlay Pattern
```css
/* Source: styles.css lines 6884-6893 */
/* Phase 2 desktop overlay pattern -- reuse for bottom sheet */
.layout-shell__hud,
.layout-shell__interaction {
  position: absolute;
  pointer-events: none;
}

.layout-shell__hud > *,
.layout-shell__interaction > * {
  pointer-events: auto;
}
```

### Existing Reconnect Banner Pattern
```css
/* Source: styles.css lines 9580-9623 */
.reconnect-banner {
  position: fixed;
  top: 16px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 1000;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 22px;
  border-radius: var(--radius-md, 10px);
  border: 1px solid rgb(140 154 193 / 24%);
  background: rgb(18 22 34 / 88%);
  backdrop-filter: blur(12px);
  animation: reconnect-slide-in 0.3s cubic-bezier(0.25, 1, 0.5, 1);
}
```

### Existing WebSocket Reconnect Logic (to be replaced)
```typescript
// Source: App.tsx lines 591-607 -- current simple retry
onClose: () => {
  if (intentionalDisconnectRef.current) {
    intentionalDisconnectRef.current = false;
    return;
  }
  if (resumeTokenRef.current) {
    setReconnecting(true);
    writeSystem("Connection lost — reconnecting...");
    window.setTimeout(() => reconnect(), 500);  // <-- replace with backoff
  } else {
    // Full reset for non-resumable disconnect
  }
},
```

### Current useMudSocket Reconnect (to be enhanced)
```typescript
// Source: useMudSocket.ts lines 71-74
const reconnect = useCallback(() => {
  disconnect();
  window.setTimeout(() => connect(), 120);  // <-- 120ms fixed delay
}, [connect, disconnect]);
```

### Design Token Variables Available
```css
/* Source: styles.css lines 1-100 */
/* Colors for bars */
--color-hp-light: #c5d8a8;    --color-hp-dark: #9ebf75;
--color-mana-light: #b8d8e8;  --color-mana-dark: #7fb6cf;
--color-xp-light: #e8d8a8;    --color-xp-dark: #ddb86f;

/* Backgrounds for glass-morphism */
--bg-primary: #2a3149;
--bg-elevated: #313a56;
--bg-deep: #141828;

/* Easing curves */
--ease-out-soft: cubic-bezier(0.33, 0.66, 0.66, 1);
--ease-in-out-smooth: cubic-bezier(0.4, 0, 0.2, 1);

/* Spacing and radii */
--space-2: 8px;   --space-3: 12px;  --space-4: 16px;
--radius-sm: 6px;  --radius-md: 10px; --radius-lg: 16px;

/* Safe area insets (set by LayoutShell) */
--safe-top: env(safe-area-inset-top, 0px);
--safe-bottom: env(safe-area-inset-bottom, 0px);
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Touch events + mouse events separately | Pointer Events API (unified) | 2020+ (W3C Level 3 Nov 2025) | Single handler for all input types [CITED: W3C Pointer Events] |
| `100vh` for mobile viewports | `100dvh` (dynamic viewport height) | Safari 15.4+ (2022) | Correct height on iOS with dynamic address bar [VERIFIED: codebase uses this] |
| Separate touch/click handlers for drag | `pointerdown`/`pointermove`/`pointerup` + `touch-action: none` | 2023+ standard practice | Simpler code, better performance [CITED: MDN touch-action] |
| PWA requires service worker for installability | Chrome 124+ removed SW requirement for installability | Feb 2024 | SW still recommended for caching but not strictly required for install prompt [CITED: MDN PWA installability guide] |
| iOS PWA EU restrictions | iOS 26 (2026): all sites default to web app on home screen | 2026 | Major improvement -- even sites without manifest get standalone behavior [CITED: firt.dev/notes/pwa-ios] |

**Deprecated/outdated:**
- `touchstart`/`touchmove`/`touchend`: Still works but Pointer Events are the W3C standard. Use Pointer Events exclusively for new code.
- `100vh` on mobile Safari: Causes layout issues. Always use `100dvh`.
- Fixed reconnect intervals: Anti-pattern for production. Always use exponential backoff with jitter.

## Assumptions Log

> List all claims tagged `[ASSUMED]` in this research. The planner and discuss-phase use this
> section to identify decisions that need user confirmation before execution.

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `will-change: transform` on sheet container improves GPU compositing | Pattern 1 | Minor -- may cause extra memory usage on mobile; can be removed |
| A2 | Restricting drag initiation to handle element resolves nested scroll conflicts | Anti-Patterns / Pitfall 3 | Medium -- if content areas also need drag-to-dismiss, need additional gesture discrimination |
| A3 | Server sends full GMCP resync on WebSocket reconnect without code changes | Pitfall 5 / D-11 | High -- if server does NOT auto-resync, client will show stale state; verify server behavior |

## Open Questions

1. **Server-side GMCP resync on reconnect**
   - What we know: D-11 states "server sends all packages just like initial login. No server changes needed."
   - What's unclear: Whether the current server implementation actually sends a full GMCP state dump on a new WebSocket connection when a player is already logged in (resume token path).
   - Recommendation: Verify by testing -- connect, disconnect, reconnect, and observe GMCP traffic. If the server already sends Char.Vitals, Room.Info, Char.Items, etc. on reconnect, no action needed. If not, a server-side change would be needed (but is out of scope for this client-only phase).

2. **Phase 1 state context availability**
   - What we know: Phase 1 extracts 88 useState calls into domain-split contexts. The HUD and bottom sheet need to consume vitals and character data from these contexts.
   - What's unclear: The exact context hook names and shapes (since Phase 1 hasn't been implemented yet).
   - Recommendation: The planner should structure tasks so HUD and bottom sheet depend on Phase 1's context API. If Phase 1 is not yet merged, use placeholder props that match the `Vitals` and `CharacterInfo` types from `types.ts` (verified to exist). The types are stable regardless of state management approach.

3. **Overflow menu panel count**
   - What we know: 14 panels exist in `web-v3/src/components/panels/`. D-07 pins 5 tabs. That leaves 9 panels for the overflow menu, plus SpellbookPanel, TrainerPanel, TradePanel which are in the components root (not panels/ directory).
   - What's unclear: Exact final count of overflow items and whether some panels (like CombatPanel) are only relevant during combat.
   - Recommendation: Include all non-pinned panels in overflow. Conditional visibility (hiding combat panel outside combat) can be a follow-up optimization.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Node.js | Build tooling | Yes | 24.14.0 | -- |
| Bun | web-v3 build (`bun run build`) | Yes | 1.3.10 | -- |
| React 19 | UI framework | Yes | 19.2.0 | -- |
| Vite | Build/dev server | Yes | 7.3.1 | -- |
| PWA icons | Manifest | Yes | All sizes present | -- |

**Missing dependencies with no fallback:** None
**Missing dependencies with fallback:** None

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | None -- no test infrastructure exists in web-v3 |
| Config file | None |
| Quick run command | `bun run lint` (ESLint only) |
| Full suite command | `bun run lint && bun run build` (lint + type-check + build) |

### Phase Requirements -> Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| PANEL-01 | Bottom sheet snaps to 3 positions | manual | Visual inspection via `./gradlew demo` | N/A |
| PANEL-02 | Tab bar navigates to all panels | manual | Visual inspection via `./gradlew demo` | N/A |
| PANEL-03 | Desktop side panel via CSS only | manual | Resize browser window, verify no JS branching | N/A |
| HUD-01 | HP/mana/XP bars visible, max 60px | manual | Visual inspection + DevTools element measurement | N/A |
| HUD-03 | Level and class badge visible | manual | Visual inspection via `./gradlew demo` | N/A |
| PWA-01 | Manifest installable | smoke | Lighthouse PWA audit in Chrome DevTools | N/A |
| PWA-02 | Service worker caches app shell | smoke | Chrome DevTools > Application > Cache Storage | N/A |
| PWA-05 | Exponential backoff reconnection | manual | Kill server, observe reconnect timing in console | N/A |

### Sampling Rate
- **Per task commit:** `cd web-v3 && bun run lint && bun run build`
- **Per wave merge:** Above + `./gradlew buildWeb` + visual inspection via `./gradlew demo`
- **Phase gate:** Lint + build + Lighthouse PWA audit + manual gesture testing on mobile viewport

### Wave 0 Gaps
- No frontend test framework exists (no vitest, no jest, no playwright). All validation is lint + build + manual.
- This is consistent with the project's current testing approach for web-v3 -- backend has extensive tests, frontend relies on type-checking + linting + visual verification.
- Adding vitest for component testing is a potential future improvement but out of scope for this phase.

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | No | N/A -- no auth changes in this phase |
| V3 Session Management | Partially | WebSocket reconnection must not bypass authentication; resume token pattern handles this [VERIFIED: App.tsx checks resumeTokenRef] |
| V4 Access Control | No | N/A -- panel visibility is cosmetic, not security |
| V5 Input Validation | No | N/A -- no new user inputs; pointer events are coordinates only |
| V6 Cryptography | No | N/A |

### Known Threat Patterns for WebSocket Reconnection

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Session hijack via reconnect | Spoofing | Server validates resume token; client cannot fabricate sessions [VERIFIED: existing resume token pattern] |
| DoS via reconnect storm | Denial of Service | Exponential backoff with jitter (D-12) + max attempts cap |
| Service worker cache poisoning | Tampering | HTTPS only; content-hash filenames from Vite; cache versioning |

## Sources

### Primary (HIGH confidence)
- `web-v3/src/styles.css` -- Existing design tokens, bar patterns, overlay patterns, responsive breakpoints (verified via Grep)
- `web-v3/src/hooks/useMudSocket.ts` -- Current WebSocket hook with reconnect logic (verified via Read)
- `web-v3/public/manifest.json` -- Existing PWA manifest with all required fields (verified via Read)
- `web-v3/public/sw.js` -- Existing service worker with cache-first strategy (verified via Read)
- `web-v3/src/App.tsx` -- Current reconnection logic at line ~600 (verified via Read)
- `web-v3/src/types.ts` -- Vitals and CharacterInfo interfaces (verified via Grep)
- `web-v3/package.json` -- React 19.2.0, no gesture/animation libs (verified via Read)

### Secondary (MEDIUM confidence)
- [MDN: Making PWAs installable](https://developer.mozilla.org/en-US/docs/Web/Progressive_web_apps/Guides/Making_PWAs_installable) -- PWA manifest requirements, browser-specific rules
- [MDN: Pointer Events](https://developer.mozilla.org/en-US/docs/Web/API/Pointer_events) -- Unified touch/mouse API
- [Can I Use: CSS Scroll Snap](https://caniuse.com/css-snappoints) -- 97.5% global support
- [Native-like bottom sheets on the web](https://viliket.github.io/posts/native-like-bottom-sheets-on-the-web/) -- CSS scroll-snap approach, pointer-events pattern, iOS Safari workarounds
- [PWA iOS Limitations 2026](https://www.magicbell.com/blog/pwa-ios-limitations-safari-support-complete-guide) -- iOS storage eviction, 50MB cache limit

### Tertiary (LOW confidence)
- [WebSocket reconnection with exponential backoff](https://dev.to/hexshift/robust-websocket-reconnection-strategies-in-javascript-with-exponential-backoff-40n1) -- Backoff parameters and patterns (community article, cross-verified with multiple sources)
- [firt.dev iOS PWA](https://firt.dev/notes/pwa-ios/) -- iOS 26 "all sites default to web app" claim (single source, needs validation)

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH -- zero new dependencies; all existing tools verified in codebase
- Architecture: HIGH -- patterns derived from existing codebase CSS and established web platform APIs
- Pitfalls: HIGH for browser-specific issues (well-documented); MEDIUM for nested scroll conflicts (experience-based, needs implementation validation)
- PWA: HIGH -- existing manifest/sw.js verified to meet requirements; minor enhancements only
- Reconnection: HIGH for backoff algorithm; MEDIUM for server-side resync behavior (A3 assumption)

**Research date:** 2026-04-05
**Valid until:** 2026-05-05 (stable domain -- web platform APIs, CSS transforms, PWA standards)
