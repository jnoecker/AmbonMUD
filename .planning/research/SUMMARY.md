# Project Research Summary

**Project:** AmbonMUD Mobile-First Redesign
**Domain:** Mobile-first RPG web client (desktop MUD to tap-first PWA)
**Researched:** 2026-04-05
**Confidence:** HIGH

## Executive Summary

AmbonMUD's web client is a React 19 + PixiJS 8 canvas application currently built for desktop. The mobile-first redesign converts it from a terminal-centric interface to a tap-first PWA. Research confirms this is a well-understood problem domain: every major mobile RPG (RuneScape Mobile, Genshin Impact, etc.) has solved the same challenge with the same pattern — bottom sheet panels, a fixed HUD overlay, an action bar, toast notifications for game events, and messenger-style chat. The core server is untouched; all changes are client-side, and the existing GMCP protocol already delivers every piece of structured data the new UI needs. No server modifications are required.

The recommended approach is a layered migration in five phases: refactor state management first (App.tsx's 90+ useState calls into a GameStateProvider context), then build the layout skeleton (responsive shell + bottom sheet + HUD), then wire up tap interactions (compass, entity taps, action menus), then add notifications and chat, and finally polish individual panels. This order is dictated by hard dependencies: panels need state access, state needs a context provider, the bottom sheet needs a layout container, and toast routing needs state in context. Skipping ahead to individual panels before the foundation is in place produces throwaway code.

The single highest-risk area is iOS performance. PixiJS 8 has a documented VRAM regression that crashes iOS Safari silently when texture counts accumulate; iOS aggressively kills WebSocket connections on backgrounding; and the virtual keyboard destroys CSS layout in ways that differ between iOS and Android. These three risks are not theoretical — they are documented bugs and platform behaviors that will surface on the first real-device test. All three must be addressed in the first phase that touches canvas rendering and the first phase that adds chat input. Retrofitting them later requires touching every panel.

## Key Findings

### Recommended Stack

The existing stack (React 19, PixiJS 8, TypeScript, Vite, xterm.js, Bun) requires no changes. Four targeted new production dependencies are recommended, all verified on npm as of April 2026. The total new runtime bundle impact is approximately 67 KB gzipped — proportional to the existing 350+ KB baseline.

**Core technologies:**
- **react-modal-sheet ^5.6.0**: bottom sheet panels — purpose-built with virtual keyboard avoidance and multi-snap-point support; better fit than Vaul (no keyboard avoidance) or Base UI Drawer (heavier dep)
- **motion ^12.38.0**: animation engine — required peer dependency of react-modal-sheet; also drives `AnimatePresence` for toast enter/exit and spring physics for panel transitions; React 19 compatible
- **sonner ^2.0.7**: toast notifications — zero-config API callable without hooks, stacks with auto-dismiss, 11x more weekly downloads than react-hot-toast, richer toast types
- **@use-gesture/react ^10.3.1**: swipe gestures — the only mature React gesture library; covers swipe-to-move, swipe-to-dismiss, velocity tracking; Hammer.js is unmaintained since 2016
- **use-long-press ^3.3.0**: context menu trigger — lighter than @use-gesture for press-and-hold use case only
- **vite-plugin-pwa ^1.2.0** (dev): service worker generation — replaces hand-written sw.js (caches 2 assets) with Workbox full-precache of entire build output
- **PixiJS pointer events (no new dep)**: canvas touch interactions use PixiJS 8's built-in `eventMode = 'static'` and `pointertap`; no additional library needed

### Expected Features

**Must have (table stakes):**
- Tap compass exits to move — typing direction commands on mobile is a non-starter
- Minimal always-visible HUD (HP/mana/XP, level, combat target) — every mobile RPG shows this permanently
- Bottom sheet panel container with snap points — replaces sidebar panels; the primary mobile interaction pattern
- Panel tab bar navigation — quick access to 14+ panels without hunting through menus
- Action bar with ability buttons and cooldown sweep overlays — core combat loop access
- Tap-to-target entity selection — required for combat without a terminal
- Toast notification system — replaces terminal scroll for loot, XP, quest, and level events
- Messenger-style chat UI with channel tabs — the only place users type; must use familiar WhatsApp/iMessage model
- Portrait-first responsive layout (375px primary) — 73% of top 100 mobile games are portrait-first
- Touch target sizing (48x48dp minimum) — Apple HIG and Android Material Design both mandate this
- Safe area inset handling — notches, Dynamic Island, punch-hole cameras obscure HUD if not handled
- PWA manifest + service worker — installability and instant repeat-load from day one

**Should have (differentiators):**
- Swipe-to-move (4-way cardinal) — complement to compass tap navigation
- Floating combat text on canvas — damage numbers rise from entities (existing GainPopup and CombatAnimator are the foundation)
- Haptic feedback on combat hits and level-up (Android only, progressive enhancement — not supported on iOS Safari)
- Chat minimized mode with keyboard avoidance
- Auto-attack toggle button in combat HUD
- Dialogue choices rendered as tappable buttons (not numbered list)
- Shop items as scrollable card list in bottom sheet
- Context-sensitive action menus on entity tap (attack/talk/trade/look)
- First-time user experience walkthrough (5-7 steps, skip option)

**Defer (v2+):**
- Push notifications — requires new server endpoints; explicitly out of scope per PROJECT.md
- Offline world map viewer — nice-to-have, not blocking MVP
- Mini-map with fog of war — high complexity, low urgency
- Canvas entity tap interaction (direct PixiJS hit testing on sprites) — room entity list taps are sufficient for MVP; full canvas tap is high complexity

**Anti-features (do not build):**
- Virtual joystick — implies analog movement, wrong for discrete MUD rooms; tap compass + swipe is correct
- Full terminal on mobile — xterm.js is documented-broken on mobile IMEs; defeats the redesign purpose
- Drag-and-drop inventory — unreliable on touch; use tap-to-act menus instead
- Native app wrapper (Capacitor/Cordova) — PWA-first; adds build complexity and app store pipeline
- Desktop-only features — PROJECT.md explicitly requires everything to work on mobile

### Architecture Approach

The architecture refactors the existing monolithic App.tsx (1,655 lines, 90+ useState calls) into a layered component tree with clear boundaries. The GMCP protocol and `applyGmcpPackage.ts` remain the single source of truth for all game state and are untouched. Two architectural contracts must be maintained throughout: (1) canvas reads state via `gameStateRef` mutable refs, not React state, to avoid re-rendering the canvas on every GMCP tick; (2) all user actions resolve through a single `sendCommand()` function regardless of origin (canvas tap, action bar, chat input, context menu).

**Major components:**
1. **GameStateProvider** — React context + `useReducer` wrapping all 90+ useState calls; dispatches GMCP actions; exposes `sendCommand()` and `sendGmcp()` via context; the foundation everything depends on
2. **MobileShell** — responsive layout container; decides viewport split across three breakpoints; manages safe area insets; the bottom sheet becomes a right-side panel on desktop via CSS only (one component tree, not separate mobile/desktop codepaths)
3. **Canvas + TouchInteractionLayer** — PixiJS rendering unchanged; new `TouchInteractionLayer` intercepts pointer/tap events, routes to `ActionResolver` for default actions, opens `ActionMenuOverlay` for ambiguous targets
4. **HUD** — always-visible floating overlay: HP/mana/XP bars, compass exits, combat target; reads from context; max ~60px vertical
5. **BottomSheet + PanelRouter** — gesture-driven pull-up drawer using CSS `scroll-snap-type: y mandatory` for compositor-thread animations; three snap points; PanelRouter renders active panel inside
6. **ToastLayer** — FIFO notification queue with per-tick combat event aggregation; max 3 visible; uses sonner; combat events aggregate (not per-event)
7. **ChatBubbleOverlay** — messenger-style floating chat; only place users type; native HTML `<input>` with `enterkeyhint="send"`, never xterm.js on mobile

### Critical Pitfalls

1. **PixiJS VRAM crash on iOS Safari** — PixiJS 8 has a documented VRAM regression (issue #11331); texture accumulation from room transitions silently crashes iOS Safari with a blank white screen and no error. Prevention: configure `textureGCMaxIdle: 300`, call `texture.unload()` explicitly on room change, cap resolution at `Math.min(devicePixelRatio, 2)`. Must be addressed in phase 1 canvas work — retrofitting is extremely painful after 14 panels add more textures.

2. **iOS WebSocket background death** — iOS silently kills WebSocket TCP connections when backgrounded or screen-locked. The existing `visibilitychange` handler lacks exponential backoff and does not trigger full state resync after reconnect. Prevention: exponential backoff (500ms/1s/2s/4s, capped at 15s), send `Core.Hello` + `look` after reconnect for full GMCP refresh, show reconnection overlay. Must be solved before any PWA work.

3. **xterm.js is broken on mobile** — xterm.js has documented, unfixed mobile IME issues: predictive text appears ahead of cursor, backspace misbehaves, Android keyboards require Enter twice. Do not use xterm.js for any input on mobile. Use native HTML `<input>` with `enterkeyhint="send"` for chat. xterm.js becomes a lazy-loaded desktop-only debug toggle.

4. **Virtual keyboard destroys layout** — When the virtual keyboard opens, iOS scrolls the viewport (pushing fixed-position HUD and action bar off-screen); Android overlays by default. The existing `100dvh` approach does not account for this. Prevention: `interactive-widget=overlays-content` in viewport meta, `navigator.virtualKeyboard.overlaysContent = true` where supported, detect keyboard open/close via `visualViewport` resize (not `window.innerHeight`), never resize the PixiJS canvas when keyboard opens.

5. **Toast fatigue from MUD combat volume** — A MUD generates 20-100 combat events per second during active fights. Mapping terminal output 1:1 to toasts buries the screen and thrashes the React reconciler. Prevention: aggregate combat events per tick into one summary toast; use the existing `CombatLog` component for in-panel scrollback; reserve toasts for discrete important events only (level up, quest complete, item drop, whisper); cap at 3 visible toasts maximum.

6. **Tap target density at 375px** — Existing WorldScene renders sprites at 128px base for a 1200x800 reference viewport; scaling to 375px makes 12px role icons 4px and untappable. Prevention: `MIN_SPRITE_SIZE = 80px` on screens under 600px, use `hitArea` Graphics for expanded invisible tap zones (44x44px minimum), show disambiguation menu when entities overlap, replace EntityPopout with full-width bottom sheet on mobile.

## Implications for Roadmap

Based on combined research, the dependency graph drives a clear 5-phase structure. The order is not arbitrary — it reflects that nothing can be built correctly without the foundation phases in place first.

### Phase 1: Foundation (State + Layout + Canvas Safety)

**Rationale:** GameStateProvider must exist before any panel or notification can consume state. Canvas mobile safety (VRAM, resolution, texture GC) must be in place before any new canvas features are added — retrofitting after 14 panels are built requires touching every panel. Safe area insets must be established before any UI element is positioned near screen edges.

**Delivers:**
- GameStateProvider context replacing App.tsx's 90+ useState calls; App.tsx refactored to ~50-line layout shell
- MobileShell responsive container with safe area CSS utility classes
- Canvas resolution capped at 2x, TextureGCSystem configured aggressively (`textureGCMaxIdle: 300`), idle ticker throttling
- `viewport-fit=cover` in viewport meta tag
- Bundle analysis baseline (target: main chunk < 200KB gzipped)

**Addresses:** Portrait-first layout architecture, safe area handling (table stakes)
**Avoids:** VRAM crash (Pitfall 1), React re-render storms from 90+ useState calls (Pitfall 10), tap target density regressions (Pitfall 6 — establishes size constants), battery drain (Pitfall 16 — idle ticker throttling)

---

### Phase 2: Core Interaction Surface (Bottom Sheet + HUD + PWA)

**Rationale:** The bottom sheet is the primary mobile interaction pattern all 14 panels live inside. It must be stable before any panel is migrated. PWA fundamentals go in early so the app is installable throughout development. WebSocket reconnection must be hardened before the app is promoted as a PWA.

**Delivers:**
- Bottom sheet with CSS `scroll-snap-type: y mandatory` snap points (collapsed/peek/full) via react-modal-sheet
- BottomNav tab bar (4-5 primary destinations)
- PanelRouter rendering existing panels inside bottom sheet with minimal content changes
- HUD overlay: HP/mana/XP bars, level/class badge, status effect icons, combat target bar
- vite-plugin-pwa replacing hand-written sw.js (Workbox full-precache, versioned caches, update notification toast)
- WebSocket reconnection with exponential backoff + full GMCP state resync after reconnect
- Reconnection overlay UI

**Addresses:** Bottom sheet panel container, minimal HUD, status effect icons, PWA manifest + service worker (table stakes)
**Avoids:** Bottom sheet gesture conflicts (Pitfall 9 — grab handle only dismiss, snap points), iOS WebSocket death (Pitfall 2 — exponential backoff + resync), service worker cache traps (Pitfall 8 — versioned caches, update notification), EU PWA restrictions (Pitfall 11 — design works as browser tab too)
**Stack:** react-modal-sheet, motion, vite-plugin-pwa, workbox-window

---

### Phase 3: Tap Interactions + Combat UI

**Rationale:** Once the layout shell exists, tap-based navigation and combat are the core gameplay loop. Toast architecture belongs here because the aggregation strategy must be established before individual panels start emitting events — retrofitting aggregation after 14 panels are wired is expensive.

**Delivers:**
- Tap compass exits to move (wires existing compass exits to `sendCommand`)
- Swipe-to-move (4-way cardinal via @use-gesture/react)
- Tap-to-target entity selection from room entity list
- Context-sensitive action menus (ActionMenuOverlay) for entity taps
- ActionResolver routing (default action vs disambiguation menu)
- Action bar with ability buttons and cooldown sweep overlays (60fps radial sweep animation)
- Toast notification system (sonner) with combat event aggregation, 3-toast cap, priority levels
- Gain event toasts (XP, gold, item acquired); combat feedback (per-tick aggregated, not per-event)

**Addresses:** Tap compass navigation, swipe-to-move, tap-to-target, action bar with cooldowns, toast system (all table stakes)
**Avoids:** Toast fatigue (Pitfall 5 — per-tick aggregation built from the start), tap target density (Pitfall 6 — 44px minimum enforced), xterm.js on mobile (Pitfall 3 — all gameplay via tap)
**Stack:** @use-gesture/react, use-long-press, sonner, motion

---

### Phase 4: Chat + NPC Interactions + Panel Content

**Rationale:** Chat requires the keyboard handling infrastructure that must not be bolted onto an existing layout — it gets its own phase so keyboard avoidance gets the attention it needs. NPC interactions (dialogue as buttons, shop as card list) are high-frequency gameplay that feels broken without tap-friendly redesigns. Panel content migration is incremental once the bottom sheet is stable.

**Delivers:**
- Messenger-style chat bubble UI (channel tabs: say/tell/gossip/guild/ooc, bubble layout, native HTML input)
- Virtual keyboard management: `interactive-widget=overlays-content`, `visualViewport` resize detection, chat-mode bottom sheet repositioning
- Chat minimized mode (floating last-message strip)
- Tap NPC to talk, dialogue choices as tappable buttons
- Shop items as scrollable card list in bottom sheet
- Remaining panel content migrations (Character, Inventory, Equipment, Quest, Crafting, Bank, Trade, Guild, etc.)
- Level up celebration overlay

**Addresses:** Messenger-style chat with channels, expandable text input, chat minimized mode, NPC tap interactions, dialogue buttons, shop cards, all panel redesigns (table stakes)
**Avoids:** Virtual keyboard layout destruction (Pitfall 4 — built correctly from the start), xterm.js mobile failure (Pitfall 3 — native input throughout)
**Stack:** react-modal-sheet keyboard avoidance (built-in), VirtualKeyboard API, visualViewport

---

### Phase 5: Polish + Performance + PWA Enhancements

**Rationale:** Polish features require all core features to exist first (FTUE must teach real interactions; floating combat text builds on stable canvas; haptics build on stable combat events). Performance optimization is most effective when the complete feature set is known — avoids premature optimization.

**Delivers:**
- Floating combat text on canvas (PixiJS text sprites, float-up + fade — extends existing GainPopup and CombatAnimator)
- Haptic feedback on combat hits and level-up (Android progressive enhancement only)
- First-time user experience walkthrough (5-7 steps, highlight overlays, skip option)
- Orientation change handling (300ms debounce, portrait-first manifest lock option)
- Auto-attack toggle in combat HUD
- xterm.js lazy-loaded as desktop-only debug toggle (saves ~120KB from initial mobile bundle)
- PixiJS tree-shaking for unused renderer features
- Lighthouse "Slow 4G" audit — Time to Interactive < 3 seconds target
- Adaptive quality settings driving mobile-specific canvas caps

**Addresses:** Floating combat text, haptics, FTUE, landscape orientation (differentiators and polish)
**Avoids:** Bundle size first-load block (Pitfall 15 — lazy-loading and tree-shaking), battery drain (Pitfall 16 — idle detection + reduced particle counts), orientation change layout break (Pitfall 14)
**Stack:** Existing PixiJS particle systems, Vibration API, vite-plugin-pwa update flow

---

### Phase Ordering Rationale

- **GameStateProvider must be first** because every downstream component depends on it; building panels before state is in context produces throwaway code
- **Canvas safety (VRAM, resolution) must be in Phase 1** because every new canvas feature in subsequent phases makes the VRAM problem worse; retrofitting is extremely painful
- **Bottom sheet before any panel migration** because panels need their container; building panels before the container exists leads to integration rework
- **Tap interactions before chat** because navigation and combat are the core gameplay loop; chat is social and secondary; combat interactions also establish the toast aggregation architecture that chat notifications inherit
- **Chat in its own phase with full keyboard management attention** because virtual keyboard handling is the most platform-divergent work and must not be squeezed into an earlier phase
- **Polish last** because FTUE requires all core features to exist, and performance optimization is most effective with the complete feature set known

### Research Flags

Phases needing deeper research during planning:
- **Phase 3 (action bar cooldown animation):** PixiJS radial sweep overlays at 60fps while React manages ability state — the GameStateBridge ref pattern may need extension; a spike to verify the animation coordination approach is recommended before committing to the implementation.
- **Phase 4 (virtual keyboard management):** iOS Safari's VirtualKeyboard API support is incomplete as of 2025; `visualViewport` + `interactive-widget=overlays-content` is the best available cross-platform approach but has documented edge cases. Mandatory real-device testing on iPhone SE and a Samsung Galaxy mid-range device before Phase 4 sign-off — BrowserStack cannot substitute for IME behavior testing.

Phases with standard patterns (skip research-phase):
- **Phase 1:** React context + useReducer is textbook; PixiJS TextureGCSystem docs are comprehensive; safe-area-inset CSS has 90%+ browser support
- **Phase 2:** Bottom sheet + CSS scroll-snap is well-documented; Workbox PWA patterns are mature; exponential backoff reconnection is a standard pattern
- **Phase 5:** Bundle optimization, Lighthouse auditing, PixiJS tree-shaking — all established with official tooling

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | HIGH | All 6 new dependencies verified on npm registry as of April 2026; React 19 and Vite 7 compatibility confirmed; alternatives evaluated with download counts, bundle sizes, and API quality |
| Features | HIGH | Feature list grounded in RuneScape Mobile, Genshin Impact, and documented mobile RPG patterns; complexity estimates supported by codebase analysis of existing GMCP packages and panel components |
| Architecture | HIGH | Component boundaries derived from codebase analysis (1,655-line App.tsx, existing GameStateBridge pattern); responsive layout strategy (one tree, CSS breakpoints) is documented best practice; PixiJS + React separation contracts are well-established |
| Pitfalls | HIGH | Top 6 pitfalls backed by first-party bug reports (PixiJS #11331, xterm.js #5377) or documented platform behaviors (iOS WebSocket death, keyboard viewport); prevention strategies include specific API names and configuration values |

**Overall confidence:** HIGH

### Gaps to Address

- **@use-gesture/react React 19 compatibility:** Last published 2 years ago; library is stable and feature-complete but verify no React 19 breaking issues before committing to it in Phase 3. Fallback: native pointer events with manual velocity calculation for swipe detection.
- **CSS scroll-snap vs react-modal-sheet decision:** ARCHITECTURE.md recommends CSS scroll-snap (zero bundle cost, compositor-thread); STACK.md recommends react-modal-sheet (more robust gesture handling). Resolve in Phase 2 with a spike — try CSS scroll-snap first, fall back to react-modal-sheet if `scrollsnapchange` event behavior is insufficient.
- **EU PWA restrictions (iOS 17.4+):** iOS removed standalone PWA mode in EU countries under the DMA. This is acknowledged but not fully resolved. Design the mobile experience to function well as a regular browser tab; document the limitation for EU users; do not depend on push notifications for core gameplay.
- **Real-device testing cadence:** VRAM crash, IME behavior, virtual keyboard, and bottom sheet gesture conflicts with system UI cannot be caught in Chrome DevTools device simulation. Real hardware (iPhone SE and Android mid-range) is required for Phase 2 sign-off and Phase 4 sign-off.

## Sources

### Primary (HIGH confidence)
- [PixiJS 8 Performance Tips](https://pixijs.com/8.x/guides/concepts/performance-tips) — canvas optimization, resolution capping, texture GC configuration
- [PixiJS 8 Garbage Collection](https://pixijs.com/8.x/guides/concepts/garbage-collection) — TextureGCSystem API and settings
- [PixiJS VRAM regression #11331](https://github.com/pixijs/pixijs/issues/11331) — VRAM crash on iOS documented as first-party bug
- [xterm.js mobile touch #5377](https://github.com/xtermjs/xterm.js/issues/5377) — mobile IME issues documented as unfixed
- [VirtualKeyboard API - MDN](https://developer.mozilla.org/en-US/docs/Web/API/VirtualKeyboard_API) — keyboard management official spec
- [CSS Scroll Snap - MDN](https://developer.mozilla.org/en-US/docs/Web/CSS/Guides/Scroll_snap) — bottom sheet implementation reference
- [react-modal-sheet npm](https://www.npmjs.com/package/react-modal-sheet) — version 5.6.0, April 2026, React 19 support confirmed
- [motion npm](https://www.npmjs.com/package/motion) — version 12.38.0, React 19 support confirmed
- [sonner npm](https://www.npmjs.com/package/sonner) — version 2.0.7 confirmed
- [vite-plugin-pwa npm](https://www.npmjs.com/package/vite-plugin-pwa) — version 1.2.0, Vite 7 support confirmed
- [Bottom Sheet UX Guidelines - NNGroup](https://www.nngroup.com/articles/bottom-sheet/) — authoritative UX research
- [PixiJS 8 Events Guide](https://pixijs.com/8.x/guides/components/events) — pointer events and touch support

### Secondary (MEDIUM confidence)
- [MagicBell PWA iOS Limitations 2026](https://www.magicbell.com/blog/pwa-ios-limitations-safari-support-complete-guide) — EU PWA restrictions, iOS PWA capabilities
- [Native-like bottom sheets on the web](https://viliket.github.io/posts/native-like-bottom-sheets-on-the-web/) — CSS scroll-snap bottom sheet reference implementation
- [LogRocket toast comparison 2025](https://blog.logrocket.com/react-toast-libraries-compared-2025/) — sonner vs react-hot-toast analysis
- [VisualViewport keyboard fix](https://dev.to/franciscomoretti/fix-mobile-keyboard-overlap-with-visualviewport-3a4a) — virtual keyboard management approach
- [PocketGamer: Portrait mode market data](https://www.pocketgamer.biz/comment-and-opinion/62882/how-portrait-mode-trumped-tablet-first-gaming/) — 73% portrait-mode statistic source
- RuneScape Mobile UI Rework documentation — panel/action bar layout reference
- Codebase analysis: `web-v3/src/App.tsx`, `web-v3/src/canvas/GameStateBridge.ts`, `web-v3/src/canvas/systems/EntityPopout.ts`

### Tertiary (LOW confidence)
- iOS WebSocket background disconnect — community reports via socket.io GitHub, corroborated by Apple Developer Forums; exact grace period behavior varies by iOS version and device generation

---
*Research completed: 2026-04-05*
*Ready for roadmap: yes*
