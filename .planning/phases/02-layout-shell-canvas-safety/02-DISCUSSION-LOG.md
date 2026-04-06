# Phase 2: Layout Shell & Canvas Safety - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md -- this log preserves the alternatives considered.

**Date:** 2026-04-05
**Phase:** 02-layout-shell-canvas-safety
**Areas discussed:** Canvas/UI split, Layout shell design, VRAM budget strategy, Existing panel impact

---

## Canvas/UI Split

| Option | Description | Selected |
|--------|-------------|----------|
| Canvas dominant (~60%) | Canvas fills upper ~60% of viewport. Leaves ~40% for action bar, HUD, and future bottom sheet. Prioritizes the visual RPG feel. | ✓ |
| Balanced (~50/50) | Canvas and interaction space split evenly. More room for text/panels but smaller world view. | |
| Flexible (CSS clamp) | Canvas height uses clamp(40%, 50vh, 65%) -- adapts to device height. | |

**User's choice:** Canvas dominant (~60%)
**Notes:** None

| Option | Description | Selected |
|--------|-------------|----------|
| Fixed 60% | Simple, predictable. Canvas always gets exactly 60% of viewport height. | |
| Fluid floor at 60% | clamp(60%, 65vh, 75%) -- on taller phones the canvas can grow up to 75%, but never below 60%. | ✓ |
| You decide | Claude picks the best approach during implementation. | |

**User's choice:** Fluid floor at 60% -- clamp(60%, 65vh, 75%)
**Notes:** None

| Option | Description | Selected |
|--------|-------------|----------|
| Canvas left, panels right | Canvas takes ~65-70% width on left, sidebar/panels stack on right. | |
| Full-width canvas + overlay | Canvas fills entire viewport. Panels float as overlays/popovers on top. Maximum immersion. | ✓ |
| Keep current dashboard | Preserve the existing desktop grid layout. Focus Phase 2 on mobile only. | |

**User's choice:** Full-width canvas + overlay on desktop
**Notes:** None

---

## Layout Shell Design

| Option | Description | Selected |
|--------|-------------|----------|
| New LayoutShell wrapper | Create a new LayoutShell component that wraps AppShell. Handles viewport zones, safe areas, and responsive breakpoints. | ✓ |
| Refactor AppShell in place | Add layout zones directly into AppShell.tsx. Fewer components, but mixes concerns. | |
| CSS-only approach | Define the layout shell purely in CSS using the existing .app-shell class. | |

**User's choice:** New LayoutShell wrapper
**Notes:** None

| Option | Description | Selected |
|--------|-------------|----------|
| Keep 960px/480px | Preserve existing breakpoints. Minimal disruption. | |
| Standard 3-tier | Mobile < 768px, tablet 768-1023px, desktop >= 1024px. Industry standard. | ✓ |
| Mobile-first 2-tier | Mobile < 1024px, desktop >= 1024px. Simple. | |

**User's choice:** Standard 3-tier breakpoints
**Notes:** None

---

## VRAM Budget Strategy

| Option | Description | Selected |
|--------|-------------|----------|
| Aggressive (recommended) | 5s GC timer + texture atlas pooling + VRAM budget cap + destroy on scene transitions + fallback quality + monitoring | ✓ |
| Moderate | 5s GC timer + destroy on scene transitions + DPR cap. No pooling or monitoring. | |
| Minimal (spec only) | Just 5s GC timer and 2x DPR cap. | |

**User's choice:** Aggressive
**Notes:** None

---

## Existing Panel Impact

| Option | Description | Selected |
|--------|-------------|----------|
| Reposition into zones | Move existing panels into the new LayoutShell zones. Transitional appearance acceptable. | ✓ |
| Hide on mobile, keep desktop | Hide sidebar and top banner on mobile entirely. Clean but loses functionality. | |
| Compact action bar only | Keep action bar, hide sidebar and top banner on mobile. | |

**User's choice:** Reposition into zones
**Notes:** None

| Option | Description | Selected |
|--------|-------------|----------|
| Keep visible (scroll below) | Terminal stays in InteractionZone, scrollable. | |
| Collapse to minimal | Terminal collapses to thin strip showing last 2-3 lines. | |
| Hide on mobile | Terminal hidden on mobile entirely. Desktop keeps terminal as-is. | ✓ |

**User's choice:** Hide terminal on mobile
**Notes:** Players rely on existing popouts and canvas for feedback until Phase 5 adds toasts.

---

## Claude's Discretion

- CSS layout strategy for LayoutShell (grid vs flexbox)
- VRAM manager architecture (standalone vs integrated in SceneManager)
- Safe-area-inset CSS implementation details
- Tablet breakpoint behavior (768-1023px)
