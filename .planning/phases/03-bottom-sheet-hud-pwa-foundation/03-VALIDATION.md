---
phase: 3
slug: bottom-sheet-hud-pwa-foundation
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-05
---

# Phase 3 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | bun test (via `bun run lint` for TypeScript/ESLint) + manual browser testing |
| **Config file** | `web-v3/package.json` (scripts section) |
| **Quick run command** | `cd web-v3 && bun run lint` |
| **Full suite command** | `cd web-v3 && bun run lint && bun run build` |
| **Estimated runtime** | ~15 seconds |

---

## Sampling Rate

- **After every task commit:** Run `cd web-v3 && bun run lint`
- **After every plan wave:** Run `cd web-v3 && bun run lint && bun run build`
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 15 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 03-01-01 | 01 | 1 | PANEL-01 | — | N/A | build | `bun run build` | ✅ | ⬜ pending |
| 03-01-02 | 01 | 1 | PANEL-02 | — | N/A | build | `bun run build` | ✅ | ⬜ pending |
| 03-01-03 | 01 | 1 | PANEL-03 | — | N/A | build | `bun run build` | ✅ | ⬜ pending |
| 03-02-01 | 02 | 1 | HUD-01 | — | N/A | build | `bun run build` | ✅ | ⬜ pending |
| 03-02-02 | 02 | 1 | HUD-03 | — | N/A | build | `bun run build` | ✅ | ⬜ pending |
| 03-03-01 | 03 | 2 | PWA-01 | — | N/A | build | `bun run build` | ✅ | ⬜ pending |
| 03-03-02 | 03 | 2 | PWA-02 | — | N/A | build | `bun run build` | ✅ | ⬜ pending |
| 03-03-03 | 03 | 2 | PWA-05 | — | N/A | build | `bun run build` | ✅ | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

Existing infrastructure covers all phase requirements. `bun run lint` and `bun run build` are already configured.

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Bottom sheet drag gesture snaps to peek/half/full | PANEL-01 | Touch gesture requires real device or simulator | Open in mobile viewport, drag sheet handle up/down, verify snap positions |
| Desktop side panel CSS-only transition | PANEL-03 | Requires visual verification at 1024px+ | Resize browser to desktop width, confirm sheet becomes side panel without JS branching |
| PWA installability | PWA-01 | Requires real browser install prompt | Open in Chrome DevTools > Application > Manifest, verify installable |
| iOS Add to Home Screen | PWA-01 | Requires real iOS Safari | Open in iOS Safari, verify Add to Home Screen works |
| WebSocket reconnection after backgrounding | PWA-05 | Requires real device app backgrounding | Background the PWA, wait, foreground, verify reconnect banner + state resync |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 15s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
