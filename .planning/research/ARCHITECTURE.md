# Architecture Patterns

**Domain:** Mobile-first RPG web client (React 19 + PixiJS 8)
**Researched:** 2026-04-05

## Recommended Architecture

The mobile-first redesign restructures the existing monolithic `App.tsx` (1,655 lines, 90+ useState calls, all state lifted to root) into a layered architecture with clear boundaries between layout orchestration, panel rendering, interaction routing, notification delivery, and canvas touch handling. The server boundary is untouched -- all changes are client-side, and the existing GMCP protocol and `applyGmcpPackage.ts` handler remain the single source of truth for game state.

### High-Level Component Tree

```
<App>                              (root -- slimmed to layout orchestration only)
  <GameStateProvider>              (React context wrapping GMCP state + command dispatch)
    <MobileShell>                  (responsive layout container, breakpoint-aware)
      <Canvas>                     (PixiJS -- fills viewport, touch-interactive)
        SceneManager               (world/battle scenes, unchanged core)
        TouchInteractionLayer      (new: tap targets, gesture routing)
      </Canvas>
      <HUD>                        (minimal overlay: HP/mana/XP bars, compass)
      <BottomSheet>                (pull-up drawer: panel switching, gesture-driven)
        <PanelRouter>              (renders active panel based on bottom sheet state)
          <CharacterPanel />
          <InventoryPanel />
          <CombatPanel />
          <ChatPanel />
          ... (all 14 existing panels, adapted)
        </PanelRouter>
      </BottomSheet>
      <BottomNav>                  (fixed tab bar: 4-5 primary destinations)
      <ToastLayer>                 (notification overlays, auto-dismiss)
      <ChatBubbleOverlay>          (messenger-style floating chat)
      <ActionMenuOverlay>          (context menus from canvas taps)
    </MobileShell>
  </GameStateProvider>
</App>
```

### Component Boundaries

| Component | Responsibility | Communicates With | Data Direction |
|-----------|---------------|-------------------|----------------|
| **GameStateProvider** | Holds all GMCP-derived state (vitals, room, mobs, inventory, etc.). Wraps `applyGmcpPackage` output. Provides `sendCommand()` and `sendGmcp()` via context. | useMudSocket (WebSocket), all consumers via context | GMCP in -> state out via context |
| **MobileShell** | Responsive layout orchestration. Decides viewport split between canvas, HUD, bottom sheet. Manages safe area insets. | Reads viewport size, passes layout hints to children | Layout props down |
| **Canvas (PixiCanvas)** | PixiJS rendering. World scene, battle scene, entity sprites. Owns the `<canvas>` DOM element. | Reads GameStateBridge (ref-based, not React state). Emits tap events via CanvasEventBus/callbacks. | State in (ref), tap events out (callback) |
| **TouchInteractionLayer** | Intercepts pointer/tap events on canvas entities. Translates taps into actions (move, attack, talk, loot). Opens context menus. | Canvas hit testing, canvasCallbacks.sendCommand, ActionMenuOverlay | Tap in -> command out |
| **HUD** | Always-visible minimal overlay. HP/mana/XP progress bars, compass exits, combat target indicator. | Reads vitals, room exits, combatTarget from context | State in -> display |
| **BottomSheet** | Gesture-driven pull-up drawer. Manages snap points (collapsed/peek/half/full). Hosts panel content. | Touch gestures in, panel visibility state, PanelRouter | Gesture in -> panel visibility |
| **PanelRouter** | Renders the active panel component inside the bottom sheet. | BottomSheet (which panel), GameStateProvider (panel data) | Panel ID in -> component out |
| **BottomNav** | Fixed tab bar at screen bottom (4-5 icons). Triggers bottom sheet open with specific panel. | BottomSheet (open/switch panel), active panel state | Tap in -> panel selection |
| **ToastLayer** | Renders toast notifications. Manages queue, auto-dismiss timers, stacking. | GameStateProvider (UiFeedback, combat events, gain events, quest notifications) | Events in -> toast display |
| **ChatBubbleOverlay** | Messenger-style floating chat UI. Channel tabs, message input, bubble display. Only place users type freely. | GameStateProvider (chatByChannel, sendCommand for chat) | Chat state in -> messages out |
| **ActionMenuOverlay** | Context menu that appears when tapping entities on canvas. Shows available actions (attack, talk, loot, look). | TouchInteractionLayer (what was tapped), sendCommand (action execution) | Entity info in -> command out |

