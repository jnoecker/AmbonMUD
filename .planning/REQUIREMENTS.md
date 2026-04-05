# Requirements: AmbonMUD Mobile-First Redesign

**Defined:** 2026-04-05
**Core Value:** Mobile players must have a first-class gameplay experience -- every game system accessible through tap interactions, no terminal required for anything except social communication.

## v1 Requirements

Requirements for initial release. Each maps to roadmap phases.

### Layout & Responsive Design

- [ ] **LAYOUT-01**: Portrait-first responsive layout (375px -> 1440px+) as single component tree
- [ ] **LAYOUT-02**: Safe area inset handling for notches, Dynamic Island, punch-hole cameras
- [ ] **LAYOUT-03**: Canvas viewport fills upper portion of mobile screen, adapts to all breakpoints
- [ ] **LAYOUT-04**: All interactive elements meet 48x48dp minimum touch target size
- [ ] **LAYOUT-05**: Landscape orientation support (canvas stretches, HUD repositions)
- [ ] **LAYOUT-06**: Tablet hybrid layout (600px-1024px) with larger canvas and optional side panel

### State Management

- [ ] **STATE-01**: Extract App.tsx monolith (90+ useState) into GameStateProvider context + reducer
- [ ] **STATE-02**: Preserve GameStateBridge ref pattern for canvas performance (60fps reads)

### Bottom Sheet Panel System

- [ ] **PANEL-01**: Bottom sheet container with peek/half/full snap points, drag-to-resize, swipe-to-dismiss
- [ ] **PANEL-02**: Panel navigation tab bar with categorized icons for all 14+ panels
- [ ] **PANEL-03**: Bottom sheet becomes sidebar on desktop breakpoint (same component, CSS positioning)
- [ ] **PANEL-04**: Character panel mobile layout
- [ ] **PANEL-05**: Inventory panel with tap-to-act (equip/use/drop/give action menu)
- [ ] **PANEL-06**: Equipment panel with tappable slot icons
- [ ] **PANEL-07**: Combat panel mobile layout
- [ ] **PANEL-08**: Quest log panel mobile layout
- [ ] **PANEL-09**: Crafting panel mobile layout
- [ ] **PANEL-10**: Bank panel mobile layout
- [ ] **PANEL-11**: Auction panel mobile layout
- [ ] **PANEL-12**: Mail panel mobile layout
- [ ] **PANEL-13**: Housing panel mobile layout
- [ ] **PANEL-14**: Leaderboard panel mobile layout
- [ ] **PANEL-15**: Admin panel mobile layout
- [ ] **PANEL-16**: Shop/Trade/Trainer as bottom sheet variants

### HUD & Status Display

- [ ] **HUD-01**: Minimal always-visible HUD (HP/mana/XP bars, max ~60px vertical)
- [ ] **HUD-02**: Target/combat status in HUD during combat (target name + HP)
- [ ] **HUD-03**: Level and class badge indicator
- [ ] **HUD-04**: Status effect icon strip with duration, tap to expand details

### Navigation

- [ ] **NAV-01**: Tap compass exits to move between rooms
- [ ] **NAV-02**: Swipe-to-move in cardinal directions as alternative navigation

### Combat Interaction

- [ ] **COMBAT-01**: Action bar with ability buttons and cooldown sweep visualization
- [ ] **COMBAT-02**: Tap-to-target entity selection (tap mob in room list or BattleScene)
- [ ] **COMBAT-03**: Auto-attack toggle button during combat
- [ ] **COMBAT-04**: Flee button accessible during combat

### Toast & Notification System

- [ ] **TOAST-01**: Toast notification infrastructure (queue, stacking max 3-4, auto-dismiss, priority levels, category styling)
- [ ] **TOAST-02**: Combat feedback as rate-limited toasts (aggregate rapid events, canvas floating text for per-hit)
- [ ] **TOAST-03**: Gain event toasts (+XP, +gold, item acquired)
- [ ] **TOAST-04**: Quest/achievement completion toasts
- [ ] **TOAST-05**: Level up celebration overlay (full-screen animation, auto-dismiss)

### Chat UI

- [ ] **CHAT-01**: Messenger-style chat bubble interface (own=right, others=left, sender colors)
- [ ] **CHAT-02**: Channel tabs (say/tell/gossip/guild/ooc) with unread indicators
- [ ] **CHAT-03**: Expandable text input (single -> multi-line)
- [ ] **CHAT-04**: Chat minimized mode (last 1-2 messages as overlay, tap to expand)
- [ ] **CHAT-05**: Virtual keyboard avoidance (layout adjusts when soft keyboard opens)

### NPC & Entity Interaction

- [ ] **NPC-01**: Tap NPC to initiate dialogue
- [ ] **NPC-02**: Dialogue choices rendered as tappable buttons
- [ ] **NPC-03**: Shop items as tappable cards in bottom sheet
- [ ] **NPC-04**: Context-sensitive action menus on entity tap (look/kill/talk/trade filtered by type)

### Canvas & Performance

- [ ] **CANVAS-01**: PixiJS touch event handling (pointertap on entities, compass)
- [ ] **CANVAS-02**: Aggressive texture GC for iOS (5-second idle instead of 60)
- [ ] **CANVAS-03**: Resolution capping on mobile devices for performance
- [ ] **CANVAS-04**: Floating combat text on canvas (damage numbers float up + fade)

### PWA

