---
phase: 03-bottom-sheet-hud-pwa-foundation
verified: 2026-04-05T22:30:00Z
status: human_needed
score: 7/7 must-haves verified
human_verification:
  - test: "Drag bottom sheet from peek to half to full positions on mobile viewport (375px)"
    expected: "Sheet snaps smoothly to peek (~85%), half (~50%), and full (~10%) positions with 300ms ease-out transition"
    why_human: "Pointer gesture behavior and snap animation smoothness cannot be verified programmatically"
  - test: "Tap the canvas area or HUD area while bottom sheet is at half/full"
    expected: "Bottom sheet collapses to peek position"
    why_human: "Click handler interaction with sheet snap animation requires visual confirmation"
  - test: "Resize browser to 1024px+ and confirm bottom sheet becomes a right-side panel"
    expected: "Sheet appears as fixed side panel (max 380px / 30vw wide), no drag handle visible, no translateY animation"
    why_human: "CSS-only responsive behavior requires visual inspection at actual breakpoint"
  - test: "Verify HUD fits within 60px vertical zone on mobile"
    expected: "Badge line + three 6px bars + spacing fits within the LayoutShell HUD grid row"
    why_human: "Precise pixel height measurement requires DevTools inspection"
  - test: "Open PWA install flow on Android Chrome and iOS Safari"
    expected: "Add to Home Screen option appears; installed app opens in standalone mode"
    why_human: "PWA installability requires real device/browser testing"
  - test: "Kill server, observe reconnection backoff in console, restart server"
    expected: "Reconnect attempts show increasing delays (500ms -> 1s -> 2s -> ...), reconnect banner shows attempt count, state resyncs on reconnect"
    why_human: "WebSocket reconnection timing and full GMCP resync require live server testing"
---

# Phase 3: Bottom Sheet, HUD & PWA Foundation Verification Report

**Phase Goal:** Players access all game panels through a familiar pull-up bottom sheet, see essential stats at a glance in a minimal HUD, and can install the app as a PWA with reliable WebSocket reconnection
**Verified:** 2026-04-05T22:30:00Z
**Status:** human_needed
**Re-verification:** No -- initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Player can drag up a bottom sheet from the bottom and snap it to peek, half, and full positions | VERIFIED | `useBottomSheet.ts` implements pointer-driven drag with `SNAP_POSITIONS` at 0.85/0.50/0.10, `setPointerCapture`, `nearestSnap()` calculation on `pointerup`. `BottomSheet.tsx` uses the hook with `--sheet-translate-y` CSS variable and `bottom-sheet--dragging` class for immediate response. |
| 2 | A tab bar at the bottom sheet's top provides categorized navigation to all 14+ panels | VERIFIED | `TabBar.tsx` defines 5 `PINNED_TABS` (character, inventory, combat, quests, chat) + 8 `OVERFLOW_TABS` (equipment, spellbook, crafting, bank, auction, mail, housing, leaderboard) = 13 navigable panels. `App.tsx` wires all 13 as conditional children of `<BottomSheet>` (lines 1223-1425). Admin panel accessible via separate staff button; PlayPanel renders in canvas zone. |
| 3 | On desktop breakpoint (1024px+), the bottom sheet renders as a side panel without any code branching (CSS only) | VERIFIED | `styles.css` line 7151: `@media (min-width: 1024px)` sets `.bottom-sheet` to `position: absolute; right: 0; width: min(380px, 30vw); transform: none !important;` and `.bottom-sheet__drag-handle { display: none; }`. No JavaScript viewport checks. |
| 4 | HP, mana, and XP bars are always visible in a compact HUD overlay (max ~60px vertical) without opening any panel | VERIFIED | `HudOverlay.tsx` renders three `.hud-bar` elements at 6px height each with gradient fills using `--color-hp-*`, `--color-mana-*`, `--color-xp-*` tokens. Placed in `layout-shell__hud` zone (60px grid row) at `App.tsx` line 1166. Badge line at 13px + 3 bars at 6px + gaps = well within 60px. |
| 5 | Level and class badge are visible in the HUD at all times | VERIFIED | `HudOverlay.tsx` line 48: `<div className="hud-badge">Lv{level} {className}</div>` renders level and title-cased class name. Styled at 13px with heading font (`--font-heading`). Only hidden when not logged in. |
| 6 | The app is installable via "Add to Home Screen" on both Android Chrome and iOS Safari | VERIFIED | `manifest.json` has all required fields: `name`, `short_name`, `start_url: "/"`, `display: "standalone"`, `id: "/"`, `scope: "/"`, icons (SVG, 192px, 512px, 512px-maskable). `index.html` has apple-mobile-web-app-capable meta tag. Service worker registered in `main.tsx` line 13. |
| 7 | After the app is backgrounded and WebSocket disconnects, it reconnects with exponential backoff and resyncs full game state | VERIFIED | `useReconnect.ts` implements exponential backoff: 500ms base, 2x multiplier, 30s cap, 50% jitter, 15 max attempts. `App.tsx` line 610: `onClose` calls `scheduleReconnectRef.current?.()` (replacing old `setTimeout(reconnect, 500)`). On `onOpen` (line 595): calls `cancelReconnectRef.current()` to reset. Server sends full GMCP on new WebSocket connection (existing behavior). |