### Data Flow

```
                WebSocket
                    |
                    v
            useMudSocket hook
                    |
          +---------+---------+
          |                   |
     text (terminal)    GMCP packages
          |                   |
          v                   v
     [deprecated       applyGmcpPackage()
      terminal only]          |
                              v
                    GameStateProvider (context)
                     /    |    |    \
                    /     |    |     \
                   v      v    v      v
                 HUD  Panels  Chat  ToastLayer
                          |
              GameStateBridge (ref sync)
                          |
                          v
                    PixiJS Canvas
                          |
                    Touch events
                          |
                          v
                  canvasCallbacks.sendCommand()
                          |
                          v
                    useMudSocket.sendLine()
                          |
                          v
                      WebSocket
```

**Key data flow principles:**

1. **GMCP is the single source of truth.** Every piece of game state arrives via GMCP packages. The client never derives game state from text output.

2. **Commands flow through a single `sendCommand()` function.** Whether from canvas tap, bottom nav button, chat input, or action menu -- all user actions resolve to a command string sent over WebSocket.

3. **Canvas reads state via ref, not React state.** The existing `gameStateRef` pattern (mutable ref synced each render) remains unchanged. This avoids re-rendering the entire canvas on every state change.

4. **React state lives in context, not App.tsx root.** The 90+ useState calls currently in App.tsx move into a GameStateProvider. Panels consume only what they need via selective context reads or `useSyncExternalStore`.

5. **Toast/notification events flow through a dedicated channel.** Combat events, gain events, UI feedback, quest notifications all route to the ToastLayer rather than the terminal.

## Patterns to Follow

### Pattern 1: GameStateProvider (Context + Reducer)

**What:** Centralize the 90+ useState calls from App.tsx into a single React context backed by a reducer. The GMCP handler dispatches actions to this reducer.

**When:** This is the foundational refactor. Must be done first because every other component depends on it.

**Why:** The current App.tsx is a 1,655-line component with all state lifted to the root. This causes unnecessary re-renders (every GMCP update re-renders every panel). A context with selective subscription eliminates this.

```typescript
// GameStateContext.ts
interface GameState {
  vitals: Vitals;
  character: CharacterInfo;
  room: RoomState;
  mobs: RoomMob[];
  inventory: ItemSummary[];
  // ... all 90+ state fields
}

type GameAction =
  | { type: "GMCP_VITALS"; payload: Partial<Vitals> }
  | { type: "GMCP_ROOM"; payload: RoomState }
  | { type: "GMCP_MOBS"; payload: RoomMob[] }
  // ... one action per GMCP handler

function gameReducer(state: GameState, action: GameAction): GameState {
  switch (action.type) {
    case "GMCP_VITALS":
      return { ...state, vitals: { ...state.vitals, ...action.payload } };
    // ...
  }
}

// Provider wraps the app, exposes state + dispatch + sendCommand
```

**Build dependency:** None. This is the foundation.

### Pattern 2: Bottom Sheet with CSS Scroll Snap

**What:** A gesture-driven pull-up drawer using CSS `scroll-snap-type: y mandatory` for native-feeling snap behavior. Three snap points: collapsed (just tab bar visible), peek (40% height, shows panel header), full (90% height, full panel content).

**When:** After GameStateProvider. The bottom sheet is the primary mobile interaction surface.

**Why:** CSS scroll snap runs on the compositor thread, delivering 60fps drag animations without JavaScript frame scheduling. This is critical on mid-range mobile devices where the PixiJS canvas already consumes GPU resources.

```typescript
// BottomSheet.tsx
interface BottomSheetProps {
  activePanel: PanelId | null;
  snapPoint: "collapsed" | "peek" | "full";
  onSnapChange: (snap: "collapsed" | "peek" | "full") => void;
  children: ReactNode;
}

// CSS: scroll-snap-type: y mandatory on the container
// Three snap targets at 10%, 40%, 90% of viewport height
// scrollsnapchange event fires onSnapChange callback
```

**Build dependency:** GameStateProvider (panels need state access).

### Pattern 3: Tap-to-Command Routing

