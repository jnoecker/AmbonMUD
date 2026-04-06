# AmbonMUD Mobile-First Redesign

## What This Is

A comprehensive UX overhaul of AmbonMUD's web client (React + PixiJS) to transform it from a terminal-with-panels layout into a tap-first visual RPG that works beautifully on both mobile and desktop. The existing GMCP infrastructure already delivers structured data for every game system — this project redesigns how players interact with that data, eliminating the terminal for gameplay and replacing it with tap-based interactions, toast notifications, bottom-sheet panels, and a messenger-style chat UI.

## Core Value

Mobile players must have a first-class gameplay experience — every game system accessible through tap interactions, no terminal required for anything except social communication.

## Requirements

### Validated

Existing capabilities already working in the web client:

- ✓ WebSocket transport with GMCP structured data — existing
- ✓ Canvas world renderer (PixiJS scenes, parallax, weather, time-of-day) — existing
- ✓ Character panel (HP/mana/XP bars, stats, level, class) — existing
- ✓ Inventory panel with item display — existing
- ✓ Equipment panel with slot display — existing
- ✓ Combat panel with threat/target info — existing
- ✓ Quest panel with quest log — existing
- ✓ Crafting panel with recipe browser — existing
- ✓ Bank panel with deposit/withdraw — existing
- ✓ Auction panel with listings — existing
- ✓ Mail panel with send/read — existing
- ✓ Housing panel — existing
- ✓ Leaderboard panel — existing
- ✓ Admin panel — existing
- ✓ Chat panel with channel support — existing
- ✓ Compass display showing available exits — existing
- ✓ Action bar with command shortcuts — existing
- ✓ Command palette for quick command access — existing
- ✓ Shop/trade/trainer popouts — existing
- ✓ Login modal with authentication flow — existing
- ✓ GMCP handler for 20+ package families — existing

### Active

- [ ] Tap-first navigation — tap compass exits to move between rooms
- [ ] Responsive layout — single design that adapts from mobile (375px) through desktop (1440px+)
- [ ] Bottom sheet panel system — pull-up drawer for accessing all game panels on mobile
- [ ] Toast/overlay notification system — replace scrolling text output with brief contextual messages
- [ ] Tap-based combat UI — action buttons for attack, abilities, flee; tap targets to select
- [ ] Tap-based item interaction — tap to equip/use/drop, tap loot to pick up, drag to rearrange
- [ ] Tap-based NPC interaction — tap NPCs to talk, dialogue choices as buttons, shop items as tappable cards
- [ ] Tap-based interaction for all game systems — crafting, quests, guilds, bank, mail, housing, auction, etc.
- [ ] Chat bubble UI — messenger-style interface with channel tabs (say/tell/gossip/guild), the only place users type
- [ ] Mobile-optimized canvas rendering — PixiJS scenes, weather, parallax working on touch devices with appropriate performance
- [ ] Context-sensitive action menus — tap entities in room to see available actions
- [ ] Minimal HUD overlay — essential stats (HP/mana/XP) visible without opening panels
- [ ] PWA support — installable from browser, offline-capable shell, app-like experience

### Out of Scope

- Native mobile apps (iOS/Android) — PWA first; native wrapper only if PWA proves insufficient
- Server-side changes — this is a client-only redesign; GMCP protocol and game engine stay as-is
- New game features — no new commands, systems, or content; only new ways to interact with existing systems
- Telnet client changes — the telnet transport is unaffected
- Desktop-only features — no features that only work on desktop; everything must work on mobile

## Context

- **Web client stack:** React 19.2, PixiJS 8.16, TypeScript 5.9, built with Bun
- **Existing client location:** `web-v3/` (built to `src/main/resources/web-v3/` by Gradle)
- **GMCP coverage:** 20+ package families already emit structured data for all game systems (Char, Room, Combat, Quest, Crafting, Guild, Bank, Auction, Trade, etc.)
- **Current mobile state:** The web client was designed with mobile breakpoints (375px, 600px, 1024px) and has responsive CSS, but the terminal-centric layout and panel proliferation make it unusable in practice on small screens
- **Canvas system:** Full PixiJS rendering pipeline with SceneManager, camera system, parallax backgrounds, weather particles, time-of-day lighting, entity rendering, compass display
- **14 existing panels:** Character, Inventory, Equipment, Combat, Quest, Crafting, Bank, Auction, Mail, Housing, Leaderboard, Admin, Chat, Play — all need mobile-friendly interaction patterns
- **Design system:** "Surreal Gentle Magic" aesthetic with CSS custom properties in `web-v3/src/styles.css`, documented in `docs/STYLE_GUIDE.md` and `.impeccable.md`

## Constraints

- **Tech stack:** React + PixiJS + TypeScript + Bun — no framework migration
- **Server boundary:** Client-only changes; no GMCP protocol modifications or server code changes
- **Design system:** Must maintain "Surreal Gentle Magic" aesthetic and existing CSS variable system
- **Accessibility:** WCAG AA contrast, `prefers-reduced-motion` support, screen reader compatibility
- **Performance:** Canvas + React must run smoothly on mid-range mobile devices (target: 30fps minimum on 2-year-old phones)
- **PWA:** Must be installable and provide app-like experience on iOS Safari and Android Chrome

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| PWA over native apps | Same codebase, faster iteration, GMCP/WebSocket logic already in JS | — Pending |
| Tap compass for navigation | Simple, discoverable, leverages existing compass UI component | — Pending |
| Bottom sheet for panels | Familiar mobile pattern (maps apps, music apps), works with one hand | — Pending |
| Toast/overlay for text output | Terminal scroll area wastes mobile real estate; brief contextual messages are more mobile-native | — Pending |
| Chat bubble UI for social | Only place users type; messenger-style is universally understood on mobile | — Pending |
| Shared responsive redesign | One design serves both mobile and desktop rather than maintaining two separate layouts | — Pending |
| Eliminate terminal for gameplay | GMCP provides structured data for everything; tap UI is more natural than typing commands on a phone | — Pending |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd-transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd-complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-04-06 — Phase 1 (State Extraction) complete: App.tsx decomposed from 1,655→14 lines, 7 domain contexts with typed reducers, all panels consuming state via useContext, canvas bridge preserved at 60fps*
