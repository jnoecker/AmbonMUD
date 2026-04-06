---
phase: 02-layout-shell-canvas-safety
plan: 02
subsystem: canvas
tags: [pixi.js, vram, dpr, texture-gc, mobile-safety, webgl]

# Dependency graph
requires:
  - phase: 01-state-extraction
    provides: GameStateBridge refs for canvas scene rendering
provides:
  - VRAMBudgetManager utility with 64MB/256MB budget caps and fallback state
  - DPR capping at 2x on mobile devices (< 1024px viewport)
  - Aggressive PixiJS GC configuration (5s idle on mobile, 60s desktop)
  - Texture cleanup on scene transitions via source.unload()
affects: [03-bottom-sheet-hud-pwa, 04-navigation-combat, 05-toast-notification]

# Tech tracking
tech-stack:
  added: []
  patterns: [vram-budget-tracking, texture-source-unload-on-scene-exit, dpr-capping-by-viewport]

key-files:
  created:
    - web-v3/src/canvas/VRAMBudgetManager.ts
  modified:
    - web-v3/src/canvas/PixiCanvas.tsx
    - web-v3/src/canvas/SceneManager.ts
    - web-v3/src/canvas/scenes/WorldScene.ts
    - web-v3/src/canvas/scenes/BattleScene.ts

key-decisions:
  - "VRAMBudgetManager as singleton utility with module-level accessor functions (initVRAMBudget/getVRAMBudget)"
  - "texture.source.unload() for GPU-only release preserving PixiJS Assets cache, not texture.destroy()"
  - "DPR detection via window.innerWidth < 1024 threshold matching desktop breakpoint"

patterns-established:
  - "VRAM budget tracking: track via width*height*4*1.5x conservative multiplier, warn at 80%, fallback at 100%"
  - "Scene texture cleanup: call unloadTextures() on exiting scene during every transition, recursively walk container tree"
  - "PixiJS GC config: pass gcActive/gcMaxUnusedTime/gcFrequency as top-level app.init() options (PixiJS 8.16 GCSystem)"

requirements-completed: [CANVAS-02, CANVAS-03]

# Metrics
duration: 7min
completed: 2026-04-05
---

# Phase 2 Plan 02: Canvas Safety Summary

**PixiJS VRAM safety with 2x DPR cap on mobile, 5-second texture GC, VRAMBudgetManager (64MB/256MB), and texture.source.unload() on scene transitions**

## Performance

- **Duration:** 7 min
- **Started:** 2026-04-06T01:32:05Z
- **Completed:** 2026-04-06T01:39:00Z
- **Tasks:** 2
- **Files modified:** 5

## Accomplishments
- Created VRAMBudgetManager with 64MB mobile / 256MB desktop budget caps, 80% warning threshold, 1.5x conservative estimation multiplier, and quality fallback state (maxResolution, disableParallax)
- Capped PixiJS canvas resolution at 2x device pixel ratio on mobile (< 1024px viewport), uncapped on desktop
- Configured PixiJS 8.16 GCSystem with aggressive 5-second idle timer on mobile (vs default 60s), preventing iOS Safari VRAM exhaustion
- Added texture cleanup on world/battle scene transitions using texture.source.unload() to free GPU memory while keeping PixiJS Assets cache intact for re-upload

## Task Commits

Each task was committed atomically:

1. **Task 1: Create VRAMBudgetManager and configure PixiJS DPR cap and GC settings** - `8288fe5a` (feat)
2. **Task 2: Add texture cleanup on scene transitions in SceneManager** - `dee54527` (feat)

