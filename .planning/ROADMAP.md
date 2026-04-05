# Roadmap: AmbonMUD Mobile-First Redesign

## Overview

This roadmap transforms AmbonMUD's web client from a desktop terminal-with-panels layout into a tap-first mobile RPG. The work progresses from foundational refactoring (state extraction, layout shell) through core interaction surfaces (bottom sheet, HUD, navigation, combat) to content migration and polish. Every phase builds on the previous -- state context must exist before panels can consume it, the layout shell must exist before anything is positioned inside it, and the bottom sheet must be stable before 13 panels are migrated into it. The existing GMCP infrastructure already delivers all structured data; this project changes how players interact with it.

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

- [ ] **Phase 1: State Extraction** - Decompose App.tsx monolith into GameStateProvider context + reducer
- [ ] **Phase 2: Layout Shell & Canvas Safety** - Portrait-first responsive container with canvas mobile hardening
- [ ] **Phase 3: Bottom Sheet, HUD & PWA Foundation** - Primary interaction surface with installability from day one
- [ ] **Phase 4: Navigation & Combat** - Tap-first core gameplay loop with compass, targeting, and action bar
- [ ] **Phase 5: Toast & Notification System** - Contextual feedback replacing terminal scroll output
- [ ] **Phase 6: Chat Interface** - Messenger-style bubble UI with full keyboard management
- [ ] **Phase 7: NPC & Entity Interaction** - Tap-based dialogue, shop, and context menus
- [ ] **Phase 8: Panel Content Migration** - All 13 game panels redesigned for mobile bottom sheet
- [ ] **Phase 9: PWA Polish & First-Time Experience** - Installability prompts, orientation, touch targets, onboarding

## Phase Details

### Phase 1: State Extraction
**Goal**: All game state lives in a single GameStateProvider context, enabling every downstream component to consume structured data without prop drilling through a 1,655-line monolith
**Depends on**: Nothing (first phase)
**Requirements**: STATE-01, STATE-02
**Success Criteria** (what must be TRUE):
  1. App.tsx is under 100 lines and contains only the component tree layout, not state logic
  2. Any component in the tree can read game state (HP, inventory, room, etc.) via useContext without prop drilling
  3. Canvas still reads state via mutable refs at 60fps without triggering React re-renders
  4. All existing functionality works identically after the refactor (no regressions)
**Plans**: TBD
**UI hint**: yes

### Phase 2: Layout Shell & Canvas Safety
**Goal**: The app renders in a portrait-first responsive shell that adapts from 375px to 1440px+, and the PixiJS canvas runs safely on mobile without VRAM crashes or performance degradation
**Depends on**: Phase 1
**Requirements**: LAYOUT-01, LAYOUT-02, LAYOUT-03, CANVAS-02, CANVAS-03
**Success Criteria** (what must be TRUE):
  1. On a 375px-wide mobile viewport, the canvas fills the upper portion and leaves room for interaction surfaces below
  2. On a 1440px desktop viewport, the layout uses available space without wasted whitespace or broken proportions
  3. Content respects safe area insets -- nothing is obscured by notches, Dynamic Island, or punch-hole cameras
  4. PixiJS canvas does not crash on iOS Safari after 20+ room transitions (VRAM managed via aggressive texture GC)
  5. Canvas resolution is capped at 2x device pixel ratio on mobile devices
**Plans**: TBD
**UI hint**: yes

### Phase 3: Bottom Sheet, HUD & PWA Foundation
**Goal**: Players access all game panels through a familiar pull-up bottom sheet, see essential stats at a glance in a minimal HUD, and can install the app as a PWA with reliable WebSocket reconnection
**Depends on**: Phase 2
**Requirements**: PANEL-01, PANEL-02, PANEL-03, HUD-01, HUD-03, PWA-01, PWA-02, PWA-05
**Success Criteria** (what must be TRUE):
  1. Player can drag up a bottom sheet from the bottom of the screen and snap it to peek, half, and full positions
  2. A tab bar at the bottom sheet's top provides categorized navigation to all 14+ panels
  3. On desktop breakpoint (1024px+), the bottom sheet renders as a side panel without any code branching (CSS only)
  4. HP, mana, and XP bars are always visible in a compact HUD overlay (max ~60px vertical) without opening any panel
  5. Level and class badge are visible in the HUD at all times
  6. The app is installable via "Add to Home Screen" on both Android Chrome and iOS Safari
  7. After the app is backgrounded and WebSocket disconnects, it reconnects with exponential backoff and resyncs full game state
**Plans**: TBD
**UI hint**: yes

### Phase 4: Navigation & Combat
**Goal**: Players navigate the world and fight enemies entirely through tap interactions -- no terminal commands needed for the core gameplay loop
**Depends on**: Phase 3
**Requirements**: NAV-01, NAV-02, CANVAS-01, COMBAT-01, COMBAT-02, COMBAT-03, COMBAT-04, HUD-02, HUD-04
**Success Criteria** (what must be TRUE):
  1. Player can tap compass exits to move between rooms
  2. Player can swipe in cardinal directions as an alternative way to move
  3. Player can tap a mob in the room list or on the canvas to select it as a target
  4. During combat, an action bar shows ability buttons with cooldown sweep animations, plus a flee button
  5. Player can toggle auto-attack on/off during combat
  6. During combat, the HUD shows the current target's name and HP bar
  7. Status effect icons appear in the HUD with duration indicators; tapping expands details