**What:** Canvas tap events on entities (mobs, items, NPCs, exits) resolve to GMCP commands via a priority-based action resolver. Single tap shows context menu for ambiguous targets; single tap on obvious actions (compass exit, loot item) executes immediately.

**When:** After canvas touch handling is wired up.

**Why:** Eliminates the need for a terminal. Every game interaction becomes a tap.

```typescript
// ActionResolver.ts
function resolveDefaultAction(entity: CanvasEntity): string | null {
  switch (entity.type) {
    case "exit": return `${entity.direction}`;  // movement
    case "mob":
      if (entity.info.questGiver && entity.info.questAvailable) return `talk ${entity.keyword}`;
      if (entity.info.shopKeeper) return `list`;
      if (entity.info.dialogue) return `talk ${entity.keyword}`;
      return `kill ${entity.keyword}`;
    case "item": return `get ${entity.keyword}`;
    case "player": return null;  // always show menu
    case "feature":
      if (entity.feature.type === "sign") return `read ${entity.keyword}`;
      return `open ${entity.keyword}`;
  }
}
```

**Build dependency:** TouchInteractionLayer, ActionMenuOverlay.

### Pattern 4: Toast Notification Queue

**What:** A FIFO queue of transient notifications that display as stacked toasts. Auto-dismiss after 3-5 seconds. Categorized by type (combat, loot, quest, system) with distinct styling.

**When:** After GameStateProvider. Replaces terminal text output for most game feedback.

**Why:** The terminal scroll area wastes mobile real estate. Brief contextual toasts are more mobile-native and don't require the user to read scrolling text.

```typescript
// useToastQueue.ts
interface Toast {
  id: string;
  type: "combat" | "loot" | "quest" | "system" | "error";
  message: string;
  icon?: string;
  duration: number;
  createdAt: number;
}

// Max 3 visible toasts, oldest dismissed first
// Quest completions get longer duration (5s)
// Combat damage numbers get shorter duration (2s)
// Gain events (XP, gold, level-up) get prominent styling
```

**Build dependency:** GameStateProvider (subscribes to UiFeedback, combat events, gain events).

### Pattern 5: Chat Bubble Overlay

**What:** Messenger-style floating chat UI anchored to bottom of screen above the bottom nav. Channel tabs along the top (say, tell, gossip, guild). Messages render as speech bubbles. Text input at the bottom. This is the only place users type.

**When:** After bottom sheet and bottom nav are in place.

**Why:** Chat is the one interaction that requires keyboard input. Isolating it into a focused messenger UI prevents the keyboard from disrupting the game layout. Messenger-style is universally understood on mobile.

```typescript
// ChatBubbleOverlay.tsx
// Slides up from bottom, overlays canvas
// Channel tabs: say | tell | gossip | guild | ooc
// Messages: bubble layout, sender avatar/name, timestamp
// Input: single-line text field with send button
// Keyboard-aware: adjusts position when soft keyboard opens
```

**Build dependency:** GameStateProvider (chatByChannel state), BottomNav (chat tab trigger).

## Anti-Patterns to Avoid

### Anti-Pattern 1: Giant Root Component

**What:** Keeping all state and logic in App.tsx as it is today.

**Why bad:** 90+ useState calls in one component means every GMCP update triggers reconciliation of the entire tree. On mobile, this causes dropped frames when the PixiJS ticker is also running. The current 1,655-line App.tsx is unmaintainable for a mobile redesign.

**Instead:** Extract state into GameStateProvider. App.tsx becomes a ~50-line layout shell.

### Anti-Pattern 2: JavaScript-Driven Bottom Sheet Animations

**What:** Using requestAnimationFrame or spring-physics libraries to animate bottom sheet drag gestures.

**Why bad:** The PixiJS canvas already occupies the GPU and runs a ticker on requestAnimationFrame. Adding JS-driven sheet animations competes for the same frame budget. On mid-range phones, this means dropped frames during sheet interactions.

**Instead:** Use CSS scroll-snap for sheet positioning. The browser handles snap animations on the compositor thread, off the main thread. Zero JS frame cost.

### Anti-Pattern 3: Separate Mobile and Desktop Codepaths

**What:** Building a completely different component tree for mobile vs desktop, with conditional rendering based on viewport width.

