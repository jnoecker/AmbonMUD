---
phase: 2
slug: layout-shell-canvas-safety
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-05
---

# Phase 2 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | None installed for web-v3 (ESLint + TypeScript only) |
| **Config file** | `web-v3/eslint.config.js` |
| **Quick run command** | `cd web-v3 && bun run lint` |
| **Full suite command** | `cd web-v3 && bun run lint && bun run build` |
| **Estimated runtime** | ~15 seconds |

---

## Sampling Rate

- **After every task commit:** Run `cd web-v3 && bun run lint`
- **After every plan wave:** Run `cd web-v3 && bun run lint && bun run build`
- **Before `/gsd-verify-work`:** Full suite must be green + visual verification at 375px, 768px, 1024px, 1440px
- **Max feedback latency:** 15 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 02-01-01 | 01 | 1 | LAYOUT-01 | — | N/A | manual | `./gradlew demo` + Chrome DevTools 375px | N/A | ⬜ pending |
| 02-01-02 | 01 | 1 | LAYOUT-02 | — | N/A | manual | iOS simulator / real device | N/A | ⬜ pending |
| 02-01-03 | 01 | 1 | LAYOUT-03 | — | N/A | manual | Chrome DevTools responsive mode | N/A | ⬜ pending |
| 02-02-01 | 02 | 1 | CANVAS-02 | — | N/A | lint | `cd web-v3 && bun run lint` | ⬜ W0 | ⬜ pending |
| 02-02-02 | 02 | 1 | CANVAS-03 | — | N/A | lint | `cd web-v3 && bun run lint` | ⬜ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- "Existing infrastructure covers all phase requirements."

*No test framework installation needed — ESLint + TypeScript type checking covers automated validation. Layout and canvas behaviors are inherently visual and verified via `./gradlew demo` + Chrome DevTools.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| LayoutShell renders three zones at mobile breakpoint | LAYOUT-01 | CSS layout cannot be tested without browser | Open `./gradlew demo`, toggle Chrome DevTools to 375px width, verify CanvasZone/HudSlot/InteractionZone visible |
| Safe area insets applied | LAYOUT-02 | Requires real device with notch/Dynamic Island | Test on iOS Safari with `viewport-fit=cover`; verify no content obscured by notch |
| Canvas adapts at all breakpoints | LAYOUT-03 | Visual rendering verification | Chrome DevTools: test at 375px, 768px, 1024px, 1440px; verify canvas proportion changes |
| PixiJS GC fires at 5s idle | CANVAS-02 | Requires running PixiJS app and monitoring texture disposal | Open demo, navigate rooms, wait 5s, verify texture count in PixiJS dev tools |
| DPR capped at 2x on mobile | CANVAS-03 | Requires high-DPR device or emulation | Check `app.renderer.resolution` value via console on mobile device/emulator |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 15s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