**Plans**: TBD
**UI hint**: yes

### Phase 5: Toast & Notification System
**Goal**: Game events appear as brief contextual notifications instead of scrolling terminal text, with combat events intelligently aggregated to prevent toast fatigue
**Depends on**: Phase 4
**Requirements**: TOAST-01, TOAST-02, TOAST-03, TOAST-04, TOAST-05, CANVAS-04
**Success Criteria** (what must be TRUE):
  1. When a player gains XP, gold, or an item, a styled toast appears briefly and auto-dismisses
  2. During combat, rapid damage/heal events are aggregated into summary toasts (not one per hit), capped at 3 visible toasts
  3. Quest completion and achievement unlock trigger distinct celebration toasts
  4. Level up triggers a full-screen celebration overlay that auto-dismisses
  5. Floating damage numbers rise and fade on the canvas above entities during combat
**Plans**: TBD
**UI hint**: yes

### Phase 6: Chat Interface
**Goal**: Social communication uses a messenger-style bubble UI -- the only place players type -- with proper virtual keyboard handling on both iOS and Android
**Depends on**: Phase 3
**Requirements**: CHAT-01, CHAT-02, CHAT-03, CHAT-04, CHAT-05
**Success Criteria** (what must be TRUE):
  1. Chat messages appear as bubbles (own messages right-aligned, others left-aligned) with sender-colored styling
  2. Channel tabs (say/tell/gossip/guild/ooc) allow switching between chat channels, with unread indicators on inactive tabs
  3. Text input expands from single-line to multi-line as the player types
  4. When minimized, the last 1-2 messages appear as a floating overlay; tapping expands the full chat
  5. Opening the virtual keyboard does not push the HUD or canvas off-screen; layout adjusts gracefully on both iOS and Android
**Plans**: TBD
**UI hint**: yes

### Phase 7: NPC & Entity Interaction
**Goal**: Players interact with NPCs and world entities through tap-based menus -- dialogue choices as buttons, shop items as cards, contextual actions on any tappable entity
**Depends on**: Phase 4
**Requirements**: NPC-01, NPC-02, NPC-03, NPC-04
**Success Criteria** (what must be TRUE):
  1. Tapping an NPC in the room list or canvas initiates dialogue
  2. Dialogue choices appear as tappable buttons (not a numbered text list)
  3. Shop inventory renders as scrollable tappable cards in the bottom sheet
  4. Tapping any entity in the room shows a context menu with relevant actions (look/kill/talk/trade) filtered by entity type
**Plans**: TBD
**UI hint**: yes

### Phase 8: Panel Content Migration
**Goal**: All 13 game-specific panels are redesigned with mobile-first layouts inside the bottom sheet, with tap-based interactions replacing any terminal-dependent workflows
**Depends on**: Phase 3, Phase 5
**Requirements**: PANEL-04, PANEL-05, PANEL-06, PANEL-07, PANEL-08, PANEL-09, PANEL-10, PANEL-11, PANEL-12, PANEL-13, PANEL-14, PANEL-15, PANEL-16
**Success Criteria** (what must be TRUE):
  1. Character panel displays stats, level, class, and progression in a mobile-friendly layout
  2. Inventory panel lets players tap any item to see an action menu (equip/use/drop/give) without typing
  3. Equipment panel shows tappable slot icons that allow equipping and removing gear
  4. Quest, crafting, bank, auction, mail, housing, and leaderboard panels all render readable, scrollable content at 375px width
  5. Shop, trade, and trainer interactions appear as bottom sheet variants with tappable UI elements
**Plans**: TBD
**UI hint**: yes

### Phase 9: PWA Polish & First-Time Experience
**Goal**: The app feels native with proper install prompting, orientation handling, consistent touch targets, and a guided walkthrough for new mobile players
**Depends on**: Phase 8
**Requirements**: PWA-03, PWA-04, LAYOUT-04, LAYOUT-05, LAYOUT-06, FTUE-01
**Success Criteria** (what must be TRUE):
  1. On Android, the browser shows an install prompt; on iOS, a custom instructional overlay guides users to "Add to Home Screen"
  2. A splash screen with branding appears while the app loads on PWA launch
  3. All interactive elements across the entire app meet the 48x48dp minimum touch target size
  4. Rotating to landscape repositions the HUD and stretches the canvas appropriately
  5. On tablets (600-1024px), a hybrid layout shows a larger canvas with an optional persistent side panel
  6. New mobile players see a 5-7 step guided walkthrough teaching move, fight, and panel access (with a skip option)
**Plans**: TBD
**UI hint**: yes

## Progress

**Execution Order:**
Phases execute in numeric order: 1 -> 2 -> 3 -> 4 -> 5 -> 6 -> 7 -> 8 -> 9

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. State Extraction | 0/0 | Not started | - |
| 2. Layout Shell & Canvas Safety | 0/0 | Not started | - |
| 3. Bottom Sheet, HUD & PWA Foundation | 0/0 | Not started | - |
| 4. Navigation & Combat | 0/0 | Not started | - |
| 5. Toast & Notification System | 0/0 | Not started | - |
| 6. Chat Interface | 0/0 | Not started | - |
| 7. NPC & Entity Interaction | 0/0 | Not started | - |
| 8. Panel Content Migration | 0/0 | Not started | - |
| 9. PWA Polish & First-Time Experience | 0/0 | Not started | - |
