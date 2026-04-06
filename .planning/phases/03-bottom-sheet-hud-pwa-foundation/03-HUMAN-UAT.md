---
status: partial
phase: 03-bottom-sheet-hud-pwa-foundation
source: [03-VERIFICATION.md]
started: 2026-04-05T00:00:00Z
updated: 2026-04-05T00:00:00Z
---

## Current Test

[awaiting human testing]

## Tests

### 1. Bottom sheet drag gesture
expected: Player can drag the bottom sheet up from the bottom and it snaps to peek (~15%), half (~50%), or full (~90%) positions on mobile viewport
result: [pending]

### 2. Tap-outside collapse
expected: Tapping outside the sheet (on canvas or HUD area) collapses the sheet to peek position
result: [pending]

### 3. Desktop side panel
expected: On desktop (1024px+), the bottom sheet renders as a side panel on the right without JavaScript branching — CSS only
result: [pending]

### 4. HUD height constraint
expected: HUD overlay with bars and badge fits within the 60px grid zone allocated by LayoutShell
result: [pending]

### 5. PWA installability
expected: The app is installable via 'Add to Home Screen' on Android Chrome and iOS Safari
result: [pending]

### 6. WebSocket reconnection backoff
expected: After WebSocket disconnect, reconnection attempts use exponential backoff (500ms -> 1s -> 2s -> 4s -> ... -> 30s cap) with attempt counter in banner
result: [pending]

## Summary

total: 6
passed: 0
issues: 0
pending: 6
skipped: 0
blocked: 0

## Gaps
