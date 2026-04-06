---
status: partial
phase: 02-layout-shell-canvas-safety
source: [02-VERIFICATION.md]
started: 2026-04-06T02:16:00Z
updated: 2026-04-06T02:16:00Z
---

## Current Test

[awaiting human testing]

## Tests

### 1. Mobile Layout Proportions (375px)
expected: Three stacked zones visible — canvas fills upper 60-75%, HUD slot ~60px, interaction zone fills remaining
result: [pending]

### 2. Desktop Layout (1440px)
expected: Full-canvas mode with overlays floating, max-width 1600px container, no wasted whitespace
result: [pending]

### 3. Safe Area Insets (notched device)
expected: Content padding adjusts for notch/Dynamic Island, nothing obscured
result: [pending]

### 4. iOS Safari VRAM Stability (20+ rooms)
expected: Canvas renders continuously without tab crash; GC console messages may appear
result: [pending]

### 5. DPR Cap Verification (3x device)
expected: app.renderer.resolution value is 2 (capped), not 3
result: [pending]

### 6. Panel Functionality Regression
expected: All existing panel popouts open and close correctly after layout restructure
result: [pending]

### 7. Texture Memory Stability (10+ scene switches)
expected: GPU memory stays stable, no monotonic growth across world/battle transitions
result: [pending]

## Summary

total: 7
passed: 0
issues: 0
pending: 7
skipped: 0
blocked: 0

## Gaps