**Why bad:** Doubles maintenance surface. Features added to one layout get missed in the other. State management diverges. Testing matrix explodes.

**Instead:** One responsive component tree. Bottom sheet collapses to sidebar on desktop. Bottom nav becomes side nav. CSS handles layout shifts. Components are viewport-agnostic.

### Anti-Pattern 4: Prop Drilling Through Panel Tree

**What:** Passing sendCommand, vitals, room state, etc. through 5+ levels of components as props (the current App.tsx pattern).

**Why bad:** Every intermediate component in the tree re-renders when any prop changes. Adding a new piece of state requires threading it through every level.

**Instead:** GameStateProvider context. Panels read exactly what they need. `sendCommand` is available everywhere via `useGameState().sendCommand`.

### Anti-Pattern 5: React State for Canvas-Consumed Data

**What:** Using React useState for data that the PixiJS canvas reads every frame (room, mobs, combat target).

**Why bad:** React state updates trigger reconciliation. The canvas reads this data 60 times per second via the ticker. If each GMCP update triggers a React render AND the canvas reads a ref, you're paying both costs.

**Instead:** Keep the existing `gameStateRef` pattern for canvas-consumed data. The ref is updated synchronously during render. Canvas reads it outside React's lifecycle.

## Responsive Layout Strategy

The layout adapts across three breakpoints using a single component tree:

| Viewport | Canvas | HUD | Panels | Chat | Nav |
|----------|--------|-----|--------|------|-----|
| **Mobile** (< 768px) | Full viewport | Floating overlay | Bottom sheet (drag up) | Bubble overlay | Fixed bottom tab bar |
| **Tablet** (768-1024px) | 60% width | Inline left side | Bottom sheet or side panel | Side panel | Bottom tab bar |
| **Desktop** (> 1024px) | Center area | Inline left side | Right sidebar (always visible) | Right sidebar tab | Left sidebar nav |

The critical insight: **the bottom sheet becomes a right-side panel on desktop**. Same component, different CSS positioning. The PanelRouter inside renders identically.

```css
/* Mobile: bottom sheet anchored to viewport bottom */
@media (max-width: 767px) {
  .bottom-sheet { position: fixed; bottom: 0; left: 0; right: 0; }
}

/* Desktop: right sidebar */
@media (min-width: 1025px) {
  .bottom-sheet {
    position: static;
    grid-area: sidebar;
    height: 100%;
    overflow-y: auto;
  }
}
```

## Component Build Order (Dependencies)

Build order is driven by what each component requires to function:

```
Phase 1: Foundation
  GameStateProvider  (no dependencies -- extract from App.tsx)
  MobileShell        (no dependencies -- responsive layout container)
  
Phase 2: Core Interaction Surface  
  HUD                (depends on: GameStateProvider)
  BottomSheet        (depends on: MobileShell, CSS scroll-snap)
  BottomNav          (depends on: BottomSheet)
  PanelRouter        (depends on: BottomSheet, existing panels)
  
Phase 3: Tap Interactions
  TouchInteractionLayer  (depends on: PixiJS canvas, GameStateBridge)
  ActionMenuOverlay      (depends on: TouchInteractionLayer)
  Tap-to-Command         (depends on: ActionMenuOverlay, sendCommand)
  
Phase 4: Notifications + Chat
  ToastLayer         (depends on: GameStateProvider events)
  ChatBubbleOverlay  (depends on: GameStateProvider chat state, BottomNav)

Phase 5: Polish + PWA
  Panel mobile adaptations  (depends on: all of above)
  PWA enhancements          (depends on: service worker, manifest)
  Performance optimization  (depends on: everything running)
```

**Why this order:**

1. **GameStateProvider first** because every component downstream needs state access. Cannot build panels, HUD, or toasts without it.
2. **Layout (MobileShell + BottomSheet) second** because panels need a container. The existing panels can render inside the bottom sheet with minimal changes.
3. **Touch interactions third** because they require the canvas to be rendering in the new layout, and the action menu needs the bottom sheet to be functional for "open panel" actions.
4. **Notifications and chat fourth** because they are overlay components that sit on top of the established layout. They can be developed in parallel with touch interactions.
5. **Polish last** because panel adaptations (making each of the 14 panels touch-friendly) is incremental work that can happen panel-by-panel.