- [ ] **PWA-01**: Web app manifest (name, icons, theme_color, display: standalone)
- [ ] **PWA-02**: Service worker for app shell caching (cache-first static, network-only WebSocket)
- [ ] **PWA-03**: Add to Home Screen prompting (Android auto, iOS instructional overlay)
- [ ] **PWA-04**: Splash screen / loading state on launch
- [ ] **PWA-05**: WebSocket reconnection with exponential backoff and state resync after backgrounding

### First-Time Experience

- [ ] **FTUE-01**: Guided walkthrough for new mobile players (move, fight, open panels -- 5-7 steps)

## v2 Requirements

Deferred to future release. Tracked but not in current roadmap.

### Push Notifications

- **PUSH-01**: Rich push notifications for auction sales, guild events, etc. (requires server-side push subscription endpoint)

### Map Features

- **MAP-01**: Offline world map viewer (cached zone adjacency, read-only when disconnected)
- **MAP-02**: Mini-map with fog of war (explored rooms shown, unexplored dimmed)

### Advanced Gestures

- **GESTURE-01**: Gesture-based emotes (swipe patterns trigger common emotes)
- **GESTURE-02**: Quick-cast gesture shortcuts (long-press + swipe to cast pinned spells)

## Out of Scope

Explicitly excluded. Documented to prevent scope creep.

| Feature | Reason |
|---------|--------|
| Virtual joystick for movement | MUD rooms are discrete locations, not continuous space; tap compass is better |
| Full terminal emulator on mobile | Entire point of redesign is eliminating terminal; hidden debug option at most |
| Native app wrapper (Capacitor/Cordova) | PWA first per project decision; native only if PWA proves insufficient |
| Drag-and-drop inventory management | Unreliable on mobile (conflicts with scroll, accidental drops); tap-to-act preferred |
| Complex multi-touch gestures | Pinch-to-zoom, two-finger rotate add complexity without clear RPG gameplay value |
| Server-side changes | Client-only redesign; GMCP already provides all structured data needed |
| Landscape-only lock | Portrait-first is mandatory (73% of top mobile games); landscape supported but not forced |
| Desktop-only features | Everything must work on mobile; shared responsive design |
| Inline text command input for gameplay | Tap interactions for all gameplay; text input reserved for social chat only |

## Traceability

Which phases cover which requirements. Updated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| STATE-01 | Phase 1 | Pending |
| STATE-02 | Phase 1 | Pending |
| LAYOUT-01 | Phase 2 | Pending |
| LAYOUT-02 | Phase 2 | Pending |
| LAYOUT-03 | Phase 2 | Pending |
| LAYOUT-04 | Phase 9 | Pending |
| LAYOUT-05 | Phase 9 | Pending |
| LAYOUT-06 | Phase 9 | Pending |
| PANEL-01 | Phase 3 | Pending |
| PANEL-02 | Phase 3 | Pending |
| PANEL-03 | Phase 3 | Pending |
| PANEL-04 | Phase 8 | Pending |
| PANEL-05 | Phase 8 | Pending |
| PANEL-06 | Phase 8 | Pending |
| PANEL-07 | Phase 8 | Pending |
| PANEL-08 | Phase 8 | Pending |
| PANEL-09 | Phase 8 | Pending |
| PANEL-10 | Phase 8 | Pending |
| PANEL-11 | Phase 8 | Pending |
| PANEL-12 | Phase 8 | Pending |
| PANEL-13 | Phase 8 | Pending |
| PANEL-14 | Phase 8 | Pending |
| PANEL-15 | Phase 8 | Pending |
| PANEL-16 | Phase 8 | Pending |
| HUD-01 | Phase 3 | Pending |
| HUD-02 | Phase 4 | Pending |
| HUD-03 | Phase 3 | Pending |
| HUD-04 | Phase 4 | Pending |
| NAV-01 | Phase 4 | Pending |
| NAV-02 | Phase 4 | Pending |
| COMBAT-01 | Phase 4 | Pending |
| COMBAT-02 | Phase 4 | Pending |
| COMBAT-03 | Phase 4 | Pending |
| COMBAT-04 | Phase 4 | Pending |
| TOAST-01 | Phase 5 | Pending |
| TOAST-02 | Phase 5 | Pending |
| TOAST-03 | Phase 5 | Pending |
| TOAST-04 | Phase 5 | Pending |
| TOAST-05 | Phase 5 | Pending |
| CHAT-01 | Phase 6 | Pending |
| CHAT-02 | Phase 6 | Pending |
| CHAT-03 | Phase 6 | Pending |
| CHAT-04 | Phase 6 | Pending |
| CHAT-05 | Phase 6 | Pending |
| NPC-01 | Phase 7 | Pending |
| NPC-02 | Phase 7 | Pending |
| NPC-03 | Phase 7 | Pending |
| NPC-04 | Phase 7 | Pending |
| CANVAS-01 | Phase 4 | Pending |
| CANVAS-02 | Phase 2 | Pending |
| CANVAS-03 | Phase 2 | Pending |
| CANVAS-04 | Phase 5 | Pending |
| PWA-01 | Phase 3 | Pending |
| PWA-02 | Phase 3 | Pending |
| PWA-03 | Phase 9 | Pending |
| PWA-04 | Phase 9 | Pending |
| PWA-05 | Phase 3 | Pending |
| FTUE-01 | Phase 9 | Pending |

**Coverage:**
- v1 requirements: 58 total
- Mapped to phases: 58
- Unmapped: 0

---
*Requirements defined: 2026-04-05*
*Last updated: 2026-04-05 after roadmap creation*
