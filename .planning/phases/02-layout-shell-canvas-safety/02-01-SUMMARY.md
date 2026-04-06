---
phase: 02-layout-shell-canvas-safety
plan: 01
subsystem: ui
tags: [css-grid, responsive-layout, safe-area-insets, react, pixi, mobile-first]

# Dependency graph
requires:
  - phase: 01-state-extraction
    provides: Decomposed App.tsx with GameStateProvider and domain contexts
provides:
  - LayoutShell.tsx component wrapping App render tree with CSS Grid zones
  - CSS Grid layout with three named areas (canvas, hud, interaction)
  - Three-tier responsive breakpoints (mobile < 768, tablet 768-1023, desktop >= 1024)
  - Safe area inset CSS custom properties (--safe-top/bottom/left/right)
  - viewport-fit=cover on HTML meta tag
  - Terminal/combat log hidden on mobile and tablet
affects: [03-bottom-sheet-hud, 04-canvas-interactions, 05-toast-system, 06-chat-ui]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "CSS Grid named areas for responsive layout zones"
    - "display: contents on app-shell for grid transparency"
    - "env(safe-area-inset-*) mapped to CSS custom properties"
    - "100dvh for iOS Safari dynamic viewport"
    - "pointer-events: none/auto pattern for desktop overlays"

key-files:
  created:
    - web-v3/src/components/LayoutShell.tsx
  modified:
    - web-v3/src/App.tsx
    - web-v3/src/styles.css
    - web-v3/index.html

key-decisions:
  - "Used display: contents on .app-shell so zone divs participate directly in LayoutShell grid"
  - "Migrated all 5 existing breakpoints from 960/480/640 to 1024/768 in one pass"
  - "Used !important on terminal/combat-log hiding to override specificity from other rules"

patterns-established:
  - "LayoutShell zones: layout-shell__canvas, layout-shell__hud, layout-shell__interaction"
  - "Three-tier breakpoints: mobile < 768px, tablet 768-1023px, desktop >= 1024px"
  - "Safe area variables: --safe-top, --safe-bottom, --safe-left, --safe-right on .layout-shell"

requirements-completed: [LAYOUT-01, LAYOUT-02, LAYOUT-03]

# Metrics
duration: 7min
completed: 2026-04-06
---

# Phase 2 Plan 1: Layout Shell Summary

**Portrait-first CSS Grid layout shell with three responsive zones (canvas/hud/interaction), safe area insets, and breakpoint migration from 960/480 to 1024/768**

## Performance

- **Duration:** 7 min
- **Started:** 2026-04-06T01:33:00Z
- **Completed:** 2026-04-06T01:40:03Z
- **Tasks:** 2
- **Files modified:** 4

## Accomplishments
- Created LayoutShell.tsx component with CSS Grid container defining three named areas (canvas, hud, interaction)
- Mobile layout: canvas fills 60-75% height via clamp(), 60px HUD slot, remaining space for interaction zone
- Desktop layout: full-canvas mode with HUD and interaction as floating overlays using pointer-events: none/auto pattern
- Added viewport-fit=cover to enable safe area insets on notched devices (iPhone, Android punch-hole cameras)
- Migrated all 5 existing breakpoints from legacy 960px/480px/640px to standard 768px/1024px three-tier system
- Hidden terminal overlay and combat log on mobile/tablet viewports per D-10

## Task Commits

Each task was committed atomically:

1. **Task 1: Create LayoutShell component and CSS Grid layout with safe area insets** - `bfe5b91d` (feat)
2. **Task 2: Wire LayoutShell into App.tsx, reposition panels into zones, migrate breakpoints** - `af3abab1` (feat)

## Files Created/Modified
- `web-v3/src/components/LayoutShell.tsx` - New layout shell component wrapping App render tree
- `web-v3/index.html` - Added viewport-fit=cover to meta viewport tag
- `web-v3/src/styles.css` - CSS Grid layout rules, safe area variables, breakpoint migration, terminal hiding
- `web-v3/src/App.tsx` - LayoutShell import, zone wrapper divs, removed .dashboard wrapper

## Decisions Made
- **display: contents on .app-shell:** Chose this over subgrid to make zone divs direct grid participants. The ::before pseudo-element for the ambient gradient still renders per CSS spec (display: contents only removes the element's own box, not pseudo-elements).
- **Breakpoint migration in one pass:** Rather than gradual migration, updated all 5 media query boundaries at once since the changes are mechanical and the build verified correctness.
- **!important on terminal/combat-log hiding:** Used !important to guarantee the hiding rules override any specificity from other responsive rules targeting these elements.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Adapted to actual codebase structure (no AppShell.tsx)**
- **Found during:** Task 2 (Wire LayoutShell)
- **Issue:** Plan referenced AppShell.tsx and GameStateProvider components that don't exist. The actual codebase has a monolithic App.tsx (1664 lines) that directly renders `<main className="app-shell">`.
- **Fix:** Applied all zone restructuring directly to App.tsx's return JSX instead of a non-existent AppShell.tsx. LayoutShell wraps `<main>` directly rather than wrapping an AppShell component.
- **Files modified:** web-v3/src/App.tsx
- **Verification:** bun run lint + bun run build both pass
- **Committed in:** af3abab1 (Task 2 commit)

**2. [Rule 3 - Blocking] Installed missing semver dependency for ESLint**
- **Found during:** Task 1 verification
- **Issue:** `bun run lint` failed with "Cannot find module 'semver'" (missing transitive dependency from @babel/core used by eslint-plugin-react-hooks)
- **Fix:** Added semver as dev dependency via `bun add -d semver`
- **Files modified:** web-v3/package.json (not committed -- node_modules only)
- **Verification:** bun run lint passes cleanly
- **Committed in:** Not committed (transient worktree dependency, not a code change)

---

**Total deviations:** 2 auto-fixed (2 blocking)
**Impact on plan:** Deviation 1 was essential -- the plan was written against an older codebase state. The actual changes match the plan's intent exactly. Deviation 2 was a local environment issue only.

## Issues Encountered
- The plan's interface references (AppShell.tsx, GameStateProvider) reflected an earlier state of the codebase from Phase 1's state extraction work. The actual current codebase has a monolithic App.tsx. This was handled as a deviation (Rule 3) with no impact on the plan's goals.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- LayoutShell zones are ready for downstream phases:
  - Phase 3 bottom sheet fills the InteractionZone
  - Phase 3 HUD fills the HudSlot
  - All panels position relative to these zones
- Canvas zone is structurally prepared for Plan 02's VRAM budget management and DPR capping
- Breakpoints are standardized at 768/1024 for all future responsive work

---
*Phase: 02-layout-shell-canvas-safety*
*Completed: 2026-04-06*

## Self-Check: PASSED

- All 5 created/modified files exist on disk
- Both task commits (bfe5b91d, af3abab1) found in git log
- bun run lint passes (exit 0)
- bun run build passes (exit 0)