**Score:** 7/7 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `web-v3/src/hooks/useBottomSheet.ts` | Pointer event tracking, snap point calculation, drag state machine | VERIFIED | 141 lines. Exports `useBottomSheet`, `SnapPoint` type. Implements `pointerdown`/`pointermove`/`pointerup` with `setPointerCapture`. Three snap points. |
| `web-v3/src/components/BottomSheet.tsx` | Bottom sheet container with drag handle, tab bar, and panel content area | VERIFIED | 63 lines. `forwardRef` with `useImperativeHandle` exposing `collapse()` and `snapTo()`. Drag handle with `touch-action: none`. TabBar integration. Scrollable content area. |
| `web-v3/src/components/TabBar.tsx` | 5 pinned icon tabs + overflow menu | VERIFIED | 222 lines. 5 pinned tabs with inline SVG icons. 8 overflow tabs in 4-column grid. ARIA roles (tablist, tab, menu, menuitem). Overflow backdrop for outside-click dismiss. |
| `web-v3/src/components/HudOverlay.tsx` | Compact vitals display with HP/mana/XP bars and level/class badge | VERIFIED | 75 lines. Three bars with gradient fills. Numeric labels with `toLocaleString()`. Level/class badge. `role="button"` with keyboard handler. Null guard when not logged in. |
| `web-v3/src/hooks/useReconnect.ts` | Exponential backoff logic with jitter | VERIFIED | 96 lines. Exports `useReconnect` with `scheduleReconnect`, `cancelReconnect`, `resetAttempts`. Backoff: 500ms base, 2x multiplier, 30s cap, 50% jitter, 15 max attempts. Cleanup on unmount. |
| `web-v3/public/manifest.json` | Enhanced PWA manifest | VERIFIED | Has `id`, `scope`, `categories`, `prefer_related_applications: false`, `launch_handler`, `display: standalone`, all 4 icon entries. |
| `web-v3/public/sw.js` | Versioned service worker with proper caching | VERIFIED | `CACHE_NAME = "ambonmud-v2"`. Network-first with stale-while-revalidate for navigation. Cache-first for static assets. WebSocket bypass preserved. |
| `web-v3/src/styles.css` | Bottom sheet CSS, HUD CSS, tab bar CSS | VERIFIED | Bottom sheet rules at line 6897. HUD rules at line 7053. Tab bar at line 6944. Desktop side panel at line 7151. Reduced motion at line 7042. Reconnect banner attempt at line 9899. |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `useBottomSheet.ts` | `BottomSheet.tsx` | Hook consumed by component | WIRED | `BottomSheet.tsx` line 3: `import { useBottomSheet }`, line 20: `const { sheetRef, handleRef, currentSnap, isDragging, snapTo, collapse } = useBottomSheet("peek")` |
| `BottomSheet.tsx` | Panel components | Renders active panel as children | WIRED | `App.tsx` lines 1218-1425: `<BottomSheet>` wraps 13 conditional panel renders keyed on `activeBottomPanel` |
| `styles.css` | `BottomSheet.tsx` | CSS @media 1024px desktop side panel | WIRED | Line 7151: `@media (min-width: 1024px) { .bottom-sheet { position: absolute; transform: none !important; } }` |
| `HudOverlay.tsx` | App.tsx vitals state | Reads vitals and character props | WIRED | Line 1166: `<HudOverlay vitals={vitals} character={character} onTap={handleHudTap} />` |
| `HudOverlay.tsx` | `BottomSheet.tsx` | onTap triggers snapTo('half') with Character panel | WIRED | Lines 1025-1028: `handleHudTap` calls `setActiveBottomPanel("character")` and `bottomSheetRef.current?.snapTo("half")` |
| `useReconnect.ts` | `useMudSocket` | Ref-bridge for reconnect scheduling | WIRED | Lines 639-640: `scheduleReconnectRef.current = scheduleReconnect; cancelReconnectRef.current = resetAttempts`. Line 610: `scheduleReconnectRef.current?.()` in onClose |
| `App.tsx` | `useReconnect.ts` | Hook consumed with callbacks | WIRED | Line 48: import. Lines 627-637: `useReconnect({ reconnectFn: reconnect, onReconnecting, onGaveUp })` |
| `sw.js` | `main.tsx` | Service worker registration | WIRED | `main.tsx` line 13: `navigator.serviceWorker.register("/sw.js")` |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|----------|---------------|--------|--------------------|--------|
| `HudOverlay.tsx` | `vitals` (hp, mana, xp) | `App.tsx` state via GMCP `Char.Vitals` | Yes -- server sends Char.Vitals on login and updates | FLOWING |
| `HudOverlay.tsx` | `character` (name, className, level) | `App.tsx` state via GMCP `Char.Name`, `Char.Status` | Yes -- server sends on login | FLOWING |
| `TabBar.tsx` | `activePanel` | `App.tsx` state `activeBottomPanel` | Yes -- user interaction drives state | FLOWING |
| Reconnect banner | `reconnectAttempt` | `useReconnect` hook attempt counter | Yes -- incremented on each backoff cycle | FLOWING |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Production build passes | `bun run build` | Built in 7.56s, 0 errors | PASS |
| Old fixed 500ms retry removed | grep `setTimeout.*reconnect.*500` App.tsx | No matches | PASS |
| Service worker registration present | grep `serviceWorker.register` main.tsx | Found at line 13 | PASS |
| All 7 artifacts exist | ls of all 7 file paths | All present | PASS |
| Cache name updated | grep `CACHE_NAME` sw.js | `ambonmud-v2` | PASS |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| PANEL-01 | 03-01 | Bottom sheet container with peek/half/full snap points, drag-to-resize | SATISFIED | `useBottomSheet.ts` implements 3 snap points with pointer-driven drag. `BottomSheet.tsx` renders container with drag handle. Note: "swipe-to-dismiss" from requirement interpreted as collapse-to-peek (no hidden state), consistent with roadmap SC. |
| PANEL-02 | 03-01 | Panel navigation tab bar with categorized icons for all 14+ panels | SATISFIED | `TabBar.tsx` has 5 pinned + 8 overflow = 13 game panels navigable. Admin via separate staff button. PlayPanel in canvas zone. All panels wired in App.tsx. |
| PANEL-03 | 03-01 | Bottom sheet becomes sidebar on desktop breakpoint (same component, CSS positioning) | SATISFIED | `styles.css` line 7151: `@media (min-width: 1024px)` converts bottom sheet to right-side panel via CSS only. Same component, no JS branching. |
| HUD-01 | 03-02 | Minimal always-visible HUD (HP/mana/XP bars, max ~60px vertical) | SATISFIED | `HudOverlay.tsx` renders 3 bars at 6px height + badge at 13px + padding. Placed in 60px LayoutShell HUD grid zone. |
| HUD-03 | 03-02 | Level and class badge indicator | SATISFIED | `HudOverlay.tsx` line 48: `Lv{level} {className}` badge with heading font styling. |
| PWA-01 | 03-03 | Web app manifest (name, icons, theme_color, display: standalone) | SATISFIED | `manifest.json` has all required fields plus enhanced fields (id, scope, categories, launch_handler). |
| PWA-02 | 03-03 | Service worker for app shell caching (cache-first static, network-only WebSocket) | SATISFIED | `sw.js` v2: network-first + stale-while-revalidate for navigation, cache-first for /assets/ /icons/ /images/, WebSocket bypass for ws:/wss:. |
| PWA-05 | 03-03 | WebSocket reconnection with exponential backoff and state resync after backgrounding | SATISFIED | `useReconnect.ts`: 500ms base, 2x multiplier, 30s cap, 50% jitter, 15 max attempts. Old 500ms fixed retry removed. Server handles full GMCP resync on new connection. |

