# Phase 3: Bottom Sheet, HUD & PWA Foundation - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md -- this log preserves the alternatives considered.

**Date:** 2026-04-05
**Phase:** 03-bottom-sheet-hud-pwa-foundation
**Areas discussed:** Bottom sheet behavior, Panel tab organization, HUD presentation, Reconnection UX

---

## Bottom Sheet Behavior

### Snap Points

| Option | Description | Selected |
|--------|-------------|----------|
| Peek / Half / Full | Peek (~15%, tab bar), Half (~50%, quick interactions), Full (~90%, deep content). Standard 3-stop. | ✓ |
| Collapsed / Full only | Two states: just tab bar or full sheet. Simpler but no intermediate. | |
| Peek / Full (no half) | Peek with tab bar + hint line, then full. Simpler than 3-stop. | |

**User's choice:** Peek / Half / Full
**Notes:** Standard pattern from Apple/Google Maps.

### Implementation Approach

| Option | Description | Selected |
|--------|-------------|----------|
| Custom (CSS + pointer events) | Build with CSS transforms + pointer handlers. Zero deps, full control. | ✓ |
| react-spring + use-gesture | Physics-based animation. Smooth feel but @use-gesture stale (2 years). | |
| You decide | Let Claude pick during planning/research. | |

**User's choice:** Custom (CSS + pointer events)
**Notes:** No external libraries.

### Dismiss Gesture

| Option | Description | Selected |
|--------|-------------|----------|
| Swipe down on drag handle | Standard pattern, no scroll conflicts. | |
| Swipe down anywhere on sheet | More discoverable but scroll conflicts. | |
| Tap outside (on canvas/HUD) | Simple, no gesture conflicts. | ✓ |

**User's choice:** Tap outside (on canvas/HUD)
**Notes:** None.

### Resize Interaction

| Option | Description | Selected |
|--------|-------------|----------|
| Drag handle + tap-to-snap | Drag to resize, tap tab to jump to half, tap canvas to collapse. Full vocabulary. | ✓ |
| Tap-to-snap only | No continuous drag. Simpler but less fluid. | |
| Drag handle + tap outside | Drag to resize, tap outside to collapse. Tabs only switch panels. | |

**User's choice:** Drag handle + tap-to-snap
**Notes:** Full gesture vocabulary selected.

---

## Panel Tab Organization

### Tab Layout

| Option | Description | Selected |
|--------|-------------|----------|
| Flat icon row (scrollable) | All panels as icons in scrollable row. Simple but cluttered. | |
| Grouped categories (4-5 tabs) | Group into categories. 2 taps to panel, cleaner bar. | |
| Top 5 pinned + overflow menu | 5 pinned icons + ⋯ overflow. Clean, 1 tap for essentials. | ✓ |

**User's choice:** Top 5 pinned + overflow menu
**Notes:** Balance of quick access and cleanliness.

### Tab Bar Position

| Option | Description | Selected |
|--------|-------------|----------|
| Top of bottom sheet | Tabs at top of sheet, below drag handle. Moves with sheet. | ✓ |
| Fixed at screen bottom | Tabs permanently at bottom like iOS tab bar. Always visible. | |

**User's choice:** Top of bottom sheet
**Notes:** Standard bottom sheet pattern -- tabs are part of the sheet.

### Default Pinned Tabs

| Option | Description | Selected |
|--------|-------------|----------|
| Character, Inventory, Combat, Quest, Chat | Core RPG loop + social. Chat pinned as only text input surface. | ✓ |
| Character, Inventory, Equipment, Quest, Chat | Swaps Combat for Equipment. Equipment used more often outside combat. | |
| Character, Inventory, Combat, Chat, Map/Play | Includes Play panel for canvas toggle. Quest moves to overflow. | |

**User's choice:** Character, Inventory, Combat, Quest, Chat
**Notes:** None.

---

## HUD Presentation

### Bar Style

| Option | Description | Selected |
|--------|-------------|----------|
| Thin stacked bars | Three thin horizontal bars (4-6px) stacked vertically with labels and values. Level badge above. | ✓ |
| Segmented bar row | HP, mana, XP as chunky side-by-side segments. Bold, game-y. | |
| Radial gauges | Circular gauges for HP/mana, XP as arc. Distinctive but harder to read. | |

**User's choice:** Thin stacked bars
**Notes:** Clean, data-dense, familiar RPG style.

### Tap Behavior

| Option | Description | Selected |
|--------|-------------|----------|
| Tap opens Character panel at half | Opens bottom sheet to half-snap with Character panel. Quick stats access. | ✓ |
| Tap expands inline details | Expands HUD in-place with more detail. Tap again to collapse. | |
| No tap behavior | Display-only. Use tab bar to open Character panel. | |

**User's choice:** Tap opens Character panel at half
**Notes:** Natural extension of at-a-glance pattern.

---

## Reconnection UX

### Reconnection UI

| Option | Description | Selected |
|--------|-------------|----------|
| Subtle banner | Slim banner with spinner. Game state visible underneath. Auto-dismiss. | ✓ |
| Full-screen overlay | Translucent overlay with retry count and manual button. Prevents stale interaction. | |
| Silent with toast on success | No UI during reconnect. Toast on success. Least disruptive. | |

**User's choice:** Subtle banner
**Notes:** Extends existing .reconnect-banner CSS pattern.

### State Resync Depth

| Option | Description | Selected |
|--------|-------------|----------|
| Full GMCP resync | Server sends all packages like initial login. Client replaces all state. Simple, reliable. | ✓ |
| Incremental delta sync | Track deltas while disconnected. Efficient but needs server changes (out of scope). | |
| Minimal + lazy load | Only vitals and room on reconnect. Other state on-demand. Fast but stale panels. | |

**User's choice:** Full GMCP resync
**Notes:** No server changes needed. Already how the server works on new connections.

### Backoff Strategy

| Option | Description | Selected |
|--------|-------------|----------|
| Fast start, moderate cap | 500ms start, double, cap 15s, max 20 attempts. Good for brief drops. | |
| Gentle ramp, long cap | 1s start, double, cap 60s, retry indefinitely. Good for background resume. | |
| You decide | Claude picks sensible defaults during implementation. | ✓ |

**User's choice:** You decide
**Notes:** Technical implementation detail deferred to Claude's discretion.

---

## Claude's Discretion

- Exact exponential backoff parameters
- Overflow menu layout (grid vs list)
- Drag handle visual design
- Nested scroll handling within sheet content
- Service worker cache versioning
- Sheet transition animation timing

## Deferred Ideas

None -- discussion stayed within phase scope.