## Scalability Considerations

| Concern | Current State | Mobile Redesign Impact |
|---------|--------------|------------------------|
| State updates | 90+ useState in root -- every GMCP causes full reconciliation | GameStateProvider with selective context reads. Only affected subtrees re-render. |
| Canvas performance | PixiJS 8 with ticker, ~60fps desktop | Must maintain 30fps+ on mobile. Reduce particle counts, LOD for mobile. Touch events use pointer events (compositor thread). |
| Bundle size | React 19 + PixiJS 8 + xterm.js | xterm.js can be lazy-loaded (only needed for legacy terminal toggle). Bottom sheet is CSS-based (no library). Toast system is ~200 lines. |
| Memory | Desktop-oriented, no constraints | Mobile Safari limits WebView memory. Lazy-load panels. Dispose PixiJS textures for off-screen scenes. Cap chat message history (already done: MAX_CHAT_MESSAGES_PER_CHANNEL). |
| Network | WebSocket with GMCP | No change to protocol. PWA service worker caches static assets. Reconnect-on-visibility already implemented. |

## Key Technical Decisions

### No New State Management Library

The project currently uses zero state management libraries (just React useState/useRef). The GameStateProvider pattern uses React's built-in `useReducer` + `createContext`. Adding Zustand or Jotai would be a marginal improvement that doesn't justify the dependency for this project's complexity level. The GMCP handler already centralizes state updates -- it just needs a better container than 90 useState calls.

**Confidence:** HIGH -- the existing pattern works, it just needs restructuring.

### CSS Scroll-Snap Bottom Sheet (No Library)

Use native CSS scroll-snap rather than react-spring-bottom-sheet, react-modal-sheet, or Vaul. Reasons:
- Zero bundle cost
- Compositor-thread animations (critical with PixiJS running)
- Modern browser support is excellent (Safari 15+, Chrome 91+)
- The pure-web-bottom-sheet web component demonstrates this pattern works in production

**Confidence:** MEDIUM -- CSS scroll-snap bottom sheets are newer. Fallback: pointer event tracking with CSS transforms.

### PixiJS 8 Pointer Events for Touch

PixiJS 8's `eventMode: "static"` on sprites already supports pointer/touch events. The existing `EntityPopout` system uses this pattern. Mobile touch handling extends this with:
- `pointertap` for single taps (opens context menu or executes default action)
- `pointerdown`/`pointerup` for long-press detection (alternative context menu trigger)
- No pinch-to-zoom needed (fixed camera per room)

**Confidence:** HIGH -- PixiJS 8 docs confirm pointer event support, existing codebase already uses `eventMode: "static"`.

### Terminal as Hidden Fallback

The xterm.js terminal remains but moves fully off-screen. It continues to receive text output for accessibility (screen readers) and as a debug tool. It is never visible in the mobile layout by default. The terminal can be toggled on desktop for power users who prefer the traditional MUD experience.

**Confidence:** HIGH -- the terminal is already hidden behind an overlay in canvas mode. This just makes hiding the default.

## Sources

- [PixiJS 8 Events/Interaction Guide](https://pixijs.com/8.x/guides/components/events) -- eventMode, pointer events, touch support
- [Native-like bottom sheets on the web](https://viliket.github.io/posts/native-like-bottom-sheets-on-the-web/) -- CSS scroll-snap bottom sheet architecture
- [pure-web-bottom-sheet](https://github.com/viliket/pure-web-bottom-sheet) -- Production CSS scroll-snap bottom sheet web component
- [CSS Scroll Snap (MDN)](https://developer.mozilla.org/en-US/docs/Web/CSS/Guides/Scroll_snap) -- Browser API reference
- [PixiJS GitHub Discussion #9307](https://github.com/pixijs/pixijs/discussions/9307) -- Multi-touch gesture patterns
- [react-modal-sheet](https://github.com/Temzasse/react-modal-sheet) -- Reference for bottom sheet UX patterns (not recommended as dependency)
- Existing codebase analysis: `web-v3/src/App.tsx`, `web-v3/src/canvas/GameStateBridge.ts`, `web-v3/src/canvas/systems/EntityPopout.ts`, `web-v3/src/components/PopoutLayer.tsx`