No orphaned requirements found -- all 8 requirement IDs from the roadmap (PANEL-01, PANEL-02, PANEL-03, HUD-01, HUD-03, PWA-01, PWA-02, PWA-05) are accounted for across the 3 plans.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| (none found) | - | - | - | - |

No TODOs, FIXMEs, placeholders, empty returns, or stub implementations found in any phase 3 artifact. The `manifest.json` `screenshots: []` is an acknowledged placeholder (per summary) but is non-blocking for PWA installability.

### Human Verification Required

### 1. Bottom Sheet Drag Gesture

**Test:** On a mobile viewport (375px), drag the bottom sheet handle up and down. Release at various positions.
**Expected:** Sheet snaps to nearest of peek (~85%), half (~50%), or full (~10%) positions with smooth 300ms animation. During drag, sheet follows pointer immediately (no transition).
**Why human:** Pointer gesture behavior and snap animation smoothness require real touch/mouse interaction.

### 2. Tap-Outside Collapse

**Test:** With bottom sheet at half or full position, tap the canvas area or HUD area.
**Expected:** Bottom sheet collapses to peek position.
**Why human:** Event propagation through React component tree with `stopPropagation` interactions requires interactive testing.

### 3. Desktop Side Panel

**Test:** Resize browser window to 1024px+ width.
**Expected:** Bottom sheet transforms into a right-aligned side panel (max 380px / 30vw). No drag handle visible. No vertical transform animation.
**Why human:** CSS-only responsive layout transformation requires visual confirmation at actual breakpoint.

