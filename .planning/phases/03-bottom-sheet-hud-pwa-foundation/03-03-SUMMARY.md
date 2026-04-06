---
phase: 03-bottom-sheet-hud-pwa-foundation
plan: 03
subsystem: pwa-reconnection
tags: [pwa, service-worker, reconnection, exponential-backoff]
dependency_graph:
  requires: [03-02]
  provides: [pwa-installability, websocket-backoff-reconnect]
  affects: [web-v3/public/manifest.json, web-v3/public/sw.js, web-v3/src/hooks/useReconnect.ts, web-v3/src/App.tsx, web-v3/src/styles.css]
tech_stack:
  added: []
  patterns: [exponential-backoff-with-jitter, stale-while-revalidate, ref-bridge-pattern]
key_files:
  created:
    - web-v3/src/hooks/useReconnect.ts
  modified:
    - web-v3/public/manifest.json
    - web-v3/public/sw.js
    - web-v3/src/App.tsx
    - web-v3/src/styles.css
decisions:
  - Backoff parameters: 500ms base, 2x multiplier, 30s cap, 50% jitter, 15 max attempts (~2 min total)
  - Used ref-bridge pattern (scheduleReconnectRef/cancelReconnectRef) to break circular dependency between useMudSocket and useReconnect hooks
  - Screenshots array left empty in manifest (non-blocking for installability; populated in future phase)
  - Visibility change handler uses scheduleReconnect for consistent backoff instead of direct reconnect
metrics:
  duration: 7m
  completed: "2026-04-06T03:42:00Z"
---

# Phase 3 Plan 3: PWA Installability & Reconnection Backoff Summary

Enhanced PWA manifest with full installability fields, upgraded service worker to v2 with stale-while-revalidate navigation caching, and replaced fixed 500ms WebSocket retry with exponential backoff hook using jitter to prevent reconnection storms.

## Commits

| Task | Commit | Description |
|------|--------|-------------|
| 1 | `e555811d` | Enhance PWA manifest and upgrade service worker cache strategy |
| 2 | `50f58a6a` | Add exponential backoff reconnection with useReconnect hook |

## Task Details

### Task 1: Enhance manifest.json, upgrade sw.js cache strategy

**manifest.json enhancements:**
- Added `id: "/"` for stable PWA identity
- Added `scope: "/"` for app scope definition
- Added `categories: ["games", "entertainment"]` for app store categorization
- Added `screenshots: []` placeholder (actual screenshots added in future phase)
- Added `prefer_related_applications: false` to prefer PWA over native
- Added `launch_handler: { client_mode: "navigate-existing" }` to reuse existing window
- All existing fields preserved (name, short_name, description, start_url, display, orientation, theme_color, background_color, icons)

**sw.js upgrades:**
- Bumped cache name from `ambonmud-v1` to `ambonmud-v2` for deployment cache refresh
- Added stale-while-revalidate to navigation handler: successful fetches now update the cache for offline resilience
- Preserved WebSocket protocol bypass (`ws:` / `wss:`)
- Preserved cache-first strategy for `/assets/`, `/icons/`, `/images/`

**Service worker registration:** Already existed in `main.tsx` -- no changes needed.

### Task 2: Create useReconnect hook and wire into App.tsx

**useReconnect.ts hook:**
- Exponential backoff: 500ms -> 1s -> 2s -> 4s -> 8s -> 16s -> 30s (capped)
- Jitter: +/- 50% randomization prevents thundering herd on server recovery
- Max 15 attempts before giving up (~2 minutes total)
- Three control functions: `scheduleReconnect()`, `cancelReconnect()`, `resetAttempts()`
- Exposes `attempt` count for UI display
- Cleanup on unmount clears pending timers
- Used `useEffect` for options ref sync to satisfy React compiler lint rules

**App.tsx integration:**
- Replaced `window.setTimeout(() => reconnect(), 500)` in onClose with `scheduleReconnectRef.current?.()` 
- Added `cancelReconnectRef.current()` + `setReconnecting(false)` to onOpen for reset after successful connection
- Updated visibility change handler to use backoff instead of direct reconnect
- Manual reconnect button cancels pending backoff before triggering immediate reconnect
- Removed `reconnect` from useEffect dependency array (no longer used directly in effect)

**Reconnect banner enhancement:**
- Added attempt counter: "Reconnecting... (attempt 3)"
- CSS: `.reconnect-banner__attempt` with 11px muted text and tabular-nums for stable layout

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] React compiler lint: ref assignment during render**
- **Found during:** Task 2
- **Issue:** `optionsRef.current = options` in useReconnect triggered `react-hooks/refs` lint error (cannot update ref during render)
- **Fix:** Wrapped ref assignment in `useEffect()` to comply with React compiler rules
- **Files modified:** `web-v3/src/hooks/useReconnect.ts`

**2. [Rule 3 - Blocking] Circular hook dependency between useMudSocket and useReconnect**
- **Found during:** Task 2
- **Issue:** `useMudSocket` onClose needs `scheduleReconnect`, but `useReconnect` needs `reconnect` from `useMudSocket`
- **Fix:** Used ref-bridge pattern (`scheduleReconnectRef`, `cancelReconnectRef`) set after both hooks initialize, allowing onClose to call through refs
- **Files modified:** `web-v3/src/App.tsx`

## Threat Mitigations Applied

| Threat ID | Mitigation |
|-----------|------------|
| T-03-06 (Spoofing) | Resume token validation preserved -- reconnect reuses existing `resumeTokenRef` pattern |
| T-03-07 (DoS) | Exponential backoff with 50% jitter: 500ms base, 2x multiplier, 30s cap, 15 max attempts |
| T-03-08 (Tampering) | Cache bumped to v2; old caches deleted on activation; content-hashed Vite assets |
| T-03-09 (Info Disclosure) | Only static assets cached (HTML, JS, CSS, icons); no game state in Cache API |

## Known Stubs

- `manifest.json` `screenshots: []` -- empty array placeholder; actual screenshots to be added in a future phase. Non-blocking for PWA installability.

## Self-Check: PASSED

All 6 key files verified present. Both task commits (e555811d, 50f58a6a) verified in git log.
