---
phase: 03-bottom-sheet-hud-pwa-foundation
plan: 01
subsystem: web-client
tags: [bottom-sheet, gesture, tab-bar, mobile, responsive, CSS]
dependency_graph:
  requires: [02-01]
  provides: [BottomSheet, TabBar, useBottomSheet]
  affects: [App.tsx, styles.css]
tech_stack:
  added: []
  patterns: [pointer-events-drag, CSS-only-responsive, forwardRef-imperative-handle]
key_files:
  created:
    - web-v3/src/hooks/useBottomSheet.ts
    - web-v3/src/components/BottomSheet.tsx
    - web-v3/src/components/TabBar.tsx
  modified:
    - web-v3/src/styles.css
    - web-v3/src/App.tsx
decisions:
  - Used CSS custom property --sheet-translate-y for snap positions instead of inline px values
  - forwardRef + useImperativeHandle to expose collapse() to parent for tap-outside behavior
  - Separate overflow backdrop div for closing overflow menu on outside click
  - Used --font-body (project standard) instead of plan's --font-ui (does not exist)
  - Used --color-primary-lavender (project standard) instead of plan's --color-accent-lavender (not a defined token)
metrics:
  duration_seconds: 522
  completed: "2026-04-06T03:22:10Z"
  tasks_completed: 2
  tasks_total: 2
  files_created: 3
  files_modified: 2
---

# Phase 3 Plan 1: Bottom Sheet + Tab Bar Summary

Pointer-driven bottom sheet with three snap points (peek/half/full), 5 pinned icon tabs + 8 overflow tabs, and CSS-only desktop side panel at 1024px.

## Task Commits

| Task | Name | Commit | Key Files |
|------|------|--------|-----------|
| 1 | Create useBottomSheet hook and BottomSheet + TabBar components | c9ce99eb | useBottomSheet.ts, BottomSheet.tsx, TabBar.tsx |
| 2 | Add bottom sheet CSS with mobile transforms, desktop side panel, and wire into App | fdae07f3 | styles.css, App.tsx |

## What Was Built

### useBottomSheet Hook (web-v3/src/hooks/useBottomSheet.ts)
- Pointer-driven drag with `pointerdown`/`pointermove`/`pointerup` events on the drag handle
- `setPointerCapture` for reliable pointer tracking during drag
- Three snap positions: peek (85%), half (50%), full (10%) of viewport height
- Direct DOM manipulation during drag (no React state updates) for smooth 60fps performance
- Nearest-snap calculation on pointer release with midpoint thresholds
- Exported `snapTo(snap)` and `collapse()` for programmatic control

### BottomSheet Component (web-v3/src/components/BottomSheet.tsx)
- `forwardRef` with `useImperativeHandle` exposing `collapse()` and `snapTo()` to parent
- Drag handle with pill indicator, `touch-action: none` to prevent browser scroll interference
- TabBar integration for panel navigation
- Scrollable content area with `-webkit-overflow-scrolling: touch`
- Auto-snaps to half when tab tapped from peek position

### TabBar Component (web-v3/src/components/TabBar.tsx)
- 5 pinned icon tabs: Character, Inventory, Combat, Quest, Chat
- 8 overflow tabs: Equipment, Spellbook, Crafting, Bank, Auction, Mail, Housing, Leaderboard
- Inline SVG icons (no icon library dependency)
- Overflow menu as 4-column grid with invisible backdrop for dismiss-on-outside-click
- ARIA roles: `tablist`, `tab`, `aria-selected`, `menu`, `menuitem`, `aria-expanded`

### CSS (web-v3/src/styles.css)
- Mobile: `transform: translateY(var(--sheet-translate-y))` with 300ms ease-out transition
- Drag state: `.bottom-sheet--dragging` disables transition for immediate response
- Tab bar: 44px min touch targets, active state uses `--color-primary-lavender`
- Overflow grid: absolute positioned, 4-column, elevated shadow
- Desktop (1024px+): CSS-only side panel -- `transform: none !important`, `width: min(380px, 30vw)`, right-aligned, drag handle hidden
- `prefers-reduced-motion: reduce` disables all bottom sheet and tab bar transitions

### App.tsx Wiring
- BottomSheet renders in `layout-shell__interaction` zone alongside existing ActionBar
- All 14 panels wired as conditional children based on `activeBottomPanel` state
- CombatPanel newly imported and wired (was not previously rendered in App.tsx)
- Canvas zone and HUD zone onClick handlers call `bottomSheetRef.current?.collapse()` per D-03

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed font token reference**
- **Found during:** Task 2
- **Issue:** Plan specified `var(--font-ui)` but this token does not exist in styles.css
- **Fix:** Used `var(--font-body)` which is the project's standard UI font token
- **Files modified:** web-v3/src/styles.css

**2. [Rule 1 - Bug] Fixed color token reference**
- **Found during:** Task 2
- **Issue:** Plan specified `var(--color-accent-lavender, #b8a9d4)` but this token is not defined
- **Fix:** Used `var(--color-primary-lavender)` which is the existing lavender token (#a897d2)
- **Files modified:** web-v3/src/styles.css

**3. [Rule 2 - Missing functionality] Added overflow backdrop for outside-click dismiss**
- **Found during:** Task 1
- **Issue:** Overflow menu needed a way to close when clicking outside
- **Fix:** Added invisible fixed backdrop div that closes overflow on click
- **Files modified:** web-v3/src/components/TabBar.tsx, web-v3/src/styles.css

## Verification Results

All acceptance criteria pass:
- useBottomSheet: SnapPoint type, pointer events, setPointerCapture, snapTo/collapse
- BottomSheet: drag handle, content area, touch-action
- TabBar: 5 pinned tabs, overflow menu
- CSS: mobile transforms, dragging state, desktop side panel, reduced-motion
- App.tsx: BottomSheet import, activeBottomPanel state, canvas/HUD collapse handlers
- ESLint: 0 errors
- TypeScript: compiles cleanly
- Vite build: production build succeeds

## Self-Check: PASSED

All created files exist. All commit hashes found in git log.