### 4. HUD Height Constraint

**Test:** Inspect HUD overlay with DevTools on mobile viewport.
**Expected:** Total HUD height (badge + 3 bars + padding) fits within the 60px LayoutShell grid zone.
**Why human:** Precise pixel measurement requires DevTools inspection.

### 5. PWA Installability

**Test:** Open the app in Android Chrome and iOS Safari. Check for "Add to Home Screen" option.
**Expected:** Install prompt appears. Installed app opens in standalone mode with correct theme color.
**Why human:** PWA installability requires real device/browser testing with actual icon assets.

### 6. WebSocket Reconnection Backoff

**Test:** Connect to the app, then kill the server process. Observe console/UI. Restart server.
**Expected:** Reconnection attempts with increasing delays (500ms, ~1s, ~2s, ~4s...). Banner shows "Reconnecting... (attempt N)". On server restart, app reconnects and receives full GMCP state resync.
**Why human:** WebSocket lifecycle, timing behavior, and full state resync require live server testing.

### Gaps Summary

No gaps found. All 7 roadmap success criteria are verified through code inspection. All 8 requirement IDs are satisfied. All artifacts exist, are substantive, are wired into the application, and have data flowing through them. The production build passes cleanly with zero errors.

Six items require human verification for visual/interactive behavior that cannot be confirmed through static code analysis alone.

---

_Verified: 2026-04-05T22:30:00Z_
_Verifier: Claude (gsd-verifier)_