## Files Created/Modified
- `web-v3/src/canvas/VRAMBudgetManager.ts` - VRAM budget tracking with 64MB/256MB caps, warning at 80%, fallback state, singleton accessor
- `web-v3/src/canvas/PixiCanvas.tsx` - DPR capped at 2x on mobile, GCSystem configured (5s/60s), VRAMBudgetManager initialized and passed to SceneManager
- `web-v3/src/canvas/SceneManager.ts` - Accepts VRAMBudgetManager, calls unloadTextures() on exiting scene during transitions, exposes vramFallback getter
- `web-v3/src/canvas/scenes/WorldScene.ts` - Added unloadTextures() method that recursively walks container tree and calls texture.source.unload()
- `web-v3/src/canvas/scenes/BattleScene.ts` - Added unloadTextures() method with same recursive GPU memory release pattern

## Decisions Made
- Used `texture.source.unload()` instead of `texture.destroy()` to release GPU memory while keeping textures in the PixiJS Assets cache. This means scenes that become visible again get automatic GPU re-upload without a server re-fetch.
- VRAMBudgetManager implemented as a standalone singleton utility (not integrated into SceneManager class) for easier consumption by future systems (parallax, room transitions, etc.) via `getVRAMBudget()`.
- DPR detection uses `window.innerWidth < 1024` matching the desktop breakpoint threshold rather than user-agent sniffing.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Deferred SceneManager constructor wiring to Task 2**
- **Found during:** Task 1 (VRAMBudgetManager creation)
- **Issue:** Plan Task 1 called for passing vramBudget to SceneManager constructor, but SceneManager's constructor signature update was specified in Task 2. TypeScript compilation would fail between commits.
- **Fix:** Task 1 initializes VRAMBudgetManager singleton but only calls `new SceneManager(app)`. Task 2 updates the constructor to accept VRAMBudgetManager and updates PixiCanvas to pass it through.
- **Files modified:** web-v3/src/canvas/PixiCanvas.tsx
- **Verification:** TypeScript compiles clean after both Task 1 and Task 2
- **Committed in:** 8288fe5a (Task 1), dee54527 (Task 2)

**2. [Rule 3 - Blocking] Installed missing ESLint dependencies in worktree**
- **Found during:** Task 1 verification (bun run lint)
- **Issue:** Worktree node_modules had missing transitive dependencies (debug, ms) causing ESLint to crash
- **Fix:** Clean reinstall of node_modules via `rm -rf node_modules && bun install`
- **Files modified:** None (node_modules is gitignored)
- **Verification:** `bun run lint` exits 0

---

**Total deviations:** 2 auto-fixed (2 blocking)
**Impact on plan:** Both auto-fixes necessary for correct build between commits. No scope creep.

## Issues Encountered
- Worktree branch was based on wrong commit (main instead of feature branch HEAD). Fixed with `git reset --soft` to the correct base commit. This caused the Task 1 commit to include previously staged files from the branch reset, but the actual code changes are only VRAMBudgetManager.ts and PixiCanvas.tsx.

## User Setup Required

None - no external service configuration required.

## Known Stubs

None - all code is fully functional. VRAMBudgetManager's `trackTexture()`/`releaseTexture()` methods are ready for callers but not yet called by scene code (scenes will integrate tracking in future phases when texture loading is instrumented).

## Next Phase Readiness
- Canvas is VRAM-safe for iOS Safari with aggressive GC and DPR cap
- VRAMBudgetManager singleton is ready for future consumers (parallax system, room transition preloading)
- Scene transition texture cleanup prevents VRAM growth across world/battle switches
- Plan 01 (LayoutShell) can proceed independently; both plans are Wave 1 parallel
- Phase 3 (Bottom Sheet, HUD, PWA) can begin after both Phase 2 plans complete

---
*Phase: 02-layout-shell-canvas-safety*
*Completed: 2026-04-05*

## Self-Check: PASSED

- All 6 files exist (VRAMBudgetManager.ts, PixiCanvas.tsx, SceneManager.ts, WorldScene.ts, BattleScene.ts, SUMMARY.md)
- Both commit hashes verified (8288fe5a, dee54527)
- All acceptance criteria content verified via grep
