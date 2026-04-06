---
phase: 03-bottom-sheet-hud-pwa-foundation
plan: 02
subsystem: web-client/hud
tags: [hud, vitals, ui, mobile, responsive]
dependency_graph:
  requires: [03-01]
  provides: [hud-overlay, vitals-display]
  affects: [App.tsx, styles.css]
tech_stack:
  added: []
  patterns: [glass-morphism-overlay, css-grid-zone-content, design-token-gradients]
key_files:
  created:
    - web-v3/src/components/HudOverlay.tsx
  modified:
    - web-v3/src/styles.css
    - web-v3/src/App.tsx
decisions:
  - "HudOverlay placed inside layout-shell__hud zone after the top-banner header, co-located with existing connection controls"
  - "handleHudTap uses existing bottomSheetRef.snapTo('half') and setActiveBottomPanel('character') -- no new ref or callback registration needed"
  - "Bar labels use toLocaleString() for all numeric values (HP, mana, XP) for consistent comma formatting"
metrics:
  duration: ~5 minutes
  completed: 2026-04-06T03:32:03Z
  tasks_completed: 2
  tasks_total: 2
  files_created: 1
  files_modified: 2
---

# Phase 3 Plan 02: HUD Overlay Summary

Compact always-visible HUD with HP/mana/XP bars, numeric labels, and level/class badge using existing design tokens and glass-morphism styling.

## What Was Done

### Task 1: Create HudOverlay Component
**Commit:** `0aa0a8c4`

Created `web-v3/src/components/HudOverlay.tsx` with:
- Three vitals bars (HP green, mana blue, XP gold) using existing `--color-hp-*`, `--color-mana-*`, `--color-xp-*` tokens
- Numeric labels on each bar formatted with `toLocaleString()` (e.g., "1,234/5,000")
- Level/class badge displaying "Lv{N} {ClassName}" with title-cased class name
- `onTap` callback prop for bottom sheet integration (D-09)
- Null guard: returns nothing when `character.name` is falsy or "-" (not logged in)
- Accessibility: `role="button"`, `tabIndex={0}`, keyboard handler for Enter/Space, descriptive `aria-label`

### Task 2: Add HUD CSS and Wire Into App
**Commit:** `9d12e178`

CSS additions to `styles.css`:
- `.hud-overlay` with glass-morphism background (`backdrop-filter: blur(8px)`), subtle hover state
- `.hud-badge` using `--font-heading` (Cormorant Garamond), 13px, 600 weight
- `.hud-bar` at 6px height with dark track background matching existing vital bar pattern
- `.hud-bar-fill--hp/--mana/--xp` with gradient fills from existing color tokens
- `.hud-bar-label` at 9px mono font with text shadow for readability on thin bars
- Desktop `@media (min-width: 1024px)`: absolute centered overlay at top, `clamp(280px, 30vw, 400px)` width
- `@media (prefers-reduced-motion: reduce)`: disables bar width transitions

App.tsx wiring:
- Imported `HudOverlay` component
- Added `handleHudTap` callback that sets active panel to "character" and snaps bottom sheet to "half"
- Rendered `<HudOverlay>` inside `layout-shell__hud` zone after the top-banner header

## Deviations from Plan

None -- plan executed exactly as written.

## Verification Results

- TypeScript compilation: PASS (`bunx tsc --noEmit` exits 0)
- ESLint: PASS (`bun run lint` exits 0)
- Production build: PASS (`bun run build` exits 0)

## Self-Check: PASSED

All files exist, all commits found, all content patterns verified.
